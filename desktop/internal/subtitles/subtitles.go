// Package subtitles finds tracks in OpenSubtitles' public Stremio addon and
// turns them into WebVTT the player can render.
package subtitles

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	base      = "https://opensubtitles-v3.strem.io"
	userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
	maxTracks = 40
)

// Track is one subtitle file, one per language.
type Track struct {
	Language string `json:"language"`
	Label    string `json:"label"`
	URL      string `json:"url"`
}

// Client searches the addon.
type Client struct {
	http *http.Client
}

// New returns a subtitle client.
func New() *Client {
	return &Client{http: &http.Client{Timeout: 15 * time.Second}}
}

type envelope struct {
	Subtitles []entry `json:"subtitles"`
}

type entry struct {
	URL      string `json:"url"`
	Language string `json:"lang"`
	FileName string `json:"subtitleFileName"`
}

// Search returns one track per language, English first. An unreachable addon
// yields nothing: subtitles are a bonus, never a reason to fail playback.
func (c *Client) Search(ctx context.Context, imdb string, season, episode int) []Track {
	if !strings.HasPrefix(imdb, "tt") {
		return nil
	}
	path := "movie/" + imdb
	if season > 0 && episode > 0 {
		path = fmt.Sprintf("series/%s:%d:%d", imdb, season, episode)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/subtitles/"+path+".json", nil)
	if err != nil {
		return nil
	}
	req.Header.Set("User-Agent", userAgent)
	res, err := c.http.Do(req)
	if err != nil {
		return nil
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		return nil
	}
	var payload envelope
	if err := json.NewDecoder(io.LimitReader(res.Body, 8<<20)).Decode(&payload); err != nil {
		return nil
	}

	seen := map[string]bool{}
	out := make([]Track, 0, len(payload.Subtitles))
	for _, row := range payload.Subtitles {
		language := Normalize(row.Language)
		if language == "" || row.URL == "" || seen[language] {
			continue
		}
		seen[language] = true
		label := strings.TrimSpace(row.FileName)
		if label == "" {
			label = language
		}
		out = append(out, Track{Language: language, Label: label, URL: row.URL})
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Language == "en" {
			return true
		}
		if out[j].Language == "en" {
			return false
		}
		return out[i].Language < out[j].Language
	})
	if len(out) > maxTracks {
		out = out[:maxTracks]
	}
	return out
}

var languageAliases = map[string]string{
	"eng": "en", "fre": "fr", "fra": "fr", "ger": "de", "deu": "de",
	"spa": "es", "por": "pt", "pob": "pt-br", "pt-br": "pt-br", "ita": "it", "jpn": "ja",
	"chi": "zh", "zho": "zh", "kor": "ko", "rus": "ru", "ara": "ar",
	"dut": "nl", "nld": "nl", "pol": "pl", "tur": "tr", "swe": "sv",
	"nor": "no", "dan": "da", "fin": "fi", "cze": "cs", "ces": "cs",
	"hun": "hu", "rum": "ro", "ron": "ro", "gre": "el", "ell": "el",
	"heb": "he", "hin": "hi", "tha": "th", "vie": "vi", "ukr": "uk",
}

// Normalize maps the catalogues' mixed codes (`fre`, `eng`) onto one code per
// language, so a picked track survives a source switch.
func Normalize(code string) string {
	code = strings.ToLower(strings.TrimSpace(code))
	code = strings.ReplaceAll(code, "_", "-")
	if alias, ok := languageAliases[code]; ok {
		return alias
	}
	if i := strings.IndexByte(code, '-'); i > 0 {
		base := code[:i]
		if alias, ok := languageAliases[base]; ok {
			return alias
		}
		return base
	}
	return code
}

// LanguageName is the label the pickers show.
func LanguageName(code string) string {
	names := map[string]string{
		"en": "English", "fr": "French", "de": "German", "es": "Spanish",
		"pt": "Portuguese", "pt-br": "Portuguese (BR)", "it": "Italian",
		"ja": "Japanese", "zh": "Chinese", "ko": "Korean", "ru": "Russian",
		"ar": "Arabic", "nl": "Dutch", "pl": "Polish", "tr": "Turkish",
		"sv": "Swedish", "no": "Norwegian", "da": "Danish", "fi": "Finnish",
		"cs": "Czech", "hu": "Hungarian", "ro": "Romanian", "el": "Greek",
		"he": "Hebrew", "hi": "Hindi", "th": "Thai", "vi": "Vietnamese",
		"uk": "Ukrainian",
	}
	if name, ok := names[code]; ok {
		return name
	}
	if code == "" {
		return "Unknown"
	}
	return strings.ToUpper(code[:1]) + code[1:]
}

// Fetch downloads a track and returns WebVTT.
func (c *Client) Fetch(ctx context.Context, url string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", userAgent)
	res, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		return "", fmt.Errorf("subtitle HTTP %d", res.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return "", err
	}
	return ToVTT(string(data)), nil
}

var (
	cueTiming = regexp.MustCompile(`(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})`)
	htmlTags  = regexp.MustCompile(`<[^>]+>`)
)

// Cue is one subtitle line span.
type Cue struct {
	Start float64
	End   float64
	Text  string
}

// ToVTT converts SRT to WebVTT, and leaves VTT alone.
func ToVTT(body string) string {
	body = strings.TrimPrefix(body, "\ufeff")
	if strings.HasPrefix(strings.TrimSpace(body), "WEBVTT") {
		return body
	}
	body = strings.ReplaceAll(body, "\r\n", "\n")
	blocks := strings.Split(body, "\n\n")
	var out strings.Builder
	out.WriteString("WEBVTT\n\n")
	for _, block := range blocks {
		lines := strings.Split(strings.TrimSpace(block), "\n")
		index := -1
		for i, line := range lines {
			if strings.Contains(line, "-->") {
				index = i
				break
			}
		}
		if index < 0 {
			continue
		}
		timing := strings.ReplaceAll(lines[index], ",", ".")
		out.WriteString(timing)
		out.WriteString("\n")
		for _, line := range lines[index+1:] {
			out.WriteString(strings.TrimSpace(line))
			out.WriteString("\n")
		}
		out.WriteString("\n")
	}
	return out.String()
}

// Parse reads the cues from a WebVTT document.
func Parse(vtt string) []Cue {
	vtt = strings.TrimPrefix(vtt, "\ufeff")
	blocks := strings.Split(strings.ReplaceAll(vtt, "\r\n", "\n"), "\n\n")
	cues := make([]Cue, 0, len(blocks))
	for _, block := range blocks {
		lines := strings.Split(strings.TrimSpace(block), "\n")
		timingIndex := -1
		for i, line := range lines {
			if strings.Contains(line, "-->") {
				timingIndex = i
				break
			}
		}
		if timingIndex < 0 {
			continue
		}
		matches := cueTiming.FindStringSubmatch(lines[timingIndex])
		if matches == nil {
			continue
		}
		start := clock(matches[1], matches[2], matches[3], matches[4])
		end := clock(matches[5], matches[6], matches[7], matches[8])
		text := make([]string, 0, len(lines)-timingIndex-1)
		for _, line := range lines[timingIndex+1:] {
			clean := strings.TrimSpace(htmlTags.ReplaceAllString(line, ""))
			if clean != "" {
				text = append(text, clean)
			}
		}
		if len(text) == 0 || end <= start {
			continue
		}
		cues = append(cues, Cue{Start: start, End: end, Text: strings.Join(text, "\n")})
	}
	return cues
}

func clock(h, m, s, frac string) float64 {
	hours, _ := strconv.Atoi(h)
	minutes, _ := strconv.Atoi(m)
	seconds, _ := strconv.Atoi(s)
	fraction, _ := strconv.ParseFloat("0."+frac, 64)
	return float64(hours*3600+minutes*60+seconds) + fraction
}

// At returns the cue that covers a position, if any.
func At(cues []Cue, seconds float64) *Cue {
	var found *Cue
	for i := range cues {
		if seconds >= cues[i].Start && seconds < cues[i].End {
			found = &cues[i]
		}
	}
	return found
}
