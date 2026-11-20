#!/usr/bin/env bash
# Memory profile for one session: idle, after browsing, during playback, after leaving.
# Prints PSS, Java heap, native heap and WebView/ExoPlayer counts at each step, so a leak shows up
# as a number that does not come back down.
#
# Usage: tools/profile-memory.sh [debug|release]
# Leaves the app installed. Run it against the debug build; release is not debuggable.
set -euo pipefail

PACKAGE="${1:-debug}"
if [[ "$PACKAGE" == "debug" ]]; then
  APP=cx.miruro.app.debug
else
  APP=cx.miruro.app
fi

export ANDROID_HOME="/Users/sambitsahoo/.local/share/mise/installs/android-sdk/23.0"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="${OUT:-/tmp/mieru-memory-$(date +%H%M%S)}"
mkdir -p "$OUT"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "no device attached" >&2
  exit 1
fi

sample() {
  local label="$1"
  {
    echo "### $label"
    "$ADB" shell dumpsys meminfo "$APP" | sed -n '1,30p'
    echo
    echo "--- activity/process ---"
    "$ADB" shell dumpsys activity processes | rg -A3 "$APP" | head -8 || true
    echo
    echo "--- webviews (rule: 0 between resolves) ---"
    "$ADB" shell dumpsys webviewupdate 2>/dev/null | head -3 || true
    echo
    echo "--- media sessions (rule: 1 while playing, 0 after) ---"
    "$ADB" shell dumpsys media_session 2>/dev/null | rg -c "$APP" || echo 0
  } | tee "$OUT/$label.txt" >/dev/null
  printf '%-22s %s\n' "$label" "$(rg -o 'TOTAL PSS: *[0-9]+' "$OUT/$label.txt" | head -1 || true)"
}

echo "output: $OUT"
"$ADB" shell am force-stop "$APP"
"$ADB" shell am start -n "$APP/cx.miruro.app.MainActivity" >/dev/null 2>&1
sleep 8
sample "01-idle"

echo
echo "Now: browse one section, then say 'browsed' + Enter."
read -r _
sample "02-browsed"

echo
echo "Now: play something for 60 s, then say 'playing' + Enter."
read -r _
sample "03-playing"

echo
echo "Leave the player but stay in the app, then Enter."
read -r _
sleep 5
sample "04-after-player"

echo
echo "Press Home (leave the app), then Enter."
read -r _
sleep 5
sample "05-backgrounded"

echo
echo "Reopen the app, then Enter."
read -r _
sleep 5
sample "06-reopened"

echo
echo "Heap dump for offline analysis:"
HEAP="$OUT/heap.hprof"
"$ADB" shell am dumpheap "$APP" /data/local/tmp/mieru.hprof
"$ADB" pull /data/local/tmp/mieru.hprof "$HEAP" >/dev/null
"$ADB" shell rm /data/local/tmp/mieru.hprof
echo "  $HEAP  (open with Android Studio Memory Profiler or jhat)"

echo
echo "Summary (PSS in KB):"
for f in "$OUT"/*.txt; do
  printf '%-22s %s\n' "$(basename "$f" .txt)" "$(rg -o 'TOTAL PSS: *[0-9]+' "$f" | head -1 || echo '?')"
done
