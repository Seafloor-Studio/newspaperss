#!/bin/bash
# Draws each page of a book, in reading order, at a Kindle Paperwhite's width in headless
# Chromium, so a session without a device can look at what changed. A browser isn't an
# e-reader: it shows layout, pictures and styles, not each reader's own quirks. Each PNG is the
# top of its page, one screen (840px) unless HEIGHT asks for more of a long article.
#
#   HEIGHT=4000 tools/render-epub.sh core/build/epubcheck/corpus-edition.epub [chromium]
#
# PNGs go to core/build/render/<book>/. Chromium defaults to the one cloud sessions have.
set -euo pipefail
book=$1
chrome=${2:-${CHROMIUM:-/opt/pw-browsers/chromium}}
out="$(dirname "$0")/../core/build/render/$(basename "$book" .epub)"
rm -rf "$out" && mkdir -p "$out/book"
unzip -q "$book" -d "$out/book"
python3 - "$out/book" <<'PY' | while read -r n page; do
import sys, xml.etree.ElementTree as ET
root = sys.argv[1]
ns = {"c": "urn:oasis:names:tc:opendocument:xmlns:container", "o": "http://www.idpf.org/2007/opf"}
opf = ET.parse(f"{root}/META-INF/container.xml").find(".//c:rootfile", ns).get("full-path")
pkg = ET.parse(f"{root}/{opf}")
base = opf.rsplit("/", 1)[0] if "/" in opf else "."
hrefs = {i.get("id"): i.get("href") for i in pkg.iterfind(".//o:manifest/o:item", ns)}
for n, ref in enumerate(pkg.iterfind(".//o:spine/o:itemref", ns)):
    print(f"{n:03d}", f"{root}/{base}/{hrefs[ref.get('idref')]}")
PY
  # Chromium chatters on stderr even when it works, so its output is shown only when it fails.
  log=$("$chrome" --headless --no-sandbox --disable-gpu --hide-scrollbars --window-size="632,${HEIGHT:-840}" \
    --screenshot="$out/$n-$(basename "$page" .xhtml).png" "file://$(realpath "$page")" 2>&1) || { echo "$log" >&2; exit 1; }
done
ls "$out"/*.png | wc -l | xargs echo "pages drawn in $out:"
