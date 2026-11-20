package embed

import (
	"sync"
	"time"

	"mieru-desktop/internal/oneshows"
	"mieru-desktop/internal/subtitles"
)

const (
	cacheTTL = 20 * time.Minute
	cacheMax = 24
)

// CacheKey identifies one episode of one title.
type CacheKey struct {
	MediaID string
	Kind    string
	Season  int
	Episode int
}

// Cached is what a successful resolve left behind: the playlist, the mirrors
// still worth trying, and the subtitle tracks that came with it.
type Cached struct {
	Stream    Stream
	Remaining []oneshows.Mirror
	Subtitles []subtitles.Track
	Caption   string
	added     time.Time
}

// Cache is the in-memory resolver cache. Reopening an episode should not spin
// up another hidden window and wait for a mirror again.
type Cache struct {
	mu    sync.Mutex
	items map[CacheKey]Cached
	now   func() time.Time
}

// NewCache returns an empty cache.
func NewCache() *Cache {
	return &Cache{items: map[CacheKey]Cached{}, now: time.Now}
}

// Get returns a cached resolve that is still inside its TTL.
func (c *Cache) Get(key CacheKey) (Cached, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	item, ok := c.items[key]
	if !ok {
		return Cached{}, false
	}
	if c.now().Sub(item.added) > cacheTTL {
		delete(c.items, key)
		return Cached{}, false
	}
	return item, true
}

// Put stores a resolve and evicts the oldest entry beyond the cap.
func (c *Cache) Put(key CacheKey, value Cached) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for existing, item := range c.items {
		if c.now().Sub(item.added) > cacheTTL {
			delete(c.items, existing)
		}
	}
	value.added = c.now()
	c.items[key] = value
	for len(c.items) > cacheMax {
		var oldestKey CacheKey
		var oldest time.Time
		first := true
		for existing, item := range c.items {
			if first || item.added.Before(oldest) {
				oldestKey, oldest, first = existing, item.added, false
			}
		}
		delete(c.items, oldestKey)
	}
}

// Delete drops one episode, as a failing source does.
func (c *Cache) Delete(key CacheKey) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, key)
}

// Len is the number of cached episodes, for tests and diagnostics.
func (c *Cache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.items)
}
