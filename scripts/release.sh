#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RELEASE_TAG="${GITHUB_REF_NAME:-}"
if [[ ! "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf 'Release tag must be vMAJOR.MINOR.PATCH.\n' >&2
  exit 1
fi
if [[ ! "${GITHUB_RUN_NUMBER:-}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'A positive GITHUB_RUN_NUMBER is required for versionCode.\n' >&2
  exit 1
fi
git merge-base --is-ancestor HEAD origin/main || {
  printf 'Release commit must be included in origin/main.\n' >&2
  exit 1
}

: "${ANDROID_KEYSTORE_BASE64:?ANDROID_KEYSTORE_BASE64 secret is required}"
: "${HANGIL_KEYSTORE_PASSWORD:?HANGIL_KEYSTORE_PASSWORD secret is required}"
: "${HANGIL_KEY_ALIAS:?HANGIL_KEY_ALIAS secret is required}"
: "${HANGIL_KEY_PASSWORD:?HANGIL_KEY_PASSWORD secret is required}"

SIGNING_DIR="$(mktemp -d)"
trap 'rm -rf "$SIGNING_DIR"' EXIT
export HANGIL_KEYSTORE_PATH="$SIGNING_DIR/release.keystore"
python3 - "$HANGIL_KEYSTORE_PATH" <<'PY'
import base64
import os
import pathlib
import sys

try:
    decoded = base64.b64decode(os.environ["ANDROID_KEYSTORE_BASE64"], validate=True)
    if not decoded:
        raise ValueError("empty keystore")
    path = pathlib.Path(sys.argv[1])
    path.write_bytes(decoded)
    path.chmod(0o600)
except (ValueError, OSError):
    sys.exit("Invalid or unreadable release keystore; no APK was published.")
PY
unset ANDROID_KEYSTORE_BASE64

./gradlew --no-daemon --console=plain \
  "-PreleaseVersion=${RELEASE_TAG#v}" \
  "-PreleaseVersionCode=$GITHUB_RUN_NUMBER" \
  :app:assembleRelease

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$SDK_DIR/build-tools/35.0.0/apksigner" verify app/build/outputs/apk/release/app-release.apk
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/hangil-${RELEASE_TAG#v}.apk"
python3 - "dist/hangil-${RELEASE_TAG#v}.apk" <<'PY'
import hashlib
import pathlib
import sys

apk = pathlib.Path(sys.argv[1])
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(apk.parent / "SHA256SUMS.txt").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
PY
