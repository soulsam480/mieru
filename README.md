# Miruro — native Android client

A small, fast Android app for a Miruro-compatible anime API. Kotlin, Jetpack Compose
(Material 3), Media3/ExoPlayer. No WebView, no embedded browser, no JS engine.

Three screens: **browse → episodes → watch**, plus local watch progress.

<p align="left">
  <img alt="version" src="https://img.shields.io/badge/version-1.1.0-7C5CFF">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-informational">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.4.21-informational">
  <img alt="license" src="https://img.shields.io/badge/use-personal-lightgrey">
</p>

## Features

**Browse**
- *Latest episodes* — a real airing feed (what came out this week), not a sorted catalogue
- *Popular*, *Top rated*, *A–Z*, genre chips, title search, cursor pagination
- Cards show covers, format, year, episode count, score; the Latest feed also shows
  `EP 24 · 2h ago`

**Show page**
- Banner, poster, metadata, synopsis
- Season switcher (TV seasons only, short labels, keeps its selection)
- Episode list with thumbnails, air dates, and a progress bar per episode

**Player**
- HLS playback with automatic quality
- Subtitles: track picker, plus size / colour / background / outline styling
- Server switcher (`Source`) with automatic fallback when a provider dies
- Centre play/pause, double-tap left/right to seek ±10 s
- Drag on the left third for brightness, right third for volume
- Fullscreen (immersive + landscape), prev/next episode, `Info` jumps to the show page

**Progress**
- Per-episode position, saved locally, survives process death
- “Continue watching” row on the home screen

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

The suite runs on the JVM against the live API — no device or emulator:

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
| `EpisodeRulesTest` | specials (20008+) are dropped; unreleased episodes are flagged |
| `VersionRulesTest` | dotted version comparison for the updater |

Providers vary between calls, so the chain test tries sources the way the app does and only
requires that one of them plays.

## How it works

```
data/    MiruroApi · EndpointStore · Payload · Models · StreamPicker · FranchiseStore
         ProgressStore · SubtitleStyle · Http
ui/      list/ · detail/ · settings/ · player/  (Kotlin + Compose, Material 3)
```

A few non-obvious things the API forces:

| Constraint | Why it matters |
|---|---|
| **HTTP/2 only** | HTTP/1.1 gets a `403` from the edge. OkHttp does HTTP/2 by default. |
| **`Sec-Fetch-Site: same-origin` + matching `Referer`** | Without this pair every request is `403`. Both are derived from the configured base URL, so mirrors work. |
| **Responses are `gzip(XOR(body, "miruro/catalog"))`** | `Payload.kt` un-XORs with the 14-byte key then gunzips. |
| **Media needs `Referer` *and* `Origin`** | Segments return `403` with only a `Referer`; `Origin` is derived from the `Referer` host. |
| **`/show` returns the franchise root first** | The screen picks the entry whose id matches the request, otherwise a season shows the wrong title. |
| **Each season reports its own `order_release`** | `FranchiseStore` keeps one group per franchise so the season row stays stable. |
| **Side-loaded subtitles need `DefaultMediaSourceFactory`** | Building `HlsMediaSource` directly silently drops `MediaItem.subtitleConfigurations`. |

The API base URL is a **setting**, not a constant: if the host moves or disappears you can point
the app at a mirror without rebuilding. Progress is local and unaffected by that change.

## Project layout

```
app/                     Gradle project (single module)
  app/src/main/java/cx/miruro/app/
    data/                API, payload decoding, models, stores
    ui/                  list, detail, player, settings
  app/src/test/          live end-to-end suite
docs/specs/              design document
docs/plans/              implementation plan, task by task
skills/                  agent skill: reverse-engineering streaming APIs
TASKS.md                 feature + bug tracker with verification evidence
```

## Caveats

- This is a personal client. It ships no content, hosts nothing, and redistributes nothing; it
  talks to a third-party API you configure.
- The backend is a third-party site: it can change, block, or vanish. The API host is
  configurable and provider ordering is data-driven (`provider → server → stream`) so a dead
  source is skipped rather than fatal.
- If the edge ever moves to a JavaScript challenge, no native client can pass it — that would
  need a relay. This is documented in `skills/reverse-engineering-streaming-apis/`.
