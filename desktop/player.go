package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/video"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/subtitles"
)

type playbackSource struct {
	Label     string
	URL       string
	Headers   map[string]string
	Mime      string
	Provider  string
	Subtitles []miruro.Subtitle
}

type skipWindow struct {
	Label string
	Start float64
	End   float64
}

type quality struct {
	Label string
	URL   string
}

type playerPage struct {
	key     string
	kind    string
	id      string
	season  int
	episode int
	title   string
	cover   string

	loading bool
	status  string
	err     string
	token   int

	sources     []playbackSource
	sourceIndex int
	player      *ui.VideoPlayer
	openedURL   string
	openedAt    time.Time

	duration    float64
	position    float64
	volume      float64
	muted       bool
	speed       float64
	playing     bool
	ended       bool
	chromeUntil time.Time
	panel       string

	subtitleTracks  []subtitles.Track
	subtitleIndex   int
	cues            []subtitles.Cue
	subtitleLoading bool
	subtitleStyle   storeStyle

	skipWindows []skipWindow
	autoSkip    bool
	skipped     map[string]bool

	qualities    []quality
	qualityIndex int

	resumeMs        int64
	lastSave        time.Time
	savedEnd        bool
	nextEpisode     int
	upNextDismissed bool
}

// storeStyle mirrors the persisted subtitle style without importing the store
// into every drawing helper.
type storeStyle struct {
	Size       string
	Color      string
	Background string
	Outline    bool
}

func (p *playerPage) enter(a *App) {
	raw := strings.TrimPrefix(a.router.Path(), "/watch/")
	parts := strings.Split(raw, "/")
	if len(parts) != 4 {
		return
	}
	season, _ := strconv.Atoi(parts[2])
	episode, _ := strconv.Atoi(parts[3])
	key := raw
	if p.key == key {
		return
	}
	*p = playerPage{key: key, kind: parts[0], id: parts[1], season: season, episode: episode}
	p.token++
	p.volume = 1
	p.speed = 1
	p.subtitleIndex = -1
	p.skipped = map[string]bool{}
	p.panel = ""
	p.loading = true
	settings := a.settings.Get()
	p.autoSkip = settings.AutoSkip
	style := settings.SubtitleStyle
	p.subtitleStyle = storeStyle{Size: style.Size, Color: style.Color, Background: style.Background, Outline: style.Outline}

	info := a.knownTitle(p.id)
	p.title, p.cover = info.Title, info.Cover
	p.resumeMs = a.progress.Position(p.id, p.season, p.episode)
	go p.resolve(a)
}

func (p *playerPage) close() {
	if p.player != nil {
		p.player.Close()
		p.player = nil
	}
	p.key = ""
}

func (p *playerPage) resolve(a *App) {
	if a.noNetwork {
		return
	}
	token := p.token
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if p.kind == "ANIME" {
		play, err := a.miruro.Play(ctx, p.id, p.episode)
		if err != nil {
			a.update(func() {
				if p.token == token {
					p.loading, p.err = false, err.Error()
				}
			})
			return
		}
		options := miruro.Sources(play, a.settings.Dub())
		converted := make([]playbackSource, 0, len(options))
		for _, option := range options {
			converted = append(converted, playbackSource{
				Label: option.Label, URL: option.URL, Headers: option.Headers,
				Mime: option.Mime, Provider: option.Provider, Subtitles: option.Subtitles,
			})
		}
		if len(converted) == 0 {
			a.update(func() {
				if p.token == token {
					p.loading, p.err = false, "No playable stream for this episode"
				}
			})
			return
		}

		episodes, episodesErr := a.miruro.Episodes(ctx, p.id)
		a.update(func() {
			if p.token != token {
				return
			}
			p.sources = converted
			p.loading = false
			p.status = ""
			if p.title == "" {
				p.title = p.id
			}
			if episodesErr == nil {
				p.skipWindows = skipTimesFor(episodes, p.episode)
				p.nextEpisode = nextAfter(episodes, p.episode)
			}
			p.loadSubtitleStyle(a)
			p.openSource(a, 0)
		})
		return
	}

	imdb := a.shows.IMDBID(ctx, oneshowsKind(p.kind), p.id)
	tracks := a.subs.Search(ctx, imdb, p.season, p.episode)
	mirrors, err := a.shows.Mirrors(ctx, p.id, oneshowsKind(p.kind), p.season, p.episode)
	if err != nil || len(mirrors) == 0 {
		message := "No mirrors found"
		if err != nil {
			message = err.Error()
		}
		a.update(func() {
			if p.token == token {
				p.loading, p.err = false, message
			}
		})
		return
	}

	// The player's next episode comes from the season list the detail page
	// already knows; fetching it again here keeps the player self-contained.
	if p.kind == "TV" {
		if episodes, err := a.shows.Season(ctx, p.id, p.season); err == nil {
			for _, episode := range episodes {
				if episode.Number > p.episode {
					a.update(func() {
						if p.token == token {
							p.nextEpisode = episode.Number
						}
					})
					break
				}
			}
		}
	}

	for i, mirror := range mirrors {
		a.update(func() {
			if p.token == token {
				p.status = fmt.Sprintf("Resolving %s (%d/%d)…", mirror.Label, i+1, len(mirrors))
			}
		})
		stream, err := a.resolver.Resolve(ctx, mirror.URL, mirror.Label)
		if err != nil {
			continue
		}
		source := playbackSource{
			Label: mirror.Label, URL: stream.URL, Headers: stream.Headers,
			Mime: mimeOf(stream.URL), Provider: mirror.Label,
		}
		remaining := append([]oneshows.Mirror(nil), mirrors[i+1:]...)
		a.update(func() {
			if p.token != token {
				return
			}
			p.sources = []playbackSource{source}
			p.subtitleTracks = tracks
			p.loading = false
			p.status = ""
			p.autoSkip = false
			if p.title == "" {
				p.title = p.id
			}
			p.openSource(a, 0)
			if len(tracks) > 0 {
				go p.pickSubtitle(a, 0)
			}
		})
		go p.resolveAlternates(a, token, remaining)
		return
	}

	a.update(func() {
		if p.token == token {
			p.loading, p.err = false, "No mirror returned a playlist"
		}
	})
}

func (p *playerPage) resolveAlternates(a *App, token int, mirrors []oneshows.Mirror) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	resolved := 0
	for _, mirror := range mirrors {
		if resolved >= 4 {
			return
		}
		stream, err := a.resolver.Resolve(ctx, mirror.URL, mirror.Label)
		if err != nil {
			continue
		}
		resolved++
		source := playbackSource{
			Label: mirror.Label, URL: stream.URL, Headers: stream.Headers,
			Mime: mimeOf(stream.URL), Provider: mirror.Label,
		}
		a.update(func() {
			if p.token == token {
				p.sources = append(p.sources, source)
			}
		})
	}
}

func (p *playerPage) loadSubtitleStyle(a *App) {
	style := a.settings.Get().SubtitleStyle
	p.subtitleStyle = storeStyle{Size: style.Size, Color: style.Color, Background: style.Background, Outline: style.Outline}
}

// openSource switches the player to a source, keeping the position and the
// chosen language.
func (p *playerPage) openSource(a *App, index int) {
	if index < 0 || index >= len(p.sources) {
		return
	}
	source := p.sources[index]
	if p.player != nil {
		p.save(a, true)
		p.player.Close()
		p.player = nil
	}
	playbackURL, err := a.media.Open(source.URL, source.Headers)
	if err != nil {
		p.err = err.Error()
		return
	}
	player, err := video.OpenURL(playbackURL)
	if err != nil {
		p.err = err.Error()
		return
	}
	p.player = player
	p.openedURL = playbackURL
	p.openedAt = time.Now()
	p.sourceIndex = index
	p.err = ""
	p.ended = false
	p.savedEnd = false
	p.skipped = map[string]bool{}
	player.SetVolume(p.volume).SetMuted(p.muted).SetRate(p.speed)
	if p.resumeMs > 0 {
		player.Seek(time.Duration(p.resumeMs) * time.Millisecond)
		p.position = float64(p.resumeMs) / 1000
	}
	p.chromeUntil = time.Now().Add(4 * time.Second)
	p.qualities = nil
	if source.Mime == "hls" {
		go p.loadVariants(a, source)
	}
	if p.kind == "ANIME" {
		wanted := p.selectedLanguage(a)
		p.subtitleTracks = embeddedTracks(source.Subtitles)
		p.subtitleIndex = -1
		if wanted != "" {
			for i, track := range p.subtitleTracks {
				if track.Language == wanted {
					p.pickSubtitle(a, i)
					break
				}
			}
		}
	}
}

func (p *playerPage) selectedLanguage(a *App) string {
	if p.subtitleIndex >= 0 && p.subtitleIndex < len(p.subtitleTracks) {
		return p.subtitleTracks[p.subtitleIndex].Language
	}
	return ""
}

func (p *playerPage) pickSubtitle(a *App, index int) {
	if index < 0 || index >= len(p.subtitleTracks) {
		return
	}
	track := p.subtitleTracks[index]
	p.subtitleIndex = index
	p.subtitleLoading = true
	p.cues = nil
	token := p.token
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		body, err := a.subs.Fetch(ctx, track.URL)
		a.update(func() {
			if p.token != token || p.subtitleIndex != index {
				return
			}
			p.subtitleLoading = false
			if err == nil {
				p.cues = subtitles.Parse(body)
			}
		})
	}()
}

func (p *playerPage) loadVariants(a *App, source playbackSource) {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	variants := a.media.Variants(ctx, source.URL, source.Headers)
	if len(variants) == 0 {
		return
	}
	token := p.token
	a.update(func() {
		if p.token != token {
			return
		}
		for _, variant := range variants {
			p.qualities = append(p.qualities, quality{Label: variant.Label, URL: variant.URL})
		}
	})
}

// ---------- view ----------

func (p *playerPage) view(c *ui.Context, a *App) {
	if p.player != nil {
		p.tick(a)
		c.After(400 * time.Millisecond)
	}
	t := c.Theme()

	ui.Box(c).Fill().Background(ui.RGB(0, 0, 0)).Children(func() {
		if p.player != nil {
			ui.Video(c, p.player).Fill().Fit(ui.Contain)
		}
		if p.subtitleCue() != "" {
			p.drawCue(c, p.subtitleCue())
		}
		if p.loading || (p.player != nil && p.duration == 0 && p.err == "") {
			ui.Column(c).Fill().Center().Gap(12).Children(func() {
				ui.Spinner(c).Size(28, 28)
				ui.Text(c, p.status).TextColor(ui.RGB(230, 230, 230))
			})
		}
		if p.err != "" {
			ui.Column(c).Fill().Center().Gap(12).Children(func() {
				ui.Text(c, "Cannot play this episode").FontSize(18).Bold().TextColor(ui.RGB(255, 255, 255))
				ui.Text(c, p.err).FontSize(12).TextColor(t.TextMuted).MaxLines(4).TextAlign(ui.Center)
				ui.Row(c).Gap(8).Children(func() {
					if ui.Button(c, "Back").Clicked() {
						a.router.Back()
					}
					if ui.Button(c, "Retry").Clicked() {
						p.loading = true
						p.err = ""
						p.token++
						go p.resolve(a)
					}
				})
			})
		}
		if p.player != nil {
			p.topBar(c, a)
			p.bottomBar(c, a)
			if window, ok := p.currentSkip(); ok {
				if ui.Button(c, window.Label).Absolute().Right(24).Bottom(190).Clicked() {
					p.seekTo(window.End)
				}
			}
			if p.ended || (p.duration > 0 && p.duration-p.position < 25) {
				p.upNext(c, a)
			}
		}
		if p.panel != "" {
			p.drawPanel(c, a)
		}
	})
}

// sourceBar is the row of mirror tabs and the players' panels, as the site
// this app replaces shows them above the video.
func (p *playerPage) sourceBar(c *ui.Context, a *App) {
	t := c.Theme()
	ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
		for i, source := range p.sources {
			i, source := i, source
			active := i == p.sourceIndex
			background, text := t.Surface, t.Text
			if active {
				background, text = ui.RGB(229, 57, 53), ui.RGB(255, 255, 255)
			}
			button := ui.ButtonBase(c).Padding(7, 14).Radius(6).Background(background)
			button.Children(func() { ui.Text(c, source.Provider).FontSize(12).TextColor(text).SingleLine() })
			if button.Clicked() && !active {
				p.openSource(a, i)
			}
		}
		if p.loading && len(p.sources) == 0 {
			ui.Spinner(c).Size(16, 16)
		}
		ui.Spacer(c).Grow(1)
		if ui.Button(c, "Subtitles").Clicked() {
			p.panel = "subs"
		}
		if ui.Button(c, speedLabel(p.speed)).Clicked() {
			p.panel = "speed"
		}
		if len(p.qualities) > 0 {
			if ui.Button(c, "Quality").Clicked() {
				p.panel = "quality"
			}
		}
	})
}

func (p *playerPage) showChrome() bool { return true }

func (p *playerPage) touch() {
	p.chromeUntil = time.Now().Add(4 * time.Second)
}

func (p *playerPage) topBar(c *ui.Context, a *App) {
	ui.Row(c).Absolute().Top(0).Left(0).Right(p.panelWidth()).Padding(16).Gap(12).AlignItems(ui.Center).
		Background(ui.RGBA(0, 0, 0, 150)).Children(func() {
		if ui.Button(c, "‹ Back").Clicked() {
			a.router.Back()
		}
		ui.Column(c).Gap(2).Children(func() {
			ui.Text(c, p.title).Bold().SingleLine().TextColor(ui.RGB(255, 255, 255))
			ui.Text(c, p.caption()).FontSize(11).TextColor(ui.RGB(200, 200, 200))
		})
		ui.Spacer(c).Grow(1)
		if p.kind != "MOVIE" {
			if ui.Button(c, "Info").Clicked() {
				a.router.Push(p.infoRoute())
			}
		}
		if ui.Button(c, "Full").Clicked() {
			if a.win != nil {
				a.win.SetFullScreen(!a.win.IsFullScreen())
			}
		}
	})
}

func (p *playerPage) bottomBar(c *ui.Context, a *App) {
	ui.Column(c).Absolute().Bottom(0).Left(0).Right(p.panelWidth()).Padding(16).Gap(8).
		Background(ui.RGBA(0, 0, 0, 170)).Children(func() {
		position := p.position
		limit := p.duration
		if limit <= 0 {
			limit = 1
		}
		if ui.Slider(c, &position, 0, limit).Label("Position").Changed() {
			p.seekTo(position)
		}
		ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
			label := "Play"
			if p.playing {
				label = "Pause"
			}
			if ui.Button(c, label).Width(68).Clicked() {
				p.togglePlay()
			}
			if ui.Button(c, "-10").Clicked() {
				p.seekBy(-10)
			}
			if ui.Button(c, "+10").Clicked() {
				p.seekBy(10)
			}
			ui.Textf(c, "%s / %s", clock(p.position), clock(p.duration)).FontSize(12).TextColor(ui.RGB(220, 220, 220))
			ui.Spacer(c).Grow(1)
			volume := p.volume
			if ui.Slider(c, &volume, 0, 1).Label("Volume").Width(110).Changed() {
				p.volume = volume
				if p.player != nil {
					p.player.SetVolume(volume)
				}
			}
			mute := "Mute"
			if p.muted {
				mute = "Unmute"
			}
			if ui.Button(c, mute).Clicked() {
				p.muted = !p.muted
				if p.player != nil {
					p.player.SetMuted(p.muted)
				}
			}
			cc := "CC"
			if p.subtitleIndex >= 0 {
				cc = "CC •"
			}
			if ui.Button(c, cc).Clicked() {
				p.panel = "subs"
			}
			if ui.Button(c, speedLabel(p.speed)).Clicked() {
				p.panel = "speed"
			}
			if ui.Button(c, fmt.Sprintf("Sources %d", len(p.sources))).Clicked() {
				p.panel = "sources"
			}
			if len(p.qualities) > 0 {
				if ui.Button(c, "Quality").Clicked() {
					p.panel = "quality"
				}
			}
		})
	})
}

func (p *playerPage) drawCue(c *ui.Context, text string) {
	color := ui.RGB(255, 255, 255)
	switch p.subtitleStyle.Color {
	case "Yellow":
		color = ui.RGB(255, 235, 59)
	case "Cyan":
		color = ui.RGB(128, 222, 234)
	}
	size := float32(20)
	switch p.subtitleStyle.Size {
	case "S":
		size = 16
	case "L":
		size = 26
	case "XL":
		size = 32
	}
	background := ui.RGBA(0, 0, 0, 0)
	switch p.subtitleStyle.Background {
	case "Dim":
		background = ui.RGBA(0, 0, 0, 128)
	case "Solid":
		background = ui.RGBA(0, 0, 0, 204)
	}
	ui.Column(c).Absolute().Bottom(110).Left(60).Right(60).Children(func() {
		ui.Box(c).Padding(4, 8).Radius(6).Background(background).Center().Children(func() {
			line := ui.Text(c, text).FontSize(size).TextColor(color).TextAlign(ui.Center)
			if p.subtitleStyle.Outline {
				line.TextStroke(2, ui.RGBA(0, 0, 0, 255))
			}
		})
	})
}

func (p *playerPage) upNext(c *ui.Context, a *App) {
	if p.upNextDismissed || p.nextEpisode == 0 {
		return
	}
	t := c.Theme()
	ui.Column(c).Absolute().Right(24).Bottom(110).Width(240).Padding(12).Gap(8).Radius(10).
		Background(t.Surface).Children(func() {
		ui.Text(c, "Up next").FontSize(11).TextColor(t.TextMuted)
		ui.Textf(c, "EP %d", p.nextEpisode).Bold()
		ui.Row(c).Gap(8).Children(func() {
			if ui.PrimaryButton(c, "Play").Clicked() {
				p.playNext(a)
			}
			if ui.Button(c, "Dismiss").Clicked() {
				p.upNextDismissed = true
			}
		})
	})
}

func (p *playerPage) drawPanel(c *ui.Context, a *App) {
	t := c.Theme()
	ui.Column(c).Absolute().Right(0).Top(0).Bottom(0).Width(300).Padding(16).Gap(10).
		Background(t.Surface).Children(func() {
		ui.Row(c).AlignItems(ui.Center).Children(func() {
			title := map[string]string{"sources": "Sources", "subs": "Subtitles", "speed": "Speed", "quality": "Quality"}[p.panel]
			ui.Text(c, title).FontSize(16).Bold()
			ui.Spacer(c).Grow(1)
			if ui.Button(c, "Close").Clicked() {
				p.panel = ""
			}
		})
		ui.Scroll(c).Grow(1).Children(func() {
			ui.Column(c).Gap(6).Children(func() {
				switch p.panel {
				case "sources":
					for i, source := range p.sources {
						source := source
						index := i
						label := source.Label
						if index == p.sourceIndex {
							label = "✓ " + label
						}
						if ui.Button(c, label).Clicked() {
							p.openSource(a, index)
							p.panel = ""
						}
					}
					if len(p.sources) == 0 {
						ui.Text(c, "No sources yet").TextColor(t.TextMuted)
					}
				case "subs":
					if p.subtitleLoading {
						ui.Spinner(c).Size(18, 18)
					}
					if ui.Button(c, map[bool]string{true: "✓ Off", false: "Off"}[p.subtitleIndex < 0]).Clicked() {
						p.subtitleIndex = -1
						p.cues = nil
						p.panel = ""
					}
					for i, track := range p.subtitleTracks {
						track := track
						index := i
						label := subtitles.LanguageName(track.Language)
						if index == p.subtitleIndex {
							label = "✓ " + label
						}
						if ui.Button(c, label).Clicked() {
							p.pickSubtitle(a, index)
							p.panel = ""
						}
					}
					if len(p.subtitleTracks) == 0 {
						ui.Text(c, "No subtitle tracks").TextColor(t.TextMuted)
					}
				case "speed":
					for _, speed := range []float64{0.5, 0.75, 1, 1.25, 1.5, 1.75, 2} {
						speed := speed
						label := speedLabel(speed)
						if speed == p.speed {
							label = "✓ " + label
						}
						if ui.Button(c, label).Clicked() {
							p.speed = speed
							if p.player != nil {
								p.player.SetRate(speed)
							}
							p.panel = ""
						}
					}
				case "quality":
					if ui.Button(c, map[bool]string{true: "✓ Auto", false: "Auto"}[p.qualityIndex == 0]).Clicked() {
						p.qualityIndex = 0
						p.openSource(a, p.sourceIndex)
						p.panel = ""
					}
					for i, variant := range p.qualities {
						index := i + 1
						label := variant.Label
						if index == p.qualityIndex {
							label = "✓ " + label
						}
						if ui.Button(c, label).Clicked() {
							p.openAt(a, variant.URL)
							p.qualityIndex = index
							p.panel = ""
						}
					}
				}
			})
		})
	})
}

func (p *playerPage) panelWidth() float32 {
	if p.panel != "" {
		return 300
	}
	return 0
}

func (p *playerPage) openAt(a *App, rawURL string) {
	if p.player != nil {
		p.save(a, true)
		p.player.Close()
		p.player = nil
	}
	playbackURL, err := a.media.Open(rawURL, p.sources[p.sourceIndex].Headers)
	if err != nil {
		p.err = err.Error()
		return
	}
	player, err := video.OpenURL(playbackURL)
	if err != nil {
		p.err = err.Error()
		return
	}
	p.player = player
	player.SetVolume(p.volume).SetMuted(p.muted).SetRate(p.speed)
	if p.position > 0 {
		player.Seek(time.Duration(p.position * float64(time.Second)))
	}
}

// ---------- controls ----------

func (p *playerPage) togglePlay() {
	if p.player == nil {
		return
	}
	if p.player.Playing() {
		p.player.Pause()
	} else {
		p.player.Play()
	}
	p.touch()
}

func (p *playerPage) seekBy(delta float64) {
	p.touch()
	if p.player == nil {
		return
	}
	target := p.position + delta
	if target < 0 {
		target = 0
	}
	if p.duration > 0 && target > p.duration {
		target = p.duration
	}
	p.player.Seek(time.Duration(target * float64(time.Second)))
	p.position = target
}

func (p *playerPage) seekTo(seconds float64) {
	if p.player == nil {
		return
	}
	p.player.Seek(time.Duration(seconds * float64(time.Second)))
	p.position = seconds
	p.touch()
}

func (p *playerPage) tick(a *App) {
	if err := p.player.Err(); err != nil {
		p.failSource(a, err)
		return
	}
	p.position = p.player.Position().Seconds()
	if duration := p.player.Duration().Seconds(); duration > 0 {
		p.duration = duration
	}
	p.playing = p.player.Playing()
	if p.player.Ended() {
		p.ended = true
	}

	if p.duration == 0 {
		p.status = "Buffering…"
		if !p.openedAt.IsZero() && time.Since(p.openedAt) > 18*time.Second {
			p.failSource(a, fmt.Errorf("%s sent no video", p.sources[p.sourceIndex].Provider))
			return
		}
	} else if p.status == "Buffering…" {
		p.status = ""
	}

	if p.autoSkip && !p.ended {
		for _, window := range p.skipWindows {
			if p.position >= window.Start && p.position < window.End && !p.skipped[window.Label] {
				p.skipped[window.Label] = true
				p.player.Seek(time.Duration(window.End * float64(time.Second)))
				p.position = window.End
				break
			}
		}
	}

	if p.ended && !p.savedEnd {
		p.save(a, true)
		p.savedEnd = true
	} else if time.Since(p.lastSave) > 5*time.Second && p.playing {
		p.save(a, false)
	}
}

// failSource rolls to the next mirror when the current one errors or sends no
// video, as the site's server tabs make the viewer do by hand.
func (p *playerPage) failSource(a *App, err error) {
	if p.sourceIndex+1 < len(p.sources) {
		p.status = fmt.Sprintf("%s: %v — trying %s…", p.sources[p.sourceIndex].Provider, err, p.sources[p.sourceIndex+1].Provider)
		p.openSource(a, p.sourceIndex+1)
		return
	}
	p.err = err.Error()
	p.loading = false
	if p.player != nil {
		p.player.Close()
		p.player = nil
	}
}

func (p *playerPage) save(a *App, force bool) {
	if p.duration <= 0 {
		return
	}
	if !force && p.position < 5 {
		return
	}
	episode := p.episode
	if p.kind == "MOVIE" {
		episode = 0
	}
	a.progress.Record(p.id, p.title, p.cover, episode,
		int64(p.position*1000), int64(p.duration*1000), p.season, p.kind)
	p.lastSave = time.Now()
}

func (p *playerPage) currentSkip() (skipWindow, bool) {
	for _, window := range p.skipWindows {
		if p.position >= window.Start && p.position < window.End {
			return window, true
		}
	}
	return skipWindow{}, false
}

func (p *playerPage) subtitleCue() string {
	if p.subtitleIndex < 0 || len(p.cues) == 0 {
		return ""
	}
	if cue := subtitles.At(p.cues, p.position); cue != nil {
		return cue.Text
	}
	return ""
}

func (p *playerPage) caption() string {
	switch {
	case p.kind == "MOVIE":
		return "Movie"
	case p.kind == "TV":
		return fmt.Sprintf("S%d E%d", p.season, p.episode)
	default:
		return fmt.Sprintf("EP %d", p.episode)
	}
}

func (p *playerPage) infoRoute() string {
	switch p.kind {
	case "ANIME":
		return "/anime/" + p.id
	case "TV", "MOVIE":
		return "/shows/" + p.kind + "/" + p.id
	}
	return "/anime"
}

func (p *playerPage) playNext(a *App) {
	if p.nextEpisode == 0 {
		return
	}
	switch p.kind {
	case "ANIME":
		a.router.Push(fmt.Sprintf("/watch/ANIME/%s/0/%d", p.id, p.nextEpisode))
	case "TV":
		a.router.Push(fmt.Sprintf("/watch/TV/%s/%d/%d", p.id, p.season, p.nextEpisode))
	}
}

// ---------- helpers ----------

func embeddedTracks(list []miruro.Subtitle) []subtitles.Track {
	out := make([]subtitles.Track, 0, len(list))
	for _, track := range list {
		language := subtitles.Normalize(track.Language)
		if language == "" {
			continue
		}
		label := strings.TrimSpace(track.Label)
		if label == "" {
			label = subtitles.LanguageName(language)
		}
		out = append(out, subtitles.Track{Language: language, Label: label, URL: track.File})
	}
	return out
}

func skipTimesFor(episodes []miruro.Episode, number int) []skipWindow {
	for _, episode := range episodes {
		if episode.Ep() != number {
			continue
		}
		out := make([]skipWindow, 0, 2)
		for _, raw := range episode.SkipTimes {
			kind, _ := raw["kind"].(string)
			start, startOK := asFloat(raw["start_seconds"])
			end, endOK := asFloat(raw["end_seconds"])
			if !startOK || !endOK || end <= start {
				continue
			}
			segment := strings.ToLower(kind)
			if index := strings.LastIndexAny(segment, "-_"); index >= 0 {
				segment = segment[index+1:]
			}
			label := ""
			switch segment {
			case "op", "intro":
				label = "Skip Intro"
			case "ed", "outro":
				label = "Skip Outro"
			}
			if label != "" {
				out = append(out, skipWindow{Label: label, Start: start, End: end})
			}
		}
		return out
	}
	return nil
}

func asFloat(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, true
	case int:
		return float64(typed), true
	case string:
		parsed, err := strconv.ParseFloat(typed, 64)
		return parsed, err == nil
	}
	return 0, false
}

func nextAfter(episodes []miruro.Episode, current int) int {
	next := 0
	for _, episode := range episodes {
		if episode.Ep() > current && (next == 0 || episode.Ep() < next) {
			next = episode.Ep()
		}
	}
	return next
}

func speedLabel(speed float64) string {
	return strconv.FormatFloat(speed, 'f', -1, 64) + "x"
}

func mimeOf(rawURL string) string {
	path := strings.SplitN(strings.ToLower(rawURL), "?", 2)[0]
	if strings.HasSuffix(path, ".mp4") {
		return "mp4"
	}
	return "hls"
}

func oneshowsKind(kind string) oneshows.Kind {
	return oneshows.Kind(kind)
}
