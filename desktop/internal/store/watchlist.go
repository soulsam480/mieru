package store

import (
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// WatchlistItem is one saved title.
type WatchlistItem struct {
	ID      string `json:"id"`
	Kind    string `json:"kind"`
	Title   string `json:"title"`
	Poster  string `json:"poster,omitempty"`
	AddedAt int64  `json:"addedAt"`
}

type watchlistData struct {
	Items []WatchlistItem `json:"items"`
}

// WatchlistStore is the saved-titles file, newest first.
type WatchlistStore struct {
	mu     sync.Mutex
	path   string
	data   watchlistData
	loaded bool
}

// OpenWatchlist loads the watchlist file.
func OpenWatchlist(dir string) *WatchlistStore {
	return &WatchlistStore{path: filepath.Join(dir, "watchlist.json")}
}

func (w *WatchlistStore) ensure() {
	if w.loaded {
		return
	}
	readJSON(w.path, &w.data)
	w.loaded = true
}

// Items returns the list, newest first.
func (w *WatchlistStore) Items() []WatchlistItem {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.ensure()
	items := append([]WatchlistItem(nil), w.data.Items...)
	sort.SliceStable(items, func(i, j int) bool { return items[i].AddedAt > items[j].AddedAt })
	return items
}

// Contains reports whether a title is saved.
func (w *WatchlistStore) Contains(id, kind string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.ensure()
	for _, item := range w.data.Items {
		if item.ID == id && item.Kind == kind {
			return true
		}
	}
	return false
}

// Toggle adds or removes a title, returning whether it ended up saved.
func (w *WatchlistStore) Toggle(item WatchlistItem) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.ensure()

	saved := false
	out := make([]WatchlistItem, 0, len(w.data.Items)+1)
	for _, existing := range w.data.Items {
		if existing.ID == item.ID && existing.Kind == item.Kind {
			saved = true
			continue
		}
		out = append(out, existing)
	}
	if !saved {
		if item.AddedAt == 0 {
			item.AddedAt = time.Now().UnixMilli()
		}
		out = append(out, item)
	}
	w.data.Items = out
	_ = writeJSON(w.path, w.data)
	return !saved
}
