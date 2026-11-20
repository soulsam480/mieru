// Package media serves playback through a loopback HTTP server, so the system
// player can fetch a playlist that needs Referer, Origin or Cookie headers it
// cannot send by itself. Playlists are rewritten to route every segment back
// through the same session.
package media

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

const userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// Server is the loopback proxy.
type Server struct {
	ln       net.Listener
	base     string
	http     *http.Server
	mu       sync.Mutex
	sessions map[string]*session
}

type session struct {
	headers map[string]string
}

// NewServer starts the proxy on an ephemeral loopback port.
func NewServer() (*Server, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &Server{
		ln:       listener,
		base:     "http://" + listener.Addr().String(),
		sessions: map[string]*session{},
	}
	s.http = &http.Server{Handler: s, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = s.http.Serve(listener) }()
	return s, nil
}

// Base is the proxy's origin, without a trailing slash.
func (s *Server) Base() string { return s.base }

// Close stops the server.
func (s *Server) Close() error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return s.http.Shutdown(ctx)
}

// Open starts a session for a stream and returns the URL the player opens.
func (s *Server) Open(rawURL string, headers map[string]string) (string, error) {
	target, merged := unwrap(rawURL, headers)
	parsed, err := url.Parse(target)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return "", fmt.Errorf("unplayable URL: %s", rawURL)
	}

	id := randomID()
	s.mu.Lock()
	s.sessions[id] = &session{headers: merged}
	s.mu.Unlock()

	return s.base + "/s/" + id + "/" + encode(target), nil
}

func (s *Server) session(id string) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessions[id]
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 3)
	if len(parts) != 3 || parts[0] != "s" {
		http.NotFound(w, r)
		return
	}
	target, err := decode(parts[2])
	if err != nil {
		http.Error(w, "bad target", http.StatusBadRequest)
		return
	}
	sess := s.session(parts[1])
	if sess == nil {
		http.Error(w, "unknown session", http.StatusNotFound)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	for key, value := range sess.headers {
		if strings.EqualFold(key, "Host") || strings.EqualFold(key, "Range") {
			continue
		}
		req.Header.Set(key, value)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", userAgent)
	}
	if r.Header.Get("Range") != "" {
		req.Header.Set("Range", r.Header.Get("Range"))
	}
	req.Header.Set("Accept-Encoding", "identity")

	client := &http.Client{Timeout: 45 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		http.Error(w, "upstream: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer res.Body.Close()

	if res.StatusCode/100 != 2 && res.StatusCode != http.StatusPartialContent {
		http.Error(w, fmt.Sprintf("upstream %d", res.StatusCode), http.StatusBadGateway)
		return
	}

	contentType := res.Header.Get("Content-Type")
	if isPlaylist(target, contentType) {
		body, err := io.ReadAll(io.LimitReader(res.Body, 16<<20))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		rewritten := s.rewritePlaylist(parts[1], target, body)
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(rewritten)
		return
	}

	for _, header := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if value := res.Header.Get(header); value != "" {
			w.Header().Set(header, value)
		}
	}
	w.WriteHeader(res.StatusCode)
	_, _ = io.Copy(w, res.Body)
}

func isPlaylist(target, contentType string) bool {
	if strings.Contains(strings.ToLower(contentType), "mpegurl") {
		return true
	}
	path := strings.SplitN(target, "?", 2)[0]
	return strings.HasSuffix(strings.ToLower(path), ".m3u8")
}

var uriAttribute = regexp.MustCompile(`URI="([^"]+)"`)

func (s *Server) rewritePlaylist(sessionID, base string, body []byte) []byte {
	baseURL, _ := url.Parse(base)
	var out strings.Builder
	scanner := bufio.NewScanner(strings.NewReader(string(body)))
	scanner.Buffer(make([]byte, 64<<10), 8<<20)
	for scanner.Scan() {
		line := scanner.Text()
		trimmed := strings.TrimSpace(line)
		switch {
		case trimmed == "":
			out.WriteString(line)
		case strings.HasPrefix(trimmed, "#"):
			if strings.Contains(trimmed, `URI="`) {
				line = uriAttribute.ReplaceAllStringFunc(line, func(match string) string {
					value := strings.TrimSuffix(strings.TrimPrefix(match, `URI="`), `"`)
					return `URI="` + s.proxyURL(sessionID, baseURL, value) + `"`
				})
			}
			out.WriteString(line)
		default:
			out.WriteString(s.proxyURL(sessionID, baseURL, trimmed))
		}
		out.WriteString("\n")
	}
	return []byte(out.String())
}

func (s *Server) proxyURL(sessionID string, base *url.URL, ref string) string {
	absolute, err := url.Parse(ref)
	if err != nil {
		return ref
	}
	if !absolute.IsAbs() {
		absolute = base.ResolveReference(absolute)
	}
	return s.base + "/s/" + sessionID + "/" + encode(absolute.String())
}

// unwrap reads the target and headers a provider wrapper carries in its query,
// so the proxy talks to the CDN directly. Providers that hand out a plain URL
// pass through with the headers the resolver captured.
func unwrap(rawURL string, headers map[string]string) (string, map[string]string) {
	merged := map[string]string{}
	for key, value := range headers {
		merged[key] = value
	}
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return rawURL, merged
	}
	query := parsed.Query()
	host := query.Get("host")
	rawHeaders := query.Get("headers")
	if host == "" || rawHeaders == "" {
		return rawURL, merged
	}
	var decoded map[string]string
	if err := json.Unmarshal([]byte(rawHeaders), &decoded); err != nil {
		return rawURL, merged
	}
	target := strings.TrimRight(host, "/") + parsed.EscapedPath()
	if strings.HasPrefix(parsed.Path, "/proxy/") {
		target = strings.TrimRight(host, "/") + strings.TrimPrefix(parsed.Path, "/proxy")
	}
	for key, value := range decoded {
		merged[key] = value
	}
	parsedTarget, err := url.Parse(target)
	if err != nil {
		return rawURL, merged
	}
	if parsed.RawQuery != "" {
		// The wrapper's own query is for the wrapper, not the CDN.
		parsedTarget.RawQuery = ""
	}
	return parsedTarget.String(), merged
}

func encode(value string) string { return base64.RawURLEncoding.EncodeToString([]byte(value)) }

func decode(value string) (string, error) {
	data, err := base64.RawURLEncoding.DecodeString(value)
	return string(data), err
}

func randomID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format(time.RFC3339Nano)))
	}
	return hex.EncodeToString(buf)
}

// Variant is one quality of an HLS master playlist.
type Variant struct {
	Label string
	URL   string
}

// Variants reads an HLS master playlist and returns its quality levels, best
// first. A media playlist or a file that is not HLS yields nothing.
func (s *Server) Variants(ctx context.Context, rawURL string, headers map[string]string) []Variant {
	target, merged := unwrap(rawURL, headers)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil
	}
	for key, value := range merged {
		req.Header.Set(key, value)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", userAgent)
	}
	res, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return nil
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return nil
	}
	base, _ := url.Parse(target)
	var out []Variant
	lines := strings.Split(strings.ReplaceAll(string(body), "\r\n", "\n"), "\n")
	for i := 0; i < len(lines)-1; i++ {
		line := strings.TrimSpace(lines[i])
		if !strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			continue
		}
		uri := strings.TrimSpace(lines[i+1])
		if uri == "" || strings.HasPrefix(uri, "#") {
			continue
		}
		resolved, err := url.Parse(uri)
		if err != nil {
			continue
		}
		if !resolved.IsAbs() && base != nil {
			resolved = base.ResolveReference(resolved)
		}
		label := "Auto"
		if match := regexp.MustCompile(`RESOLUTION=\d+x(\d+)`).FindStringSubmatch(line); match != nil {
			label = match[1] + "p"
		}
		if match := regexp.MustCompile(`NAME="([^"]+)"`).FindStringSubmatch(line); match != nil {
			label = match[1]
		}
		out = append(out, Variant{Label: label, URL: resolved.String()})
	}
	return out
}
