#!/bin/bash
# Gets a Claude Code cloud container ready for Gradle: the Android SDK, local.properties and
# a Gradle init script. Some environments' setup scripts already do part of this, so each step
# only fills in what's missing. Local sessions are left alone.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
compile_sdk=$(sed -n 's/^ *compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/tools.zip" \
    https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  mkdir -p "$sdk/cmdline-tools"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$sdk/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm -rf "$tmp"
fi

# Build-tools are left to AGP: it downloads the version it wants (not compileSdk's) on first build.
if [ ! -d "$sdk/platforms/android-$compile_sdk.0" ] && [ ! -d "$sdk/platforms/android-$compile_sdk" ]; then
  # A hook's output lands in the session's context, so sdkmanager's banners are kept out of it.
  log=$(mktemp)
  yes | "$sdkmanager" --sdk_root="$sdk" --licenses >"$log" 2>&1 || true
  "$sdkmanager" --sdk_root="$sdk" "platforms;android-$compile_sdk.0" "platform-tools" \
    >"$log" 2>&1 || { cat "$log" >&2; exit 1; }
  rm -f "$log"
fi

if ! grep -qs "^sdk.dir=$sdk\$" local.properties; then
  echo "sdk.dir=$sdk" > local.properties
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ] && ! grep -qs "ANDROID_HOME=" "$CLAUDE_ENV_FILE"; then
  echo "export ANDROID_HOME=\"$sdk\"" >> "$CLAUDE_ENV_FILE"
fi

# Maven Central answers the shared egress with 429, and a 429 stops resolution instead of
# falling through to the next repository. Robolectric fetches its android-all jars itself.
init="$HOME/.gradle/init.d/central-mirror.gradle.kts"
if [ ! -f "$init" ]; then
  mkdir -p "$(dirname "$init")"
  cat > "$init" <<'EOF'
fun RepositoryHandler.mirrorCentral() {
    removeIf { it is MavenArtifactRepository && it.url.host == "repo.maven.apache.org" }
    val r = maven { name = "GoogleCentralMirror"; url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    remove(r); addFirst(r)
}
settingsEvaluated {
    pluginManagement.repositories.mirrorCentral()
    dependencyResolutionManagement.repositories.mirrorCentral()
}
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
EOF
fi
