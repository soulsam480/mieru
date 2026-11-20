package oneshows

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func newTestClient(t *testing.T, handler http.Handler) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return &Client{
		site:     func() string { return server.URL },
		database: func() string { return server.URL },
		http:     server.Client(),
	}
}

func TestTrendingHydratesEachIDAndDropsDeadOnes(t *testing.T) {
	client := newTestClient(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/management/trending/tv":
			_, _ = w.Write([]byte(`{"success":true,"tmdbIds":["1","2","1"]}`))
		case "/api/tv/1":
			_, _ = w.Write([]byte(`{"name":"The Mentalist","first_air_date":"2008-09-23","vote_average":8.4,"poster_path":"/p1.jpg"}`))
		case "/api/tv/2":
			http.Error(w, "gone", http.StatusInternalServerError)
		default:
			http.NotFound(w, r)
		}
	}))

	cards, err := client.Trending(context.Background(), KindTV)
	if err != nil {
		t.Fatal(err)
	}
	if len(cards) != 1 {
		t.Fatalf("cards = %+v", cards)
	}
	if cards[0].Title != "The Mentalist" || cards[0].Year != 2008 {
		t.Fatalf("card = %+v", cards[0])
	}
	if !strings.HasSuffix(cards[0].Poster, "/w500/p1.jpg") {
		t.Fatalf("poster = %q", cards[0].Poster)
	}
}

func TestSearchKeepsOnlyTVAndMovies(t *testing.T) {
	client := newTestClient(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("query") != "mental" {
			t.Errorf("query = %s", r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"results":[
			{"id":5920,"media_type":"tv","name":"The Mentalist","first_air_date":"2008-09-23"},
			{"id":9,"media_type":"movie","title":"A Film","release_date":"2020-01-01"},
			{"id":3,"media_type":"person","name":"Someone"}
		]}`))
	}))

	cards, err := client.Search(context.Background(), "mental")
	if err != nil {
		t.Fatal(err)
	}
	if len(cards) != 2 {
		t.Fatalf("cards = %+v", cards)
	}
	if cards[0].Kind != KindTV || cards[0].ID != "5920" {
		t.Fatalf("tv card = %+v", cards[0])
	}
	if cards[1].Kind != KindMovie || cards[1].Title != "A Film" {
		t.Fatalf("movie card = %+v", cards[1])
	}
}

func TestDetailAndSeasonMapping(t *testing.T) {
	client := newTestClient(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/tv/1":
			_, _ = w.Write([]byte(`{"name":"Show","overview":" An overview ","poster_path":"/p.jpg","backdrop_path":"/b.jpg","first_air_date":"2008-09-23","vote_average":8.4,"genres":[{"name":"Crime"}],"seasons":[{"season_number":1,"name":"Season 1","episode_count":2}]}`))
		case "/api/tv/1/season/1":
			_, _ = w.Write([]byte(`{"episodes":[{"episode_number":1,"name":"Pilot","air_date":"2008-09-23","runtime":45,"still_path":"/s.jpg"}]}`))
		case "/api/movie/9":
			_, _ = w.Write([]byte(`{"title":"Film","runtime":145,"release_date":"2026-01-01","genres":[{"name":"Action"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))

	detail, err := client.Detail(context.Background(), KindTV, "1")
	if err != nil {
		t.Fatal(err)
	}
	if detail.Title != "Show" || detail.Year != 2008 || len(detail.Seasons) != 1 {
		t.Fatalf("detail = %+v", detail)
	}
	if detail.Overview != "An overview" || detail.Genres[0] != "Crime" {
		t.Fatalf("detail text = %+v", detail)
	}

	episodes, err := client.Season(context.Background(), "1", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(episodes) != 1 || episodes[0].Title != "Pilot" || episodes[0].Runtime != 45 {
		t.Fatalf("episodes = %+v", episodes)
	}

	movie, err := client.Detail(context.Background(), KindMovie, "9")
	if err != nil {
		t.Fatal(err)
	}
	if movie.Runtime != 145 || movie.Kind != KindMovie {
		t.Fatalf("movie = %+v", movie)
	}
}

func TestIMDBIDRequiresTT(t *testing.T) {
	client := newTestClient(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"imdb_id":"tt1234567"}`))
	}))
	if got := client.IMDBID(context.Background(), KindTV, "1"); got != "tt1234567" {
		t.Fatalf("imdb = %q", got)
	}
}

func TestBuildMirrorsOrdersCaptchaLast(t *testing.T) {
	mirrors := buildMirrors([]providerDTO{
		{ID: "MAIN_1", Label: "Main 1", TV: "https://www.viduki.net/1/tv/{id}/{s}/{e}"},
		{ID: "MAIN_2", Label: "Main 2", TV: "https://vidlink.pro/tv/{id}/{s}/{e}"},
	}, KindTV, "5920", 1, 2)

	if len(mirrors) != 2 {
		t.Fatalf("mirrors = %+v", mirrors)
	}
	if mirrors[0].URL != "https://vidlink.pro/tv/5920/1/2" {
		t.Fatalf("first mirror = %+v", mirrors[0])
	}
	if !strings.Contains(mirrors[1].URL, "viduki.net") {
		t.Fatalf("captcha mirror should be last: %+v", mirrors[1])
	}
}
