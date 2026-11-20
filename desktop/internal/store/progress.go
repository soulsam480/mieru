package store

import (
	"fmt"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// EpisodeProgress is one episode's watch time.
type EpisodeProgress struct {
	PositionMs int64 `json:"positionMs"`
	DurationMs int64 `json:"durationMs"`
	UpdatedAt  int64 `json:"updatedAt"`
}

// Fraction is how much of the episode has played, 0 to 1.
func (p EpisodeProgress) Fraction() float64 {
	if p.DurationMs <= 0 {
		return 0
	}
	fraction := float64(p.PositionMs) / float64(p.DurationMs)
	if fraction < 0 {
		return 0
	}
	if fraction > 1 {
		return 1
	}
	return fraction
}

// ContinueItem is one card in "Continue watching".
type ContinueItem struct {
	AnimeID    string `json:"animeId"`
	Title      string `json:"title"`
	Cover      string `json:"cover,omitempty"`
	Episode    int    `json:"episode"`
	PositionMs int64  `json:"positionMs"`
	DurationMs int64  `json:"durationMs"`
	UpdatedAt  int64  `json:"updatedAt"`
	Season     int    `json:"season"`
	Kind       string `json:"kind"`
}

// Caption is the row's second line.
func (c ContinueItem) Caption() string {
	if c.Kind == "MOVIE" {
		return "Movie"
	}
	if c.Season > 0 && c.Kind == "TV" {
		return fmt.Sprintf("S%d E%d", c.Season, c.Episode)
	}
	return fmt.Sprintf("EP %d", c.Episode)
}

// ProgressData is the file's shape.
type ProgressData struct {
	Episodes map[string]EpisodeProgress `json:"episodes"`
	Recents  map[string]ContinueItem    `json:"recents"`
}

// ProgressStore keeps watch time, purely local.
type ProgressStore struct {
	mu     sync.Mutex
	path   string
	data   ProgressData
	loaded bool
}

// OpenProgress loads the progress file.
func OpenProgress(dir string) *ProgressStore {
	return &ProgressStore{path: filepath.Join(dir, "progress.json")}
}

func (p *ProgressStore) ensure() {
	if p.loaded {
		return
	}
	p.data = ProgressData{}
	readJSON(p.path, &p.data)
	if p.data.Episodes == nil {
		p.data.Episodes = map[string]EpisodeProgress{}
	}
	if p.data.Recents == nil {
		p.data.Recents = map[string]ContinueItem{}
	}
	p.loaded = true
}

func progressKey(id string, season, episode int) string {
	if season > 0 {
		return fmt.Sprintf("%s:%d:%d", id, season, episode)
	}
	return fmt.Sprintf("%s:%d", id, episode)
}

// Position returns where an episode was left.
func (p *ProgressStore) Position(id string, season, episode int) int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ensure()
	return p.data.Episodes[progressKey(id, season, episode)].PositionMs
}

// Snapshot returns a copy of the progress data.
func (p *ProgressStore) Snapshot() ProgressData {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ensure()
	copy := ProgressData{
		Episodes: make(map[string]EpisodeProgress, len(p.data.Episodes)),
		Recents:  make(map[string]ContinueItem, len(p.data.Recents)),
	}
	for key, value := range p.data.Episodes {
		copy.Episodes[key] = value
	}
	for key, value := range p.data.Recents {
		copy.Recents[key] = value
	}
	return copy
}

// Record writes one position and moves the show's continue card forward.
func (p *ProgressStore) Record(id, title, cover string, episode int, positionMs, durationMs int64, season int, kind string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ensure()

	now := time.Now().UnixMilli()
	p.data.Episodes[progressKey(id, season, episode)] = EpisodeProgress{
		PositionMs: positionMs, DurationMs: durationMs, UpdatedAt: now,
	}

	key := kind + ":" + id
	existing, ok := p.data.Recents[key]
	if !ok || season > existing.Season || (season == existing.Season && episode >= existing.Episode) {
		p.data.Recents[key] = ContinueItem{
			AnimeID: id, Title: title, Cover: cover, Episode: episode,
			PositionMs: positionMs, DurationMs: durationMs, UpdatedAt: now,
			Season: season, Kind: kind,
		}
	}
	_ = writeJSON(p.path, p.data)
}

// ContinueWatchingAll mixes every section, newest first, skipping anything
// essentially finished.
func (p *ProgressStore) ContinueWatchingAll(limit int) []ContinueItem {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.ensure()
	items := make([]ContinueItem, 0, len(p.data.Recents))
	for _, item := range p.data.Recents {
		if item.DurationMs > 0 && float64(item.PositionMs)/float64(item.DurationMs) >= 0.95 {
			continue
		}
		items = append(items, item)
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].UpdatedAt > items[j].UpdatedAt })
	if limit > 0 && len(items) > limit {
		items = items[:limit]
	}
	return items
}

// ContinueWatching returns one section's cards.
func (p *ProgressStore) ContinueWatching(kind string, limit int) []ContinueItem {
	all := p.ContinueWatchingAll(0)
	out := make([]ContinueItem, 0, len(all))
	for _, item := range all {
		if item.Kind == kind {
			out = append(out, item)
		}
	}
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out
}
