#!/usr/bin/env python3
"""Drives an Android emulator or phone over adb, by what is on its screen.

What a Claude Code session uses to work through docs/EMULATOR-CHECKS.md: it reads the screen as
text (uiautomator sees Compose's text and content descriptions), taps by text, and saves
screenshots to look at. Any app works, not only NewspapeRSS: Gmail, the Kindle app, KOReader.

    tools/emulator/ui.py texts                  # what the screen says, one node per line
    tools/emulator/ui.py wait "Get started"     # until it is on screen (30 s, --timeout)
    tools/emulator/ui.py tap "Get started"      # wait for it, then tap its middle
    tools/emulator/ui.py type "example.com"     # into the focused field
    tools/emulator/ui.py shot welcome           # build/emulator/welcome.png

Text matches exactly first, then as a case-insensitive substring. Set ANDROID_SERIAL when more
than one device is attached, and ADB if adb isn't on the PATH.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "emulator")


def adb(*args, binary=False):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=not binary).stdout


def dump():
    """The screen's hierarchy as XML, or None while there is none to give (an app starting up)."""
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    if "<hierarchy" not in raw:
        # Some images write nothing to the terminal; the file on the device is the documented way.
        subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        raw = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    if "<hierarchy" not in raw:
        return None
    return raw[raw.find("<?xml"):raw.rfind("</hierarchy>") + len("</hierarchy>")]


def nodes():
    xml = dump()
    if xml is None:
        return
    for node in ET.fromstring(xml).iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
        if label.strip() and len(bounds) == 4:
            yield label.strip(), bounds


def screen_size():
    # "Physical size: 1080x2400", then an "Override size" line when one is set, which is the one in use.
    w, h = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
    return int(w), int(h)


def find(text):
    found = list(nodes())
    for match in (lambda l: l == text, lambda l: text.lower() in l.lower()):
        hits = [(l, b) for l, b in found if match(l)]
        if hits:
            return hits[0]
    return None


def wait(text, timeout):
    deadline = time.monotonic() + timeout
    while True:
        hit = find(text)
        if hit:
            return hit
        if time.monotonic() > deadline:
            sys.exit(f"not on screen after {timeout}s: {text!r}\non screen: " + " | ".join(l for l, _ in nodes()))
        time.sleep(1)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["texts", "wait", "tap", "type", "shot"])
    p.add_argument("arg", nargs="?")
    p.add_argument("--timeout", type=float, default=30)
    a = p.parse_args()
    if a.command != "texts" and not a.arg:
        p.error(f"{a.command} needs an argument")
    if a.command == "texts":
        for label, _ in nodes():
            print(label)
    elif a.command == "wait":
        print(wait(a.arg, a.timeout)[0])
    elif a.command == "tap":
        label, (x1, y1, x2, y2) = wait(a.arg, a.timeout)
        # A scrolling screen lists what's below the fold too, at bounds off the screen, where a tap
        # lands on nothing: swipe it into view first.
        width, height = screen_size()
        for _ in range(10):
            y = (y1 + y2) // 2
            if 0 < y < height * 0.9:
                break
            down = y >= height * 0.9
            x = str(width // 2)
            adb("shell", "input", "swipe", x, str(int(height * (0.7 if down else 0.3))), x, str(int(height * (0.3 if down else 0.7))), "300")
            label, (x1, y1, x2, y2) = wait(a.arg, a.timeout)
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        print(f"tapped {label!r}")
    elif a.command == "type":
        # input text takes %s for a space and nothing else that the shell would read.
        adb("shell", "input", "text", re.sub(r"([\\'\"`$&|;<>()])", r"\\\1", a.arg).replace(" ", "%s"))
    elif a.command == "shot":
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, f"{a.arg}.png")
        with open(path, "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binary=True))
        print(os.path.normpath(path))


if __name__ == "__main__":
    main()
