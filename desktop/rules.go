package main

import (
	"strings"
	"time"

	"mieru-desktop/internal/miruro"
)

// releasedNumbers says which episodes exist yet. Providers pad the list: a
// show claiming 24 episodes can have 7 aired and the rest as blank
// placeholders with no air date. An undated row counts only when a later row
// has aired, and a season with no dates at all counts as fully released.
func releasedNumbers(episodes []miruro.Episode, today time.Time) map[int]bool {
	released := map[int]bool{}
	if len(episodes) == 0 {
		return released
	}
	dated := false
	lastAired := 0
	for _, episode := range episodes {
		if strings.TrimSpace(episode.AiredOn) == "" {
			continue
		}
		dated = true
		if episode.ReleasedOn(today) && episode.Ep() > lastAired {
			lastAired = episode.Ep()
		}
	}
	if !dated {
		for _, episode := range episodes {
			released[episode.Ep()] = true
		}
		return released
	}
	for _, episode := range episodes {
		if strings.TrimSpace(episode.AiredOn) != "" {
			if episode.ReleasedOn(today) {
				released[episode.Ep()] = true
			}
			continue
		}
		if episode.Ep() <= lastAired {
			released[episode.Ep()] = true
		}
	}
	return released
}

var placeholderTitle = func(title string) bool {
	title = strings.ToLower(strings.TrimSpace(title))
	if title == "" {
		return true
	}
	if !strings.HasPrefix(title, "episode ") {
		return false
	}
	for _, ch := range strings.TrimPrefix(title, "episode ") {
		if ch < '0' || ch > '9' {
			return false
		}
	}
	return true
}

// realSchedule drops the rows the provider invented: no date, and a generic
// or empty title. A row with a date is real even when its title is generic.
func realSchedule(episode miruro.Episode) bool {
	if strings.TrimSpace(episode.AiredOn) != "" {
		return true
	}
	return !placeholderTitle(episode.Title)
}

// displayTitle hides the "Episode 57" titles on episode 9.
func displayTitle(episode miruro.Episode) string {
	title := strings.TrimSpace(episode.Title)
	if placeholderTitle(title) {
		return ""
	}
	return title
}

// nextEpisode is the first released episode that is unwatched or unfinished,
// falling back to the last released one.
func nextEpisode(episodes []miruro.Episode, watched map[int]float64) int {
	released := releasedNumbers(episodes, time.Now())
	available := make([]miruro.Episode, 0, len(episodes))
	for _, episode := range episodes {
		if released[episode.Ep()] {
			available = append(available, episode)
		}
	}
	if len(available) == 0 {
		for _, episode := range episodes {
			if realSchedule(episode) {
				return episode.Ep()
			}
		}
		return 1
	}
	for _, episode := range available {
		if fraction, ok := watched[episode.Ep()]; !ok || fraction < 0.9 {
			return episode.Ep()
		}
	}
	return available[len(available)-1].Ep()
}
