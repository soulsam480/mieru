package media

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestProxyRewritesPlaylistAndForwardsHeaders(t *testing.T) {
	var sawToken, sawRange string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawToken = r.Header.Get("X-Token")
		switch r.URL.Path {
		case "/master.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			_, _ = io.WriteString(w, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720\nseg.ts\n")
		case "/seg.ts":
			sawRange = r.Header.Get("Range")
			w.Header().Set("Content-Type", "video/mp2t")
			w.Header().Set("Content-Range", "bytes 0-3/4")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = io.WriteString(w, "SEGM")
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	proxy, err := NewServer()
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()

	playback, err := proxy.Open(upstream.URL+"/master.m3u8", map[string]string{"X-Token": "secret"})
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.Get(playback)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()

	if sawToken != "secret" {
		t.Fatalf("upstream saw token %q", sawToken)
	}
	text := string(body)
	if !strings.Contains(text, "#EXTM3U") {
		t.Fatalf("playlist not served: %q", text)
	}
	if !strings.Contains(text, `URI="`+proxy.Base()+`/s/`) {
		t.Fatalf("key URI not rewritten: %q", text)
	}

	var segment string
	for _, line := range strings.Split(text, "\n") {
		if line != "" && !strings.HasPrefix(line, "#") {
			segment = line
		}
	}
	if !strings.HasPrefix(segment, proxy.Base()+"/s/") {
		t.Fatalf("segment not rewritten: %q", segment)
	}

	request, _ := http.NewRequest(http.MethodGet, segment, nil)
	request.Header.Set("Range", "bytes=0-3")
	segmentRes, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	segmentBody, _ := io.ReadAll(segmentRes.Body)
	segmentRes.Body.Close()
	if segmentRes.StatusCode != http.StatusPartialContent || string(segmentBody) != "SEGM" {
		t.Fatalf("segment = %d %q", segmentRes.StatusCode, segmentBody)
	}
	if sawRange != "bytes=0-3" {
		t.Fatalf("range not forwarded: %q", sawRange)
	}
}

func TestUnwrapReadsWrapperTargetAndHeaders(t *testing.T) {
	headers := url.QueryEscape(`{"Referer":"https://cdn.test/","Cookie":"a=b"}`)
	wrapper := "https://wrap.test/proxy/hls/abc/local.m3u8?headers=" + headers + "&host=" + url.QueryEscape("https://cdn.test")
	target, merged := unwrap(wrapper, map[string]string{"Origin": "https://page.test"})

	if target != "https://cdn.test/hls/abc/local.m3u8" {
		t.Fatalf("target = %q", target)
	}
	if merged["Cookie"] != "a=b" || merged["Referer"] != "https://cdn.test/" {
		t.Fatalf("headers = %+v", merged)
	}
	if merged["Origin"] != "https://page.test" {
		t.Fatalf("captured header lost: %+v", merged)
	}
}

func TestOpenRejectsNonHTTP(t *testing.T) {
	proxy, err := NewServer()
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	if _, err := proxy.Open("file:///etc/passwd", nil); err == nil {
		t.Fatal("file URL should be rejected")
	}
}

func TestVariantsReadsMaster(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080\nhi/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720\nlo/index.m3u8\n")
	}))
	defer upstream.Close()

	proxy, err := NewServer()
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()

	variants := proxy.Variants(t.Context(), upstream.URL+"/master.m3u8", nil)
	if len(variants) != 2 {
		t.Fatalf("variants = %+v", variants)
	}
	if variants[0].Label != "1080p" || !strings.HasSuffix(variants[0].URL, "/hi/index.m3u8") {
		t.Fatalf("first variant = %+v", variants[0])
	}
}
