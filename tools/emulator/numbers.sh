#!/bin/bash
# How the app fares on a slow, small phone, as numbers to compare from run to run. Debug builds
# skip R8, so these overstate what a release build costs; the trend is what to read.
#
#   tools/emulator/numbers.sh start    # cold start, three times: Android's own TotalTime
#   tools/emulator/numbers.sh memory   # what the app holds now (PSS), and the device's RAM
set -euo pipefail
pkg=${PKG:-com.app.newspaperss.debug}

case "${1:-}" in
  start)
    activity=$(adb shell cmd package resolve-activity --brief "$pkg" | tr -d '\r' | tail -1)
    times=()
    for _ in 1 2 3; do
      adb shell am force-stop "$pkg"
      times+=("$(adb shell am start -W -n "$activity" | tr -d '\r' | sed -n 's/^TotalTime: //p')")
      sleep 3
    done
    echo "cold start (ms): ${times[*]}" ;;
  memory)
    total=$(adb shell dumpsys meminfo "$pkg" | tr -d '\r' | awk '/TOTAL/ {print $2; exit}')
    ram=$(adb shell cat /proc/meminfo | tr -d '\r' | awk '/MemTotal/ {print $2}')
    echo "app memory: $((total / 1024)) MB PSS, on a device with $((ram / 1024)) MB" ;;
  *) sed -n '2,7p' "$0" >&2; exit 2 ;;
esac
