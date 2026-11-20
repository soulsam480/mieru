#!/usr/bin/env bash
# Behaviour pass over the running app. Every assertion is a user-visible state
# after a real press, not a unit call.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${MIERU_VERIFY_DIR:-/tmp/mieru-verify}"
CLI="$(mise exec -- go env GOBIN)/mygo-automation"
export PATH="$(dirname "$CLI"):$PATH"
export MYGO_AUTOMATION="$WORK/mieru.sock"
EVIDENCE="$WORK/evidence"
PASS=0; FAIL=0
mkdir -p "$EVIDENCE"

snap() { "$CLI" snapshot -i 2>/dev/null; }
ref() { snap | awk -v pat="$1" '$0 ~ pat {print $1; exit}'; }
has() { "$CLI" snapshot 2>/dev/null | grep -qE "$1"; }
step() { printf '%-46s' "$1"; }
ok() { PASS=$((PASS+1)); echo "PASS"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL ${1:-}"; }
assert() { if eval "$2"; then ok; else bad "$1"; fi; }
press_ref() { local r; r=$(ref "$1"); [[ -n "$r" ]] && "$CLI" press "$r" --settle --timeout 60s >/dev/null 2>&1; }
wait_has() { for _ in $(seq 1 30); do has "$1" && return 0; sleep 1; done; return 1; }

cleanup() { "$ROOT/scripts/verify.sh" stop >/dev/null 2>&1; }
trap cleanup EXIT
"$ROOT/scripts/verify.sh" stop >/dev/null 2>&1
"$ROOT/scripts/verify.sh" start >/dev/null 2>&1 || { echo "app did not start"; exit 1; }

step "anime catalogue";          assert "search field"   'wait_has "Search anime"'
step "sort: Popular";            press_ref '"Popular"';          assert "popular chosen" 'has "\"Popular\" checked"'
step "search";                   r=$(ref 'Search anime'); "$CLI" fill "$r" "dandadan" --settle --timeout 20s >/dev/null 2>&1; sleep 4; assert "search results" 'has "Dandadan|No results"'
r=$(ref 'Search anime'); "$CLI" fill "$r" "" --settle --timeout 20s >/dev/null 2>&1; sleep 2

step "open anime detail";        press_ref '"EP [0-9]'; sleep 5; assert "episodes header" 'wait_has "Episodes"'
step "watchlist save";           press_ref '"+ My List"';        assert "saved" 'has "My List"'
step "library lists it";         press_ref 'key="/library"';     sleep 2; assert "library saved title" 'has "Remove"'
"$CLI" screenshot "$EVIDENCE/e2e-library.png" >/dev/null 2>&1

step "tv section";               press_ref 'key="/tv"';          assert "tv grid" 'wait_has "Search tv shows"'
step "movies section";           press_ref 'key="/movies"';      assert "movies grid" 'wait_has "Search movies"'
step "settings";                 press_ref 'key="/settings"';    assert "settings sections" 'wait_has "Anime source"'
press_ref '"Dub"';               assert "audio choice saved" 'has "\"Dub\" checked"'
press_ref '"XL"';                assert "subtitle size saved" 'has "\"XL\" checked"'
press_ref '"Test"';              sleep 6;                        assert "mirror test reported" 'has "answers|did not answer"'
"$CLI" screenshot "$EVIDENCE/e2e-settings.png" >/dev/null 2>&1

step "open tv detail";           press_ref 'key="/tv"'; sleep 4; press_ref 'Game of Thrones|Mentalist|button.*S1 E1'; sleep 6; assert "episode list" 'has "Episodes"'
"$CLI" screenshot "$EVIDENCE/e2e-detail.png" >/dev/null 2>&1

step "player";                   press_ref '"(Play|Continue)';    assert "transport appears" 'wait_has "Pause"'
step "player: space pauses";     "$CLI" key Space --settle >/dev/null 2>&1; sleep 1; assert "pause label" 'has "Play"'
step "player: space resumes";    "$CLI" key Space --settle >/dev/null 2>&1; sleep 1; assert "pause control" 'has "Pause"'
step "player: subtitles panel";  press_ref '"CC"';               assert "subtitle list" 'has "English"'
"$CLI" screenshot "$EVIDENCE/e2e-subs.png" >/dev/null 2>&1
"$CLI" key Escape >/dev/null 2>&1

step "replay after back";        press_ref '"‹ Back"'; sleep 3; press_ref '"(Play|Continue)'; assert "player reopens" 'wait_has "Pause"'

step "back to library";          press_ref '"‹ Back"'; sleep 2; press_ref 'key="/library"'; assert "library still there" 'has "Watchlist|Nothing here"'

echo
echo "e2e: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
