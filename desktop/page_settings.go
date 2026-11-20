package main

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/store"
)

type settingsPage struct {
	entered   bool
	newName   string
	newURL    string
	siteDraft string
	testing   bool
	testText  string
	token     int
}

func (p *settingsPage) enter(a *App) {
	p.entered = true
	p.siteDraft = a.settings.ShowsSite()
}

func (p *settingsPage) view(c *ui.Context, a *App) {
	settings := a.settings.Get()
	t := c.Theme()
	ui.Scroll(c).Fill().Padding(28).Children(func() {
		ui.Column(c).Gap(18).MaxWidth(720).Children(func() {
			sectionTitle(c, "Settings")

			// ---- Miruro mirrors ----
			ui.Column(c).Gap(8).Children(func() {
				ui.Text(c, "Anime source").FontSize(15).Bold()
				muted(c, "The API base every anime request goes through.")
				for _, source := range settings.Sources {
					source := source
					row := ui.ButtonBase(c).Padding(8, 12).Radius(8)
					row.Children(func() {
						ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
							mark := "○"
							if source.ID == settings.SelectedID {
								mark = "●"
							}
							ui.Text(c, mark).FontSize(14)
							ui.Column(c).Grow(1).Children(func() {
								ui.Text(c, source.Name).SingleLine()
								muted(c, source.BaseURL)
							})
							if ui.Button(c, "Test").Clicked() {
								p.test(a, source.BaseURL)
							}
						})
					})
					if row.Clicked() && source.ID != settings.SelectedID {
						a.settings.Update(func(s *store.Settings) { s.SelectedID = source.ID })
						a.anime.entered, a.tv.entered, a.movies.entered = false, false, false
						c.Toast("Anime source changed to " + source.Name)
					}
				}

				ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
					ui.TextInput(c, &p.newName).Placeholder("Name (optional)").Width(160)
					ui.TextInput(c, &p.newURL).Placeholder("https://mirror.example").Width(280)
					if ui.Button(c, "Add").Clicked() {
						if p.addSource(a) {
							p.newName, p.newURL = "", ""
							c.Toast("Mirror added")
						} else {
							c.Toast("That is not a URL")
						}
					}
					if ui.Button(c, "Reset").Clicked() {
						a.settings.Update(func(s *store.Settings) { *s = store.DefaultSettings() })
						p.siteDraft = store.DefaultSettings().ShowsSite
						a.anime.entered, a.tv.entered, a.movies.entered = false, false, false
						c.Toast("Settings reset")
					}
				})
				if p.testing {
					ui.Spinner(c).Size(16, 16)
				} else if p.testText != "" {
					muted(c, p.testText)
				}
			})

			ui.Divider(c)

			// ---- 1shows ----
			ui.Column(c).Gap(8).Children(func() {
				ui.Text(c, "TV and movie source").FontSize(15).Bold()
				muted(c, "The 1shows site. Its metadata host is derived from this URL.")
				ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
					ui.TextInput(c, &p.siteDraft).Grow(1).Placeholder("https://www.1shows.org")
					if ui.Button(c, "Save").Clicked() {
						parsed, err := url.Parse(strings.TrimSpace(p.siteDraft))
						if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
							c.Toast("That is not a URL")
						} else {
							site := strings.TrimRight(p.siteDraft, "/")
							a.settings.Update(func(s *store.Settings) { s.ShowsSite = site })
							p.siteDraft = site
							a.tv.entered, a.movies.entered = false, false
							c.Toast("Site saved")
						}
					}
				})
			})

			ui.Divider(c)

			// ---- playback ----
			ui.Column(c).Gap(10).Children(func() {
				ui.Text(c, "Playback").FontSize(15).Bold()
				ui.Row(c).Gap(24).Children(func() {
					track := 0
					if settings.Track == "DUB" {
						track = 1
					}
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Preferred audio").FontSize(13)
						if ui.Segmented(c, &track, "Sub", "Dub").Label("Preferred audio").Changed() {
							a.settings.Update(func(s *store.Settings) {
								s.Track = map[int]string{0: "SUB", 1: "DUB"}[track]
							})
						}
					})
					skip := settings.AutoSkip
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Auto-skip intro and outro").FontSize(13)
						if ui.Switch(c, &skip).Changed() {
							a.settings.Update(func(s *store.Settings) { s.AutoSkip = skip })
						}
					})
				})
			})

			ui.Divider(c)

			// ---- subtitles ----
			ui.Column(c).Gap(10).Children(func() {
				ui.Text(c, "Subtitle style").FontSize(15).Bold()
				style := settings.SubtitleStyle
				ui.Row(c).Gap(24).Children(func() {
					sizes := []string{"S", "M", "L", "XL"}
					size := indexOf(sizes, style.Size)
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Size").FontSize(13)
						if ui.Segmented(c, &size, sizes...).Changed() {
							a.saveStyle(func(s *store.SubtitleStyle) { s.Size = sizes[size] })
						}
					})
					colors := []string{"White", "Yellow", "Cyan"}
					color := indexOf(colors, style.Color)
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Colour").FontSize(13)
						if ui.Segmented(c, &color, colors...).Changed() {
							a.saveStyle(func(s *store.SubtitleStyle) { s.Color = colors[color] })
						}
					})
					backgrounds := []string{"None", "Dim", "Solid"}
					background := indexOf(backgrounds, style.Background)
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Background").FontSize(13)
						if ui.Segmented(c, &background, backgrounds...).Changed() {
							a.saveStyle(func(s *store.SubtitleStyle) { s.Background = backgrounds[background] })
						}
					})
					outline := style.Outline
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, "Outline").FontSize(13)
						if ui.Switch(c, &outline).Changed() {
							a.saveStyle(func(s *store.SubtitleStyle) { s.Outline = outline })
						}
					})
				})
			})

			ui.Divider(c)

			// ---- about ----
			ui.Column(c).Gap(6).Children(func() {
				ui.Text(c, "About").FontSize(15).Bold()
				muted(c, fmt.Sprintf("Mieru Desktop %s", appVersion))
				muted(c, "Anime from Miruro, TV and movies from 1shows, subtitles from OpenSubtitles.")
				ui.Row(c).Gap(8).Children(func() {
					ui.Link(c, "GitHub", "https://github.com/soulsam480/mieru")
					ui.Link(c, "Report an issue", "https://github.com/soulsam480/mieru/issues")
				})
				if ui.Button(c, "Open data folder").Clicked() {
					_ = mygo.Shell.OpenExternal("file://" + store.Dir())
				}
			})
			_ = t
		})
	})
}

func (p *settingsPage) addSource(a *App) bool {
	parsed, err := url.Parse(strings.TrimSpace(p.newURL))
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return false
	}
	base := strings.TrimRight(strings.TrimSpace(p.newURL), "/")
	name := strings.TrimSpace(p.newName)
	if name == "" {
		name = parsed.Host
	}
	id := fmt.Sprintf("mirror-%d", time.Now().UnixMilli())
	a.settings.Update(func(s *store.Settings) {
		s.Sources = append(s.Sources, store.Source{ID: id, Name: name, BaseURL: base})
		s.SelectedID = id
	})
	a.anime.entered, a.tv.entered, a.movies.entered = false, false, false
	a.anime.sort = 0
	return true
}

func (p *settingsPage) test(a *App, base string) {
	p.testing = true
	p.testText = ""
	p.token++
	token := p.token
	go func() {
		client := miruro.New(func() string { return base })
		ok := client.Health(context.Background())
		a.update(func() {
			if p.token != token {
				return
			}
			p.testing = false
			if ok {
				p.testText = base + " answers"
			} else {
				p.testText = base + " did not answer"
			}
		})
	}()
}

func (a *App) saveStyle(fn func(*store.SubtitleStyle)) {
	a.settings.Update(func(s *store.Settings) { fn(&s.SubtitleStyle) })
}

func indexOf(values []string, value string) int {
	for i, candidate := range values {
		if candidate == value {
			return i
		}
	}
	return 0
}
