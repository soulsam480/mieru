---
name: verify-desktop
description: Drive and verify the Mieru desktop app (mygo native UI, macOS/Windows) end to end. Use when checking a desktop change, reproducing a desktop bug, or proving a feature works before shipping.
---

# Verify Mieru Desktop

The app is a mygo native UI binary. The automation plugin exposes its element
tree over a Unix socket, so a drive presses real controls by label and captures
what the window shows.

All commands run from `desktop/`.

## Deterministic gate

Run this first. It is the regression gate; the socket drive below is only for
looking at a real window.

```sh
mise exec -- go test ./... -count=1
MIERU_LIVE=1 mise exec -- go test ./... -count=1
```

`ui.NewTester` runs the view in memory, presses controls by label, and needs no
window, socket, or sleep. `app_test.go` covers the grids, detail, bookmark,
library, settings and the player error state; `internal/*` tests use `httptest`
for the catalogue clients, the media proxy and the stores. The live suite hits
Miruro, 1shows and OpenSubtitles and skips a mirror endpoint that is down
upstream instead of failing the run.

## Launch

```sh
desktop/scripts/verify.sh start
```

It builds with `-tags e2e` (the automation plugin), starts the binary with
`MYGO_AUTOMATION=$WORK/mieru.sock`, waits for the socket, and attaches. Output
is the window list.

- `WORK` is `/tmp/mieru-verify` unless `MIERU_VERIFY_DIR` says otherwise.
- Evidence goes to `$WORK/evidence/`.
- The app talks to the live Miruro and 1shows APIs. A drive needs network.
- State lives in the real user config dir (`Mieru Desktop/`). A drive may
  change watch progress and the watchlist; that is production-shaped data, so
  restore what you remove.

## Doctor

```sh
desktop/scripts/verify.sh doctor
```

Fails when the process is gone or the socket is missing. If either changed
under you, `stop` then `start` before driving.

## Drive

```sh
export PATH="$(mise exec -- go env GOBIN):$PATH"
mygo-automation open --socket /tmp/mieru-verify/mieru.sock
mygo-automation snapshot -i
mygo-automation press "TV Shows" --settle
mygo-automation screenshot /tmp/mieru-verify/evidence/tv.png
```

- Press by label (`press "TV Shows"`), refs (`press @e4`), or the app's keys.
- Prefer `press` and `fill` over coordinates.
- After an action that loads, use `--settle --timeout 25s`: the API can take a
  few seconds.
- `diff` reads what changed without acting. Refs go stale when the element
  goes; take a new snapshot.

## Evidence

Capture the action and the result, not just the final screen:

```sh
mygo-automation snapshot -i > /tmp/mieru-verify/evidence/before.txt
mygo-automation press "Settings" --settle
desktop/scripts/verify.sh shot settings
```

Files stay in `$WORK/evidence/` after cleanup. Name the flow in the file name.
For a player proof, also read the control labels after ten seconds of playback
(`diff --settle`), which shows position and duration moving.

## Cleanup

```sh
desktop/scripts/verify.sh stop
```

Kills the recorded PID only. Evidence and logs stay in `$WORK/`.

## Helpers

- `desktop/scripts/verify.sh start|doctor|stop|shot <name>` is the only helper.
- `desktop/e2e.go` links the plugin under the `e2e` tag; it must never be in a
  release build.
- Feature map: `features/README.md`.

## Gotchas

- `mygo build` (release) leaves the plugin out, as does `-tags mygo_noinspector`.
  The e2e binary is built by the helper, not by `mygo build`.
- The app window is 1280×840 by default and restores its size from `StateKey`.
  A drive that resizes leaves the user's window changed; put it back.
- The resolver for TV and movie mirrors opens hidden windows. A snapshot shows
  only the main window; a resolve can take 30 s per mirror, so use a long
  `--timeout` for the player.
- `press` on a card whose title is also a label can match the wrong element;
  take a snapshot first and press the ref.
