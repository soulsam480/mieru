package main

import (
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/store"
	"mieru-desktop/internal/subtitles"
)

// newTestApp builds the window's state without network or a window, so the
// tester can drive the same view the app uses.
func newTestApp(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	return &App{
		router:    ui.NewRouter("/anime"),
		settings:  store.OpenSettings(dir),
		progress:  store.OpenProgress(dir),
		watch:     store.OpenWatchlist(dir),
		images:    newImageCache(),
		titles:    map[string]titleInfo{},
		noNetwork: true,
	}
}

func TestAnimeGridRendersAndOpensDetail(t *testing.T) {
	app := newTestApp(t)
	app.anime.entered = true
	app.anime.loading = false
	app.anime.items = []miruro.Anime{{
		ID:            "a1",
		Title:         miruro.Titles{English: "Dandadan"},
		EpisodeCounts: miruro.EpisodeCounts{Sub: 12},
	}}

	tt := ui.NewTester(app.view, 1280, 840)
	if !tt.HasText("Dandadan") {
		t.Fatalf("grid did not render, texts: %q", tt.Texts())
	}
	if !tt.HasText("12 episodes") {
		t.Fatalf("episode count missing, texts: %q", tt.Texts())
	}
	if err := tt.Click("Dandadan"); err != nil {
		t.Fatal(err)
	}
	if app.router.Path() != "/anime/a1" {
		t.Fatalf("path = %q", app.router.Path())
	}
}

func TestAnimeSortChangesTheFeed(t *testing.T) {
	app := newTestApp(t)
	app.anime.entered = true
	app.anime.loading = false
	app.anime.items = []miruro.Anime{{ID: "a1", Title: miruro.Titles{English: "Dandadan"}}}

	tt := ui.NewTester(app.view, 1280, 840)
	if err := tt.Click("Popular"); err != nil {
		t.Fatal(err)
	}
	if app.anime.sort != 1 {
		t.Fatalf("sort = %d, want 1", app.anime.sort)
	}
}

func TestShowsGridRendersTV(t *testing.T) {
	app := newTestApp(t)
	app.router = ui.NewRouter("/tv")
	app.tv.kind = oneshows.KindTV
	app.tv.entered = true
	app.tv.loading = false
	app.tv.items = []oneshows.Card{{
		ID: "5920", Kind: oneshows.KindTV, Title: "The Mentalist", Year: 2008, Score: 8.4,
	}}

	tt := ui.NewTester(app.view, 1280, 840)
	if !tt.HasText("TV Shows") || !tt.HasText("The Mentalist") {
		t.Fatalf("texts: %q", tt.Texts())
	}
	if !tt.HasText("2008 · ★ 8.4") {
		t.Fatalf("metadata missing: %q", tt.Texts())
	}
}

func TestDetailShowsEpisodesAndPlays(t *testing.T) {
	app := newTestApp(t)
	tt := ui.NewTester(app.view, 1280, 840)

	app.router.Push("/anime/a1")
	tt.Advance(600 * time.Millisecond)

	episodes := []miruro.Episode{
		{Number: 1, Title: "Pilot", AiredOn: "2020-01-01", DurationSeconds: 1440},
		{Number: 2, Title: "Second", AiredOn: "2030-01-01", DurationSeconds: 1440},
	}
	app.detail.kind = "ANIME"
	app.detail.id = "a1"
	app.detail.shownID = "a1"
	app.detail.loading = false
	app.detail.anime = miruro.Anime{ID: "a1", Title: miruro.Titles{English: "Dandadan"}, SeasonYear: 2024, AverageScore: 8}
	app.detail.episodes = episodes
	app.detail.released = releasedNumbers(episodes, time.Now())
	app.detail.watched = map[int]float64{}
	tt.Frame()

	if !tt.HasText("Episodes") || !tt.HasText("Play EP 1") {
		t.Fatalf("detail texts: %q", tt.Texts())
	}
	if !tt.HasText("airs 1 Jan 2030") {
		t.Fatalf("unaired row not marked: %q", tt.Texts())
	}
	if err := tt.Click("Play EP 1"); err != nil {
		t.Fatal(err)
	}
	if app.router.Path() != "/watch/ANIME/a1/0/1" {
		t.Fatalf("path = %q", app.router.Path())
	}
}

func TestDetailBookmarkToggles(t *testing.T) {
	app := newTestApp(t)
	tt := ui.NewTester(app.view, 1280, 840)
	app.router.Push("/anime/a1")
	tt.Advance(600 * time.Millisecond)
	app.detail.kind = "ANIME"
	app.detail.id = "a1"
	app.detail.shownID = "a1"
	app.detail.loading = false
	app.detail.anime = miruro.Anime{ID: "a1", Title: miruro.Titles{English: "Dandadan"}}
	tt.Frame()

	if !tt.HasText("+ My List") {
		t.Fatalf("bookmark missing: %q", tt.Texts())
	}
	if err := tt.Click("+ My List"); err != nil {
		t.Fatal(err)
	}
	if !app.watch.Contains("a1", "ANIME") {
		t.Fatal("bookmark did not save")
	}
	tt.Frame()
	if !tt.HasText("✓ My List") {
		t.Fatalf("bookmark did not flip: %q", tt.Texts())
	}
}

func TestLibraryListsProgressAndWatchlist(t *testing.T) {
	app := newTestApp(t)
	app.progress.Record("9", "Spider-Man", "", 0, 1000, 600000, 0, "MOVIE")
	app.watch.Toggle(store.WatchlistItem{ID: "9", Kind: "MOVIE", Title: "Spider-Man"})
	app.router = ui.NewRouter("/library")
	app.library.entered = true
	app.library.continueItems = app.progress.ContinueWatchingAll(20)
	app.library.watchlist = app.watch.Items()

	tt := ui.NewTester(app.view, 1280, 840)
	if !tt.HasText("Continue watching") || !tt.HasText("Watchlist") {
		t.Fatalf("sections missing: %q", tt.Texts())
	}
	if !tt.HasText("Spider-Man") {
		t.Fatalf("saved title missing: %q", tt.Texts())
	}
	if err := tt.Click("Remove"); err != nil {
		t.Fatal(err)
	}
	if app.watch.Contains("9", "MOVIE") {
		t.Fatal("Remove did not clear the watchlist")
	}
}

func TestLibraryEmptyState(t *testing.T) {
	app := newTestApp(t)
	app.router = ui.NewRouter("/library")
	app.library.entered = true

	tt := ui.NewTester(app.view, 1280, 840)
	want := "Nothing here yet. Save something with the bookmark, or start watching."
	if !tt.HasText(want) {
		t.Fatalf("empty state missing: %q", tt.Texts())
	}
}

func TestSettingsRendersEverySection(t *testing.T) {
	app := newTestApp(t)
	app.router = ui.NewRouter("/settings")

	tt := ui.NewTester(app.view, 1280, 840)
	for _, text := range []string{"Anime source", "TV and movie source", "Playback", "Subtitle style", "About"} {
		if !tt.HasText(text) {
			t.Errorf("settings is missing %q; texts: %q", text, tt.Texts())
		}
	}
	if err := tt.Click("Dub"); err != nil {
		t.Fatal(err)
	}
	if app.settings.Get().Track != "DUB" {
		t.Fatalf("track = %q", app.settings.Get().Track)
	}
}

func TestPlayerErrorState(t *testing.T) {
	app := newTestApp(t)
	app.router = ui.NewRouter("/anime")
	tt := ui.NewTester(app.view, 1280, 840)

	app.router.Push("/watch/ANIME/a1/0/1")
	tt.Advance(600 * time.Millisecond)
	app.player.loading = false
	app.player.err = "No playable stream for this episode"
	tt.Frame()

	if !tt.HasText("Cannot play this episode") {
		t.Fatalf("player error missing: %q", tt.Texts())
	}
	if !tt.HasText("Retry") {
		t.Fatalf("retry missing: %q", tt.Texts())
	}
}

func TestPlayerSkipWindowsAndCaption(t *testing.T) {
	episodes := []miruro.Episode{{
		Number: 1,
		SkipTimes: []map[string]any{
			{"kind": "op", "start_seconds": 12.0, "end_seconds": 102.0},
			{"kind": "mixed-ed", "start_seconds": 1300.0, "end_seconds": 1380.0},
			{"kind": "preview", "start_seconds": 1400.0, "end_seconds": 1420.0},
		},
	}}
	windows := skipTimesFor(episodes, 1)
	if len(windows) != 2 {
		t.Fatalf("windows = %+v", windows)
	}
	if windows[0].Label != "Skip Intro" || windows[0].Start != 12 {
		t.Fatalf("intro = %+v", windows[0])
	}
	if windows[1].Label != "Skip Outro" {
		t.Fatalf("outro = %+v", windows[1])
	}

	player := &playerPage{kind: "TV", season: 2, episode: 5}
	if got := player.caption(); got != "S2 E5" {
		t.Fatalf("caption = %q", got)
	}
	player.kind = "MOVIE"
	if got := player.caption(); got != "Movie" {
		t.Fatalf("movie caption = %q", got)
	}
}

func TestSpeedLabelKeepsFractions(t *testing.T) {
	if got := speedLabel(1.25); got != "1.25x" {
		t.Fatalf("speed label = %q", got)
	}
	if got := speedLabel(1); got != "1x" {
		t.Fatalf("speed label = %q", got)
	}
}

func TestPlayerDrawsSubtitleCue(t *testing.T) {
	app := newTestApp(t)
	app.router = ui.NewRouter("/anime")
	tt := ui.NewTester(app.view, 1280, 840)

	app.router.Push("/watch/TV/x/1/1")
	tt.Advance(600 * time.Millisecond)
	app.player.loading = false
	app.player.kind = "TV"
	app.player.season = 1
	app.player.episode = 1
	app.player.subtitleIndex = 0
	app.player.position = 12
	app.player.duration = 600
	app.player.cues = []subtitles.Cue{{Start: 10, End: 20, Text: "The night is dark"}}
	tt.Frame()

	if !tt.HasText("The night is dark") {
		t.Fatalf("the cue was not drawn, texts: %q", tt.Texts())
	}
}

func TestPlayerSpeedSteps(t *testing.T) {
	player := &playerPage{speed: 1}
	player.nudgeSpeed(1)
	if player.speed != 1.25 {
		t.Fatalf("speed after one step = %v", player.speed)
	}
	player.nudgeSpeed(-1)
	if player.speed != 1 {
		t.Fatalf("speed back = %v", player.speed)
	}
	player.speed = 2
	player.nudgeSpeed(1)
	if player.speed != 2 {
		t.Fatalf("speed should clamp at 2, got %v", player.speed)
	}
}

func TestAppNavigationFlow(t *testing.T) {
	app := newTestApp(t)
	app.anime.entered = true
	app.anime.loading = false
	app.anime.items = []miruro.Anime{{ID: "a1", Title: miruro.Titles{English: "Dandadan"}}}
	tt := ui.NewTester(app.view, 1280, 840)

	if !tt.HasText("Dandadan") {
		t.Fatalf("anime did not render: %q", tt.Texts())
	}
	for _, step := range []struct{ label, path string }{
		{"TV Shows", "/tv"},
		{"Movies", "/movies"},
		{"Library", "/library"},
		{"Settings", "/settings"},
		{"Anime", "/anime"},
	} {
		if err := tt.Click(step.label); err != nil {
			t.Fatalf("%s: %v", step.label, err)
		}
		if app.router.Path() != step.path {
			t.Fatalf("%s went to %q, want %q", step.label, app.router.Path(), step.path)
		}
	}
	if !tt.HasText("Dandadan") {
		t.Fatalf("returning to Anime lost the grid: %q", tt.Texts())
	}
}
