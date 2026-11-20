package main

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/embed"
	"mieru-desktop/internal/media"
	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/store"
	"mieru-desktop/internal/subtitles"
)

const (
	appVersion = "1.0.0"
	userAgent  = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

var imageHTTP = &http.Client{Timeout: 20 * time.Second}

// App is the window's state and the services behind it.
type App struct {
	win    *mygo.Window
	router *ui.Router

	miruro   *miruro.Client
	shows    *oneshows.Client
	subs     *subtitles.Client
	settings *store.SettingsStore
	progress *store.ProgressStore
	watch    *store.WatchlistStore
	media    *media.Server
	resolver *embed.Resolver
	images   *imageCache

	anime      animePage
	tv         showsPage
	movies     showsPage
	detail     detailPage
	player     playerPage
	library    libraryPage
	settingsUI settingsPage

	// titles remembers what detail pages learned, so the player can label itself
	// when it is entered from history.
	titles map[string]titleInfo

	lastPath  string
	noNetwork bool
}

type titleInfo struct {
	Title  string
	Cover  string
	Poster string
}

// NewApp wires the services. It does not start any network work; that waits
// for the window.
func NewApp(dataDir string) (*App, error) {
	settings := store.OpenSettings(dataDir)
	proxy, err := media.NewServer()
	if err != nil {
		return nil, err
	}
	app := &App{
		router:   ui.NewRouter("/anime"),
		settings: settings,
		progress: store.OpenProgress(dataDir),
		watch:    store.OpenWatchlist(dataDir),
		media:    proxy,
		resolver: embed.New(),
		images:   newImageCache(),
		titles:   map[string]titleInfo{},
	}
	app.miruro = miruro.New(settings.BaseURL)
	app.shows = oneshows.New(settings.ShowsSite)
	app.subs = subtitles.New()
	return app, nil
}

// Update runs fn on the UI thread. Tests have no window and do not need it.
func (a *App) update(fn func()) {
	if a.win == nil {
		return
	}
	a.win.Update(fn)
}

// view builds the window.
func (a *App) view(c *ui.Context) {
	path := a.router.Path()
	if path != a.lastPath {
		a.lastPath = path
		a.routeChanged(path)
	}

	if strings.HasPrefix(path, "/watch/") {
		a.player.view(c, a)
		return
	}

	t := c.Theme()
	ui.Column(c).Fill().Background(t.Background).Children(func() {
		a.topBar(c)
		ui.Box(c).Grow(1).Children(func() {
			a.router.View(c, func(r *ui.Route) {
				switch {
				case r.Match("/anime"):
					r.Title("Anime")
					a.anime.view(c, a)
				case r.Match("/anime/{id}"):
					r.Title("Anime")
					a.detail.view(c, a)
				case r.Match("/tv"):
					r.Title("TV Shows")
					a.tv.view(c, a)
				case r.Match("/movies"):
					r.Title("Movies")
					a.movies.view(c, a)
				case r.Match("/shows/{kind}/{id}"):
					r.Title("Details")
					a.detail.view(c, a)
				case r.Match("/library"):
					r.Title("Library")
					a.library.view(c, a)
				case r.Match("/settings"):
					r.Title("Settings")
					a.settingsUI.view(c, a)
				default:
					ui.Text(c, "Not found").Padding(24)
				}
			})
		})
	})
}

// topBar is the app's navigation: the wordmark, the three catalogues, then
// Library and Settings. It mirrors the site layouts this app replaces.
func (a *App) topBar(c *ui.Context) {
	t := c.Theme()
	path := a.router.Path()
	section := "/anime"
	switch {
	case path == "/tv" || strings.HasPrefix(path, "/tv/"):
		section = "/tv"
	case path == "/movies" || strings.HasPrefix(path, "/movies/"):
		section = "/movies"
	case path == "/library":
		section = "/library"
	case path == "/settings":
		section = "/settings"
	case strings.HasPrefix(path, "/shows/"):
		section = "/tv"
		if strings.HasPrefix(path, "/shows/MOVIE/") {
			section = "/movies"
		}
	case strings.HasPrefix(path, "/watch/"):
		switch {
		case strings.HasPrefix(path, "/watch/TV/"):
			section = "/tv"
		case strings.HasPrefix(path, "/watch/MOVIE/"):
			section = "/movies"
		}
	}

	ui.Row(c).FillWidth().Padding(22, 10).Gap(28).AlignItems(ui.Center).Background(t.Background).Children(func() {
		ui.Row(c).Gap(7).AlignItems(ui.Center).Children(func() {
			ui.Box(c).Size(17, 17).Radius(9).Background(ui.RGB(229, 57, 53))
			ui.Text(c, "Mieru").FontSize(18).Bold().TextColor(ui.RGB(240, 240, 240))
		})
		ui.Row(c).Gap(18).AlignItems(ui.Center).Children(func() {
			for _, item := range []struct{ path, label string }{
				{"/anime", "Anime"},
				{"/tv", "TV Shows"},
				{"/movies", "Movies"},
			} {
				item := item
				active := section == item.path
				button := ui.ButtonBase(c.Key(item.path)).Padding(2, 2).Radius(0)
				button.Children(func() {
					color := t.TextMuted
					if active {
						color = ui.RGB(245, 245, 245)
					}
					ui.Column(c).Gap(4).Children(func() {
						ui.Text(c, item.label).FontSize(13).TextColor(color)
						if active {
							ui.Box(c).FillWidth().Height(2).Radius(1).Background(ui.RGB(229, 57, 53))
						}
					})
				})
				if button.Clicked() && !active {
					a.router.Push(item.path)
				}
			}
		})
		ui.Spacer(c).Grow(1)
		for _, item := range []struct{ path, label string }{
			{"/library", "Library"},
			{"/settings", "Settings"},
		} {
			item := item
			color := t.TextMuted
			if section == item.path {
				color = ui.RGB(245, 245, 245)
			}
			b := ui.ButtonBase(c.Key(item.path)).Padding(2, 4).Radius(0)
			b.Children(func() { ui.Text(c, item.label).FontSize(12).TextColor(color) })
			if b.Clicked() && section != item.path {
				a.router.Push(item.path)
			}
		}
	})
}

func (a *App) routeChanged(path string) {
	if !strings.HasPrefix(path, "/watch/") {
		a.player.close()
	}
	switch {
	case path == "/anime":
		a.anime.enter(a)
	case path == "/tv":
		a.tv.enter(a, oneshows.KindTV)
	case path == "/movies":
		a.movies.enter(a, oneshows.KindMovie)
	case strings.HasPrefix(path, "/library"):
		a.library.enter(a)
	case strings.HasPrefix(path, "/settings"):
		a.settingsUI.enter(a)
	case strings.HasPrefix(path, "/anime/"):
		a.detail.enterAnime(a, strings.TrimPrefix(path, "/anime/"))
	case strings.HasPrefix(path, "/shows/"):
		parts := strings.Split(strings.TrimPrefix(path, "/shows/"), "/")
		if len(parts) == 2 {
			a.detail.enterShow(a, oneshows.Kind(parts[0]), parts[1])
		}
	case strings.HasPrefix(path, "/watch/"):
		a.player.enter(a)
	}
}

// ---------- images ----------

type imageEntry struct {
	bitmap   *ui.Bitmap
	loading  bool
	failed   bool
	failedAt time.Time
}

// imageSem keeps the bursts of TMDB stills from tripping its rate limit; a
// 429 cached as a failure used to leave a thumbnail blank for the whole run.
var imageSem = make(chan struct{}, 6)

type imageCache struct {
	mu    sync.Mutex
	items map[string]*imageEntry
}

func newImageCache() *imageCache {
	return &imageCache{items: map[string]*imageEntry{}}
}

// bitmap returns the decoded picture, starting a download the first time it is
// asked for. It returns nil until the bytes are decoded.
func (a *App) bitmap(rawURL string) *ui.Bitmap {
	if strings.TrimSpace(rawURL) == "" {
		return nil
	}
	a.images.mu.Lock()
	entry, ok := a.images.items[rawURL]
	if !ok {
		a.images.items[rawURL] = &imageEntry{loading: true}
		a.images.mu.Unlock()
		go a.loadImage(rawURL)
		return nil
	}
	a.images.mu.Unlock()
	if entry.failed {
		if time.Since(entry.failedAt) < 20*time.Second {
			return nil
		}
		a.images.mu.Lock()
		entry.failed, entry.loading = false, true
		a.images.mu.Unlock()
		go a.loadImage(rawURL)
		return nil
	}
	return entry.bitmap
}

func (a *App) loadImage(rawURL string) {
	imageSem <- struct{}{}
	defer func() { <-imageSem }()
	fail := func() {
		a.images.mu.Lock()
		if entry := a.images.items[rawURL]; entry != nil {
			entry.failed = true
			entry.failedAt = time.Now()
			entry.loading = false
		}
		a.images.mu.Unlock()
	}
	res, err := imageHTTP.Get(rawURL)
	if err != nil {
		fail()
		return
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		fail()
		return
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, 12<<20))
	if err != nil {
		fail()
		return
	}
	bitmap, err := ui.DecodeBitmap(data)
	if err != nil {
		fail()
		return
	}
	a.images.mu.Lock()
	a.images.items[rawURL] = &imageEntry{bitmap: bitmap}
	if len(a.images.items) > 500 {
		a.images.items = map[string]*imageEntry{rawURL: {bitmap: bitmap}}
	}
	a.images.mu.Unlock()
	a.update(func() {})
}

// poster draws a 2:3 picture, or a placeholder while it loads.
func (a *App) poster(c *ui.Context, rawURL string, width float32) {
	height := width * 1.5
	if bitmap := a.bitmap(rawURL); bitmap != nil {
		ui.Image(c, bitmap).Size(width, height).Fit(ui.Cover).Radius(10)
		return
	}
	t := c.Theme()
	ui.Box(c).Size(width, height).Radius(10).Background(t.Surface).Center().Children(func() {
		if rawURL != "" {
			ui.Spinner(c).Size(18, 18)
		}
	})
}

func (a *App) backdrop(c *ui.Context, rawURL string, width, height float32) {
	if bitmap := a.bitmap(rawURL); bitmap != nil {
		ui.Image(c, bitmap).Size(width, height).Fit(ui.Cover).Radius(14)
		return
	}
	t := c.Theme()
	ui.Box(c).Size(width, height).Radius(14).Background(t.Surface)
}

// ---------- shared pieces ----------

func sectionTitle(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(32).Bold()
}

func muted(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(12).TextColor(c.Theme().TextMuted)
}

func (a *App) empty(c *ui.Context, message string) {
	t := c.Theme()
	ui.Column(c).Grow(1).Center().Gap(10).Children(func() {
		ui.Text(c, message).TextColor(t.TextMuted).TextAlign(ui.Center)
	})
}

func (a *App) errorBox(c *ui.Context, message string, retry func()) {
	t := c.Theme()
	ui.Column(c).Grow(1).Center().Gap(12).Children(func() {
		ui.Text(c, "Something went wrong").Bold()
		ui.Text(c, message).FontSize(12).TextColor(t.TextMuted).MaxLines(4).TextAlign(ui.Center)
		if retry != nil {
			ui.Button(c, "Retry").OnClick(retry)
		}
	})
}

func (a *App) loading(c *ui.Context) {
	ui.Column(c).Grow(1).Center().Children(func() {
		ui.Spinner(c).Size(26, 26)
	})
}

// chip is a filter pill: filled when chosen, plain otherwise.
func chip(c *ui.Context, label string, active bool) bool {
	t := c.Theme()
	background, text := t.Surface, t.Text
	if active {
		background, text = t.Accent, t.AccentText
	}
	button := ui.ButtonBase(c).Padding(6, 12).Radius(14).Background(background)
	button.Children(func() {
		ui.Text(c, label).FontSize(12).TextColor(text).SingleLine()
	})
	return button.Clicked()
}

func (a *App) backButton(c *ui.Context) {
	if ui.Button(c, "‹ Back").Clicked() {
		a.router.Back()
	}
}

func (a *App) rememberTitle(id string, info titleInfo) {
	if id != "" {
		a.titles[id] = info
	}
}

func (a *App) knownTitle(id string) titleInfo { return a.titles[id] }

func formatScore(score float64) string {
	if score <= 0 {
		return ""
	}
	return fmt.Sprintf("★ %.1f", score)
}

func formatYear(year int) string {
	if year <= 0 {
		return ""
	}
	return fmt.Sprint(year)
}

func clock(seconds float64) string {
	if seconds < 0 || seconds != seconds {
		seconds = 0
	}
	total := int(seconds)
	hours := total / 3600
	minutes := (total % 3600) / 60
	secs := total % 60
	if hours > 0 {
		return fmt.Sprintf("%d:%02d:%02d", hours, minutes, secs)
	}
	return fmt.Sprintf("%d:%02d", minutes, secs)
}
