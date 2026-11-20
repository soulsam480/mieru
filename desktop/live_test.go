package main

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"mieru-desktop/internal/miruro"
	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/subtitles"
)

// Live tests hit the real catalogues. They run only with MIERU_LIVE=1, so the
// default suite stays deterministic and offline.
func live(t *testing.T) {
	t.Helper()
	if os.Getenv("MIERU_LIVE") == "" {
		t.Skip("set MIERU_LIVE=1 to hit the live catalogues")
	}
}

func TestLiveMiruroCatalog(t *testing.T) {
	live(t)
	client := miruro.New(func() string { return "https://www.miruro.cx" })
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	page, err := client.Catalog(ctx, 15, "-popularity", "", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Data) == 0 {
		t.Fatal("catalogue returned no anime")
	}
	if page.Data[0].Title.Best() == "" || page.Data[0].ID == "" {
		t.Fatalf("first entry = %+v", page.Data[0])
	}
	t.Logf("%d entries, first: %s (%s)", len(page.Data), page.Data[0].Title.Best(), page.Data[0].ID)

	latest, err := client.RecentlyAired(ctx, 7)
	if err != nil {
		t.Fatal(err)
	}
	if len(latest) == 0 {
		t.Fatal("schedule returned no airings")
	}
	t.Logf("%d airings, newest: %s ep %d", len(latest), latest[0].AnimeID, latest[0].Ep())
}

func TestLiveMiruroEpisodesAndPlay(t *testing.T) {
	live(t)
	client := miruro.New(func() string { return "https://www.miruro.cx" })
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	latest, err := client.RecentlyAired(ctx, 7)
	if err != nil {
		t.Fatal(err)
	}
	var played, attempts int
	var lastErr error
	for _, entry := range latest {
		if played >= 3 || attempts >= 4 {
			break
		}
		attempts++
		episodes, err := client.Episodes(ctx, entry.AnimeID)
		if err != nil || len(episodes) == 0 {
			continue
		}
		episode := episodes[len(episodes)-1].Ep()
		play, err := client.Play(ctx, entry.AnimeID, episode)
		if err != nil {
			lastErr = err
			t.Logf("%s ep %d: %v", entry.AnimeID, episode, err)
			continue
		}
		sources := miruro.Sources(play, false)
		if len(sources) == 0 {
			t.Logf("%s ep %d: no playable sources", entry.AnimeID, episode)
			continue
		}
		played++
		t.Logf("%s ep %d: %d sources, first: %s", entry.AnimeID, episode, len(sources), sources[0].Label)
	}
	if played == 0 {
		if lastErr != nil && (strings.Contains(lastErr.Error(), "502") || strings.Contains(lastErr.Error(), "timeout")) {
			t.Skipf("miruro's play endpoint is down upstream (catalogue and schedule answered): %v", lastErr)
		}
		t.Fatal("no live episode returned a playable source")
	}
}

func TestLiveOneShows(t *testing.T) {
	live(t)
	client := oneshows.New(func() string { return "https://www.1shows.org" })
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	cards, err := client.Search(ctx, "the mentalist")
	if err != nil {
		t.Fatal(err)
	}
	if len(cards) == 0 {
		t.Fatal("search returned nothing")
	}
	t.Logf("search: %s (%s)", cards[0].Title, cards[0].ID)

	detail, err := client.Detail(ctx, cards[0].Kind, cards[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("detail: %s %d, %d seasons", detail.Title, detail.Year, len(detail.Seasons))

	season := 0
	for _, candidate := range detail.Seasons {
		if candidate.Number > 0 && candidate.EpisodeCount > 0 {
			season = candidate.Number
			break
		}
	}
	if detail.Kind == oneshows.KindTV && season > 0 {
		episodes, err := client.Season(ctx, detail.ID, season)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("season %d: %d episodes", season, len(episodes))
		if imdb := client.IMDBID(ctx, detail.Kind, detail.ID); imdb == "" {
			t.Error("no IMDb id")
		}
		mirrors, err := client.Mirrors(ctx, detail.ID, detail.Kind, season, 1)
		if err != nil {
			t.Fatal(err)
		}
		if len(mirrors) == 0 {
			t.Fatal("no mirrors")
		}
		t.Logf("%d mirrors, first: %s %s", len(mirrors), mirrors[0].Label, mirrors[0].URL)
	}

	movies, err := client.Trending(ctx, oneshows.KindMovie)
	if err != nil {
		t.Fatal(err)
	}
	if len(movies) == 0 {
		t.Fatal("movie trending returned nothing")
	}
	t.Logf("trending movies: %d, first: %s", len(movies), movies[0].Title)
}

func TestLiveSubtitles(t *testing.T) {
	live(t)
	client := subtitles.New()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	tracks := client.Search(ctx, "tt1196946", 1, 1)
	if len(tracks) == 0 {
		t.Fatal("no subtitle tracks")
	}
	t.Logf("%d tracks, first: %s %s", len(tracks), tracks[0].Language, tracks[0].Label)
	if tracks[0].Language != "en" {
		t.Errorf("English should be first, got %q", tracks[0].Language)
	}
	body, err := client.Fetch(ctx, tracks[0].URL)
	if err != nil {
		t.Fatal(err)
	}
	cues := subtitles.Parse(body)
	if len(cues) == 0 {
		t.Fatal("track parsed to no cues")
	}
	t.Logf("%d cues, first at %.1fs: %q", len(cues), cues[0].Start, cues[0].Text)
}
