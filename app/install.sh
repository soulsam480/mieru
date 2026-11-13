#!/usr/bin/env bash
# Build (if needed) and install the app on a connected phone.
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="$(mise where java)"
export ANDROID_HOME="$(mise where android-sdk)"
ADB="$ANDROID_HOME/platform-tools/adb"

if ! "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "No phone detected."
  echo
  echo "1. On the phone: Settings -> About phone -> tap 'Build number' 7x"
  echo "2. Settings -> System -> Developer options -> enable 'USB debugging'"
  echo "3. Plug the phone in, then accept the 'Allow USB debugging?' prompt"
  echo
  echo "No cable? Copy app/build/outputs/apk/debug/app-debug.apk to the phone"
  echo "(Drive/AirDrop/Telegram), tap it, allow 'install unknown apps'."
  exit 1
fi

gradle --console=plain :app:assembleDebug

"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n cx.miruro.app/.MainActivity
echo "Launched. Logs: $ADB logcat -s cx.miruro.app AndroidRuntime"
