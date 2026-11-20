package main

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/store"
)

var animeSorts = []struct {
	label string
	value string
}{
	{"Latest", "latest"},
	{"Popular", "-popularity"},
	{"Top rated", "-score"},
	{"A–Z", "title"},
}

var animeGenres = []string{
	"Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror",
	"Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance",
	"Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
}

type animePage struct {
	entered bool
	query   string
	sort    int
	genre   string

	items       []miruro.Anime
	airings     map[string]miruro.AiringEntry
	cursor      string
	hasMore     bool
	loading     bool
	loadingMore bool
	err         string

	token         int
	grid          ui.GridState
	continueItems []store.ContinueItem

	latestPool  []miruro.Anime
	latestIndex int
}

func (p *animePage) enter(a *App) {
	p.refresh(a)
	if p.entered {
		return
	}
	p.entered = true
	p.load(a, true)
}

func (p *animePage) refresh(a *App) {
	p.continueItems = a.progress.ContinueWatching("ANIME", 12)
}

func (p *animePage) usesLatestFeed() bool {
	return animeSorts[p.sort].value == "latest" && p.genre == "" && strings.TrimSpace(p.query) == ""
}

func (p *animePage) load(a *App, reset bool) {
	if a.noNetwork {
		return
	}
	p.token++
	token := p.token
	snapshot := *p
	if reset {
		p.loading = true
		p.items = nil
		p.airings = nil
		p.latestIndex = 0
	} else {
		p.loadingMore = true
	}
	p.err = ""

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()

		if snapshot.usesLatestFeed() {
			if !reset && len(snapshot.latestPool) > 0 {
				start := snapshot.latestIndex
				end := min(start+24, len(snapshot.latestPool))
				slice := snapshot.latestPool[start:end]
				a.update(func() {
					if p.token != token {
						return
					}
					p.items = append(p.items, slice...)
					p.latestIndex = end
					p.hasMore = end < len(p.latestPool)
					p.loading, p.loadingMore = false, false
				})
				return
			}

			entries, err := a.miruro.RecentlyAired(ctx, 7)
			if err != nil {
				a.update(func() {
					if p.token == token {
						p.loading, p.loadingMore, p.err = false, false, err.Error()
					}
				})
				return
			}
			if len(entries) > 120 {
				entries = entries[:120]
			}
			ids := make([]string, 0, len(entries))
			airings := make(map[string]miruro.AiringEntry, len(entries))
			for _, entry := range entries {
				ids = append(ids, entry.AnimeID)
				airings[entry.AnimeID] = entry
			}
			shows, err := a.miruro.AnimeByIDs(ctx, ids)
			if err != nil {
				a.update(func() {
					if p.token == token {
						p.loading, p.loadingMore, p.err = false, false, err.Error()
					}
				})
				return
			}
			byID := make(map[string]miruro.Anime, len(shows))
			for _, show := range shows {
				byID[show.ID] = show
			}
			pool := make([]miruro.Anime, 0, len(ids))
			for _, id := range ids {
				if show, ok := byID[id]; ok {
					pool = append(pool, show)
				}
			}
			end := min(24, len(pool))
			first := pool[:end]
			a.update(func() {
				if p.token != token {
					return
				}
				p.latestPool = pool
				p.latestIndex = end
				p.items = first
				p.airings = airings
				p.hasMore = end < len(pool)
				p.loading, p.loadingMore = false, false
				p.grid.ScrollTo(0, ui.Start)
			})
			return
		}

		sort := animeSorts[snapshot.sort].value
		if sort == "latest" {
			sort = "-popularity"
		}
		var genres []string
		if snapshot.genre != "" {
			genres = []string{snapshot.genre}
		}
		page, err := a.miruro.Catalog(ctx, 15, sort, snapshot.query, genres, map[bool]string{true: "", false: snapshot.cursor}[reset])
		if err != nil {
			a.update(func() {
				if p.token == token {
					p.loading, p.loadingMore, p.err = false, false, err.Error()
				}
			})
			return
		}
		a.update(func() {
			if p.token != token {
				return
			}
			if reset {
				p.items = page.Data
				p.grid.ScrollTo(0, ui.Start)
			} else {
				p.items = append(p.items, page.Data...)
			}
			p.cursor = page.NextCursor
			p.hasMore = page.HasMore && page.NextCursor != ""
			p.loading, p.loadingMore = false, false
		})
	}()
}

func (p *animePage) onQueryChanged(a *App) {
	p.token++
	token := p.token
	go func() {
		time.Sleep(400 * time.Millisecond)
		a.update(func() {
			if p.token == token {
				p.load(a, true)
			}
		})
	}()
}

func (p *animePage) view(c *ui.Context, a *App) {
	ui.Column(c).Fill().Padding(24).Gap(16).Children(func() {
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			sectionTitle(c, "Anime")
			ui.Spacer(c).Grow(1)
			if ui.SearchField(c, &p.query).Placeholder("Search anime").Width(280).Changed() {
				p.onQueryChanged(a)
			}
		})

		before := p.sort
		ui.Segmented(c, &p.sort, "Latest", "Popular", "Top rated", "A–Z").Label("Sort").Width(360)
		if p.sort != before {
			// Latest is the unfiltered feed; a genre picked elsewhere must not
			// keep filtering a chip row that is hidden.
			if p.sort == 0 {
				p.genre = ""
			}
			p.load(a, true)
		}
		if p.sort != 0 {
			ui.ScrollHorizontal(c).Height(34).Children(func() {
				ui.Row(c).Gap(6).Children(func() {
					for _, genre := range animeGenres {
						if chip(c, genre, p.genre == genre) {
							if p.genre == genre {
								p.genre = ""
							} else {
								p.genre = genre
							}
							p.load(a, true)
						}
					}
				})
			})
		}

		switch {
		case p.loading:
			a.loading(c)
		case p.err != "":
			a.errorBox(c, p.err, func() { p.load(a, true) })
		default:
			if len(p.continueItems) > 0 && p.usesLatestFeed() {
				continueStrip(c, a, p.continueItems, func(item store.ContinueItem) {
					a.router.Push("/anime/" + item.AnimeID)
				})
			}
			if len(p.items) == 0 {
				a.empty(c, "No anime here. Try another genre or search.")
				return
			}
			p.grid.Key = func(i int) any {
				if i >= len(p.items) {
					return "load-more"
				}
				return p.items[i].ID
			}
			p.grid.Label = func(i int) string {
				if i >= len(p.items) {
					return "Load more"
				}
				return p.items[i].Title.Best()
			}
			count := len(p.items)
			if p.hasMore {
				count++
			}
			ui.GridView(c, &p.grid, count, 160, 296, func(i int) {
				if i == len(p.items) {
					if p.loadingMore {
						ui.Column(c).Fill().Center().Children(func() { ui.Spinner(c).Size(22, 22) })
					} else if ui.Button(c, "Load more").Clicked() {
						p.load(a, false)
					}
					return
				}
				p.card(c, a, i, func() { a.router.Push("/anime/" + p.items[i].ID) })
			}).Grow(1)
		}
	})
}

func (p *animePage) card(c *ui.Context, a *App, i int, open func()) {
	anime := p.items[i]
	card := ui.ButtonBase(c).Padding(0).Radius(12)
	card.Children(func() {
		ui.Column(c).Gap(6).Children(func() {
			a.poster(c, anime.CoverURL, 160)
			ui.Text(c, anime.Title.Best()).SingleLine().MaxLines(1)
			if airing, ok := p.airings[anime.ID]; ok {
				muted(c, "EP "+fmt.Sprint(airing.Ep())+" · "+when(airing.AirAt))
			} else {
				count := anime.ShownEpisodes()
				if count > 0 {
					muted(c, fmt.Sprintf("%d episodes", count))
				} else if year := anime.SeasonYear; year > 0 {
					muted(c, fmt.Sprint(year))
				}
			}
		})
	})
	if card.Hovered() {
		card = card.Background(c.Theme().Surface)
	}
	if card.Clicked() {
		open()
	}
}

// continueStrip is the "Continue watching" row shared by every section.
func continueStrip(c *ui.Context, a *App, items []store.ContinueItem, open func(store.ContinueItem)) {
	ui.Column(c).Gap(8).Children(func() {
		ui.Text(c, "Continue watching").FontSize(14).Bold()
		ui.ScrollHorizontal(c).Height(196).Children(func() {
			ui.Row(c).Gap(12).Children(func() {
				for _, item := range items {
					item := item
					card := ui.ButtonBase(c).Padding(0).Radius(12)
					card.Children(func() {
						ui.Column(c).Width(112).Gap(5).Children(func() {
							a.poster(c, item.Cover, 112)
							ui.Text(c, item.Title).SingleLine().FontSize(12)
							muted(c, item.Caption()+" · "+percent(item.PositionMs, item.DurationMs))
						})
					})
					if card.Clicked() {
						open(item)
					}
				}
			})
		})
	})
}

func percent(position, duration int64) string {
	if duration <= 0 {
		return "started"
	}
	value := float64(position) / float64(duration) * 100
	return fmt.Sprintf("%.0f%%", value)
}

// when turns an RFC3339 air time into "2h ago".
func when(raw string) string {
	parsed, err := time.Parse(time.RFC3339, raw)
	if err != nil {
		return ""
	}
	delta := time.Since(parsed)
	switch {
	case delta < time.Minute:
		return "just now"
	case delta < time.Hour:
		return fmt.Sprintf("%dm ago", int(delta.Minutes()))
	case delta < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(delta.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(delta.Hours()/24))
	}
}

func openItem(a *App, id, kind, title string) {
	if kind == "ANIME" {
		a.router.Push("/anime/" + id)
		return
	}
	a.router.Push("/shows/" + kind + "/" + id)
}

var _ = oneshows.KindTV
