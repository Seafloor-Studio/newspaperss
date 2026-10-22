#!/usr/bin/env bash
# Writes gradle/verification-metadata.xml: the checksum of every dependency CI's builds use.
# Gradle refuses any artifact whose checksum isn't listed, so run this after changing a
# dependency, and read the diff: each new line is a file Gradle will trust from now on.
#
# Gradle lists only what a build resolves, so this runs what CI runs. aapt2 comes in one jar
# per system and Gradle records only this machine's, which would break a build on macOS or
# Windows, so the others are added by hand.
set -euo pipefail
cd "$(dirname "$0")/.."

# --continue, and on to aapt2 after a failure: Gradle writes the checksums of all it resolved
# even when a test fails, and the failure is reported at the end.
status=0
./gradlew --write-verification-metadata sha256 --continue \
  build koverXmlReport koverHtmlReport koverVerify \
  assembleRelease bundleRelease checkReleaseKokoroNativeLibs checkReleaseKokoroBundle \
  :app:assembleDebugAndroidTest :epubsink:assembleDebug :app:screenshotTest || status=$?

python3 - <<'EOF'
import hashlib, re, urllib.request

path = "gradle/verification-metadata.xml"
xml = open(path, encoding="utf-8").read()
maven = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"

def with_jars(match):
    component, version = match.group(0), match.group(1)
    for system in ("linux", "osx", "windows"):
        name = f"aapt2-{version}-{system}.jar"
        if f'name="{name}"' in component:
            continue
        with urllib.request.urlopen(f"{maven}/{version}/{name}", timeout=120) as response:
            digest = hashlib.sha256(response.read()).hexdigest()
        artifact = (f'         <artifact name="{name}">\n'
                    f'            <sha256 value="{digest}" origin="aapt2 for {system}, by tools/verification-metadata.sh"/>\n'
                    f'         </artifact>\n')
        component = component.replace("      </component>", artifact + "      </component>")
    # In Gradle's order, by name, so its next rewrite of the file doesn't move them.
    head, rest = component.split("\n", 1)
    artifacts = re.findall(r'         <artifact name="[^"]*">.*?</artifact>\n', rest, flags=re.S)
    artifacts.sort(key=lambda a: re.search(r'name="([^"]*)"', a).group(1))
    return head + "\n" + "".join(artifacts) + "      </component>"

pattern = r'      <component group="com\.android\.tools\.build" name="aapt2" version="([^"]+)">.*?      </component>'
xml, found = re.subn(pattern, with_jars, xml, flags=re.S)
if not found:
    raise SystemExit("No aapt2 in the verification metadata: has the Android plugin changed how it gets it?")
open(path, "w", encoding="utf-8").write(xml)
EOF
exit $status
