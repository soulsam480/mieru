package main

import (
	"context"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/store"
)

type showsPage struct {
	kind    oneshows.Kind
	entered bool
	query   string

	items   []oneshows.Card
	loading bool
	err     string
	token   int
	grid    ui.GridState

	continueItems []store.ContinueItem
}

func (p *showsPage) enter(a *App, kind oneshows.Kind) {
	p.kind = kind
	p.refresh(a)
	if p.entered {
		return
	}
	p.entered = true
	p.load(a)
}

func (p *showsPage) refresh(a *App) {
	p.continueItems = a.progress.ContinueWatching(string(p.kind), 12)
}

func (p *showsPage) load(a *App) {
	if a.noNetwork {
		return
	}
	p.token++
	token := p.token
	query := strings.TrimSpace(p.query)
	p.loading = true
	p.err = ""
	if query == "" {
		p.items = nil
	}

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		var (
			items []oneshows.Card
			err   error
		)
		if query == "" {
			items, err = a.shows.Trending(ctx, p.kind)
		} else {
			items, err = a.shows.Search(ctx, query)
		}
		a.update(func() {
			if p.token != token {
				return
			}
			if err != nil {
				p.loading, p.err = false, err.Error()
				return
			}
			p.items = items
			p.loading = false
			if query == "" {
				p.grid.ScrollTo(0, ui.Start)
			}
		})
	}()
}

func (p *showsPage) onQueryChanged(a *App) {
	p.token++
	token := p.token
	go func() {
		time.Sleep(400 * time.Millisecond)
		a.update(func() {
			if p.token == token {
				p.load(a)
			}
		})
	}()
}

func (p *showsPage) view(c *ui.Context, a *App) {
	title := "TV Shows"
	placeholder := "Search tv shows"
	if p.kind == oneshows.KindMovie {
		title = "Movies"
		placeholder = "Search movies"
	}
	ui.Column(c).Fill().Padding(24).Gap(16).Children(func() {
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			sectionTitle(c, title)
			ui.Spacer(c).Grow(1)
			if ui.SearchField(c, &p.query).Placeholder(placeholder).Width(280).Changed() {
				p.onQueryChanged(a)
			}
		})

		switch {
		case p.loading:
			a.loading(c)
		case p.err != "":
			a.errorBox(c, p.err, func() { p.load(a) })
		default:
			if len(p.continueItems) > 0 && strings.TrimSpace(p.query) == "" {
				continueStrip(c, a, p.continueItems, func(item store.ContinueItem) {
					a.router.Push("/shows/" + item.Kind + "/" + item.AnimeID)
				})
			}
			if len(p.items) == 0 {
				if p.query != "" {
					a.empty(c, "No results.")
				} else {
					a.empty(c, "Nothing trending right now.")
				}
				return
			}
			p.grid.Key = func(i int) any { return p.items[i].Kind.String() + p.items[i].ID }
			p.grid.Label = func(i int) string { return p.items[i].Title }
			ui.GridView(c, &p.grid, len(p.items), 160, 296, func(i int) {
				item := p.items[i]
				card := ui.ButtonBase(c).Padding(0).Radius(12)
				card.Children(func() {
					ui.Column(c).Gap(6).Children(func() {
						a.poster(c, item.Poster, 160)
						ui.Text(c, item.Title).SingleLine()
						bits := make([]string, 0, 2)
						if item.Year > 0 {
							bits = append(bits, formatYear(item.Year))
						}
						if score := formatScore(item.Score); score != "" {
							bits = append(bits, score)
						}
						if len(bits) > 0 {
							muted(c, strings.Join(bits, " · "))
						}
					})
				})
				if card.Clicked() {
					a.router.Push("/shows/" + item.Kind.String() + "/" + item.ID)
				}
			}).Grow(1)
		}
	})
}
