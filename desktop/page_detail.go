package main

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/store"
)

type detailPage struct {
	key  string
	id   string
	kind string

	loading bool
	err     string
	token   int

	// anime
	anime       miruro.Anime
	shownID     string
	franchise   []miruro.Anime
	episodes    []miruro.Episode
	released    map[int]bool
	season      int
	episodeList ui.ListState

	// shows
	detail    oneshows.Detail
	seasons   []oneshows.SeasonInfo
	episodes2 []oneshows.Episode

	watched map[int]float64
	resume  int
}

func (p *detailPage) reset(key, kind, id string) {
	*p = detailPage{key: key, kind: kind, id: id, loading: true}
	p.watched = map[int]float64{}
}

func (p *detailPage) enterAnime(a *App, id string) {
	key := "ANIME:" + id
	if p.key == key {
		p.refresh(a)
		return
	}
	p.reset(key, "ANIME", id)
	p.shownID = id
	go p.loadAnime(a, id)
}

func (p *detailPage) enterShow(a *App, kind oneshows.Kind, id string) {
	key := string(kind) + ":" + id
	if p.key == key {
		p.refresh(a)
		return
	}
	p.reset(key, string(kind), id)
	go p.loadShow(a, kind, id)
}

func (p *detailPage) loadAnime(a *App, id string) {
	if a.noNetwork {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	token := p.token
	show, err := a.miruro.Show(ctx, id)
	if err != nil {
		a.update(func() {
			if p.token == token && p.key == "ANIME:"+id {
				p.loading, p.err = false, err.Error()
			}
		})
		return
	}

	order := make(map[string]int, len(show.OrderRelease))
	for i, showID := range show.OrderRelease {
		order[showID] = i
	}
	franchise := append([]miruro.Anime(nil), show.Data...)
	sort.SliceStable(franchise, func(i, j int) bool {
		return order[franchise[i].ID] < order[franchise[j].ID]
	})
	entry := franchise[0]
	for _, candidate := range franchise {
		if candidate.ID == id {
			entry = candidate
		}
	}

	episodes, err := a.miruro.Episodes(ctx, id)
	if err != nil {
		a.update(func() {
			if p.token == token && p.key == "ANIME:"+id {
				p.loading, p.err = false, err.Error()
			}
		})
		return
	}

	a.update(func() {
		if p.token != token || p.key != "ANIME:"+id {
			return
		}
		p.franchise = franchise
		p.shownID = id
		p.anime = entry
		p.episodes = episodes
		p.released = releasedNumbers(episodes, time.Now())
		p.watched = a.watchedFractions(id, 0, episodes)
		p.loading = false
		p.err = ""
		p.episodeList.ScrollTo(0, ui.Start)
		a.rememberTitle(id, titleInfo{Title: entry.Title.Best(), Cover: entry.CoverURL})
	})
}

func (p *detailPage) loadShow(a *App, kind oneshows.Kind, id string) {
	if a.noNetwork {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	token := p.token
	detail, err := a.shows.Detail(ctx, kind, id)
	if err != nil {
		a.update(func() {
			if p.token == token && p.key == string(kind)+":"+id {
				p.loading, p.err = false, err.Error()
			}
		})
		return
	}

	season := 1
	if kind == oneshows.KindTV {
		season = 0
		for _, candidate := range detail.Seasons {
			if candidate.Number > 0 && candidate.EpisodeCount > 0 {
				season = candidate.Number
				break
			}
		}
	}

	var episodes []oneshows.Episode
	if kind == oneshows.KindTV && season > 0 {
		episodes, err = a.shows.Season(ctx, id, season)
		if err != nil {
			a.update(func() {
				if p.token == token && p.key == string(kind)+":"+id {
					p.loading, p.err = false, err.Error()
				}
			})
			return
		}
	}

	a.update(func() {
		if p.token != token || p.key != string(kind)+":"+id {
			return
		}
		p.detail = detail
		p.seasons = detail.Seasons
		p.episodes2 = episodes
		p.season = season
		p.loading = false
		p.err = ""
		p.episodeList.ScrollTo(0, ui.Start)
		p.watched = a.watchedShows(id, season, episodes)
		a.rememberTitle(id, titleInfo{Title: detail.Title, Cover: detail.Poster})
	})
}

func (p *detailPage) selectSeason(a *App, season int) {
	if p.kind != "TV" || season == p.season || season <= 0 {
		return
	}
	p.season = season
	p.episodes2 = nil
	token := p.token
	id := p.id
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		episodes, err := a.shows.Season(ctx, id, season)
		a.update(func() {
			if p.token != token || p.key != "TV:"+id || p.season != season {
				return
			}
			if err != nil {
				p.err = err.Error()
				return
			}
			p.episodes2 = episodes
			p.episodeList.ScrollTo(0, ui.Start)
			p.watched = a.watchedShows(id, season, episodes)
		})
	}()
}

func (p *detailPage) refresh(a *App) {
	if p.kind == "ANIME" {
		p.watched = a.watchedFractions(p.shownID, 0, p.episodes)
		return
	}
	if p.kind == "TV" {
		p.watched = a.watchedShows(p.id, p.season, p.episodes2)
	}
}

func (a *App) watchedFractions(mediaID string, season int, episodes []miruro.Episode) map[int]float64 {
	snapshot := a.progress.Snapshot()
	out := make(map[int]float64, len(episodes))
	for _, episode := range episodes {
		key := fmt.Sprintf("%s:%d", mediaID, episode.Ep())
		if season > 0 {
			key = fmt.Sprintf("%s:%d:%d", mediaID, season, episode.Ep())
		}
		if saved, ok := snapshot.Episodes[key]; ok {
			out[episode.Ep()] = saved.Fraction()
		}
	}
	return out
}

func (a *App) watchedShows(mediaID string, season int, episodes []oneshows.Episode) map[int]float64 {
	snapshot := a.progress.Snapshot()
	out := make(map[int]float64, len(episodes))
	for _, episode := range episodes {
		key := fmt.Sprintf("%s:%d:%d", mediaID, season, episode.Number)
		if saved, ok := snapshot.Episodes[key]; ok {
			out[episode.Number] = saved.Fraction()
		}
	}
	return out
}

// ---------- view ----------

func (p *detailPage) view(c *ui.Context, a *App) {
	switch {
	case p.loading:
		a.loading(c)
	case p.err != "":
		ui.Column(c).Fill().Padding(24).Children(func() {
			a.errorBox(c, p.err, func() {
				if p.kind == "ANIME" {
					p.enterAnime(a, p.id)
				} else {
					p.enterShow(a, oneshows.Kind(p.kind), p.id)
				}
			})
		})
	case p.kind == "ANIME":
		p.animeView(c, a)
	default:
		p.showView(c, a)
	}
}

// detailSide is the information rail: poster, title, meta, genres, overview
// and the actions, leaving the rest of the window to the episode list.
func (a *App) detailSide(c *ui.Context, title, poster, meta, overview string, genres []string, buttons func()) {
	t := c.Theme()
	ui.Box(c).Width(370).Background(t.Surface).Children(func() {
		ui.Column(c).Fill().Padding(20).Gap(10).Children(func() {
			if ui.Button(c, "‹ Back").Width(88).Clicked() {
				a.router.Back()
			}
			ui.Row(c).FillWidth().Center().Children(func() { a.poster(c, poster, 180) })
			ui.Text(c, title).FontSize(24).Bold().MaxLines(3)
			if meta != "" {
				ui.Text(c, meta).FontSize(12).TextColor(t.TextMuted)
			}
			if len(genres) > 0 {
				ui.Text(c, strings.Join(genres, " · ")).FontSize(11).TextColor(t.TextMuted).MaxLines(2)
			}
			if overview != "" {
				ui.Text(c, overview).FontSize(12).TextColor(t.TextMuted).MaxLines(7).LineHeight(1.4)
			}
			ui.Column(c).Gap(8).Children(buttons)
		})
	})
}

func (p *detailPage) animeView(c *ui.Context, a *App) {
	episode := nextEpisode(p.episodes, p.watched)
	aired := len(p.released) > 0
	position := a.progress.Position(p.shownID, 0, episode)
	label := fmt.Sprintf("Play EP %d", episode)
	if position > 0 {
		label = fmt.Sprintf("Continue · EP %d", episode)
	}
	if !aired {
		label = "Not aired yet"
	}

	meta := make([]string, 0, 4)
	if year := formatYear(p.anime.SeasonYear); year != "" {
		meta = append(meta, year)
	}
	if p.anime.Format != "" {
		meta = append(meta, p.anime.Format)
	}
	if p.anime.AverageScore > 0 {
		meta = append(meta, fmt.Sprintf("★ %d", p.anime.AverageScore))
	}
	if p.anime.Status != "" {
		meta = append(meta, p.anime.Status)
	}
	if count := p.anime.ShownEpisodes(); count > 0 {
		meta = append(meta, fmt.Sprintf("%d episodes", count))
	}

	visible := make([]miruro.Episode, 0, len(p.episodes))
	for _, episode := range p.episodes {
		if realSchedule(episode) {
			visible = append(visible, episode)
		}
	}

	ui.Row(c).Fill().AlignItems(ui.Stretch).Children(func() {
		a.detailSide(c, p.anime.Title.Best(), p.anime.CoverURL, strings.Join(meta, " · "),
			stripHTML(p.anime.Description), nil, func() {
				if p.playButton(c, label, aired) {
					p.play(a, episode)
				}
				ui.Row(c).Gap(8).Children(func() { p.heroActions(c, a) })
			})

		ui.Column(c).Grow(1).Padding(24, 18).Gap(10).Children(func() {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Text(c, "Episodes").FontSize(18).Bold()
				muted(c, fmt.Sprintf("%d released", len(p.released)))
				ui.Spacer(c).Grow(1)
				if len(p.franchise) > 1 {
					ui.MenuButton(c, "Seasons", func(m *ui.Menu) {
						for _, season := range p.franchise {
							season := season
							if m.Item(seasonTitle(season)).Checked(season.ID == p.shownID).Chosen() && season.ID != p.shownID {
								p.enterAnime(a, season.ID)
							}
						}
					})
				}
			})
			if len(visible) == 0 {
				a.empty(c, "No episodes yet.")
				return
			}
			p.episodeList.Key = func(i int) any { return visible[i].Ep() }
			ui.List(c, &p.episodeList, len(visible), func(i int) {
				episode := visible[i]
				released := p.released[episode.Ep()]
				p.episodeRow(c, a, episode.Ep(), displayTitle(episode), "", episode.ThumbnailURL, episode.AiredOn, episode.DurationSeconds, released, p.watched[episode.Ep()])
			}).Gap(8).Padding(0, 12, 0, 0).Grow(1)
		})
	})
}

func (p *detailPage) showView(c *ui.Context, a *App) {
	meta := make([]string, 0, 4)
	if year := formatYear(p.detail.Year); year != "" {
		meta = append(meta, year)
	}
	if score := formatScore(p.detail.Score); score != "" {
		meta = append(meta, score)
	}
	if p.kind == "TV" && len(p.seasons) > 0 {
		count := 0
		for _, season := range p.seasons {
			if season.Number > 0 && season.EpisodeCount > 0 {
				count++
			}
		}
		if count > 0 {
			meta = append(meta, fmt.Sprintf("%d seasons", count))
		}
	}
	if p.detail.Runtime > 0 {
		meta = append(meta, fmt.Sprintf("%d min", p.detail.Runtime))
	}

	ui.Row(c).Fill().AlignItems(ui.Stretch).Children(func() {
		a.detailSide(c, p.detail.Title, p.detail.Poster, strings.Join(meta, " · "),
			p.detail.Overview, p.detail.Genres, func() {
				if p.kind == "MOVIE" {
					if p.playButton(c, "Play movie", true) {
						p.play(a, 1)
					}
				} else if len(p.episodes2) > 0 {
					next := p.episodes2[0].Number
					for _, episode := range p.episodes2 {
						if fraction, ok := p.watched[episode.Number]; !ok || fraction < 0.9 {
							next = episode.Number
							break
						}
					}
					if p.playButton(c, fmt.Sprintf("Play S%d E%d", p.season, next), true) {
						p.play(a, next)
					}
				}
				ui.Row(c).Gap(8).Children(func() { p.heroActions(c, a) })
			})

		if p.kind == "MOVIE" {
			ui.Box(c).Grow(1).Padding(24, 18).Children(func() {
				if bitmap := a.bitmap(p.detail.Backdrop); bitmap != nil {
					ui.Image(c, bitmap).Fill().Fit(ui.Cover).Radius(12)
				} else {
					ui.Box(c).Fill().Background(c.Theme().Surface).Radius(12)
				}
			})
			return
		}

		ui.Column(c).Grow(1).Padding(24, 18).Gap(10).Children(func() {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Text(c, "Episodes").FontSize(18).Bold()
				ui.Spacer(c).Grow(1)
				ui.MenuButton(c, fmt.Sprintf("Season %d", p.season), func(m *ui.Menu) {
					for _, season := range p.seasons {
						if season.Number <= 0 || season.EpisodeCount == 0 {
							continue
						}
						season := season
						if m.Item(fmt.Sprintf("Season %d", season.Number)).Checked(season.Number == p.season).Chosen() && season.Number != p.season {
							p.selectSeason(a, season.Number)
						}
					}
				})
			})
			if p.episodes2 == nil {
				a.loading(c)
				return
			}
			episodes := p.episodes2
			p.episodeList.Key = func(i int) any { return episodes[i].Number }
			ui.List(c, &p.episodeList, len(episodes), func(i int) {
				episode := episodes[i]
				p.episodeRow(c, a, episode.Number, episode.Title, episode.Overview, episode.Still, episode.AirDate, episode.Runtime*60, true, p.watched[episode.Number])
			}).Gap(8).Padding(0, 12, 0, 0).Grow(1)
		})
	})
}

func (p *detailPage) episodeRow(c *ui.Context, a *App, number int, title, overview, still, airDate string, durationSeconds int, released bool, watched float64) {
	t := c.Theme()
	row := ui.ButtonBase(c).FillWidth().Padding(6, 10).Radius(8).Background(t.Surface).Disabled(!released)
	if row.Hovered() {
		row = row.Background(t.SurfaceHover)
	}
	row.Children(func() {
		ui.Row(c).FillWidth().Gap(12).AlignItems(ui.Center).Children(func() {
			ui.Text(c, fmt.Sprintf("%d", number)).FontSize(12).TextColor(t.TextMuted).Width(28)
			if still != "" {
				if bitmap := a.bitmap(still); bitmap != nil {
					ui.Image(c, bitmap).Size(112, 63).Fit(ui.Cover).Radius(6)
				} else {
					ui.Box(c).Size(112, 63).Radius(6).Background(t.Background)
				}
			}
			ui.Column(c).Grow(1).Gap(3).Children(func() {
				if title == "" {
					title = fmt.Sprintf("Episode %d", number)
				}
				ui.Text(c, title).FontSize(13).Bold().SingleLine()
				if overview != "" {
					ui.Text(c, overview).FontSize(11).TextColor(t.TextMuted).MaxLines(2).LineHeight(1.35)
				}
				bits := make([]string, 0, 2)
				if !released {
					bits = append(bits, "airs "+shortDate(airDate))
				}
				if watched >= 0.01 {
					value := watched * 100
					if value < 1 {
						value = 1
					}
					bits = append(bits, fmt.Sprintf("%.0f%% watched", value))
				}
				if len(bits) > 0 && overview == "" {
					muted(c, strings.Join(bits, " · "))
				}
			})
			if durationSeconds > 0 {
				ui.Text(c, fmt.Sprintf("%dm", durationSeconds/60)).FontSize(11).TextColor(t.TextMuted)
			}
		})
	})
	if row.Clicked() && released {
		p.play(a, number)
	}
}

// playButton is the white primary action the site this app replaces uses.
func (p *detailPage) playButton(c *ui.Context, label string, enabled bool) bool {
	background := ui.RGB(240, 240, 240)
	if !enabled {
		background = ui.RGBA(240, 240, 240, 60)
	}
	button := ui.Button(c, label).Width(170).Padding(9, 12).Radius(6).Background(background).TextColor(ui.Hex("#0a0a0a")).Bold()
	if !enabled {
		return false
	}
	return button.Clicked()
}

// heroActions are the watchlist and share buttons beside Play.
func (p *detailPage) heroActions(c *ui.Context, a *App) {
	saved := a.watch.Contains(p.id, p.kind)
	label := "+ My List"
	if saved {
		label = "✓ My List"
	}
	if ui.Button(c, label).Width(100).Clicked() {
		a.watch.Toggle(store.WatchlistItem{ID: p.id, Kind: p.kind, Title: p.heroTitle(), Poster: p.heroPoster()})
		c.Toast(map[bool]string{true: "Removed from watchlist", false: "Saved to watchlist"}[saved])
	}
	if ui.Button(c, "Share").Width(80).Clicked() {
		mygo.Clipboard.WriteText(p.shareText(a))
		c.Toast("Link copied")
	}
}

func (p *detailPage) play(a *App, episode int) {
	switch p.kind {
	case "ANIME":
		a.router.Push(fmt.Sprintf("/watch/ANIME/%s/0/%d", p.shownID, episode))
	case "TV":
		a.router.Push(fmt.Sprintf("/watch/TV/%s/%d/%d", p.id, p.season, episode))
	case "MOVIE":
		a.router.Push(fmt.Sprintf("/watch/MOVIE/%s/0/0", p.id))
	}
}

func (p *detailPage) heroTitle() string {
	if p.kind == "ANIME" {
		return p.anime.Title.Best()
	}
	return p.detail.Title
}

func (p *detailPage) heroPoster() string {
	if p.kind == "ANIME" {
		return p.anime.CoverURL
	}
	return p.detail.Poster
}

func (p *detailPage) shareText(a *App) string {
	switch p.kind {
	case "ANIME":
		return p.heroTitle() + " " + strings.TrimRight(a.settings.BaseURL(), "/") + "/watch/" + p.shownID
	case "TV":
		return p.heroTitle() + " " + strings.TrimRight(a.settings.ShowsSite(), "/") + "/tv/" + p.id
	default:
		return p.heroTitle() + " " + strings.TrimRight(a.settings.ShowsSite(), "/") + "/movies/" + p.id
	}
}

func seasonTitle(anime miruro.Anime) string {
	if strings.TrimSpace(anime.AiredSeason.Name) != "" {
		return anime.AiredSeason.Name
	}
	if anime.SeasonYear > 0 {
		return fmt.Sprint(anime.SeasonYear)
	}
	return anime.Title.Best()
}

func shortDate(value string) string {
	if len(value) >= 10 {
		if parsed, err := time.Parse("2006-01-02", value[:10]); err == nil {
			return parsed.Format("2 Jan 2006")
		}
		return value[:10]
	}
	return value
}

func stripHTML(value string) string {
	out := strings.Builder{}
	inTag := false
	for _, ch := range value {
		switch {
		case ch == '<':
			inTag = true
		case ch == '>':
			inTag = false
		case !inTag:
			out.WriteRune(ch)
		}
	}
	return out.String()
}
