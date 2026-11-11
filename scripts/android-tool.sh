#!/usr/bin/env bash
# Prints the path of an Android SDK build tool (aapt2, apksigner, zipalign) from the newest
# build-tools installed. GitHub's Ubuntu runners have the SDK in $ANDROID_HOME.
set -euo pipefail
tool="$1"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk/build-tools" ]; then
  echo "No Android SDK build-tools found (ANDROID_HOME is '${sdk}')." >&2
  exit 1
fi
dir="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -n 1)"
path="${dir%/}/$tool"
if [ ! -x "$path" ]; then
  echo "$tool not found in $dir" >&2
  exit 1
fi
echo "$path"
