#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="/mnt/disk1/chenjunwei/tools"
JAVA_HOME="${JAVA_HOME:-$TOOLS_DIR/jdk-21}"
METABASE_HOME="${METABASE_HOME:-$TOOLS_DIR/metabase-local}"
METABASE_JAR="${METABASE_JAR:-$METABASE_HOME/metabase.jar}"
MB_PLUGINS_DIR="${MB_PLUGINS_DIR:-$METABASE_HOME/plugins}"
MB_DB_FILE="${MB_DB_FILE:-$METABASE_HOME/metabase-app-db}"
MB_JETTY_PORT="${MB_JETTY_PORT:-3001}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export MB_PLUGINS_DIR
export MB_DB_FILE
export MB_JETTY_PORT

mkdir -p "$MB_PLUGINS_DIR"

echo "JAVA_HOME=$JAVA_HOME"
echo "METABASE_HOME=$METABASE_HOME"
echo "METABASE_JAR=$METABASE_JAR"
echo "MB_PLUGINS_DIR=$MB_PLUGINS_DIR"
echo "MB_DB_FILE=$MB_DB_FILE"
echo "MB_JETTY_PORT=$MB_JETTY_PORT"

cd "$ROOT_DIR"
java -jar "$METABASE_JAR"
