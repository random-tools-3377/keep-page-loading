#!/usr/bin/env bash
# Fails if the built APK asks for any permission not listed in scripts/allowed-permissions.txt.
# Libraries can add permissions when manifests are merged, which is why this looks at the APK.
# Usage: check-permissions.sh app.apk
set -euo pipefail
apk="$1"
here="$(cd "$(dirname "$0")" && pwd)"
aapt2="$("$here/android-tool.sh" aapt2)"

dump="$("$aapt2" dump permissions "$apk")"
echo "$dump"
package="$(printf '%s\n' "$dump" | sed -n 's/^package: *//p' | head -n 1)"
requested="$(printf '%s\n' "$dump" | sed -n "s/^uses-permission[^:]*: name='\([^']*\)'.*/\1/p" | sort -u)"
allowed="$(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' -e "s/\${applicationId}/$package/" "$here/allowed-permissions.txt" | tr -d ' ' | sort -u)"
unexpected="$(comm -23 <(printf '%s\n' "$requested" | sed '/^$/d') <(printf '%s\n' "$allowed"))"
if [ -n "$unexpected" ]; then
  echo "::error::The APK asks for permissions that are not in scripts/allowed-permissions.txt:"
  printf '%s\n' "$unexpected"
  exit 1
fi
echo "Permission check passed."
