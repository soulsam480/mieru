// Package miruro is the anime catalogue client. It speaks the same API as the
// Android app, including the payload encoding the edge applies.
package miruro

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const (
	userAgent       = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
	payloadKey      = "miruro/catalog"
	defaultPageSize = 30
)

// Client talks to whichever Miruro base the user selected.
type Client struct {
	base func() string
	http *http.Client
}

// New returns a client for the base URL the function returns at call time, so
// switching mirrors takes effect on the next request.
func New(base func() string) *Client {
	return &Client{
		base: base,
		http: &http.Client{
			Timeout: 40 * time.Second,
			Transport: &http.Transport{
				Proxy:                 http.ProxyFromEnvironment,
				MaxIdleConnsPerHost:   8,
				IdleConnTimeout:       60 * time.Second,
				TLSHandshakeTimeout:   15 * time.Second,
				ResponseHeaderTimeout: 25 * time.Second,
			},
		},
	}
}

// Base returns the active catalogue base without a trailing slash.
func (c *Client) Base() string { return strings.TrimRight(c.base(), "/") }

// Health reports whether the configured edge answers.
func (c *Client) Health(ctx context.Context) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Base()+"/health", nil)
	if err != nil {
		return false
	}
	c.decorate(req)
	res, err := c.http.Do(req)
	if err != nil {
		return false
	}
	defer res.Body.Close()
	return res.StatusCode/100 == 2
}

func (c *Client) decorate(req *http.Request) {
	// The edge answers 403 unless the request looks like the site's own fetch:
	// the full Sec-Fetch set plus an Origin, all derived from the configured
	// base so mirrors behave the same.
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	req.Header.Set("Sec-Fetch-Mode", "cors")
	req.Header.Set("Sec-Fetch-Dest", "empty")
	req.Header.Set("Origin", c.Base())
	req.Header.Set("Referer", c.Base()+"/")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("User-Agent", userAgent)
}

func (c *Client) get(ctx context.Context, path string, query url.Values, out any) error {
	target := c.Base() + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return err
	}
	c.decorate(req)

	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(res.Body, 32<<20))
	if err != nil {
		return err
	}
	if res.StatusCode/100 != 2 {
		detail := string(raw)
		if len(detail) > 300 {
			detail = detail[:300]
		}
		return &Error{Code: res.StatusCode, Detail: detail}
	}
	body, err := DecodePayload(raw)
	if err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	return json.Unmarshal(body, out)
}

// Error is an HTTP failure with the edge's own words attached.
type Error struct {
	Code   int
	Detail string
}

func (e *Error) Error() string { return fmt.Sprintf("HTTP %d · %s", e.Code, e.Detail) }

// DecodePayload reverses `gzip(xor(bytes, "miruro/catalog"))`, the encoding the
// web client applies to every API response.
func DecodePayload(raw []byte) ([]byte, error) {
	key := []byte(payloadKey)
	plain := make([]byte, len(raw))
	for i := range raw {
		plain[i] = raw[i] ^ key[i%len(key)]
	}
	zr, err := gzip.NewReader(bytes.NewReader(plain))
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	return io.ReadAll(io.LimitReader(zr, 64<<20))
}

// ---------- catalogue ----------

// Titles is the three names a show can carry.
type Titles struct {
	Romaji  string `json:"romaji"`
	English string `json:"english"`
	Native  string `json:"native"`
}

// Best picks the name the rest of the app shows.
func (t Titles) Best() string {
	for _, candidate := range []string{t.English, t.Romaji, t.Native} {
		if strings.TrimSpace(candidate) != "" {
			return strings.TrimSpace(candidate)
		}
	}
	return "Untitled"
}

// EpisodeCounts is how many episodes each track has.
type EpisodeCounts struct {
	Raw int `json:"raw"`
	Sub int `json:"sub"`
	Dub int `json:"dub"`
}

// AiredSeason is the season a show belongs to.
type AiredSeason struct {
	Name   string `json:"name"`
	Number int    `json:"number"`
}

// Anime is one catalogue entry.
type Anime struct {
	ID              string        `json:"id"`
	Title           Titles        `json:"title"`
	Format          string        `json:"format"`
	Status          string        `json:"status"`
	Season          string        `json:"season"`
	SeasonYear      int           `json:"season_year"`
	AverageScore    int           `json:"average_score"`
	Popularity      int           `json:"popularity"`
	Description     string        `json:"description"`
	CoverURL        string        `json:"cover_url"`
	BannerURL       string        `json:"banner_url"`
	BackgroundURL   string        `json:"background_url"`
	EpisodeCount    int           `json:"episode_count"`
	EpisodeCounts   EpisodeCounts `json:"episode_counts"`
	ClusterPosition int           `json:"show_cluster_position"`
	AiredSeason     AiredSeason   `json:"aired_season"`
}

// ShownEpisodes is the count the cards display.
func (a Anime) ShownEpisodes() int {
	if a.EpisodeCounts.Sub > 0 {
		return a.EpisodeCounts.Sub
	}
	if a.EpisodeCounts.Raw > 0 {
		return a.EpisodeCounts.Raw
	}
	return a.EpisodeCount
}

// CatalogPage is one page of the grid.
type CatalogPage struct {
	Data       []Anime `json:"data"`
	NextCursor string  `json:"next_cursor"`
	HasMore    bool    `json:"has_more"`
}

// ShowEnvelope is a show plus the order its franchise should be read in.
type ShowEnvelope struct {
	Data         []Anime  `json:"data"`
	OrderRelease []string `json:"order_release"`
}

// Catalog asks for one page, newest or most popular first.
func (c *Client) Catalog(ctx context.Context, limit int, sortBy, q string, genres []string, cursor string) (CatalogPage, error) {
	if limit <= 0 {
		limit = defaultPageSize
	}
	query := url.Values{}
	query.Set("limit", fmt.Sprint(limit))
	query.Set("sort", sortBy)
	if strings.TrimSpace(q) != "" {
		query.Set("q", strings.TrimSpace(q))
	}
	if len(genres) > 0 {
		query.Set("genre_all", strings.Join(genres, ","))
	}
	if cursor != "" {
		query.Set("cursor", cursor)
	}
	var page CatalogPage
	return page, c.get(ctx, "/api/v1/anime", query, &page)
}

// Show returns a show and its franchise entries.
func (c *Client) Show(ctx context.Context, animeID string) (ShowEnvelope, error) {
	var show ShowEnvelope
	return show, c.get(ctx, "/api/v1/anime/"+url.PathEscape(animeID)+"/show", nil, &show)
}

// AnimeByIDs hydrates ids in one call, for the franchise switcher.
func (c *Client) AnimeByIDs(ctx context.Context, ids []string) ([]Anime, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	query := url.Values{}
	query.Set("id_in", strings.Join(ids, ","))
	query.Set("limit", "200")
	var page CatalogPage
	return page.Data, c.get(ctx, "/api/v1/anime", query, &page)
}

// AiringEntry is one row of the schedule feed.
type AiringEntry struct {
	AnimeID string  `json:"anime_id"`
	AirAt   string  `json:"air_at"`
	Episode float64 `json:"episode_number"`
	Track   string  `json:"track"`
}

// Ep returns the episode number as an int.
func (a AiringEntry) Ep() int { return int(a.Episode) }

// ScheduleEnvelope wraps the airing feed.
type ScheduleEnvelope struct {
	Data []AiringEntry `json:"data"`
}

// RecentlyAired is what came out lately, newest first, one row per show. Only
// subbed airings are asked for: the raw feed drags in long-running kids shows
// and region-locked simulcasts.
func (c *Client) RecentlyAired(ctx context.Context, daysBack int) ([]AiringEntry, error) {
	if daysBack < 7 {
		daysBack = 7
	}
	today := time.Now()
	query := url.Values{}
	query.Set("from", today.AddDate(0, 0, -daysBack).Format("2006-01-02"))
	query.Set("to", today.Format("2006-01-02"))
	query.Set("track", "raw,sub")
	query.Set("limit", "10000")

	var envelope ScheduleEnvelope
	if err := c.get(ctx, "/api/v1/schedule", query, &envelope); err != nil {
		return nil, err
	}
	sort.SliceStable(envelope.Data, func(i, j int) bool {
		return envelope.Data[i].AirAt > envelope.Data[j].AirAt
	})
	seen := map[string]bool{}
	out := make([]AiringEntry, 0, len(envelope.Data))
	for _, entry := range envelope.Data {
		if entry.Track != "" && entry.Track != "sub" {
			continue
		}
		if seen[entry.AnimeID] {
			continue
		}
		seen[entry.AnimeID] = true
		out = append(out, entry)
	}
	return out, nil
}

// ---------- episodes ----------

// Episode is one row of a show's episode list.
type Episode struct {
	Number          float64          `json:"episode_number"`
	AbsoluteNumber  float64          `json:"absolute_number"`
	Title           string           `json:"title"`
	ThumbnailURL    string           `json:"thumbnail_url"`
	DurationSeconds int              `json:"duration_seconds"`
	AiredOn         string           `json:"aired_on"`
	SkipTimes       []map[string]any `json:"skip_times"`
	Kind            string           `json:"kind"`
}

// Ep is the episode number as an int.
func (e Episode) Ep() int { return int(e.Number) }

// ReleasedOn reports whether the air date is on or before today.
func (e Episode) ReleasedOn(today time.Time) bool {
	if strings.TrimSpace(e.AiredOn) == "" {
		return true
	}
	date, err := time.Parse("2006-01-02", e.AiredOn[:min(10, len(e.AiredOn))])
	if err != nil {
		return true
	}
	return !date.After(today)
}

// EpisodesEnvelope wraps the episode list.
type EpisodesEnvelope struct {
	Data    []Episode `json:"data"`
	HasMore bool      `json:"has_more"`
}

// Episodes returns a show's regular episodes. Specials carry synthetic numbers
// in the two-thousands and are dropped unless nothing else exists.
func (c *Client) Episodes(ctx context.Context, animeID string) ([]Episode, error) {
	query := url.Values{}
	query.Set("limit", "10000")
	var envelope EpisodesEnvelope
	if err := c.get(ctx, "/api/v1/anime/"+url.PathEscape(animeID)+"/episodes", query, &envelope); err != nil {
		return nil, err
	}
	regular := make([]Episode, 0, len(envelope.Data))
	for _, episode := range envelope.Data {
		if episode.Kind == "" || episode.Kind == "regular" {
			regular = append(regular, episode)
		}
	}
	if len(regular) == 0 {
		return envelope.Data, nil
	}
	return regular, nil
}

// ---------- playback ----------

// Subtitle is a side-loaded track the provider already ships.
type Subtitle struct {
	Language  string `json:"language"`
	Label     string `json:"label"`
	File      string `json:"file"`
	Format    string `json:"format"`
	IsDefault bool   `json:"default"`
}

// Stream is one file a server offers.
type Stream struct {
	URL        string `json:"url"`
	Format     string `json:"format"`
	Quality    string `json:"quality"`
	Resolution string `json:"resolution"`
	Codec      string `json:"codec"`
}

// Server is one host with its headers and files.
type Server struct {
	Server  string            `json:"server"`
	Headers map[string]string `json:"headers"`
	Streams []Stream          `json:"streams"`
}

// Provider groups servers under one provider name.
type Provider struct {
	Provider  string     `json:"provider"`
	Subtitles []Subtitle `json:"subtitles"`
	Servers   []Server   `json:"servers"`
}

// Track is a sub or dub track with its providers.
type Track struct {
	Track     string     `json:"track"`
	Providers []Provider `json:"providers"`
}

// PlayEnvelope is everything the play endpoint offers for one episode.
type PlayEnvelope struct {
	EpisodeNumber float64 `json:"episode_number"`
	Requested     string  `json:"requested_track"`
	Tracks        []Track `json:"tracks"`
}

// Play asks for one episode's sources.
func (c *Client) Play(ctx context.Context, animeID string, episode int) (PlayEnvelope, error) {
	var play PlayEnvelope
	path := fmt.Sprintf("/api/v1/anime/%s/episodes/%d/play", url.PathEscape(animeID), episode)
	return play, c.get(ctx, path, nil, &play)
}

// Source is a playable file with everything it needs carried through.
type Source struct {
	Track     string            `json:"track"`
	Label     string            `json:"label"`
	URL       string            `json:"url"`
	Mime      string            `json:"mime"`
	Headers   map[string]string `json:"headers"`
	Provider  string            `json:"provider"`
	Server    string            `json:"server"`
	Subtitles []Subtitle        `json:"subtitles"`
}

var providerOrder = []string{"kickassanime", "anikoto", "icarus"}

func providerRank(name string) int {
	for i, candidate := range providerOrder {
		if strings.EqualFold(name, candidate) {
			return i
		}
	}
	return 99
}

func trackRank(track string, dub bool) int {
	order := []string{"ssub", "sub", "dub", "raw"}
	if dub {
		order = []string{"dub", "ssub", "sub", "raw"}
	}
	for i, candidate := range order {
		if strings.EqualFold(track, candidate) {
			return i
		}
	}
	return 99
}

// Sources flattens the play response into the list the player offers, honouring
// the viewer's sub/dub preference and the provider preference order.
func Sources(play PlayEnvelope, dub bool) []Source {
	tracks := append([]Track(nil), play.Tracks...)
	sort.SliceStable(tracks, func(i, j int) bool {
		return trackRank(tracks[i].Track, dub) < trackRank(tracks[j].Track, dub)
	})

	var out []Source
	for _, track := range tracks {
		providers := append([]Provider(nil), track.Providers...)
		sort.SliceStable(providers, func(i, j int) bool {
			return providerRank(providers[i].Provider) < providerRank(providers[j].Provider)
		})
		for _, provider := range providers {
			for _, server := range provider.Servers {
				for _, stream := range server.Streams {
					format := strings.ToLower(stream.Format)
					if format != "hls" && format != "mp4" {
						continue
					}
					headers := map[string]string{}
					for key, value := range server.Headers {
						headers[key] = value
					}
					// The media CDN 403s every segment unless Origin accompanies
					// Referer; derive it rather than hardcoding rotating hosts.
					if origin := originOf(headers["Referer"]); origin != "" {
						headers["Origin"] = origin
					}
					mime := "hls"
					if format == "mp4" || strings.Contains(strings.ToLower(stream.URL), ".mp4") {
						mime = "mp4"
					}
					out = append(out, Source{
						Track:     strings.ToUpper(track.Track),
						Label:     strings.ToUpper(track.Track) + " · " + provider.Provider + " · " + server.Server,
						URL:       stream.URL,
						Mime:      mime,
						Headers:   headers,
						Provider:  provider.Provider,
						Server:    server.Server,
						Subtitles: usableSubtitles(provider.Subtitles),
					})
					break
				}
			}
		}
	}
	return out
}

// usableSubtitles keeps the tracks the browser can play; the rest are dropped
// rather than shown and failing.
func usableSubtitles(tracks []Subtitle) []Subtitle {
	out := make([]Subtitle, 0, len(tracks))
	for _, track := range tracks {
		path := strings.SplitN(track.File, "?", 2)[0]
		if strings.HasSuffix(strings.ToLower(path), ".vtt") {
			out = append(out, track)
		}
	}
	return out
}

func originOf(raw string) string {
	if strings.TrimSpace(raw) == "" {
		return ""
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return ""
	}
	return parsed.Scheme + "://" + parsed.Host
}
