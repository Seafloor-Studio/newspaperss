#!/bin/bash
# The first thing an emulator session runs: installs the debug build, opens it on a fresh start
# and walks the first onboarding step by its text, with a screenshot of each screen in
# build/emulator/. If this passes, ui.py can drive the app, and what docs/EMULATOR-CHECKS.md
# asks can start. It clears the debug app's data: never point it at a phone in daily use.
#
#   tools/emulator/smoke.sh [app-debug.apk]
#
# Without an APK it installs the one ./gradlew :app:assembleDebug made for the device's processor:
# the build splits by ABI, so there is no universal APK.
set -euo pipefail
cd "$(dirname "$0")/../.."
ui=tools/emulator/ui.py
pkg=com.app.newspaperss.debug

adb wait-for-device
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
apk=${1:-app/build/outputs/apk/debug/app-$abi-debug.apk}
adb install -r -g "$apk" > /dev/null
adb shell pm clear "$pkg" > /dev/null
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null

$ui wait "Your own newspaper" --timeout 90
$ui shot 01-welcome
$ui tap "Get started"
# The next step asks which e-reader; any of its choices proves the tap landed.
$ui wait "Kindle" --timeout 30
$ui shot 02-device
echo "--- on screen:"
$ui texts
echo "--- smoke passed: the app installs, starts and answers taps"
