#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
METABASE_VERSION="${METABASE_TEST_VERSION:-0.60.1}"
METABASE_CACHE_DIR="$ROOT_DIR/.cache/test-deps"
METABASE_VERSIONED_JAR="$METABASE_CACHE_DIR/metabase-$METABASE_VERSION.jar"
METABASE_JAR="$METABASE_CACHE_DIR/metabase.jar"
METABASE_URL="${METABASE_TEST_URL:-https://downloads.metabase.com/v$METABASE_VERSION/metabase.jar}"

case "$METABASE_VERSION" in
  0.59.6.3)
    METABASE_SHA256="d38b19fb8b8507dc4237ce42633698eda1c3e60ce911b5975b23a076414fcd74"
    ;;
  0.60.1)
    METABASE_SHA256="b36e774818c590255086b63f4b56699fed7e7f03bb866642f32e55ada21564d5"
    ;;
  0.60.2.2)
    METABASE_SHA256="f2e7e27a81c2168ce4c87801c420a4b7fa4bfcfb89fdbc4944a9db82bbe45f70"
    ;;
  *)
    echo "Unsupported Metabase test version: $METABASE_VERSION" >&2
    exit 1
    ;;
esac

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "A SHA-256 tool (sha256sum or shasum) is required." >&2
    exit 1
  fi
}

JAVA_SPEC_VERSION="$(java -XshowSettings:properties -version 2>&1 | awk '$1 == "java.specification.version" {print $3; exit}')"
JAVA_MAJOR="${JAVA_SPEC_VERSION#1.}"
JAVA_MAJOR="${JAVA_MAJOR%%.*}"

if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then
  echo "Java 21 or newer is required; found ${JAVA_SPEC_VERSION:-unknown}." >&2
  exit 1
fi

mkdir -p "$METABASE_CACHE_DIR"

if [[ ! -f "$METABASE_VERSIONED_JAR" || "$(sha256 "$METABASE_VERSIONED_JAR")" != "$METABASE_SHA256" ]]; then
  DOWNLOAD_PATH="$METABASE_VERSIONED_JAR.download"
  trap 'rm -f "$DOWNLOAD_PATH"' EXIT
  curl --fail --location --silent --show-error "$METABASE_URL" --output "$DOWNLOAD_PATH"

  if [[ "$(sha256 "$DOWNLOAD_PATH")" != "$METABASE_SHA256" ]]; then
    echo "SHA-256 verification failed for Metabase $METABASE_VERSION." >&2
    exit 1
  fi

  mv "$DOWNLOAD_PATH" "$METABASE_VERSIONED_JAR"
  trap - EXIT
fi

ln -sfn "$(basename "$METABASE_VERSIONED_JAR")" "$METABASE_JAR"

cd "$ROOT_DIR"
clojure -X:test
