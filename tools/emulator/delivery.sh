#!/bin/bash
# Delivery routes that need no account, driven on an emulator by what is on screen. Run smoke.sh
# first: it installs the app on a fresh start. Screenshots go to build/emulator/.
#
#   tools/emulator/delivery.sh make        # onboard with a starter pack; wait for the first edition
#   tools/emulator/delivery.sh save-copy   # ⋮ › Save a copy… into Downloads; check the file is there
#   tools/emulator/delivery.sh koreader    # install KOReader from F-Droid; Open on this phone in it
#
# make fetches the starter pack's real feeds, so it needs the internet and takes a few minutes.
set -euo pipefail
cd "$(dirname "$0")/../.."
ui=tools/emulator/ui.py

# The edition's title, read from its card's menu button ("More options for <title>").
title() { $ui texts | sed -n 's/^More options for //p' | head -1; }

open_edition() {
  $ui tap "Today" > /dev/null
  local t
  t=$(title)
  [ -n "$t" ] || { echo "no edition on Today" >&2; exit 1; }
  $ui tap "$t" > /dev/null
  $ui wait "More options" > /dev/null
}

case "${1:-}" in
  make)
    # smoke.sh leaves the app a step further on.
    $ui tap "Get started" --timeout 3 > /dev/null 2>&1 || true
    $ui tap "Something else"
    $ui tap "Next"
    $ui tap "I'll pick some sites"
    # Next stays disabled until a pick lands; a tap that only stopped the list scrolling is tried again.
    for _ in 1 2 3; do
      $ui tap "All of"
      $ui tap "Next"
      $ui wait "Make my first edition" --timeout 10 > /dev/null 2>&1 && break
    done
    $ui shot 10-size
    $ui tap "Make my first edition"
    # Its card's menu button is there once there's an edition, whatever Today says above it.
    $ui wait "More options for" --timeout 600
    $ui shot 11-today-ready
    echo "edition: $(title)" ;;
  save-copy)
    before=$(adb shell 'ls /sdcard/Download/ 2>/dev/null' | grep -c '\.epub' || true)
    open_edition
    $ui tap "More options"
    $ui tap "Save a copy"
    # Android's own picker, opened in Downloads or the last folder used.
    $ui wait "Save" --timeout 30 > /dev/null
    $ui shot 20-picker
    $ui tap "Downloads" --timeout 5 > /dev/null 2>&1 || true
    $ui tap "Save"
    # The picker makes the file empty before the app writes the book into it, so a file alone
    # proves nothing: wait for the app to say it's done, then look inside.
    $ui wait "Saved a copy" --timeout 60 > /dev/null || { $ui shot 21-not-saved; exit 1; }
    $ui shot 21-after-save
    after=$(adb shell 'ls /sdcard/Download/ 2>/dev/null' | grep -c '\.epub' || true)
    [ "$after" -gt "$before" ] || { echo "no new .epub in Downloads ($before before, $after after)" >&2; exit 1; }
    book=$(adb shell 'ls -t /sdcard/Download/*.epub' | head -1 | tr -d '\r')
    adb shell ls -l "'$book'"
    # An EPUB is a zip whose first entry is "mimetype", stored, holding application/epub+zip.
    head=$(adb exec-out cat "'$book'" | head -c 58 | tail -c 20)
    [ "$head" = "application/epub+zip" ] || { echo "$book isn't an EPUB: it starts ${head:-empty}" >&2; exit 1; }
    echo "save-copy passed: the book is in Downloads" ;;
  koreader)
    abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
    # F-Droid's builds by ABI; there is no x86_64 one, so x86 serves where the device runs it.
    code=$(curl -fsSL https://f-droid.org/api/v1/packages/org.koreader.launcher.fdroid | python3 -c '
import json, sys
abis = sys.argv[1].split(",")
offset = {"armeabi-v7a": 1, "arm64-v8a": 2, "x86": 3}
pkgs = json.load(sys.stdin)["packages"]
top = max(p["versionCode"] for p in pkgs) // 10 * 10
for abi in abis:
    if abi in offset and any(p["versionCode"] == top + offset[abi] for p in pkgs):
        print(top + offset[abi]); break
' "$abis")
    [ -n "$code" ] || { echo "no KOReader build on F-Droid for $abis" >&2; exit 1; }
    apk=build/emulator/koreader-$code.apk
    mkdir -p build/emulator
    [ -f "$apk" ] || curl -fsSL -o "$apk" "https://f-droid.org/repo/org.koreader.launcher.fdroid_$code.apk"
    adb install -r -g "$apk" > /dev/null
    # KOReader asks for all-files access on its first start and opens nothing until it has it.
    adb shell appops set --uid org.koreader.launcher.fdroid MANAGE_EXTERNAL_STORAGE allow
    open_edition
    $ui tap "More options"
    $ui tap "Open on this phone"
    # KOReader draws its own pages, out of uiautomator's sight: the screenshot is the proof.
    front=""
    for _ in $(seq 60); do
      front=$(adb shell dumpsys activity activities | grep -m1 'topResumedActivity' || true)
      [[ $front == *koreader* ]] && break
      sleep 1
    done
    [[ $front == *koreader* ]] || { $ui shot 30-not-koreader; echo "KOReader isn't in front after 60 s: $front" >&2; exit 1; }
    # In front isn't drawn: give its first page the time a reader would before looking.
    sleep 10
    $ui shot 30-koreader
    # Its own pages are invisible to uiautomator, but Android's dialogs over them are not.
    if $ui texts | grep -qiE 'allow|permission'; then
      echo "KOReader is asking for something instead of showing the book:" >&2; $ui texts >&2; exit 1
    fi
    echo "koreader passed: it opened the book; look at build/emulator/30-koreader.png" ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
