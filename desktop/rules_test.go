package main

import (
	"testing"
	"time"

	"mieru-desktop/internal/miruro"
)

func episode(number int, aired string) miruro.Episode {
	return miruro.Episode{Number: float64(number), Title: "Real title", AiredOn: aired}
}

func TestReleasedNumbersDatedRun(t *testing.T) {
	today := time.Date(2026, 11, 20, 12, 0, 0, 0, time.UTC)
	episodes := []miruro.Episode{
		episode(1, "2026-11-01"),
		episode(2, "2026-11-08"),
		episode(3, "2026-12-01"),
	}
	released := releasedNumbers(episodes, today)
	if !released[1] || !released[2] {
		t.Fatalf("aired episodes missing: %v", released)
	}
	if released[3] {
		t.Fatal("a future episode counted as released")
	}
}

func TestReleasedNumbersUndatedRowsInsideAiredRun(t *testing.T) {
	today := time.Date(2026, 11, 20, 12, 0, 0, 0, time.UTC)
	episodes := []miruro.Episode{
		episode(1, "2026-11-01"),
		episode(2, ""),
		episode(3, "2026-11-10"),
		episode(4, ""),
	}
	released := releasedNumbers(episodes, today)
	for _, number := range []int{1, 2, 3} {
		if !released[number] {
			t.Fatalf("episode %d should be released, got %v", number, released)
		}
	}
	if released[4] {
		t.Fatal("a padded placeholder counted as released")
	}
}

func TestReleasedNumbersNoDatesMeansEverything(t *testing.T) {
	episodes := []miruro.Episode{episode(1, ""), episode(2, "")}
	released := releasedNumbers(episodes, time.Now())
	if len(released) != 2 {
		t.Fatalf("no dates should release everything, got %v", released)
	}
}

func TestNextEpisodeSkipsFinishedAndUnreleased(t *testing.T) {
	episodes := []miruro.Episode{
		episode(1, "2026-11-01"),
		episode(2, "2026-11-08"),
		episode(3, "2026-11-15"),
		episode(4, "2026-12-20"),
	}
	watched := map[int]float64{1: 1, 2: 0.95}
	if got := nextEpisode(episodes, watched); got != 3 {
		t.Fatalf("next episode = %d, want 3", got)
	}
	watched[3] = 1
	if got := nextEpisode(episodes, watched); got != 3 {
		t.Fatalf("all watched should fall back to the last released, got %d", got)
	}
}

func TestPlaceholderTitlesAreHidden(t *testing.T) {
	if !placeholderTitle("Episode 57") {
		t.Fatal("generic title should be a placeholder")
	}
	if placeholderTitle("The Beginning") {
		t.Fatal("a real title was treated as a placeholder")
	}
	if got := displayTitle(miruro.Episode{Number: 9, Title: "Episode 9"}); got != "" {
		t.Fatalf("displayTitle = %q, want empty", got)
	}
}

func TestNextAfter(t *testing.T) {
	episodes := []miruro.Episode{episode(1, ""), episode(2, ""), episode(5, "")}
	if got := nextAfter(episodes, 2); got != 5 {
		t.Fatalf("nextAfter = %d, want 5", got)
	}
	if got := nextAfter(episodes, 5); got != 0 {
		t.Fatalf("nextAfter at the end = %d, want 0", got)
	}
}
