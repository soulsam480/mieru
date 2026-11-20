package embed

import (
	"testing"
	"time"
)

func key(id string) CacheKey {
	return CacheKey{MediaID: id, Kind: "TV", Season: 1, Episode: 1}
}

func TestCacheReturnsWhatItStored(t *testing.T) {
	cache := NewCache()
	if _, ok := cache.Get(key("a")); ok {
		t.Fatal("an empty cache returned a hit")
	}
	cache.Put(key("a"), Cached{Stream: Stream{URL: "https://cdn/a.m3u8", Provider: "Main 2"}, Caption: "S1 E1"})
	hit, ok := cache.Get(key("a"))
	if !ok || hit.Stream.URL != "https://cdn/a.m3u8" || hit.Caption != "S1 E1" {
		t.Fatalf("hit = %+v, %v", hit, ok)
	}
	if _, ok := cache.Get(key("b")); ok {
		t.Fatal("another episode matched")
	}
}

func TestCacheExpiresAfterTwentyMinutes(t *testing.T) {
	cache := NewCache()
	now := time.Unix(1000, 0)
	cache.now = func() time.Time { return now }
	cache.Put(key("a"), Cached{Stream: Stream{URL: "u"}})

	now = now.Add(19 * time.Minute)
	if _, ok := cache.Get(key("a")); !ok {
		t.Fatal("19 minutes should still be cached")
	}
	now = now.Add(2 * time.Minute)
	if _, ok := cache.Get(key("a")); ok {
		t.Fatal("21 minutes should have expired")
	}
}

func TestCacheEvictsOldestBeyondTwentyFour(t *testing.T) {
	cache := NewCache()
	now := time.Unix(1000, 0)
	cache.now = func() time.Time { return now }
	for i := 0; i < 25; i++ {
		now = now.Add(time.Second)
		cache.Put(key(string(rune('a'+i))), Cached{Stream: Stream{URL: "u"}})
	}
	if cache.Len() != cacheMax {
		t.Fatalf("len = %d, want %d", cache.Len(), cacheMax)
	}
	if _, ok := cache.Get(key("a")); ok {
		t.Fatal("the oldest entry survived")
	}
}

func TestCacheDelete(t *testing.T) {
	cache := NewCache()
	cache.Put(key("a"), Cached{})
	cache.Delete(key("a"))
	if cache.Len() != 0 {
		t.Fatal("delete left the entry")
	}
}
