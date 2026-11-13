# Implementation plan — Miruro native Android client

Date: 2026-11-13
Companion to: `docs/specs/2026-11-13-miruro-native-android-design.md`
Audience: an implementer (human or agent) with no prior context. Every task is self-contained and
ends with a verification step you must actually run.

## Ground rules

* **Do not install or upgrade anything outside `mise`.** This machine has limited free storage,
  a working mise setup, and a user who does not want surprise downloads.
* **Delete nothing.** No `rm -rf`, no cache purges, no "clean up" of the user's files.
* **No emulator, no system image.** Test on a physical Android phone with `adb`. This alone saves
  ~2 GB.
* Build with the Gradle wrapper: `./gradlew assembleDebug`. Android Studio is not needed.
* All shell work happens in `<project>/app`.
* Before writing Kotlin, prove the API is reachable from your machine with a two-line curl that
  sends `Sec-Fetch-Site: same-origin` plus a `Referer`, and confirm the body starts with `0x1f 0x8b`
  after un-XORing.

---

## T0 — Toolchain (mise, ~900 MB total, additive)

```bash
cd <project>
mise use -g java@21.0.13+9.0.LTS
mise use -g gradle@9.7.0
mise use -g kotlin@2.4.21
mise use -g android-sdk@latest          # provides sdkmanager

export ANDROID_HOME="$(mise where android-sdk)"
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37" "build-tools;36.0.0"
```

Only those three SDK packages. Deliberately **not** installed: `emulator`,
`system-images;*`, `ndk`, `cmake`, `sources;android-*`.

Verification (all four must print something):

```bash
java -version          # 21.0.13
gradle --version       # 9.7.0
kotlin -version        # 2.4.21
sdkmanager --list_installed | rg 'platforms;android-37|build-tools;36'
```

If `android-sdk` via mise misbehaves, fallback (still small, nothing removed):
download `commandlinetools-mac_arm64-*.zip` (~130 MB) into `$HOME/Library/Android/sdk/cmdline-tools/latest`.

## T1 — Project skeleton

`app/` Gradle project, single module `:app`.

* `settings.gradle.kts` — `pluginManagement` repos = google(), mavenCentral(), gradlePluginPortal().
* `gradle/libs.versions.toml` — versions from the design doc's table.
* `app/build.gradle.kts` — `compileSdk 37`, `targetSdk 36`, `minSdk 26`, Compose enabled,
  `buildFeatures { compose = true }`, kotlinx-serialization plugin.
* Manifest: single `MainActivity`, `INTERNET`, `usesCleartextTraffic=false`,
  `configChanges` for rotation, `android:screenOrientation` free (player handles it), theme with
  edge-to-edge.
* `.gitignore`, `local.properties` with `sdk.dir`.

Dependencies (exactly these to start, nothing speculative):

```
androidx.activity:activity-compose
androidx.compose:compose-bom  (ui, material3, ui-tooling-preview)
androidx.lifecycle:lifecycle-viewmodel-compose
androidx.navigation:navigation-compose
com.squareup.okhttp3:okhttp
org.jetbrains.kotlinx:kotlinx-serialization-json
org.jetbrains.kotlinx:kotlinx-coroutines-android
io.coil-kt.coil3:coil-compose  + coil-network-okhttp
androidx.datastore:datastore-preferences
androidx.media3:media3-exoplayer, media3-exoplayer-hls, media3-ui, media3-session
```

Verification: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`;
`adb install -r` succeeds; app opens to "Hello" and does not crash.

## T2 — Network layer (the only tricky part, and it is small)

`data/MiruroPayload.kt`

```kotlin
private val KEY = "miruro/catalog".toByteArray(Charsets.UTF_8)

fun decode(raw: ByteArray): ByteArray {           // XOR then gunzip
    val x = ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }
    return GZIPInputStream(x.inputStream()).readBytes()
}
```

`data/MiruroApi.kt` — OkHttp client with one interceptor:

```
Sec-Fetch-Site: same-origin
Referer: https://www.miruro.cx/
Accept: */*
User-Agent: <browser UA string>       // any modern UA is fine
```

Then `suspend fun <T> get(path: String, serializer: KSerializer<T>): T` that:
1. checks `response.code == 200`, else throws `ApiException(code, body)`,
2. `decode(response.body.bytes())`, `json.decodeFromString`.

DTOs (kotlinx.serialization, `ignoreUnknownKeys = true`, nullable where the API sends null):

```kotlin
@Serializable data class CatalogPage(val data: List<Anime>, val next_cursor: String? = null, val has_more: Boolean = false)
@Serializable data class Anime(
  val id: String, val external_ids: Map<String, List<String>> = emptyMap(),
  val title: Titles, val format: String? = null, val status: String? = null,
  val season_year: Int? = null, val average_score: Int? = null,
  val cover_url: String? = null, val banner_url: String? = null, val background_url: String? = null,
  val description: String? = null, val episode_counts: EpisodeCounts = EpisodeCounts(),
  val next_airing: NextAiring? = null)
@Serializable data class Episode(val episode_number: Double, val title: String? = null,
  val thumbnail_url: String? = null, val duration_seconds: Int? = null,
  val aired_on: String? = null, val skip_times: List<SkipTime> = emptyList())
```

Endpoints exactly as in the design doc §8. Functions:

```kotlin
suspend fun catalog(limit: Int = 15, sort: String = "-popularity", q: String? = null,
                    genres: List<String> = emptyList(), tags: List<String> = emptyList(),
                    status: String? = null, format: String? = null, cursor: String? = null): CatalogPage
suspend fun show(id: String): ShowEnvelope
suspend fun episodes(id: String): EpisodesEnvelope
suspend fun play(id: String, episode: Int): PlayEnvelope
```

Verification (must be a real test, not a print):
`app/src/test/kotlin/.../MiruroApiTest.kt` — decode a checked-in `play.bin`
(a small binary captured once from the live API) and assert the JSON contains `"providers"` and a URL starting with
`https://`. Then one `@Ignore`d live test you can run by hand against the real host.

## T3 — List screen (feature 1)

* `ListViewModel`: `StateFlow<ListUiState>` with `items`, `query`, `genre`, `sort`, `isLoading`,
  `error`, `canLoadMore`. Debounce query 300 ms. `loadMore()` uses `next_cursor`.
* `ListScreen`: `LazyVerticalGrid(GridCells.Fixed(2))` of `CoverCard`
  (Coil async image, title 2 lines, `format · year · ★score`), a `SearchBar` at the top, genre
  chips in a `LazyRow`, and a "Continue watching" `LazyRow` when progress exists.
* Empty/error states. Skeleton shimmer optional.

Notes: `average_score` is int 0–100 — show as-is or /10 rounded; `episode_counts.sub` may be null
while airing; title prefers `english`, falls back to `romaji`.

Verification: `adb shell am start` → covers render, typing "dandadan" filters, scrolling loads a
second page (watch `adb logcat` for the second request), rotating does not reset the list.

## T4 — Detail screen (bridge to feature 2)

* `DetailViewModel`: loads `show(id)` and `episodes(id)` in parallel.
* `DetailScreen`: banner (`banner_url`), poster, title, chips (format/status/score/season),
  synopsis (3 lines + expand), primary "Watch" button (jumps to newest unwatched episode),
  `LazyColumn` of episodes: `EP 12 · Title`, aired date, thin progress bar when started,
  tap → player.
* `ProgressStore.get(animeId, ep)` drives the bars. Compute "newest unwatched" = first episode
  with `progress == null` or `< 0.9`.

Verification: open a show with 12 episodes → 12 rows; tap ep 3 → player opens with `ep=3`.

## T5 — Player (feature 2)

`PlayerViewModel`:

```kotlin
1. play(animeId, ep) -> PlayEnvelope
2. choose source:
   providers (in app-preferred order) -> servers -> streams
   pick first stream with format == "hls" (else "mp4"); skip servers that only have `embed`
3. build headers: server.headers (contains Referer)
                 + ("Origin" to scheme://host of Referer)      // required or the CDN 403s
4. build ExoPlayer:
   DefaultHttpDataSource.Factory()
     .setUserAgent(browserUa)
     .setDefaultRequestProperties(headers)
     .setAllowCrossProtocolRedirects(true)
   HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url))
5. subtitles: for each provider.subtitles -> MediaItem.SubtitleConfiguration(uri = file,
   mimeType = MimeTypes.TEXT_VTT, language, label, selectionFlags = default?)
6. resume: player.seekTo(ProgressStore.position(animeId, ep))
```

UI: `AndroidView` wrapping `PlayerView` (media3-ui), controller auto-hides, episode prev/next
buttons, quality menu from `player.currentTracks` (media3 exposes HLS renditions), subtitle menu.
Keep-screen-on while playing. Landscape works (activity `configChanges`).

**The `Origin` header is non-negotiable** — verified: without it every segment returns 403, with it
200. Derive it from the resolved `Referer` rather than hardcoding a host, because providers change.

Verification: play an episode → video renders; seek → resumes; audio track present; without
`Origin` the same code must visibly 403 (prove the fix matters).

## T6 — Watch-time tracking (feature 3)

`data/ProgressStore.kt` (DataStore Preferences, single JSON string):

```kotlin
@Serializable data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long)
// map key: "$animeId:$episodeNumber"
```

* Write at most once every 5 s while playing, plus on pause/stop/`ON_STOP` — never per frame.
* Mark complete at ≥ 92 % and then start the next episode from 0.
* `ListViewModel` reads the map to build "Continue watching" (sorted by `updatedAt`, top 10).
* Nothing leaves the device. No login, no AniList sync (web app stores this locally too — see
  design §2.4).

Verification: watch 30 s, kill the app (`adb shell am force-stop`), reopen → List shows the show in
"Continue watching" with the right episode; tapping it resumes within ~2 s of where you stopped.

## T7 — Polish

* Cold start: no work on the main thread in `Application`; the list's first page is fetched in the
  ViewModel.
* APK size: no `ui-tooling` in release, `isMinifyEnabled = true` with default rules, `resConfigs`
  limited to `en`.
* `./gradlew assembleRelease` with a debug keystore is fine for personal sideloading.
* Optional: `MediaSessionService` + notification if you want playback to survive leaving the app.
  Plain `ExoPlayer` in the Activity is enough for v1.

Verification: `./gradlew assembleRelease` → APK < 12 MB (`ls -lh`), cold start measured with
`adb shell am start -W -n <pkg>/.MainActivity` (< 1000 ms totalTime).

---

## API quick reference (copy-paste)

```
Host: https://www.miruro.cx      (HTTP/2 ONLY)
Headers on every call:
  Sec-Fetch-Site: same-origin
  Referer: https://www.miruro.cx/
  Accept: */*

GET /api/v1/anime?limit=15&sort=-popularity&q=<text>            search
GET /api/v1/anime?limit=15&sort=-popularity&genre_all=<Genre>   browse
GET /api/v1/anime?limit=15&sort=-popularity&cursor=<cursor>     next page
GET /api/v1/anime/{id}/show
GET /api/v1/anime/{id}/episodes?limit=10000
GET /api/v1/anime/{id}/episodes/{n}/play

Response bytes = gzip( XOR(body, "miruro/catalog") )

Allowed values:
  limit        12 | 15            (anything else = 400)
  sort         -popularity popularity -score score -updated_at updated_at title -title
  format       TV MOVIE OVA ONA TV_SHORT SPECIAL MUSIC     (single value only)
  status       RELEASING FINISHED NOT_YET_RELEASED HIATUS CANCELLED
  genre_all    Action Adventure Comedy Drama Ecchi Fantasy Hentai Horror Mahou Shoujo
               Mecha Music Mystery Psychological Romance Sci-Fi Slice of Life Sports
               Supernatural Thriller
```

Sanity check any of this from the shell before coding: call the same URL with `curl --http2`,
the two headers above, and decode the body with the XOR+gzip key.
