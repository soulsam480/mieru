# Build tracker — everything asked for, and its state

Last updated: 2026-11-13 (final)

## Product decisions (locked)

- Native Android app, Kotlin + **Jetpack Compose / Material 3**, **no WebView**, no JS engine.
- Backend: **Miruro API**; the base URL is a user setting so a mirror can be swapped in.
- Other sources researched and stored for later ("selectable providers"); `StreamOption` is the seam.
- Watch time tracked **locally**, no account.
- Toolchain via mise; no Android Studio; no emulator (physical Pixel 8).
- Storage-conscious: nothing extra installed, nothing deleted.
- Code style is enforced: Spotless + ktlint (`spotlessApply` / `spotlessCheck`).

## Everything asked for

| # | Request | State |
|---|---|---|
| 1 | Explore miruro.cx with the playwright skill | DONE — API reverse-engineered end to end |
| 2 | Can it be an Android app? | DONE — yes; design + plan written |
| 3 | Native, fast, 3 features: browse / watch / track time | DONE — all three working on device |
| 4 | Research the top sites from everythingmoe | DONE — `recon/sources/{REANIME,ANIKOTO,ANIMEPAHE}.md` |
| 5 | Keep that research for later selectable providers | DONE — `StreamPicker`/`StreamOption` seam |
| 6 | Low storage: don't install junk, don't delete | HELD — mise + 3 SDK packages only |
| 7 | Use mise for versions | DONE — java 21.0.13, gradle 9.7.0, android-sdk 23.0 |
| 8 | Must be testable end to end before handover | DONE — 7/7 live tests, incl. real segment + real VTT |
| 9 | Run it on my phone | DONE — installed, `versionName=1.1` |
| 10 | Fix "no playable stream" (video played underneath) | DONE — elvis-operator bug |
| 11 | Better player / missing subtitles | DONE — see §Subtitle root cause |
| 12 | Switch streams / servers | DONE — Source sheet + automatic fallback on error |
| 13 | Fullscreen | DONE — `rotation=1` → `rotation=0` round-trip verified |
| 14 | Left swipe = brightness, right swipe = volume | DONE — `PlayerGestures.kt` |
| 15 | Prettier overlay menus | DONE — M3 `ModalBottomSheet` |
| 16 | "Latest episodes" as home | DONE — real airing feed (`/api/v1/schedule`), cards show "EP 24 · 2h ago" |
| 17 | Native/Google Material UI | DONE — M3 only |
| 18 | Multiple seasons per anime | DONE — proven: `loaded id=4ueoEc title=Attack on Titan Season 2 … selected=1` |
| 19 | Which sources have subs / subs on top | DONE — "N subtitle tracks" per row, sorted |
| 20 | Subtitle style customisation | DONE — size / colour / background / outline, persisted |
| 21 | Info button in player → detail page | DONE — `Info` pill, swaps player for the show page |
| 22 | Configurable API base URL | DONE — Settings screen; headers derived from it; list reloads |
| 23 | Episode thumbnails | DONE — 112dp 16:9 thumb + episode badge |
| 24 | Season chips: too many + selection not sticking | DONE — TV-only, short labels, in-place switch, auto-scroll |
| 25 | Settings button visible | DONE — gear in the list top bar |
| 26 | Centre play/pause, double-tap ±10s | DONE — centre button + double-tap zones with −10s/+10s feedback |
| 27 | Subtitles too low on screen | DONE — letterbox-aware offset + control-bar clearance |
| 28 | Kotlin linter + formatter | DONE — Spotless + ktlint 1.8.0 |
| 29 | Keep code clean | DONE — screens split by responsibility; largest file 506 → see §Layout |
| 30 | Keep a tracker of requests | DONE — this file |
| 31 | Sign the app ourselves | DONE — self-signed RSA-4096, v2 scheme verified |
| 32 | Initialise a git repo with a proper .gitignore | DONE — 53 files, no secrets, no `recon/`, no APKs |

## Bugs found and fixed along the way

- **Subtitles never rendered.** Two causes: building `HlsMediaSource` by hand bypasses
  `DefaultMediaSourceFactory`, which is what merges side-loaded subtitle files; and some providers
  advertise subtitle entries that are not subtitle files (icarus: `.jpeg` → 403 HTML). Fixed by
  routing through `DefaultMediaSourceFactory` and attaching only `.vtt`.
- **"No playable stream" while video played.** `stream?.let { null } ?: msg` always takes `msg`.
- **Season page showed the wrong title.** `/show` returns the franchise root first; now we pick the
  entry whose id matches the request.
- **Season chips lost their selection.** Each season reports a different `order_release`; a
  `FranchiseStore` keeps one group per franchise, and switching reloads the screen in place.
- **Searching inside "Latest" returned nothing.** Search/genre now always use the catalogue.
- **Tab switch felt hung.** Progress bar + dimming + scroll-to-top on query change.
- **Test reused the API client for media.** That client overwrites `Referer`; media fetches now use
  a plain client.

## Verification evidence

```
gradle :app:assembleDebug :app:testDebugUnitTest        -> BUILD SUCCESSFUL
PayloadTest   tests=1 failures=0
LiveApiTest   tests=6 failures=0        (catalog, search, genre, cursor, seasons, play chain, VTT)

gradle :app:assembleRelease
  app-release.apk  2.7 MB
  apksigner verify -> Verifies, v2 scheme true
  certificate DN: CN=Miruro Client, OU=Personal, O=Personal, L=Bangalore, ST=KA, C=IN
  SHA-256: 61c1680951caf068dcc2d2dee1467a6da7129d7afc7f356b4a4b726b4cd1d6b5

device (Pixel 8, cx.miruro.app 1.1):
  MiruroSeasons: loaded id=7oSZWu title=Attack on Titan episodes=39 seasons=6 selected=0
  MiruroSeasons: loaded id=4ueoEc title=Attack on Titan Season 2 episodes=12 seasons=6 selected=1
  MiruroPlayer: attaching 10 subtitle(s) from anikoto: en(English) …
  MiruroPlayer: text track loaded: en English
  screenshot: cue rendered "To help humanity achieve victory!"
  dumpsys window: Full -> rotation=1, Exit -> rotation=0
  resume after force-stop: Continue watching -> EP 3 at 0:43
```

## Layout (clean-code pass)

```
data/   MiruroApi · EndpointStore · Payload · Models · StreamPicker · FranchiseStore
        ProgressStore · SubtitleStyle (store) · Http
ui/list      ListScreen · ListViewModel · AnimeCard
ui/detail    DetailScreen · DetailViewModel · SeasonRow · EpisodeRow
ui/player    PlayerScreen · PlayerController · PlayerSurface · PlayerChrome · PlayerGestures
             Fullscreen · MediaSources · PlaybackDiagnostics · TimeFormat · PlayerViewModel
ui/settings  SettingsScreen
```

## Optional backlog (not requested, not blocking)

- AniList/MAL sync for progress (the web app has it; the API endpoints exist).
- Downloads / offline playback.
- Provider #2 (Anikoto is the cheapest to add — one AES step; see `recon/sources/ANIKOTO.md`).
- Landscape pillarbox-aware subtitle offset (currently based on letterbox height only).
