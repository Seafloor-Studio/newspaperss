#!/bin/bash
# Ace by DAISY, the accessibility checker publishers use, on the books EpubCheckTest leaves in
# core/build/epubcheck/. Ace exits 0 even when a book fails, so this reads its report and fails
# on any violation, listing each rule once with the first place it was seen.
#
#   ./gradlew :core:test --tests '*EpubCheckTest*' --rerun && tools/ace-check.sh
#
# Needs Node. In a cloud session, point Puppeteer at the Chromium already there:
#   PUPPETEER_EXECUTABLE_PATH=/opt/pw-browsers/chromium tools/ace-check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

shopt -s nullglob
books=(core/build/epubcheck/*.epub)
if [ ${#books[@]} -eq 0 ]; then
  echo "No books in core/build/epubcheck/: run EpubCheckTest first." >&2
  exit 1
fi

failed=0
for book in "${books[@]}"; do
  out="core/build/ace/$(basename "$book" .epub)"
  npx --yes -p @daisy/ace@1.4.6 ace-puppeteer --force --silent --outdir "$out" "$book"
  python3 - "$out/report.json" "$book" <<'EOF' || failed=1
import collections, json, sys
report, book = sys.argv[1], sys.argv[2]
found = collections.OrderedDict()
for doc in json.load(open(report)).get("assertions", []):
    for a in doc.get("assertions", []):
        test = a["earl:test"]
        key = (test["earl:impact"], test["dct:title"])
        found.setdefault(key, [doc["earl:testSubject"]["url"], 0])[1] += 1
if not found:
    print(f"{book}: no violations")
    sys.exit(0)
print(f"{book}: {sum(n for _, n in found.values())} violations")
for (impact, rule), (where, n) in found.items():
    print(f"  {impact:8} {rule}: {n}, first in {where}")
sys.exit(1)
EOF
done
exit $failed
