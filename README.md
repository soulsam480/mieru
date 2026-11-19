# Mieru — a native Android client

> **This project hosts no content.** It is a client that talks to third-party endpoints you configure.
> No media, no catalogue, no streams are bundled, mirrored, or served from here — nothing is
> hosted, re-uploaded, or proxied by this repository or its releases. Point it at whatever server
> you are entitled to use; the base URLs are settings.

Both endpoint hosts are user-configurable defaults, not services this project runs. The app bundles
no media, no credentials and no keys. Subtitle tracks come from OpenSubtitles' public Stremio addon.
You are responsible for using sources you are entitled to use.

A Kotlin, Jetpack Compose (Material 3), Media3/ExoPlayer Android client with three sections:
**Anime** from a Miruro-compatible API, **TV** and **Movies** from a 1shows-style catalogue. All
three play through one shared ExoPlayer screen.

Playback is always ExoPlayer; no screen renders web content. The app does contain a WebView, but it
is never visible: to turn a mirror's embed page into a stream it loads that page in a two-pixel,
muted, offscreen view, captures the playlist URL together with the headers and cookies the request
used, then destroys the view. What you watch is an ExoPlayer stream, never the embed page.

Each section follows the same shape: browse → detail with seasons and episodes → watch, plus local
watch progress.

<p align="left">
  <img alt="version" src="https://img.shields.io/badge/version-1.3.4-7C5CFF">
  <img alt="licence" src="https://img.shields.io/badge/licence-AGPL--3.0-blue">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-informational">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.4.21-informational">
  <img alt="license" src="https://img.shields.io/badge/use-personal-lightgrey">
</p>

## Features

**Browse**
- *Anime* — *Latest episodes* feed of subbed airings from the last week, newest first; genre filters
  are hidden there because it is a feed, not a catalogue query. Also *Popular*, *Top rated*,
  *A–Z*, genre chips, title search, cursor pagination
- *TV* and *Movies* — trending catalogue from the configured 1shows site, with title search in
  each section
- Cards show covers, format, year, episode count, score; the Latest feed also shows
  `EP 24 · 2h ago`

**Detail**
- Banner, poster, metadata, synopsis
- Season switcher for anime franchises and TV shows (short labels, keeps its selection); a movie
  goes straight to playback
- Episode list with thumbnails, air dates, and a progress bar per episode
- Tap an episode row to play that episode
- Unreleased episodes are locked and show their air date

**Player** (one screen for anime, TV and movies)
- HLS and mp4 mirrors through ExoPlayer, automatic quality
- `Source` sheet lists every mirror that resolved a playlist; the remaining mirrors are resolved in
  the background and appended as they succeed, and playback falls back automatically when one dies
- Subtitles: the embed's own tracks, plus `.srt` tracks side-loaded from OpenSubtitles, picked by
  language; size / colour / background / outline styling
- Sub/Dub preference in Settings — the source picker then starts on that audio track
- Next/previous episode stay inside the season you started from, with an up-next countdown
- Intro/outro skip pill where the provider marked the window, optionally automatic
- Centre play/pause, double-tap left/right to seek ±10 s, hold for speed boost
- Drag on the left third for brightness, right third for volume
- Fullscreen (immersive + landscape), prev/next episode, `Info` jumps to the show page

**Progress**
- Per-episode position, keyed by media id, season and episode, saved locally, survives process death
- A continue-watching row in each section, newest first; tapping it opens the right season and
  resumes from the saved position
- Finished episodes drop out of the row

**Settings**
- `Sources` — list, add, test, remove and reset Miruro-compatible base URLs
- `1shows (TV & Movies)` — the site URL, with reset; the `db.` trending host is derived from it
- `Playback` — Sub/Dub preference, auto-skip intro and outro
- `Updates` — version check and install

**System integration**
- Media controls in the notification shade / lock screen (MediaSession)
- Pauses when the app goes to the background; brightness is handed back to the system on exit

**Updates**
- Self-updating from GitHub releases: a daily check, a banner on the Anime screen, and
  “Download & install” in Settings
- No account needed while this repository is public. If it is ever private, paste a read-only
  GitHub token in Settings and the updater uses it.

## Install

Grab `app-release.apk` from the [latest release](../../releases/latest):

```bash
adb install -r app-release.apk
```

Or copy the APK to the phone and tap it (allow “install unknown apps”). `minSdk 26`.

## Build from source

No Android Studio required. Toolchain is managed with [mise](https://mise.jdx.dev):

```bash
mise use -g java@21.0.13+9.0.LTS gradle@9.7.0 android-sdk@latest
export ANDROID_HOME="$(mise where android-sdk)"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

export JAVA_HOME="$(mise where java)"
cd app && gradle :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with a local keystore; `app/keystore.properties` (gitignored) holds
the credentials:

```bash
gradle :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Code style is enforced by Spotless + ktlint: `gradle :app:spotlessApply` / `:app:spotlessCheck`.

## Test

The suite runs on the JVM — no device or emulator; the end-to-end cases call the live API:

```bash
cd app && gradle :app:testDebugUnitTest
```

What it proves:

| Test | Covers |
|---|---|
| `decodes_a_real_recorded_play_response` | the payload decoder, using a checked-in real response |
| `catalog_and_search` | catalogue + search endpoints, decoding, relevance |
| `genre_browse` | genre filter |
| `pagination_uses_cursor` | cursor pagination, no duplicate pages |
| `seasons_are_discoverable` | `order_release` + `id_in` hydration |
| `full_watch_chain_resolves_and_plays` | `/play` → source pick → master playlist → variant → a **real video segment** (200/206) |
| `subtitle_files_are_real_vtt` | subtitle URLs actually return `WEBVTT` |
| `OneShowsMappingTest` | search, detail, season and trending payloads map to the app's cards |
| `OneShowsStoreTest` | a mirror moves both hosts, persists, and junk URLs are refused |
| `MediaProgressTest` | positions per season, continue-watching per section, finished episodes drop out |
| `EpisodeRulesTest` | specials (20008+) are dropped; unreleased episodes are flagged |
| `EpisodeNavigationTest` | next/previous never leave the season on screen |
| `TrackPreferenceTest` | Sub/Dub preference decides which track is offered first |
| `LatestFeedTest` | the latest feed contains no raw-only airings |
| `VersionRulesTest` | dotted version comparison for the updater |

Providers vary between calls, so the chain test tries sources the way the app does and only
requires that one of them plays.

## How it works

```
data/    MiruroApi · EndpointStore · Payload · Models · StreamPicker · FranchiseStore
         oneshows/OneShowsApi · OneShowsStore
         embed/EmbedResolver · PlaybackRequest · PlaybackHandoff · ResolvedPlaybackCache
         subtitles/OpenSubtitles · ProgressStore · SubtitleStyle · Http
ui/      list/ · detail/ · media/ · settings/ · player/  (Kotlin + Compose, Material 3)
```

A few non-obvious things the endpoints force:

| Constraint | Why it matters |
|---|---|
| **HTTP/2 only** | HTTP/1.1 gets a `403` from the Miruro edge. OkHttp does HTTP/2 by default. |
| **`Sec-Fetch-Site: same-origin` + matching `Referer`** | Without this pair every Miruro request is `403`. Both are derived from the configured base URL, so mirrors work. |
| **Responses are `gzip(XOR(body, "miruro/catalog"))`** | `Payload.kt` un-XORs with the 14-byte key then gunzips. |
| **Media needs `Referer` *and* `Origin`** | Segments return `403` with only a `Referer`; `Origin` is derived from the `Referer` host. |
| **`/show` returns the franchise root first** | The screen picks the entry whose id matches the request, otherwise a season shows the wrong title. |
| **Each season reports its own `order_release`** | `FranchiseStore` keeps one group per franchise so the season row stays stable. |
| **An embed page signs its own playlist** | `EmbedResolver` loads it in an offscreen 2-pixel WebView, watches the traffic, and captures the first `.m3u8` / `.mp4` request with its `Referer`, `Origin`, `User-Agent` and cookies. The view is destroyed as soon as the URL is in hand. |
| **The 1shows trending feed is ids only** | Each id needs a detail call to become a card; a dead id is dropped instead of failing the row. |
| **Subtitle providers key on IMDb ids** | `external_ids` from the 1shows catalogue supplies the `tt…` id, and OpenSubtitles' addon answers with `.srt` links. |
| **Side-loaded subtitles need `DefaultMediaSourceFactory`** | Building `HlsMediaSource` directly silently drops `MediaItem.subtitleConfigurations`. |

## Configuration

Both hosts are settings, so a mirror can be swapped in without rebuilding. Progress is local and
unaffected by either change.

| Setting | Default | Used for |
|---|---|---|
| Miruro-compatible base URL (a list) | `https://www.miruro.cx` | Anime catalogue, search, `/play` and media requests. `Referer`, `Origin` and `Sec-Fetch-Site` are derived from it, so each entry works as its own mirror. `Sources` can test, add and remove entries. |
| 1shows site URL | `https://www.1shows.org` | TV and movie catalogue, search, seasons, and `external_ids` for subtitles. The trending host is `db.` in front of the configured host. |

## Project layout

```
app/                     Gradle project (single module)
  app/src/main/java/cx/miruro/app/
    data/                anime API, 1shows catalogue, embed resolver, subtitles, stores
    ui/                  list, detail, media, player, settings
  app/src/test/          live end-to-end suite plus pure JVM unit tests
docs/specs/              design document
docs/plans/              implementation plan, task by task
skills/                  agent skill: reverse-engineering streaming APIs
TASKS.md                 feature + bug tracker with verification evidence
```

## Caveats

- This is a personal client. It ships no content, hosts nothing, and redistributes nothing; it
  talks to third-party endpoints you configure.
- Both backends are third-party sites: they can change, block, or vanish. Both hosts are
  configurable, and provider ordering is data-driven (`provider → server → stream`) so a dead
  source is skipped rather than fatal.
- The anime path is plain HTTP: if that edge ever moves to a JavaScript challenge, no native client
  can pass it — that would need a relay. The embed path for TV and movies is different, because the
  mirror's own page runs JavaScript inside the offscreen WebView. This is documented in
  `skills/reverse-engineering-streaming-apis/`.

## Licence and credits

**AGPL-3.0-or-later** — see [`LICENSE`](LICENSE).

Features and design decisions were inspired by **[Mangayomi](https://github.com/kodjodevf/mangayomi)**
(Apache-2.0, by the Mangayomi contributors), in particular its interchangeable-source model, its
intro/outro skipping, and its player affordances (speed, up-next, hold-to-boost). Mieru contains no
Mangayomi source code, but the ideas came from there and are credited in [`NOTICE`](NOTICE).
