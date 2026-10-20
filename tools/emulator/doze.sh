#!/bin/bash
# Doze, the deep sleep a phone left alone overnight falls into, on demand: timed editions have
# to arrive through it, and waiting a night for it is the check this replaces.
#
#   tools/emulator/doze.sh on      # unplugged, screen off, deep idle now
#   tools/emulator/doze.sh window  # one maintenance window, as Android grants a few times a night
#   tools/emulator/doze.sh off     # back to normal
#   tools/emulator/doze.sh check   # on, confirm IDLE, off: these commands work on this device
set -euo pipefail

state() { adb shell dumpsys deviceidle get deep | tr -d '\r'; }

on() {
  adb shell dumpsys battery unplug
  adb shell input keyevent KEYCODE_SLEEP
  adb shell dumpsys deviceidle force-idle > /dev/null
  echo "deep: $(state)"
}

off() {
  adb shell dumpsys deviceidle unforce > /dev/null
  adb shell dumpsys battery reset
  adb shell input keyevent KEYCODE_WAKEUP
  echo "deep: $(state)"
}

case "${1:-}" in
  on) on ;;
  off) off ;;
  window)
    # Stepping out of IDLE gives IDLE_MAINTENANCE, the window deferred work runs in.
    adb shell dumpsys deviceidle step deep > /dev/null
    echo "deep: $(state)" ;;
  check)
    on
    [ "$(state)" = IDLE ] || { echo "the device didn't go into deep idle" >&2; off; exit 1; }
    off
    echo "doze commands work here" ;;
  *) sed -n '2,9p' "$0" >&2; exit 2 ;;
esac
