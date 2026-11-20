package store

import "testing"

func TestProgressRecentsMoveForwardAndHideFinished(t *testing.T) {
	progress := OpenProgress(t.TempDir())
	progress.Record("a1", "Dandadan", "cover.jpg", 1, 1000, 60000, 0, "ANIME")
	progress.Record("a1", "Dandadan", "cover.jpg", 2, 2000, 60000, 0, "ANIME")

	items := progress.ContinueWatchingAll(10)
	if len(items) != 1 {
		t.Fatalf("items = %+v", items)
	}
	if items[0].Episode != 2 || items[0].Kind != "ANIME" {
		t.Fatalf("item = %+v", items[0])
	}
	if items[0].Caption() != "EP 2" {
		t.Fatalf("caption = %q", items[0].Caption())
	}

	progress.Record("a1", "Dandadan", "cover.jpg", 3, 57000, 60000, 0, "ANIME")
	if items := progress.ContinueWatchingAll(10); len(items) != 0 {
		t.Fatalf("95%% finished still listed: %+v", items)
	}
}

func TestProgressNeverMovesBackwards(t *testing.T) {
	progress := OpenProgress(t.TempDir())
	progress.Record("tv1", "Show", "", 5, 1000, 60000, 2, "TV")
	progress.Record("tv1", "Show", "", 1, 1000, 60000, 1, "TV")

	items := progress.ContinueWatchingAll(10)
	if len(items) != 1 || items[0].Season != 2 || items[0].Episode != 5 {
		t.Fatalf("recents moved backwards: %+v", items)
	}
	if items[0].Caption() != "S2 E5" {
		t.Fatalf("caption = %q", items[0].Caption())
	}
}

func TestProgressPersists(t *testing.T) {
	dir := t.TempDir()
	OpenProgress(dir).Record("m1", "Film", "", 0, 1000, 600000, 0, "MOVIE")
	reloaded := OpenProgress(dir)
	if got := reloaded.Position("m1", 0, 0); got != 1000 {
		t.Fatalf("position = %d", got)
	}
	if items := reloaded.ContinueWatching("MOVIE", 10); len(items) != 1 || items[0].Caption() != "Movie" {
		t.Fatalf("movie items = %+v", items)
	}
}

func TestWatchlistToggleAndIdentity(t *testing.T) {
	watch := OpenWatchlist(t.TempDir())
	item := WatchlistItem{ID: "9", Kind: "MOVIE", Title: "Spider-Man"}
	if !watch.Toggle(item) {
		t.Fatal("toggle should save")
	}
	if !watch.Contains("9", "MOVIE") {
		t.Fatal("saved title not found")
	}
	if watch.Contains("9", "TV") {
		t.Fatal("the same id under another kind is another title")
	}
	if watch.Toggle(item) {
		t.Fatal("toggle should remove")
	}
	if len(watch.Items()) != 0 {
		t.Fatalf("items = %+v", watch.Items())
	}
}

func TestSettingsDefaultsAndPersistence(t *testing.T) {
	dir := t.TempDir()
	settings := OpenSettings(dir)
	if got := settings.BaseURL(); got != "https://www.miruro.cx" {
		t.Fatalf("default base = %q", got)
	}
	settings.Update(func(s *Settings) {
		s.SelectedID = "mirror-2"
		s.Sources = append(s.Sources, Source{ID: "mirror-2", Name: "Two", BaseURL: "https://two.test"})
		s.Track = "DUB"
		s.SubtitleStyle.Size = "XL"
	})

	reloaded := OpenSettings(dir)
	if reloaded.BaseURL() != "https://two.test" {
		t.Fatalf("base = %q", reloaded.BaseURL())
	}
	if !reloaded.Dub() {
		t.Fatal("dub preference lost")
	}
	if reloaded.Get().SubtitleStyle.Size != "XL" {
		t.Fatal("subtitle style lost")
	}
}

func TestResumePositionIgnoresFinishedEpisodes(t *testing.T) {
	progress := OpenProgress(t.TempDir())
	progress.Record("a", "Show", "", 1, 1000, 60000, 0, "ANIME")
	if got := progress.ResumePosition("a", 0, 1); got != 1000 {
		t.Fatalf("partial resume = %d", got)
	}
	progress.Record("a", "Show", "", 2, 59000, 60000, 0, "ANIME")
	if got := progress.ResumePosition("a", 0, 2); got != 0 {
		t.Fatalf("finished resume = %d, want 0", got)
	}
}
