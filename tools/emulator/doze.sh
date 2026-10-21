#!/bin/bash
# Doze, the deep sleep a phone left alone overnight falls into, on demand: timed editions have
# to arrive through it, and waiting a night for it is the check this replaces.
#
#   tools/emulator/doze.sh on      # unplugged, screen off, deep idle now
#   tools/emulator/doze.sh window  # one maintenance window, as Android grants a few times a night
#   tools/emulator/doze.sh off     # back to normal
#   tools/emulator/doze.sh check   # on, confirm IDLE, off: these commands work on this device
#   tools/emulator/doze.sh timed   # a timed edition through deep sleep, after delivery.sh make
set -euo pipefail
cd "$(dirname "$0")/../.."
ui=tools/emulator/ui.py
pkg=com.app.newspaperss.debug

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

now() { adb shell date +%s | tr -d '\r'; }

# A value from the scheduler's own record (EditionScheduler), 0 while it has none.
pref() {
  adb shell run-as "$pkg" cat shared_prefs/edition-schedule.xml 2> /dev/null |
    sed -n "s/.*name=\"$1\" value=\"\([0-9]*\)\".*/\1/p" | head -1 | grep . || echo 0
}

# Polls until the timer has fired (its start is recorded) or $1 seconds pass.
fired_within() {
  local until=$(( $(now) + $1 ))
  while [ "$(now)" -lt "$until" ]; do
    [ "$(pref last_start_epoch_ms)" -gt 0 ] && return 0
    sleep 10
  done
  [ "$(pref last_start_epoch_ms)" -gt 0 ]
}

# Taps the field showing $1, empties it and types $2.
set_field() {
  $ui tap "$1" > /dev/null
  adb shell input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
  $ui type "$2"
}

timed() {
  # Typed into the time picker as 24-hour digits, whatever the device's habit.
  adb shell settings put system time_12_24 24
  $ui tap "Settings" > /dev/null
  $ui tap "Schedule" > /dev/null
  $ui wait "Ready by" --timeout 3 > /dev/null 2>&1 || $ui tap "Make an edition automatically" > /dev/null
  # A timer fires 30 minutes before its edition is due: due in 32, it fires in about 2.
  local due
  due=$(python3 -c 'import sys,datetime as d; o=sys.argv[2]; tz=d.timezone(d.timedelta(hours=int(o[:3]),minutes=int(o[0]+o[3:])))
print(d.datetime.fromtimestamp(int(sys.argv[1])+32*60,tz).strftime("%H %M"))' "$(now)" "$(adb shell date +%z | tr -d '\r')")
  # The button shows the time in the locale's own style ("6:30 AM", "06:30").
  $ui tap "$($ui texts | grep -E '^[0-9]{1,2}:[0-9]{2}' | head -1)" > /dev/null
  $ui tap "text input mode" > /dev/null
  $ui wait "Type in time" > /dev/null
  # The hour and minute fields, found by the numbers they show.
  local fields
  mapfile -t fields < <($ui texts | grep -E '^[0-9]{1,2}$')
  [ "${#fields[@]}" -ge 2 ] || { $ui shot 39-no-fields; echo "the time fields aren't both there:" >&2; $ui texts >&2; exit 1; }
  set_field "${fields[0]}" "${due% *}"
  # Typed, the hour now shows the new value; the minutes still show theirs.
  set_field "${fields[1]}" "${due#* }"
  $ui shot 39-time-typed
  $ui tap "OK" > /dev/null
  $ui shot 40-schedule
  # The new time took if the scheduler armed a timer for about two minutes from now.
  local start=0
  for _ in $(seq 15); do
    start=$(( $(pref pending_start_epoch_ms) / 1000 ))
    [ $(( start - $(now) )) -gt 0 ] && [ $(( start - $(now) )) -lt 200 ] && break
    sleep 2
  done
  [ $(( start - $(now) )) -gt 0 ] && [ $(( start - $(now) )) -lt 200 ] ||
    { echo "no timer armed for $due (pending start $start, now $(now))" >&2; exit 1; }
  echo "timer armed: fires at $(date -u -d @"$start" +%T) UTC, $(( start - $(now) )) s from now"
  on
  # Past its time, with a minute's grace: whether deep sleep held it back.
  if fired_within $(( start - $(now) + 60 )); then
    echo "fired in deep sleep, $(( $(pref last_start_epoch_ms) / 1000 - start )) s after its time"
  else
    echo "held in deep sleep: not fired $(( $(now) - start )) s after its time"
    window
    if fired_within 180; then
      echo "fired in a maintenance window, $(( $(pref last_start_epoch_ms) / 1000 - start )) s after its time"
    else
      off
      fired_within 180 || { echo "never fired, even awake" >&2; exit 1; }
      echo "fired only once awake, $(( $(pref last_start_epoch_ms) / 1000 - start )) s after its time"
    fi
  fi
  off > /dev/null
  $ui shot 41-after-timer
}

window() {
  # Stepping out of IDLE gives IDLE_MAINTENANCE, the window deferred work runs in.
  adb shell dumpsys deviceidle step deep > /dev/null
  echo "deep: $(state)"
}

case "${1:-}" in
  on) on ;;
  off) off ;;
  window) window ;;
  check)
    on
    [ "$(state)" = IDLE ] || { echo "the device didn't go into deep idle" >&2; off; exit 1; }
    off
    echo "doze commands work here" ;;
  timed) timed ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
