#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="/mnt/disk1/chenjunwei/tools"
JAVA_HOME="${JAVA_HOME:-$TOOLS_DIR/jdk-21}"
METABASE_HOME="${METABASE_HOME:-$TOOLS_DIR/metabase-local}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$METABASE_HOME/plugins"

echo "JAVA_HOME=$JAVA_HOME"
echo "METABASE_HOME=$METABASE_HOME"

cd "$ROOT_DIR"
java -jar "$METABASE_HOME/metabase.jar"
