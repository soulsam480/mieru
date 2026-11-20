#!/usr/bin/env bash
# Verification harness for Mieru Desktop: build with the automation plugin,
# start it with a socket, attach, and keep evidence.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${MIERU_VERIFY_DIR:-/tmp/mieru-verify}"
SOCKET="$WORK/mieru.sock"
PIDFILE="$WORK/app.pid"
EVIDENCE="$WORK/evidence"
GOBIN="$(mise exec -- go env GOBIN)"
CLI="$GOBIN/mygo-automation"

mkdir -p "$WORK" "$EVIDENCE"

alive() {
  [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

case "${1:-}" in
  start)
    if alive; then
      echo "already running (pid $(cat "$PIDFILE"))"
      exit 0
    fi
    (cd "$ROOT" && mise exec -- go build -tags e2e -o "$WORK/mieru" .)
    rm -f "$SOCKET"
    python3 - "$WORK/mieru" "$SOCKET" "$WORK/app.log" "$PIDFILE" <<'PY'
import os, subprocess, sys
binary, socket, log, pidfile = sys.argv[1:5]
env = dict(os.environ, MYGO_AUTOMATION=socket)
with open(log, "ab") as out:
    child = subprocess.Popen([binary], stdout=out, stderr=out, env=env, start_new_session=True)
open(pidfile, "w").write(str(child.pid))
PY
    for _ in $(seq 1 60); do
      [[ -S "$SOCKET" ]] && break
      sleep 0.25
    done
    [[ -S "$SOCKET" ]] || { echo "no socket; log:"; tail -20 "$WORK/app.log"; exit 1; }
    "$CLI" open --socket "$SOCKET"
    ;;
  doctor)
    alive || { echo "app not running"; exit 1; }
    [[ -S "$SOCKET" ]] || { echo "socket missing"; exit 1; }
    "$CLI" open --socket "$SOCKET" | sed -n '1,6p'
    echo "doctor: ok (pid $(cat "$PIDFILE"))"
    ;;
  stop)
    if alive; then
      kill "$(cat "$PIDFILE")"
      for _ in $(seq 1 20); do alive || break; sleep 0.1; done
    fi
    rm -f "$SOCKET" "$PIDFILE"
    echo "stopped (evidence kept in $EVIDENCE)"
    ;;
  shot)
    name="${2:-shot}"
    "$CLI" screenshot "$EVIDENCE/$name.png"
    echo "$EVIDENCE/$name.png"
    ;;
  *)
    echo "usage: $0 start|doctor|stop|shot <name>" >&2
    exit 2
    ;;
esac
