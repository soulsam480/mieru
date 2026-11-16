#!/usr/bin/env bash
# Build the release APK, prove it carries the version declared in Gradle, then publish it.
# Exists because a silent version bump once shipped an old APK as a new release.
set -euo pipefail

cd "$(dirname "$0")"
export JAVA_HOME="$(mise where java)"
export ANDROID_HOME="/Users/sambitsahoo/.local/share/mise/installs/android-sdk/23.0"

GRADLE_FILE=app/build.gradle.kts
DECLARED_CODE=$(rg -o 'versionCode = [0-9]+' "$GRADLE_FILE" | rg -o '[0-9]+')
DECLARED_NAME=$(rg -o 'versionName = "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)

gradle --console=plain --no-daemon :app:spotlessCheck :app:assembleRelease :app:testDebugUnitTest

APK=app/build/outputs/apk/release/app-release.apk
ACTUAL=$("$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging "$APK" |
  rg -o "versionCode='[0-9]+' versionName='[^']+'" | head -1)

echo "declared: versionCode=$DECLARED_CODE versionName=$DECLARED_NAME"
echo "built   : $ACTUAL"

if ! grep -q "versionCode='$DECLARED_CODE'" <<<"$ACTUAL"; then
  echo "REFUSING TO RELEASE: versionCode in the APK does not match $GRADLE_FILE" >&2
  exit 1
fi
if ! grep -q "versionName='$DECLARED_NAME'" <<<"$ACTUAL"; then
  echo "REFUSING TO RELEASE: versionName in the APK does not match $GRADLE_FILE" >&2
  exit 1
fi

TAG="v$DECLARED_NAME"
echo "ok → $APK ($(du -h "$APK" | cut -f1)) for $TAG"
echo "next: git tag -a $TAG && git push origin $TAG && gh release create $TAG \"$APK\" --title \"Mieru $TAG\""
