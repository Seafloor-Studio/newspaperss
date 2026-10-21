# What delivery.sh and journeys.sh share. Sourced from the repository's root.
# PKG is the app under test: the debug build unless it says otherwise (the release build's
# com.app.newspaperss).
# A failure inside $(...) stops the script too, as it would outside one.
shopt -s inherit_errexit
# A failure names its line, as a bare exit code from adb doesn't say where it came from.
set -E
trap 'echo "failed: line $LINENO: $BASH_COMMAND" >&2' ERR
ui=tools/emulator/ui.py
pkg=${PKG:-com.app.newspaperss.debug}

# The edition's title, read from its card's menu button ("More options for <title>").
title() { $ui texts | sed -n 's/^More options for //p' | head -1; }

bring_forward() {
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null
  $ui wait "Sources" > /dev/null
}

today() {
  # A tap while the app is still coming forward is lost, so try a few times; an edition may
  # also still be in the making (a timed one, say), so the last wait is long. Today keeps its
  # scroll, and the latest edition is at the top: scroll back up to it.
  for wait in 10 10 300; do
    $ui tap "Today" > /dev/null
    for _ in 1 2 3; do scroll_up; done
    $ui wait "More options for" --timeout "$wait" > /dev/null 2>&1 && return
  done
  echo "no edition on Today" >&2
  exit 1
}

# The latest edition's own screen, from wherever the app was left.
open_edition() {
  bring_forward
  today
  local t
  t=$(title)
  [ -n "$t" ] || { echo "no edition on Today" >&2; exit 1; }
  $ui tap "$t" > /dev/null
  # Exactly: Today's cards are "More options for <title>".
  $ui wait "More options" --exact > /dev/null
}

# A slow swipe by half the screen, to bring what's below (or above) the fold of a list into view.
swipe() {
  local w h
  read -r w h < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
  adb shell input swipe $((w / 2)) $((h * $1 / 4)) $((w / 2)) $((h * $2 / 4)) 600
  sleep 1
}
scroll_down() { swipe 3 1; }
scroll_up() { swipe 1 3; }

# The first book in a folder on the device once it is whole: written by another process, it
# grows for a while. Prints its path, or fails after a minute.
whole_book_in() {
  local dir=$1 book="" size=0 last=-1
  for _ in $(seq 60); do
    book=$(adb shell "ls -t '$dir'/*.epub 2> /dev/null" | head -1 | tr -d '\r' || true)
    if [ -n "$book" ]; then
      size=$(adb shell stat -c %s "'$book'" 2> /dev/null | tr -d '\r' || echo 0)
      [ "$size" -gt 0 ] && [ "$size" = "$last" ] && { echo "$book"; return; }
      last=$size
    fi
    sleep 1
  done
  echo "no whole book in $dir after 60 s (${book:-none}, $size bytes)" >&2
  return 1
}

# Pulls a book off the device to build/emulator/<name> and checks it is an EPUB: a zip whose
# first entry is "mimetype", stored, holding application/epub+zip.
pull_epub() {
  adb pull "$1" "build/emulator/$2" > /dev/null
  local head
  head=$(head -c 58 "build/emulator/$2" | tail -c 20)
  [ "$head" = "application/epub+zip" ] || { echo "$1 isn't an EPUB: it starts ${head:-empty}" >&2; exit 1; }
}
