#!/bin/bash
# Journeys past the first edition that need no account, driven on an emulator by what is on
# screen. Run smoke.sh and delivery.sh make first, then these in this order: each starts from
# where the one before left the app. Screenshots go to build/emulator/.
#
#   tools/emulator/journeys.sh two-editions   # make another without sending: the first is let go
#   tools/emulator/journeys.sh cold-send      # app killed; Send from its notification to EPUB sink
#   tools/emulator/journeys.sh share          # a page saved, and a site subscribed, from a share
#   tools/emulator/journeys.sh folder         # Save to a folder in Settings; the next edition lands there
#   tools/emulator/journeys.sh listen         # Listen in the phone's voice: it plays, then pauses
#
# cold-send needs a timed edition, as only those notify (doze.sh soon), and the test app that
# stands in for an e-reader's: ./gradlew :epubsink:assembleDebug, or its APK as SINK_APK.
set -euo pipefail
cd "$(dirname "$0")/../.."
. tools/emulator/lib.sh

# Scrolls down until the text is on screen, for a list longer than the screen.
scroll_to() {
  for _ in $(seq 8); do
    $ui texts | grep -qiF -- "$1" && return
    scroll_down
  done
  $ui texts | grep -qiF -- "$1"
}

# A new edition from Today, waited for by its title changing from the one given.
make_another() {
  local old=$1
  # Not offered while the app is still busy after the last edition (Today's build panel), and
  # below the card: wait for it, scrolling down to look.
  local offered=""
  for _ in $(seq 12); do
    scroll_to "Make another edition" && { offered=1; break; }
    sleep 5
  done
  [ -n "$offered" ] || { echo "Make another edition isn't offered on Today; on screen:" >&2; $ui texts >&2; return 1; }
  $ui tap "Make another edition" > /dev/null
  # Back up to the latest edition's card, where its title shows once it's made.
  for _ in 1 2 3; do scroll_up; done
  for _ in $(seq 600); do
    local now
    now=$(title)
    [ -n "$now" ] && [ "$now" != "$old" ] && { echo "$now"; return; }
    sleep 1
  done
  echo "no new edition 10 minutes after Make another edition" >&2
  return 1
}

# What the app's database holds about a host, when the screen doesn't say why something is missing.
# Debug builds only (run-as).
db_about() {
  local dir=build/emulator/db
  mkdir -p "$dir"
  for f in newspaperss.db newspaperss.db-wal; do
    adb exec-out run-as "$pkg" cat "databases/$f" > "$dir/$f" 2> /dev/null || true
  done
  python3 - "$dir/newspaperss.db" "$1" <<'PY' || true
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
like = f"%{sys.argv[2]}%"
print("sources:", db.execute("SELECT id, kind, title, url FROM sources").fetchall())
print("articles from", sys.argv[2] + ":", db.execute("SELECT sourceId, title, url FROM articles WHERE url LIKE ?", (like,)).fetchall())
PY
}

share() {
  adb shell am start -a android.intent.action.SEND -t text/plain -n "$pkg/com.app.newspaperss.ShareActivity" "$@" > /dev/null
}

case "${1:-}" in
  two-editions)
    # An edition still ready when the next is made is marked not sent, and its articles go back
    # into the pool: the new one has them, so it isn't "No new edition".
    bring_forward
    today
    first=$(title)
    second=$(make_another "$first")
    $ui shot 40-second-edition
    scroll_to "not sent" || { $ui shot 41-no-not-sent; echo "$first isn't marked not sent under Earlier editions" >&2; exit 1; }
    $ui shot 41-earlier-not-sent
    echo "two-editions passed: $second made; $first marked not sent" ;;
  cold-send)
    sink=${SINK_APK:-epubsink/build/outputs/apk/debug/epubsink-debug.apk}
    adb install -r "$sink" > /dev/null
    # The timed edition's notification, once it's made.
    t=""
    for _ in $(seq 120); do
      t=$(adb shell dumpsys notification --noredact | sed -n 's/.*android.title=String (\(.*\) is ready).*/\1/p' | head -1)
      [ -n "$t" ] && break
      sleep 5
    done
    [ -n "$t" ] || { echo "no edition-ready notification 10 minutes after the timer; titles:" >&2; adb shell dumpsys notification --noredact | grep 'android.title=' >&2 || true; exit 1; }
    # Android ending a backgrounded app, as it does overnight: the process goes, the notification
    # stays. force-stop would take the notification with it.
    adb shell input keyevent HOME
    sleep 2
    adb shell am kill "$pkg"
    sleep 2
    if adb shell pidof "$pkg" > /dev/null; then echo "$pkg is still running after am kill" >&2; exit 1; fi
    adb logcat -c
    adb shell cmd statusbar expand-notifications
    $ui wait "$t is ready" > /dev/null
    $ui shot 42-notification
    $ui tap "Send" --exact > /dev/null
    # Android's share sheet: the stand-in app is one of few that take an EPUB on this image.
    $ui tap "EPUB sink" --timeout 30 > /dev/null
    got=""
    for _ in $(seq 30); do
      got=$(adb logcat -d -s EpubSink | grep -m1 RESULT || true)
      [ -n "$got" ] && break
      sleep 1
    done
    echo "EPUB sink: ${got:-nothing}"
    [[ $got == *zip=true* ]] || { echo "the book didn't reach EPUB sink whole after a cold start" >&2; exit 1; }
    # The share sheet tells the app which app was picked; that marks it sent.
    open_edition
    $ui wait "Sent" --exact --timeout 30 > /dev/null || { $ui shot 43-not-marked; echo "$t isn't marked sent" >&2; exit 1; }
    $ui shot 43-marked-sent
    echo "cold-send passed: $t reached another app from its notification and is marked sent" ;;
  share)
    # A page, then a site's front page, from any app's share sheet; both public and long-lived.
    share --es android.intent.extra.TEXT https://en.wikipedia.org/wiki/Printing_press --es android.intent.extra.SUBJECT "'Printing press'"
    $ui wait "Printing press" > /dev/null
    $ui shot 44-share-page
    $ui tap "Save to your reading list" > /dev/null
    # Gone before the next share, so its Subscribe isn't tapped on this one's dialog.
    for _ in $(seq 10); do $ui texts | grep -qF "Save to your reading list" || break; sleep 1; done
    share --es android.intent.extra.TEXT https://xkcd.com/
    $ui wait "Subscribe to this site" > /dev/null
    $ui shot 45-share-site
    $ui tap "Subscribe to this site" > /dev/null
    bring_forward
    # The reading list's row is at the top of Sources, so before any scrolling.
    $ui tap "Sources" > /dev/null
    $ui tap "Your reading list" > /dev/null
    $ui wait "Printing press" > /dev/null || { $ui shot 46-reading-list; exit 1; }
    $ui shot 46-reading-list
    adb shell input keyevent BACK
    # The feed is found after the dialog has gone, so it may take a moment to be listed.
    found=""
    for _ in $(seq 6); do
      scroll_to "xkcd" && { found=1; break; }
      sleep 5
    done
    $ui shot 47-sources
    [ -n "$found" ] || { echo "xkcd isn't in Sources after subscribing from a share; on screen:" >&2; $ui texts >&2; db_about xkcd >&2; exit 1; }
    echo "share passed: the page is in the reading list and xkcd in Sources" ;;
  folder)
    # Android doesn't let an app have the whole of Downloads, only a folder in it.
    dir=/sdcard/Download/Editions
    adb shell mkdir -p "$dir"
    adb shell "rm -f '$dir'/*.epub"
    bring_forward
    # Settings comes back on whichever of its pages was open (doze.sh leaves Schedule); a second
    # tap goes to its first page.
    $ui tap "Settings" > /dev/null
    sleep 1
    $ui tap "Settings" > /dev/null
    $ui tap "E-reader & delivery" > /dev/null
    # Below the tips for sending by hand.
    scroll_to "Save to a folder" || { echo "no Save to a folder in E-reader & delivery" >&2; $ui texts >&2; exit 1; }
    $ui tap "Save to a folder" > /dev/null
    # Android's folder picker, opened wherever it was last: at the top of the phone's storage
    # ("Download"), or in Downloads itself, or elsewhere, where its list of places has Downloads.
    if ! $ui wait "Editions" --exact --timeout 10 > /dev/null 2>&1; then
      if ! $ui tap "Download" --exact --timeout 5 > /dev/null 2>&1; then
        $ui tap "Show roots" --timeout 10 > /dev/null 2>&1 || true
        $ui tap "Downloads" > /dev/null
      fi
    fi
    $ui tap "Editions" --exact > /dev/null
    $ui shot 48-picker
    $ui tap "Use this folder" > /dev/null
    $ui tap "Allow" --exact > /dev/null
    $ui wait "Saved automatically to Editions" > /dev/null
    $ui shot 49-settings-folder
    today
    t=$(make_another "$(title)")
    book=$(whole_book_in "$dir")
    pull_epub "$book" folder.epub
    echo "$book: $(stat -c %s build/emulator/folder.epub) bytes"
    open_edition
    $ui wait "Sent" --exact --timeout 30 > /dev/null || { $ui shot 50-not-marked; echo "$t isn't marked sent after saving to the folder" >&2; exit 1; }
    $ui shot 50-folder-sent
    echo "folder passed: $t was saved to $dir and is marked sent" ;;
  listen)
    echo "text-to-speech engines: $(adb shell cmd package query-services -a android.intent.action.TTS_SERVICE | grep -oE 'packageName=[^ ]+' | sort -u | tr '\n' ' ')"
    open_edition
    $ui tap "Listen ·" > /dev/null
    $ui wait "Pause" --exact --timeout 60 > /dev/null || { $ui shot 51-not-playing; exit 1; }
    # The app shows Pause before the voice has said anything, so the proof is Android's own
    # record of a player started: the voice's audio, whichever process plays it.
    heard=""
    for _ in $(seq 30); do
      heard=$(adb shell dumpsys audio | grep -m1 -E 'state:started' || true)
      [ -n "$heard" ] && break
      sleep 1
    done
    $ui shot 51-playing
    if $ui texts | grep -qiE "isn't working|can't be read"; then echo "Listen stopped on an error:" >&2; $ui texts >&2; exit 1; fi
    [ -n "$heard" ] || { echo "no audio started 30 s after Listen; players:" >&2; adb shell dumpsys audio | grep -A3 -i 'playback activity\|players' >&2 || true; exit 1; }
    echo "playing: $heard"
    $ui tap "Pause" --exact > /dev/null
    $ui wait "Play" --exact --timeout 10 > /dev/null
    $ui shot 52-paused
    echo "listen passed: the phone's voice started playing, and it paused" ;;
  *) sed -n '2,13p' "$0" >&2; exit 2 ;;
esac
