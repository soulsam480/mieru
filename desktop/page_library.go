package main

import (
	"fmt"

	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/store"
)

type libraryPage struct {
	entered bool
	kind    int

	continueItems []store.ContinueItem
	watchlist     []store.WatchlistItem
}

func (p *libraryPage) enter(a *App) {
	p.entered = true
	p.continueItems = a.progress.ContinueWatchingAll(20)
	p.watchlist = a.watch.Items()
}

func (p *libraryPage) view(c *ui.Context, a *App) {
	ui.Column(c).Fill().Padding(24).Gap(16).Children(func() {
		ui.Row(c).Gap(12).AlignItems(ui.Center).Children(func() {
			sectionTitle(c, "Library")
			ui.Spacer(c).Grow(1)
			muted(c, fmt.Sprintf("%d saved", len(p.watchlist)))
		})

		if len(p.continueItems) == 0 && len(p.watchlist) == 0 {
			a.empty(c, "Nothing here yet. Save something with the bookmark, or start watching.")
			return
		}

		if len(p.continueItems) > 0 {
			ui.Text(c, "Continue watching").FontSize(16).Bold()
			ui.Scroll(c).Grow(2).Children(func() {
				rows(c, len(p.continueItems), 6, func(i int, width float32) {
					item := p.continueItems[i]
					card := ui.ButtonBase(c).Padding(0).Radius(10).Width(width)
					card.Children(func() {
						ui.Column(c).Gap(4).Children(func() {
							a.poster(c, item.Cover, width)
							ui.Text(c, item.Title).SingleLine().FontSize(12)
							muted(c, item.Caption()+" · "+percent(item.PositionMs, item.DurationMs))
						})
					})
					if card.Clicked() {
						openItem(a, item.AnimeID, item.Kind, item.Title)
					}
				})
			})
		}

		if len(p.watchlist) > 0 {
			ui.Divider(c)
			ui.Text(c, "Watchlist").FontSize(16).Bold()
			ui.Scroll(c).Grow(3).Children(func() {
				rows(c, len(p.watchlist), 6, func(i int, width float32) {
					item := p.watchlist[i]
					ui.Column(c).Gap(4).Width(width).Children(func() {
						card := ui.ButtonBase(c).Padding(0).Radius(10)
						card.Children(func() {
							ui.Column(c).Gap(4).Children(func() {
								a.poster(c, item.Poster, width)
								ui.Text(c, item.Title).SingleLine().FontSize(12)
							})
						})
						if card.Clicked() {
							openItem(a, item.ID, item.Kind, item.Title)
						}
						if ui.Button(c, "Remove").FontSize(11).Clicked() {
							a.watch.Toggle(item)
							p.watchlist = a.watch.Items()
							p.continueItems = a.progress.ContinueWatchingAll(20)
							c.Toast("Removed from watchlist")
						}
					})
				})
			})
		}
	})
}

// rows lays items out in rows of perRow, sharing the width the window gives.
func rows(c *ui.Context, count, perRow int, item func(i int, width float32)) {
	total := (count + perRow - 1) / perRow
	for row := 0; row < total; row++ {
		start := row * perRow
		end := min(start+perRow, count)
		ui.Row(c).Gap(12).Children(func() {
			// 20 padding on each side and the gaps are taken out of the share a
			// little; the poster keeps its 2:3 shape whatever the window is.
			width := float32(140)
			for i := start; i < end; i++ {
				item(i, width)
			}
		})
	}
}
