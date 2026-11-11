#!/usr/bin/env bash
# Fails unless the APK is signed by exactly one certificate, the app's release certificate.
# A build signed with a debug key, or not signed, never passes.
# The certificate's name fields (DN) are left out of the public log.
# Usage: check-signature.sh app.apk AA:BB:...   (the expected SHA-256)
set -euo pipefail
apk="$1"
expected="$(printf '%s' "$2" | tr -d ': ' | tr 'A-F' 'a-f')"

here="$(cd "$(dirname "$0")" && pwd)"
apksigner="$("$here/android-tool.sh" apksigner)"
report="$(mktemp)"
trap 'rm -f "$report"' EXIT

if ! "$apksigner" verify --verbose --print-certs "$apk" > "$report" 2>&1; then
  grep -E '^(ERROR|DOES NOT VERIFY)' "$report" || true
  echo "::error::$apk is not validly signed."
  exit 1
fi

# Show the result without the certificate's name fields or key details.
grep -viE 'DN:|public key' "$report" | grep -iE '^(Verifies|Verified using|Number of signers|Signer.*(SHA-256|SHA-1|MD5) digest)' || true

if ! grep -qE '^Verified using v(2|3) scheme .*: true' "$report"; then
  echo "::error::$apk is not signed with APK Signature Scheme v2 or v3."
  exit 1
fi

# apksigner lists the certificate once per signature scheme; they must all be the same one.
digests="$( (grep -iE 'certificate SHA-256 digest:' "$report" || true) | sed 's/.*digest: *//' | tr -d ': \r' | tr 'A-F' 'a-f' | sort -u)"
count="$(printf '%s\n' "$digests" | grep -c . || true)"
if [ "$count" = "0" ]; then
  echo "apksigner output, with the certificate's name fields removed:"
  sed -E 's/(DN:).*/\1 (not shown)/I' "$report" | grep -viE 'public key' | head -n 40
  echo "::error::apksigner printed no certificate digest for $apk."
  exit 1
fi
if [ "$count" != "1" ]; then
  echo "::error::Expected one signing certificate, found $count."
  exit 1
fi
if [ "$digests" != "$expected" ]; then
  echo "::error::$apk is signed with the wrong certificate (SHA-256 $digests). It must be signed with the release key."
  exit 1
fi
echo "Certificate SHA-256: $digests"
echo "Signature check passed: signed with the release certificate."
