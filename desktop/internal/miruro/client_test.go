package miruro

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// encodePayload is what the edge does to every response.
func encodePayload(t *testing.T, value any) []byte {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var compressed bytes.Buffer
	writer := gzip.NewWriter(&compressed)
	if _, err := writer.Write(body); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	raw := compressed.Bytes()
	key := []byte(payloadKey)
	for i := range raw {
		raw[i] ^= key[i%len(key)]
	}
	return raw
}

func TestDecodePayloadRoundTrip(t *testing.T) {
	want := `{"data":[{"id":"a1"}],"has_more":true}`
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer fake.Close()

	var encoded bytes.Buffer
	writer := gzip.NewWriter(&encoded)
	_, _ = writer.Write([]byte(want))
	_ = writer.Close()
	raw := encoded.Bytes()
	key := []byte(payloadKey)
	for i := range raw {
		raw[i] ^= key[i%len(key)]
	}
	got, err := DecodePayload(raw)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != want {
		t.Fatalf("decoded %q, want %q", got, want)
	}
}

func TestCatalogSendsSameOriginHeadersAndDecodes(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/anime" {
			t.Errorf("path = %s", r.URL.Path)
		}
		if r.Header.Get("Sec-Fetch-Site") != "same-origin" {
			t.Errorf("Sec-Fetch-Site = %q", r.Header.Get("Sec-Fetch-Site"))
		}
		if r.Header.Get("Referer") != serverURL(r)+"/" {
			t.Errorf("Referer = %q", r.Header.Get("Referer"))
		}
		if r.URL.Query().Get("limit") != "15" || r.URL.Query().Get("sort") != "-popularity" {
			t.Errorf("query = %s", r.URL.RawQuery)
		}
		_, _ = w.Write(encodePayload(t, CatalogPage{
			Data: []Anime{{
				ID:            "a1",
				Title:         Titles{English: "Dandadan"},
				CoverURL:      "https://example.test/cover.jpg",
				EpisodeCounts: EpisodeCounts{Sub: 12},
			}},
			NextCursor: "c2",
			HasMore:    true,
		}))
	}))
	defer server.Close()

	client := New(func() string { return server.URL })
	page, err := client.Catalog(context.Background(), 15, "-popularity", "", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Data) != 1 || page.Data[0].Title.Best() != "Dandadan" {
		t.Fatalf("page = %+v", page)
	}
	if page.Data[0].ShownEpisodes() != 12 {
		t.Fatalf("shown episodes = %d", page.Data[0].ShownEpisodes())
	}
	if !page.HasMore || page.NextCursor != "c2" {
		t.Fatalf("paging = %+v", page)
	}
}

func serverURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func TestSourcesPreferSubThenBestProviderAndDeriveOrigin(t *testing.T) {
	play := PlayEnvelope{Tracks: []Track{
		{
			Track: "dub",
			Providers: []Provider{{
				Provider: "anikoto",
				Servers: []Server{{
					Server:  "dub-server",
					Headers: map[string]string{"Referer": "https://cdn.example/a"},
					Streams: []Stream{{URL: "https://cdn.example/dub.m3u8", Format: "hls"}},
				}},
			}},
		},
		{
			Track: "ssub",
			Providers: []Provider{
				{
					Provider: "unknown",
					Servers: []Server{{
						Server:  "plain",
						Streams: []Stream{{URL: "https://cdn.example/unknown.m3u8", Format: "hls"}},
					}},
				},
				{
					Provider: "kickassanime",
					Servers: []Server{{
						Server:  "main",
						Headers: map[string]string{"Referer": "https://cdn.example/player"},
						Streams: []Stream{
							{URL: "https://cdn.example/ignore.flv", Format: "flv"},
							{URL: "https://cdn.example/best.m3u8", Format: "hls"},
						},
					}},
				},
			},
		},
	}}
	options := Sources(play, false)
	if len(options) != 3 {
		t.Fatalf("options = %d: %+v", len(options), options)
	}
	if options[0].Provider != "kickassanime" || options[0].URL != "https://cdn.example/best.m3u8" {
		t.Fatalf("first option = %+v", options[0])
	}
	if options[0].Headers["Origin"] != "https://cdn.example" {
		t.Fatalf("Origin = %q", options[0].Headers["Origin"])
	}
	if options[2].Track != "DUB" {
		t.Fatalf("dub option = %+v", options[2])
	}
}

func TestSourcesDropFlvOnlyServers(t *testing.T) {
	play := PlayEnvelope{Tracks: []Track{{Track: "sub", Providers: []Provider{{
		Provider: "kickassanime",
		Servers:  []Server{{Server: "s", Streams: []Stream{{URL: "x.flv", Format: "flv"}}}},
	}}}}}
	if len(Sources(play, false)) != 0 {
		t.Fatal("a flv-only server should produce no option")
	}
}

func TestUsableSubtitlesKeepsVTT(t *testing.T) {
	tracks := usableSubtitles([]Subtitle{
		{File: "https://x/a.vtt"},
		{File: "https://x/b.ass"},
		{File: "https://x/c.vtt?token=1"},
	})
	if len(tracks) != 2 {
		t.Fatalf("tracks = %+v", tracks)
	}
	if !strings.HasPrefix(tracks[1].File, "https://x/c.vtt") {
		t.Fatalf("query VTT dropped: %+v", tracks)
	}
}

func TestPlayDecodesEnvelope(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/anime/a1/episodes/3/play" {
			t.Errorf("path = %s", r.URL.Path)
		}
		_, _ = w.Write(encodePayload(t, map[string]any{
			"episode_number": 3,
			"tracks": []map[string]any{{
				"track": "ssub",
				"providers": []map[string]any{{
					"provider": "kickassanime",
					"subtitles": []map[string]any{{
						"language": "en", "label": "English", "file": "https://cdn/x.vtt", "format": "vtt",
					}},
					"servers": []map[string]any{{
						"server":  "main",
						"headers": map[string]string{"Referer": "https://cdn.test/"},
						"streams": []map[string]any{{"url": "https://cdn.test/e3.m3u8", "format": "hls"}},
					}},
				}},
			}},
		}))
	}))
	defer server.Close()

	client := New(func() string { return server.URL })
	play, err := client.Play(context.Background(), "a1", 3)
	if err != nil {
		t.Fatal(err)
	}
	if play.EpisodeNumber != 3 {
		t.Fatalf("episode = %v", play.EpisodeNumber)
	}
	sources := Sources(play, false)
	if len(sources) != 1 || sources[0].Label != "SSUB · kickassanime · main" {
		t.Fatalf("sources = %+v", sources)
	}
	if len(sources[0].Subtitles) != 1 {
		t.Fatalf("subtitles = %+v", sources[0].Subtitles)
	}
}
