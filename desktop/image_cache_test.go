package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestImageCacheFetchesOnceAndReusesBitmap(t *testing.T) {
	var hits int32
	picture := image.NewRGBA(image.Rect(0, 0, 4, 6))
	for y := 0; y < 6; y++ {
		for x := 0; x < 4; x++ {
			picture.Set(x, y, color.RGBA{R: 200, G: 40, B: 40, A: 255})
		}
	}
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, picture); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		_, _ = w.Write(encoded.Bytes())
	}))
	defer server.Close()

	app := newTestApp(t)
	if app.bitmap(server.URL) != nil {
		t.Fatal("the first call should return a placeholder")
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		app.images.mu.Lock()
		entry := app.images.items[server.URL]
		app.images.mu.Unlock()
		if entry != nil && entry.bitmap != nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	first := app.bitmap(server.URL)
	if first == nil {
		t.Fatal("the picture never decoded")
	}
	if second := app.bitmap(server.URL); second != first {
		t.Fatal("a second decode replaced the cached bitmap")
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("the server saw %d requests, want 1", got)
	}
}
