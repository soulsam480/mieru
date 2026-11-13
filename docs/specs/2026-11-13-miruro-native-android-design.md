# Miruro native Android client — design

Date: 2026-11-13
Status: proposed, awaiting approval
Author: implementation session

## 1. Intent

A **native** Android app (Kotlin + Compose, no WebView) that does three things, fast:

1. browse a list of anime (search + genre),
2. watch an episode,
3. remember where you stopped in each episode.

Everything else Miruro does (MAL/AniList sync, comments, schedule, notifications, downloads,
community widgets) is explicitly **out of scope**.

The goal is a 3-screen app that opens in well under a second, scrolls at 120 Hz, and starts video
in one tap. The web app is 1.3 MB of JS behind a Cloudflare edge; the native app is a thin client
over the same JSON API with none of that weight.

## 2. Why this is feasible (evidence, not assumption)

All of the following was verified live against miruro.cx on 2026-11-13.

### 2.1 The API is public JSON, just wrapped in obfuscation

`env2.js` exposes the whole config in the clear:

```json
{"VITE_ANILIST_CLIENT_ID":"18244","VITE_ANILIST_REDIRECT_URI":"https://www.miruro.cx/callback",
 "VITE_MAL_CLIENT_ID":"7ba6069f69816f7fc6dd8ec933ae8586",
 "VITE_PLAYER_ORIGIN":"https://strm.cx","VITE_PROXY_A":"https://s1.keeply.top/",
 "VITE_PROXY_B":"https://s2.keeply.top/","VITE_PROXY_OBF_KEY":"a54d389c18527d9fd3e7f0643e27edbe"}
```

API responses come back as `application/octet-stream`. Decoding is 3 lines, lifted from
`assets/Button-BTdICcI9.js`:

```js
var Le = new TextEncoder().encode(`miruro/catalog`);
async function Re(e){ let t=e.slice();
  for (let i=0;i<t.length;i++) t[i]^=Le[i%Le.length];            // XOR, repeating key
  return await new Response(new Blob([t]).stream().pipeThrough(new DecompressionStream(`gzip`))).text();
}
```

i.e. **gzip( XOR(body, b"miruro/catalog") ) → JSON**. Proven locally: decoded the
`/episodes/1/play` payload into a 13 KB JSON document.

### 2.2 The edge only wants to look like a browser

* HTTP/1.1 → `403` Cloudflare "Attention Required".
* HTTP/2 + `Sec-Fetch-Site: same-origin` + `Referer: https://www.miruro.cx/` → `200`.

No cookie, no token, no JS challenge, no signed request. (Verified: `curl --http1.1` 403,
`curl --http2` with those two headers 200, repeatedly.)

### 2.3 Playback is direct HLS — the browser player is not needed

`GET /api/v1/anime/{id}/episodes/{n}/play` returns providers → servers → streams, e.g.

```json
{"provider":"kickassanime",
 "servers":[{"server":"Vid","headers":{"Referer":"https://krussdomi.com/"},
   "streams":[{"url":"https://hls.krussdomi.com/manifest/6ac67e10402141b0cb58ac67/master.m3u8",
               "format":"hls","quality":null,"resolution":null}]}],
 "subtitles":[{"language":"eng","label":"English","file":"https://subst.krussdomi.com/.../....vtt","format":"vtt","default":true}],
 "thumbnails":{"vtt":"...preview-to2gl.vtt","sprite":null}}
```

Verified with plain `curl`:
* master playlist → `200`, 6 renditions up to 1920x1080, `CODECS="avc1.4D4C28,mp4a.40.2"`.
* variant playlist → `200`, 23 KB, `TARGETDURATION:6`.
* a video segment → `200` **only with** `Referer` *and* `Origin: https://krussdomi.com` on the
  request. Without them: `403`.
* subtitle `.vtt` → `200`, 20 KB.

So the media path is ordinary HTTPS + two headers. ExoPlayer handles it with
`setDefaultRequestProperties(...)`. `strm.cx` (the web player) is a UI shell we simply do not need.

### 2.4 Watch progress is client-side

The web app keeps progress in localStorage, not on a server:

* `miruro:watching` → `{schemaVersion, history:{<id>:[{episode_number,currentTime:"12:34",
  duration:"24:00",playbackPercentage:51.4,updated_at:"..."}]}, recentVisits:{...}}`
* `miruro:watching:playback:<id>:<ep>` → `{currentTime,duration,playbackPercentage,lastUpdated}`

That means "track watch time" is a local database in our app — no account, no login, no sync.

## 3. Goals / non-goals / anti-goals

**Goals**
* 3 screens: List → Detail (episodes) → Player.
* Search by title, browse by genre, sort by popularity/score/newest.
* HLS playback with quality selection, subtitles on/off, resume from last position.
* Per-episode progress persisted locally; "Continue watching" row on the list screen.
* Cold start < 1 s; APK < 12 MB; no WebView anywhere.

**Non-goals**
* Accounts, AniList/MAL sync, ratings, notifications, downloads, comments, airing schedule,
  PiP-on-lock-screen niceties beyond a media session and a keep-screen-on flag.

**Anti-goals (things that would make this a failure)**
* Any WebView, embedded browser, or JS engine in the render path.
* A server we have to run ourselves.
* Depending on `strm.cx` or any embed iframe.
* A build that needs Android Studio.

## 4. Constraints

* **No JDK, no Android Studio, no Android SDK on this machine** (verified: `java -version` →
  "Unable to locate a Java Runtime"; `/Applications` has no Android Studio). All three are
  installed from the command line, **managed by mise** (`java`, `gradle`, `kotlin`, `android-sdk`
  plugins all resolve — verified). `./gradlew assembleDebug` is the whole build.
* **Storage is tight.** Footprint budget, and nothing else gets pulled: JDK ~180 MB, Android
  cmdline-tools ~130 MB, `platforms;android-37` ~90 MB, `build-tools` ~60 MB, Gradle cache
  ~400 MB. **No emulator / no system image** (~2 GB saved) — test on a physical phone over `adb`.
  Nothing on this machine is deleted or upgraded; mise installs are additive.
* **HTTP/2 is mandatory** for miruro.cx. OkHttp does HTTP/2 by default, so this constrains library
  choice, not design: do not use a client that is HTTP/1.1-only.
* **No JS challenge solving.** Cloudflare here is header-based, not a Turnstile wall. If miruro
  ever switches to a JS challenge, a native client cannot pass it — that is the one architectural
  risk that would force a relay (see §10).
* The API validates its own query shapes. Only these are accepted (else
  `400 Unsupported catalog request`): `limit` ∈ {12, 15}; catalog sort ∈ {-popularity, popularity,
  -score, score, -updated_at, updated_at, title, -title}; `format` single-valued only;
  `q` requires `limit=15`.

## 5. Approaches considered

### A. Miruro API only (chosen)
One backend, the whole catalogue, all providers Miruro aggregates. Three screens fall out of the
API as it is. Zero infrastructure.

*Cost*: depends on one host's edge rules. *Risk*: if Miruro blocks us we have nowhere to go —
mitigated by the provider/server/stream abstraction already present in the play response.

### B. Miruro + independent fallback sources
Add anikoto / animepahe / re:anime as secondary resolvers behind the same interface, so a dead
provider is invisible to the user. Real reliability, real cost: each source needs its own scraper,
its own decryption/unpacking, its own breakage schedule. This is the classic maintenance sink.

### C. Own aggregator service
Run a relay that talks to all sources and normalises them, app talks only to the relay. Solves
Cloudflare permanently and makes sources swappable server-side. But it is a server to deploy and
babysit — explicitly not wanted ("simple, native, fast").

**Decision: A now, structured so B can be added later without touching UI code.** The play
response is already `provider → server → stream`; the app models that hierarchy from day one and
picks the first playable stream. Adding a second source means adding one resolver class, not a
refactor. We do not build C.

## 6. Architecture

Single module, single activity, 3 destinations.

```
data/    MiruroApi (OkHttp)  ·  MiruroPayload (XOR+gzip)  ·  DTOs  ·  ProgressStore (DataStore)
domain/  Anime, Episode, StreamSource, PlaybackProgress
ui/      list/ ListViewModel + ListScreen
         detail/ DetailViewModel + DetailScreen
         player/ PlayerScreen + PlayerViewModel (Media3 ExoPlayer)
di/      AppContainer (manual DI, no Hilt)
```

* **Networking**: OkHttp + kotlinx.serialization. One `Interceptor` adds the three required headers;
  one `decode` function applies XOR+gzip. The same `OkHttpClient` (and `Call.Factory`) backs Coil
  and Media3, so there is exactly one HTTP stack and one connection pool.
* **Player**: `media3-exoplayer-hls` with `DefaultHttpDataSource.Factory` carrying
  `Referer` + `Origin` per resolved server, `setAllowCrossProtocolRedirects(true)`.
* **Progress**: DataStore (single JSON blob, throttled) — a few hundred `(animeId, episode)` →
  `{positionMs, durationMs, updatedAt}` rows. No Room, no KSP, no migrations.
* **DI**: manual `AppContainer` (a small app; Hilt is a plugin and a build-time cost for nothing).
* **Images**: Coil 3 via the shared OkHttp client.

Pinned versions (each verified against Google Maven / Maven Central / Gradle today):

| | |
|---|---|
| JDK | Temurin 21.0.13 (mise `java@21.0.13+9.0.LTS`) |
| Gradle wrapper | 9.7.0 (mise `gradle@9.7.0`) |
| AGP | 9.3.3 |
| Kotlin | 2.4.21 (mise `kotlin@2.4.21`) |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| Media3 | 1.11.1 |

## 7. Screens

1. **List** — one search field, genre chips, a 2-column cover grid (title, format, year, score),
   and a "Continue watching" row at the top when progress exists. Infinite scroll via `cursor`.
2. **Detail** — banner, title, score/status chips, synopsis, "Watch" button, episode list
   (number + title + aired date + a progress bar if started).
3. **Player** — full-screen video, tap to toggle chrome, episode prev/next, quality menu from the
   master playlist, subtitle track menu, resume toast.

Reference screenshots from the web app are in `.playwright-cli/{home,list,info}.png`.

## 8. Data contract (what the app must implement)

Base `https://www.miruro.cx`. Every request needs:
`Http-2`, `Sec-Fetch-Site: same-origin`, `Referer: https://www.miruro.cx/`, browser `User-Agent`.

| # | Request | Returns |
|---|---|---|
| 1 | `GET /api/v1/anime?limit=15&sort=-popularity[&q=][&genre_all=][&tag_all=][&status=][&format=][&season=&season_year=][&country_of_origin=][&cursor=]` | `{data:[Anime], next_cursor, has_more}` |
| 2 | `GET /api/v1/anime/{id}/show` | `{data:[Anime], order_release:[], order_story:[]}` |
| 3 | `GET /api/v1/anime/{id}/episodes?limit=10000` | `{data:[{episode_number, title, thumbnail_url, duration_seconds, aired_on, skip_times}], has_more}` |
| 4 | `GET /api/v1/anime/{id}/episodes/{n}/play` | `{tracks:[{track:"ssub", providers:[{provider, subtitles[], thumbnails, servers:[{server, headers:{Referer}, streams:[{url, format:"hls"}]}]}]}]}` |

`Anime` fields used by the UI: `id` (opaque 32-char, stable), `title.{romaji,english,native}`,
`cover_url`, `banner_url`, `format`, `status`, `season_year`, `average_score`,
`episode_counts.{sub,dub}`, `description`, `next_airing`.

18 genre values come from the app's own enum: Action, Adventure, Comedy, Drama, Ecchi, Fantasy,
Hentai, Horror, Mahou Shoujo, Mecha, Music, Mystery, Psychological, Romance, Sci-Fi, Slice of Life,
Sports, Supernatural, Thriller.

Stream selection: first `server.streams` entry whose `format == "hls"` (fall back to `mp4`);
request headers = `server.headers` + `Origin` = scheme+host of that `Referer`; if no direct stream,
skip the server (never use `embed`).

A working reference client is three commands in a shell: `curl --http2` with the two headers
above, strip the XOR key, gunzip, and you have JSON.

## 9. Milestones

| | | done when |
|---|---|---|
| M0 | toolchain | `./gradlew assembleDebug` produces an APK on this Mac |
| M1 | skeleton | app opens to an empty Compose screen on a device/emulator |
| M2 | list | search + genre + infinite scroll, covers loading |
| M3 | detail | episodes listed, tap-through to player |
| M4 | watch | HLS plays with correct headers, quality + subtitles |
| M5 | progress | resume + "Continue watching", survives process death |
| M6 | polish | cold start < 1 s, APK < 12 MB, no jank on scroll |

## 10. Risks

1. **Miruro starts requiring a JS challenge.** Would break every non-browser client. Mitigation:
   the resolver interface; worst case a Cloudflare Worker relay (option C) for the API only.
2. **Provider CDNs rotate headers/hosts.** The `headers` object comes from the API, so this is
   data, not code — but a provider can also start requiring `Range`/`Cookie`. Mitigation: a
   custom `HttpDataSource` that we can extend with per-host rules; provider reordering in the app.
3. **`403` from the segment CDN if `Origin` is missing.** Already known and handled; this is the
   single most likely "it plays in browser, not in app" bug.
4. **Version drift in the toolchain** (AGP 9.x is new). Mitigation: the pins above are verified to
   exist; if AGP 9.3.3 misbehaves, drop to 9.4.1 / 9.5.1 and Gradle 9.9.1.
5. **Legal.** This is a private client for personal use against a third-party stream site; nothing
   is redistributed. Worth stating out loud, not worth designing around.

## 11. What is left to decide (for you)

Only one thing actually branches the build: **do we ship A alone, or A + one fallback source (B)?**
The plan below is written for A, because it is proven end to end today and gets you a working app
in ~6 tasks. B is additive and can be dropped in later without UI changes.
