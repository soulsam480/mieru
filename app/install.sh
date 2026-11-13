#!/usr/bin/env bash
# Build and install the app on a connected phone (USB or wireless debugging).
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="$(mise where java)"
export ANDROID_HOME="$(mise where android-sdk)"
ADB="$ANDROID_HOME/platform-tools/adb"

if ! "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "No phone over USB."
  echo
  echo "USB checklist"
  echo "  1. Use a DATA cable (many USB-C cables are charge-only). Try another port."
  echo "  2. Settings → About phone → tap 'Build number' 7× (enables Developer options)."
  echo "  3. Developer options → USB debugging = ON."
  echo "  4. Plug in, then accept 'Allow USB debugging?' on the phone (check 'always allow')."
  echo "  5. Pull down the USB notification and pick 'File transfer' if it says 'Charging only'."
  echo "  6. Verify the Mac sees it:   system_profiler SPUSBDataType | grep -i pixel"
  echo
  echo "Wireless (no cable, Android 11+)"
  echo "  Developer options → Wireless debugging → 'Pair device with pairing code'"
  echo "  then:  $ADB pair <ip>:<pair-port>      (enter the 6-digit code)"
  echo "         $ADB connect <ip>:<debug-port>   (the port shown on that screen)"
  echo
  echo "No computer at all? Copy app/build/outputs/apk/debug/app-debug.apk to the phone,"
  echo "tap it, and allow 'install unknown apps' for the app that opens it."
  exit 1
fi

gradle --console=plain :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n cx.miruro.app.debug/cx.miruro.app.MainActivity
echo "Launched (package cx.miruro.app.debug — uninstall with: $ADB uninstall cx.miruro.app.debug)"
echo "Logs: $ADB logcat -s MiruroPlayer:* MiruroSeasons:*"
