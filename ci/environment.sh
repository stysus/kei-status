#!/usr/bin/env bash
#
# Asserts the toolchain a sweep needs, and points the extension build at the Android SDK.
#
# Both failures this catches would otherwise surface as a thousand failed loads, which looks like
# every site being down at once. Fail here instead, before any module runs.
#
# Usage: environment.sh [extensions-source-dir]

set -euo pipefail

source_root="${1:-extensions-source}"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  echo "::error::no Android SDK on this runner; the extension build cannot run"
  exit 1
fi

echo "sdk.dir=$sdk" >"$source_root/local.properties"
echo "android sdk: $sdk"

for candidate in google-chrome-stable google-chrome chromium chromium-browser; do
  if command -v "$candidate" >/dev/null 2>&1; then
    echo "browser: $(command -v "$candidate")"
    exit 0
  fi
done

# A missing browser is not fatal: the extension still loads and the WebView scenarios report it.
echo "::warning::no Chromium found; sources that need a WebView will report a browser error"
