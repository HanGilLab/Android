#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

GRADLE=("$ROOT_DIR/gradlew" --no-daemon --console=plain)

quality() {
  python3 "$ROOT_DIR/scripts/check-public-content.py"
  "${GRADLE[@]}" spotlessCheck checkModuleBoundaries lintDebug
}

tests() {
  "${GRADLE[@]}" -p build-logic check
  "${GRADLE[@]}" :core:domain:test testDebugUnitTest
}

builds() {
  # Verification always builds an unsigned release, even on a machine with signing configured.
  env -u HANGIL_KEYSTORE_PATH -u HANGIL_KEYSTORE_PASSWORD -u HANGIL_KEY_ALIAS -u HANGIL_KEY_PASSWORD \
    "${GRADLE[@]}" :app:assembleDebug :app:assembleRelease
}

case "${1:-all}" in
  quality) quality ;;
  test) tests ;;
  build) builds ;;
  all) quality; tests; builds ;;
  *) printf 'Usage: %s [all|quality|test|build]\n' "$0" >&2; exit 2 ;;
esac
