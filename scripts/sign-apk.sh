#!/usr/bin/env bash
# Signs the release APK with the app's one release key, read from two repository secrets:
#   SIGNING_KEYSTORE_B64  the keystore (a PKCS12 file), as base64 text
#   SIGNING_PASSWORD      its password
# The key inside is called "keep-page-loading". The keystore only exists in a temporary folder
# for the length of this script.
# Usage: sign-apk.sh unsigned.apk signed.apk
set -euo pipefail
in="$1"
out="$2"
alias_name="keep-page-loading"

for name in SIGNING_KEYSTORE_B64 SIGNING_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "::error::The repository secret $name is not set, so the release APK can't be signed."
    exit 1
  fi
done

here="$(cd "$(dirname "$0")" && pwd)"
zipalign="$("$here/android-tool.sh" zipalign)"
apksigner="$("$here/android-tool.sh" apksigner)"

work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/sign.XXXXXX")"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"

printf '%s' "$SIGNING_KEYSTORE_B64" | tr -d ' \r\n' | base64 --decode > "$work/release.p12"
if "$zipalign" -c -P 16 4 "$in" > /dev/null 2>&1; then
  cp "$in" "$work/aligned.apk"
else
  "$zipalign" -P 16 -f 4 "$in" "$work/aligned.apk"
fi
"$apksigner" sign \
  --ks "$work/release.p12" \
  --ks-type PKCS12 \
  --ks-pass env:SIGNING_PASSWORD \
  --ks-key-alias "$alias_name" \
  --key-pass env:SIGNING_PASSWORD \
  --out "$out" \
  "$work/aligned.apk"
echo "Signed $out"
