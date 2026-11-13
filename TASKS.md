# Build tracker — everything asked for, and its state

Last updated: 2026-11-13 (session 1)

## Product decisions (locked)

- Native Android app, Kotlin + **Jetpack Compose / Material 3**, **no WebView**, no JS engine.
- Backend: **Miruro API only** for v1. Other sources researched and saved for later ("selectable providers").
- Watch time tracked **locally** (DataStore-style JSON file), no account.
- Build with mise-managed toolchain; no Android Studio; no emulator (physical Pixel 8).
- Storage-conscious: nothing extra installed, nothing deleted.

## From you, item by item

| # | Request | State |
|---|---|---|
| 1 | Explore miruro.cx with the playwright skill | DONE — API fully reverse-engineered, screenshots in `.playwright-cli/` |
| 2 | "Can we make it an Android app?" — decide feasibility | DONE — yes, documented in `docs/specs/…-design.md` |
| 3 | Stop at a crystal-clear plan / build it | DONE — design + `docs/plans/…-plan.md`, then built |
| 4 | Native, super fast, 3 features: browse, watch, track watch time | DONE — all three implemented |
| 5 | Research the top 4 sites from everythingmoe | DONE — `recon/sources/{REANIME,ANIKOTO,ANIMEPAHE}.md` + `README.md` index |
| 6 | Keep that research as separate files, for later selectable providers | DONE — `StreamPicker`/`StreamOption` is the extension point |
| 7 | Low storage: don't install random stuff, don't delete anything | HELD — only mise + 3 SDK packages, no emulator |
| 8 | Use mise to manage versions | DONE — java 21.0.13, gradle 9.7.0, android-sdk 23.0 |
| 9 | Build it so it can be tested E2E before I use it | DONE — `ApiE2ETest` (5 tests, live chain incl. real segment) |
| 10 | Run it on my phone | DONE — installed on Pixel 8 (`cx.miruro.app`) |
| 11 | Fix "no playable stream" (played underneath the error) | DONE — elvis-operator bug in `PlayerViewModel` |
| 12 | Better player / subtitles missing | FIXED — root cause: building `HlsMediaSource` directly bypassed `DefaultMediaSourceFactory`, which is what merges side-loaded subtitle files. Now uses it + only real `.vtt` files |
| 13 | Way to switch streams / servers | DONE — Source sheet + automatic fallback on player error |
| 14 | Fullscreen | DONE — immersive + landscape toggle |
| 15 | Swipe left side = brightness, right side = volume | DONE — `PlayerGestures.kt` |
| 16 | Prettier overlay menus | DONE — Material 3 `ModalBottomSheet` instead of dropdowns |
| 17 | "Latest updates" as the home screen | DONE — default sort `-updated_at`, tab row (Latest/Popular/Top rated/A–Z) |
| 18 | Native/Google Material UI | DONE — M3 components only |
| 22 | Multiple seasons per anime | DONE — `order_release` + `id_in`, season chips on Detail |
| 23 | Show which sources have subs / put them on top | DONE — "N subtitle tracks" per source, subs-first ordering |
| 24 | Subtitle style customisation | DONE — size / colour / background / outline, persisted |
| 25 | Info button in player → show details page | DONE — `Info` pill swaps player for the detail screen |
| 26 | Configurable API base URL (site could be taken down) | DONE — Settings screen, headers derived from base URL, list reloads on change |
| 27 | Episode thumbnails | DONE — 112dp 16:9 thumb with episode-number badge |
| 28 | Season switcher: too many entries + selection not sticking | **IN PROGRESS** — filtered to TV seasons, short labels, always include current. Needs on-device proof |
| 29 | Settings button visible in the UI | FIXED — gear icon in the list top bar (the earlier patch had silently not applied) |
| 19 | Kotlin linter + formatter | ADDED — Spotless + ktlint 1.8.0 (`spotlessApply` / `spotlessCheck`) |
| 20 | Keep the code super clean | ONGOING — player split into 8 focused files |
| 21 | Keep track of requests (this file) | DONE |

## Open bugs / verification queue

1. **Season switcher selection** — must prove: tap "Season 2" → header changes to Season 2 AND the
   chip is highlighted. Currently the tap navigates but selection does not visibly stick.
2. **On-device subtitle rendering** — logic fixed and covered by tests, but never watched a cue on
   the device. Needs: play an episode → confirm text appears over the video.
2. Verify gesture HUD (brightness/volume) and fullscreen orientation restore on device.
3. Verify resume-after-kill and "Continue watching" ordering.
4. Release build (R8 + shrink) and check APK size.

## Files that matter

```
app/                              the Android app (Gradle, no wrapper; use mise gradle)
  app/src/main/java/cx/miruro/app/
    data/                         MiruroApi, Payload decode, Models, StreamPicker, ProgressStore
    ui/list/ ui/detail/ ui/player/  three screens
    ui/player/                    8 files: Screen, Controller, Chrome, Gestures, Surface, MediaSources, Fullscreen, TimeFormat
  app/src/test/java/…/ApiE2ETest.kt   live end-to-end suite
docs/specs/ docs/plans/           design + implementation plan
recon/                            API notes, probes, provider research, screenshots
```
