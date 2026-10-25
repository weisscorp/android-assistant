#!/usr/bin/env bash
# Only invoked by the trusted publish job, after all Gradle work finishes.
set -euo pipefail
set +x
umask 077
: "${BUILD_RUN_ATTEMPT:?Missing producing build attempt from build job}"
for variable in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD EXPECTED_SIGNER_SHA256; do
  if [[ -z "${!variable:-}" ]]; then
    echo "Missing owner-provisioned signing input: $variable" >&2
    exit 1
  fi
done

identity_dir="$(mktemp -d "$RUNNER_TEMP/android-signing.XXXXXXXX")"
trap 'rm -rf -- "$identity_dir"' EXIT
export CI_KEYSTORE_FILE="$identity_dir/signing.keystore"
python3 - <<'PY'
import base64, os
from pathlib import Path
Path(os.environ['CI_KEYSTORE_FILE']).write_bytes(base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True))
PY
unset ANDROID_KEYSTORE_BASE64

build_tools="$ANDROID_HOME/build-tools/35.0.0"
mkdir -p dist
apk="dist/android-assistant-$VERSION_CODE.apk"
"$build_tools/zipalign" -P 16 -f 4 unsigned/app-release-unsigned.apk "$identity_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$CI_KEYSTORE_FILE" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$apk" "$identity_dir/aligned.apk"
unset ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD ANDROID_KEY_ALIAS CI_KEYSTORE_FILE
python3 tools/ci_release.py manifest --apk "$apk" --build-tools "$build_tools" \
  --build-run-attempt "$BUILD_RUN_ATTEMPT" > dist/manifest.json
python3 - <<'PY'
import json
from pathlib import Path
m = json.loads(Path('dist/manifest.json').read_text())
Path('dist/release-notes.md').write_text(
    f"Signed `{m['packageName']}` APK, versionCode {m['versionCode']}, channel {m['channel']}.\n\n"
    f"Source: {m['source']['commit']}\n\nBuild: {m['build']['url']}\n\n"
    f"APK SHA-256: `{m['sha256']}`\n\nSigner SHA-256: `{m['signingCertificateSha256']}`\n\n"
    "Public ASR/OCR assets included. Offline voice is incomplete: Silero export is unavailable; "
    "optional noncommercial Piper voice is omitted. See docs/ci-cd.md.\n\n"
    "Publication makes bytes available to the OTA pull proxy; it does not prove installation.\n")
PY
