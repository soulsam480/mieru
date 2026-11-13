# Miruro — native Android client

Kotlin + Jetpack Compose (Material 3) + Media3/ExoPlayer. **No WebView, no JS engine.**
Three screens: browse → episodes → watch. Watch progress is stored locally.

```
app/src/main/java/cx/miruro/app/
  data/    MiruroApi · Payload (XOR+gzip decode) · Models · StreamPicker · ProgressStore
  ui/      list/ · detail/ · player/ · Theme.kt · Nav.kt
  app/src/test/java/cx/miruro/app/ApiE2ETest.kt     <- end-to-end data-path tests
```

## Build

Everything is managed by mise (`java`, `gradle`, `android-sdk`). No Android Studio.

```bash
export JAVA_HOME="$(mise where java)"
export ANDROID_HOME="$(mise where android-sdk)"

gradle --console=plain :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Test (no device needed)

The E2E suite hits the live API and proves the whole chain: decode → catalog → search → pagination
→ show → episodes → `/play` → `StreamPicker` → fetch master playlist → fetch variant → fetch a real
video segment → fetch a subtitle.

```bash
export JAVA_HOME="$(mise where java)"
export ANDROID_HOME="$(mise where android-sdk)"

gradle --console=plain :app:testDebugUnitTest
# report: app/build/reports/tests/testDebugUnitTest/index.html
```

`PayloadTest` decodes a checked-in real response (`src/test/resources/play.bin`), so the decoder is
covered even with no network.

## Install (needs a phone)

```bash
adb devices                       # must list your phone (USB debugging on)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cx.miruro.app/.MainActivity
```

Manual smoke test:

1. List shows covers; type "dandadan" → results; tap a genre chip → filtered.
2. Tap a show → episodes listed with a progress bar on the one you watched.
3. Tap "Watch EP n" → video plays within a few seconds.
4. Back out, reopen the app → the show is in **Continue watching** at the same timestamp.

If playback fails with 403 on segments, the `Origin` header derived in `StreamPicker` is the first
thing to check — the CDN rejects requests that carry `Referer` alone.

## Where the API contract lives

`docs/specs/2026-11-13-miruro-native-android-design.md` (why) and
`docs/plans/2026-11-13-miruro-native-android-plan.md` (how, task by task). The full reverse
engineered contract is in `recon/API-NOTES.md`, and the alternative backends that were evaluated
for later use are in `recon/sources/`.
