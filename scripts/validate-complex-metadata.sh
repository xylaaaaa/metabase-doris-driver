#!/usr/bin/env bash
set -euo pipefail

METABASE_URL="${METABASE_URL:-http://127.0.0.1:3001}"
USERNAME="${METABASE_USERNAME:-codex.local@example.com}"
PASSWORD="${METABASE_PASSWORD:-MetabaseTest123}"

DORIS_HOST="${DORIS_HOST:-127.0.0.1}"
DORIS_PORT="${DORIS_PORT:-9030}"
DORIS_CATALOG="${DORIS_CATALOG:-}"
DORIS_DB="${DORIS_DB:-}"
DORIS_USER="${DORIS_USER:-root}"
DORIS_PASSWORD="${DORIS_PASSWORD:-}"

METABASE_DB_NAME="${METABASE_DB_NAME:-}"
COMPLEX_TABLE_NAME="${COMPLEX_TABLE_NAME:-}"
EXPECTED_FIELD_BASE_TYPES="${EXPECTED_FIELD_BASE_TYPES:-}"

for required in DORIS_CATALOG DORIS_DB METABASE_DB_NAME COMPLEX_TABLE_NAME EXPECTED_FIELD_BASE_TYPES; do
  if [[ -z "${!required:-}" ]]; then
    echo "Missing required environment variable: $required" >&2
    exit 1
  fi
done

curl_no_proxy() {
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY curl -sS "$@"
}

get_cookie() {
  curl_no_proxy -D - -o /dev/null \
    -H "Content-Type: application/json" \
    -X POST "$METABASE_URL/api/session" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
  | awk '/Set-Cookie: metabase.SESSION=/{print $2}' \
  | cut -d';' -f1
}

find_database_id() {
  local target_name="$1"
  local output_file="$2"
  curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database" >"$output_file"
  TARGET_NAME="$target_name" TARGET_FILE="$output_file" python - <<'PY'
import json
import os

payload = json.load(open(os.environ["TARGET_FILE"]))
target = os.environ["TARGET_NAME"]
for db in payload.get("data", []):
    if db.get("name") == target:
        print(db["id"])
        break
PY
}

create_database_entry() {
  local database_name="$1"
  local body
  body="$(cat <<JSON
{"name":"$database_name","engine":"doris","details":{"host":"$DORIS_HOST","port":$DORIS_PORT,"catalog":"$DORIS_CATALOG","dbname":"$DORIS_DB","user":"$DORIS_USER","password":"$DORIS_PASSWORD","ssl":false},"is_full_sync":true,"auto_run_queries":true}
JSON
)"
  curl_no_proxy \
    -H "Cookie: $COOKIE" \
    -H "Content-Type: application/json" \
    -X POST "$METABASE_URL/api/database" \
    -d "$body" >/tmp/metabase-doris-driver-complex-create.json
}

wait_for_expected_fields() {
  local db_id="$1"
  local target_table="$2"
  local expected_pairs="$3"
  local output_file="$4"
  local attempts="${5:-60}"
  local sleep_seconds="${6:-2}"
  for _ in $(seq 1 "$attempts"); do
    curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database/$db_id/metadata" >"$output_file"
    if TARGET_TABLE="$target_table" EXPECTED_PAIRS="$expected_pairs" TARGET_FILE="$output_file" python - <<'PY'
import json
import os

try:
    obj = json.load(open(os.environ["TARGET_FILE"]))
except Exception:
    raise SystemExit(1)
target_table = os.environ["TARGET_TABLE"]
expected_pairs = os.environ["EXPECTED_PAIRS"]
expected = [p.split(":", 1)[0] for p in expected_pairs.split(",") if p]

for table in obj.get("tables", []):
    if table.get("name") != target_table:
        continue
    fields = {f["name"] for f in table.get("fields", [])}
    for field_name in expected:
        if field_name not in fields:
            raise SystemExit(1)
    raise SystemExit(0)
raise SystemExit(1)
PY
    then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

echo "[1/5] Metabase health"
curl_no_proxy "$METABASE_URL/api/health"
echo

echo "[2/5] Login"
COOKIE="$(get_cookie)"
if [[ -z "$COOKIE" ]]; then
  echo "Failed to obtain metabase session cookie" >&2
  exit 1
fi
echo "session cookie acquired"

echo "[3/5] Validate Doris external catalog connection"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/database/validate" \
  -d "{\"details\":{\"engine\":\"doris\",\"details\":{\"host\":\"$DORIS_HOST\",\"port\":$DORIS_PORT,\"catalog\":\"$DORIS_CATALOG\",\"dbname\":\"$DORIS_DB\",\"user\":\"$DORIS_USER\",\"password\":\"$DORIS_PASSWORD\",\"ssl\":false}}}"
echo

echo "[4/5] Resolve existing Metabase database and metadata"
TMP_DB_LIST="/tmp/metabase-doris-driver-complex-databases.json"
TMP_DB_META="/tmp/metabase-doris-driver-complex-metadata.json"

DB_ID="$(find_database_id "$METABASE_DB_NAME" "$TMP_DB_LIST")"

if [[ -z "$DB_ID" ]]; then
  echo "Database '$METABASE_DB_NAME' not found in Metabase. Creating it now."
  create_database_entry "$METABASE_DB_NAME"
  DB_ID="$(find_database_id "$METABASE_DB_NAME" "$TMP_DB_LIST")"
fi

if [[ -z "$DB_ID" ]]; then
  echo "Could not locate or create Metabase database entry named: $METABASE_DB_NAME" >&2
  exit 1
fi

if ! wait_for_expected_fields "$DB_ID" "$COMPLEX_TABLE_NAME" "$EXPECTED_FIELD_BASE_TYPES" "$TMP_DB_META"; then
  echo "Timed out waiting for expected fields in metadata for table '$COMPLEX_TABLE_NAME'" >&2
  exit 1
fi

echo "[5/5] Validate top-level base types"
TARGET_TABLE="$COMPLEX_TABLE_NAME" EXPECTED_PAIRS="$EXPECTED_FIELD_BASE_TYPES" TARGET_FILE="$TMP_DB_META" python - <<'PY'
import json
import os
import sys

obj = json.load(open(os.environ["TARGET_FILE"]))
target_table = os.environ["TARGET_TABLE"]
expected_pairs = os.environ["EXPECTED_PAIRS"]
expected = {}
for pair in expected_pairs.split(","):
    if not pair:
        continue
    field_name, base_type = pair.split(":", 1)
    expected[field_name] = base_type

for table in obj.get("tables", []):
    if table.get("name") != target_table:
        continue
    fields = {f["name"]: f for f in table.get("fields", [])}
    for field_name, expected_base_type in expected.items():
        field = fields[field_name]
        actual_base_type = field.get("base_type")
        actual_database_type = field.get("database_type")
        print("{} {} {}".format(field_name, actual_database_type, actual_base_type))
        if actual_base_type != expected_base_type:
            print("Mismatch for {}: expected {} but got {}".format(field_name, expected_base_type, actual_base_type), file=sys.stderr)
            raise SystemExit(1)
    raise SystemExit(0)

print("Could not find table {}".format(target_table), file=sys.stderr)
raise SystemExit(1)
PY
