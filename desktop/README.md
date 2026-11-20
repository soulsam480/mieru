# Mieru Desktop

A native desktop app for the same catalogues as the Android app: anime from
Miruro, TV and movies from 1shows, one player, local progress and watchlist.
Built with [mygo](https://github.com/egoist/mygo): Go draws the window, there is
no HTML, JavaScript or embedded page for the interface, and playback uses the
system's media framework (AVFoundation on macOS, Media Foundation on Windows).

## Run

```sh
cd desktop
mise exec -- go tool mygo dev
```

`mise exec` picks Go 1.27.4 and Bun through `.mise.toml`. The app stores its
files in the OS config directory under `Mieru Desktop/` (`progress.json`,
`watchlist.json`, `settings.json`).

## Test

```sh
mise exec -- go test ./...            # deterministic suite, no network
MIERU_LIVE=1 mise exec -- go test ./...   # also hits the live catalogues
```

The UI tests use `ui.NewTester`, the documented way to run a mygo view without a
window: it builds the real view, presses real controls by label, and asserts
what the window shows. `desktop/app_test.go` covers the grids, detail page,
bookmark, library, settings and the player's error state.

`desktop/.agents/skills/verify-desktop/` is the maintained verification skill:
the feature map, the exact launch/doctor/cleanup commands, and the automation
socket flow for a visual smoke test.

## Build

```sh
mise exec -- go tool mygo build                       # macOS app + dmg
mise exec -- go tool mygo build -platform windows/amd64
```

The macOS app is signed by `mygo` and lands in `build/darwin-arm64/Mieru.app`.
Windows builds need NSIS for the installer; without it the portable `Mieru.exe`
is still produced.

## How playback works

Anime sources come from Miruro's `/play` endpoint and are played directly.
TV and movie mirrors are JS players, so the app opens each mirror in a
**hidden** mygo window with a preload script that records the first playlist
request, then destroys it. That is the same offscreen resolver the Android app
uses; no provider page is ever shown. A loopback HTTP proxy re-serves the
playlist and segments with the `Referer`, `Origin` and `Cookie` headers the CDN
requires, and rewrites HLS URIs so the system player fetches everything through
the proxy. If a mirror errors or sends no video, the player rolls to the next
one and says which one failed.

Subtitles: anime tracks come from the play response, TV and movie tracks from
OpenSubtitles' public Stremio addon, one per language. Cues are drawn over the
video so size, colour, background and outline follow Settings.

## Known limits

- Miruro's `/play` endpoint has been answering `502` from the edge at times.
  Catalogue, schedule, episodes and 1shows all answer; the live test reports
  the upstream failure and skips instead of pretending playback is verified.
- The Windows executable compiles but has not been run on Windows here.
- `PRODUCT.md` / `DESIGN.md` are not written yet; the app follows the visual
  language of the site it replaces (dark stage, poster-led grids, episode rows
  with stills and synopsis).
