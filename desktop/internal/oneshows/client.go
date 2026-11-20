// Package oneshows is the TV and movie catalogue: trending, search, detail,
// episodes, and the embed mirrors that carry their streams.
package oneshows

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	defaultSite = "https://www.1shows.org"
	imagesBase  = "https://image.tmdb.org/t/p"
	providerAPI = "https://api.viduki.net"
	userAgent   = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

// String is the wire name the API and the store use.
func (k Kind) String() string { return string(k) }

// Kind selects one of the app's three catalogues.
type Kind string

const (
	KindAnime Kind = "ANIME"
	KindTV    Kind = "TV"
	KindMovie Kind = "MOVIE"
)

// Client talks to the configured 1shows site and the provider feed.
type Client struct {
	site     func() string
	database func() string
	http     *http.Client
}

// New returns a client for the site the function returns at call time.
func New(site func() string) *Client {
	return &Client{
		site:     site,
		database: nil,
		http: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				Proxy:               http.ProxyFromEnvironment,
				MaxIdleConnsPerHost: 16,
				IdleConnTimeout:     60 * time.Second,
			},
		},
	}
}

// Site returns the active site without a trailing slash.
func (c *Client) Site() string { return strings.TrimRight(c.site(), "/") }

// Database derives the metadata host the site reads from, `db.<host>`.
func (c *Client) Database() string {
	if c.database != nil {
		return c.database()
	}
	parsed, err := url.Parse(c.Site())
	if err != nil || parsed.Host == "" {
		return "https://db.1shows.org"
	}
	host := strings.TrimPrefix(parsed.Host, "www.")
	return parsed.Scheme + "://db." + host
}

func (c *Client) get(ctx context.Context, target string, out any, origin string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Referer", c.Site()+"/")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", userAgent)
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 16<<20))
	if err != nil {
		return err
	}
	if res.StatusCode/100 != 2 {
		detail := string(raw)
		if len(detail) > 300 {
			detail = detail[:300]
		}
		return fmt.Errorf("HTTP %d · %s", res.StatusCode, detail)
	}
	return json.Unmarshal(raw, out)
}

// Card is the tile shape the grids render.
type Card struct {
	ID     string  `json:"id"`
	Kind   Kind    `json:"kind"`
	Title  string  `json:"title"`
	Poster string  `json:"poster,omitempty"`
	Year   int     `json:"year,omitempty"`
	Score  float64 `json:"score,omitempty"`
}

// Detail is a show or film page.
type Detail struct {
	ID        string       `json:"id"`
	Kind      Kind         `json:"kind"`
	Title     string       `json:"title"`
	Overview  string       `json:"overview"`
	Poster    string       `json:"poster,omitempty"`
	Backdrop  string       `json:"backdrop,omitempty"`
	Year      int          `json:"year,omitempty"`
	Score     float64      `json:"score,omitempty"`
	Genres    []string     `json:"genres"`
	Seasons   []SeasonInfo `json:"seasons"`
	Runtime   int          `json:"runtime,omitempty"`
}

// SeasonInfo is one season entry.
type SeasonInfo struct {
	Number       int    `json:"number"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episodeCount"`
}

// Episode is one row of a season.
type Episode struct {
	Number   int    `json:"number"`
	Title    string `json:"title"`
	Overview string `json:"overview"`
	Still    string `json:"still,omitempty"`
	AirDate  string `json:"airDate,omitempty"`
	Runtime  int    `json:"runtime,omitempty"`
}

// Mirror is one embed provider for an episode.
type Mirror struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	URL   string `json:"url"`
}

// ---------- wire ----------

type trendingDTO struct {
	TMDBIDs []string `json:"tmdbIds"`
}

type searchDTO struct {
	Results []searchResultDTO `json:"results"`
}

type searchResultDTO struct {
	ID          json.Number `json:"id"`
	MediaType   string      `json:"media_type"`
	Title       string      `json:"title"`
	Name        string      `json:"name"`
	Overview    string      `json:"overview"`
	PosterPath  string      `json:"poster_path"`
	ReleaseDate string      `json:"release_date"`
	FirstAir    string      `json:"first_air_date"`
	VoteAverage float64     `json:"vote_average"`
}

type genreDTO struct {
	Name string `json:"name"`
}

type seasonDTO struct {
	SeasonNumber int    `json:"season_number"`
	Name         string `json:"name"`
	EpisodeCount int    `json:"episode_count"`
}

type tvDTO struct {
	Name        string     `json:"name"`
	Overview    string     `json:"overview"`
	PosterPath  string     `json:"poster_path"`
	Backdrop    string     `json:"backdrop_path"`
	FirstAir    string     `json:"first_air_date"`
	VoteAverage float64    `json:"vote_average"`
	Genres      []genreDTO `json:"genres"`
	Seasons     []seasonDTO `json:"seasons"`
}

type movieDTO struct {
	Title       string     `json:"title"`
	Overview    string     `json:"overview"`
	PosterPath  string     `json:"poster_path"`
	Backdrop    string     `json:"backdrop_path"`
	ReleaseDate string     `json:"release_date"`
	VoteAverage float64    `json:"vote_average"`
	Runtime     int        `json:"runtime"`
	Genres      []genreDTO `json:"genres"`
}

type seasonDetailDTO struct {
	Episodes []episodeDTO `json:"episodes"`
}

type episodeDTO struct {
	Number   int    `json:"episode_number"`
	Name     string `json:"name"`
	Overview string `json:"overview"`
	Still    string `json:"still_path"`
	AirDate  string `json:"air_date"`
	Runtime  int    `json:"runtime"`
}

type externalIDsDTO struct {
	IMDBID string `json:"imdb_id"`
}

type providerDTO struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Movie string `json:"movie"`
	TV    string `json:"tv"`
}

type providersDTO struct {
	Providers []providerDTO `json:"providers"`
}

// ---------- calls ----------

// Trending returns the current row for one catalogue. The feed carries ids only,
// so each one needs a detail call; a dead id is dropped instead of failing the
// whole row.
func (c *Client) Trending(ctx context.Context, kind Kind) ([]Card, error) {
	feed := ""
	switch kind {
	case KindTV:
		feed = "tv"
	case KindMovie:
		feed = "movies"
	default:
		return nil, nil
	}
	var trending trendingDTO
	if err := c.get(ctx, c.Database()+"/api/management/trending/"+feed, &trending, ""); err != nil {
		return nil, err
	}

	ids := unique(trending.TMDBIDs)
	cards := make([]Card, len(ids))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for i, id := range ids {
		wg.Add(1)
		go func(i int, id string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			detail, err := c.Detail(ctx, kind, id)
			if err != nil {
				return
			}
			cards[i] = Card{ID: detail.ID, Kind: kind, Title: detail.Title, Poster: detail.Poster, Year: detail.Year, Score: detail.Score}
		}(i, id)
	}
	wg.Wait()

	out := make([]Card, 0, len(cards))
	for _, card := range cards {
		if card.ID != "" {
			out = append(out, card)
		}
	}
	return out, nil
}

// Search queries the site's TMDB-backed search.
func (c *Client) Search(ctx context.Context, query string) ([]Card, error) {
	if strings.TrimSpace(query) == "" {
		return nil, nil
	}
	var page searchDTO
	target := c.Site() + "/api/search/query?query=" + url.QueryEscape(strings.TrimSpace(query))
	if err := c.get(ctx, target, &page, ""); err != nil {
		return nil, err
	}
	out := make([]Card, 0, len(page.Results))
	for _, row := range page.Results {
		kind := KindTV
		if row.MediaType == "movie" {
			kind = KindMovie
		} else if row.MediaType != "tv" {
			continue
		}
		title := row.Name
		if kind == KindMovie {
			title = row.Title
		}
		out = append(out, Card{
			ID:     row.ID.String(),
			Kind:   kind,
			Title:  strings.TrimSpace(title),
			Poster: thumb(row.PosterPath, "w500"),
			Year:   yearOf(row.FirstAir, row.ReleaseDate),
			Score:  row.VoteAverage,
		})
	}
	return out, nil
}

// Detail fetches a show or film.
func (c *Client) Detail(ctx context.Context, kind Kind, id string) (Detail, error) {
	switch kind {
	case KindTV:
		var row tvDTO
		if err := c.get(ctx, c.Site()+"/api/tv/"+url.PathEscape(id), &row, ""); err != nil {
			return Detail{}, err
		}
		detail := Detail{
			ID:       id,
			Kind:     kind,
			Title:    fallbackTitle(row.Name),
			Overview: strings.TrimSpace(row.Overview),
			Poster:   thumb(row.PosterPath, "w500"),
			Backdrop: thumb(row.Backdrop, "w1280"),
			Year:     yearOf(row.FirstAir),
			Score:    row.VoteAverage,
		}
		for _, genre := range row.Genres {
			if name := strings.TrimSpace(genre.Name); name != "" {
				detail.Genres = append(detail.Genres, name)
			}
		}
		for _, season := range row.Seasons {
			name := strings.TrimSpace(season.Name)
			if name == "" {
				name = fmt.Sprintf("Season %d", season.SeasonNumber)
			}
			detail.Seasons = append(detail.Seasons, SeasonInfo{Number: season.SeasonNumber, Name: name, EpisodeCount: season.EpisodeCount})
		}
		return detail, nil
	case KindMovie:
		var row movieDTO
		if err := c.get(ctx, c.Site()+"/api/movie/"+url.PathEscape(id), &row, ""); err != nil {
			return Detail{}, err
		}
		detail := Detail{
			ID:       id,
			Kind:     kind,
			Title:    fallbackTitle(row.Title),
			Overview: strings.TrimSpace(row.Overview),
			Poster:   thumb(row.PosterPath, "w500"),
			Backdrop: thumb(row.Backdrop, "w1280"),
			Year:     yearOf(row.ReleaseDate),
			Score:    row.VoteAverage,
			Runtime:  row.Runtime,
		}
		for _, genre := range row.Genres {
			if name := strings.TrimSpace(genre.Name); name != "" {
				detail.Genres = append(detail.Genres, name)
			}
		}
		return detail, nil
	}
	return Detail{}, fmt.Errorf("1shows has no %s catalogue", kind)
}

// Season returns one season's episodes.
func (c *Client) Season(ctx context.Context, id string, season int) ([]Episode, error) {
	var page seasonDetailDTO
	target := fmt.Sprintf("%s/api/tv/%s/season/%d", c.Site(), url.PathEscape(id), season)
	if err := c.get(ctx, target, &page, ""); err != nil {
		return nil, err
	}
	out := make([]Episode, 0, len(page.Episodes))
	for _, row := range page.Episodes {
		out = append(out, Episode{
			Number:   row.Number,
			Title:    strings.TrimSpace(row.Name),
			Overview: strings.TrimSpace(row.Overview),
			Still:    thumb(row.Still, "w300"),
			AirDate:  strings.TrimSpace(row.AirDate),
			Runtime:  row.Runtime,
		})
	}
	return out, nil
}

// IMDBID returns the id subtitle providers key their catalogues by.
func (c *Client) IMDBID(ctx context.Context, kind Kind, id string) string {
	feed := ""
	switch kind {
	case KindTV:
		feed = "tv"
	case KindMovie:
		feed = "movie"
	default:
		return ""
	}
	var row externalIDsDTO
	if err := c.get(ctx, fmt.Sprintf("%s/api/%s/%s/external_ids", c.Site(), feed, url.PathEscape(id)), &row, ""); err != nil {
		return ""
	}
	if strings.HasPrefix(row.IMDBID, "tt") {
		return row.IMDBID
	}
	return ""
}

// Mirrors lists the embed pages for one episode, cheapest first. The viduki
// mirrors gate on a captcha, so they go last and usually fail without a human.
func (c *Client) Mirrors(ctx context.Context, id string, kind Kind, season, episode int) ([]Mirror, error) {
	var feed providersDTO
	target := providerAPI + "/embed_providers?site=1shows"
	if err := c.get(ctx, target, &feed, c.Site()); err != nil {
		return nil, err
	}
	return buildMirrors(feed.Providers, kind, id, season, episode), nil
}

// buildMirrors expands each provider template and orders the captcha mirrors
// last, so a normal mirror is always tried first.
func buildMirrors(providers []providerDTO, kind Kind, id string, season, episode int) []Mirror {
	out := make([]Mirror, 0, len(providers))
	for _, provider := range providers {
		template := provider.TV
		if kind == KindMovie {
			template = provider.Movie
		}
		if template == "" {
			continue
		}
		link := strings.NewReplacer(
			"{id}", url.PathEscape(id),
			"{s}", fmt.Sprint(season),
			"{e}", fmt.Sprint(episode),
		).Replace(template)
		mirror := Mirror{ID: provider.ID, Label: provider.Label, URL: link}
		if strings.Contains(link, "viduki.net") {
			out = append(out, mirror)
		} else {
			out = append([]Mirror{mirror}, out...)
		}
	}
	return out
}

// ---------- helpers ----------

func thumb(path, size string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return ""
	}
	if strings.HasPrefix(path, "http") {
		return path
	}
	return imagesBase + "/" + size + "/" + strings.TrimPrefix(path, "/")
}

func yearOf(dates ...string) int {
	for _, date := range dates {
		date = strings.TrimSpace(date)
		if len(date) < 4 {
			continue
		}
		year := 0
		for _, ch := range date[:4] {
			if ch < '0' || ch > '9' {
				year = 0
				break
			}
			year = year*10 + int(ch-'0')
		}
		if year > 0 {
			return year
		}
	}
	return 0
}

func fallbackTitle(title string) string {
	if trimmed := strings.TrimSpace(title); trimmed != "" {
		return trimmed
	}
	return "Untitled"
}

func unique(values []string) []string {
	seen := make(map[string]bool, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		if !seen[value] {
			seen[value] = true
			out = append(out, value)
		}
	}
	return out
}
