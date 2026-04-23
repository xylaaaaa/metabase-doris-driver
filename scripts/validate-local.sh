#!/usr/bin/env bash
set -euo pipefail

METABASE_URL="${METABASE_URL:-http://127.0.0.1:3001}"
USERNAME="${METABASE_USERNAME:-codex.local@example.com}"
PASSWORD="${METABASE_PASSWORD:-MetabaseTest123}"
DORIS_HOST="${DORIS_HOST:-127.0.0.1}"
DORIS_PORT="${DORIS_PORT:-9030}"
DORIS_CATALOG="${DORIS_CATALOG:-internal}"
DORIS_DB="${DORIS_DB:-metabase_driver_test}"
DORIS_USER="${DORIS_USER:-root}"
DORIS_PASSWORD="${DORIS_PASSWORD:-}"
METABASE_DB_NAME="${METABASE_DB_NAME:-Local Doris V1 Test}"
TARGET_TABLE_NAME="${TARGET_TABLE_NAME:-metabase_v1_orders}"
GROUP_FIELD_NAME="${GROUP_FIELD_NAME:-category}"
METRIC_FIELD_NAME="${METRIC_FIELD_NAME:-amount}"
TIME_FIELD_NAME="${TIME_FIELD_NAME:-event_time}"

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
    -d "$body" >/tmp/metabase-doris-driver-create.json
}

wait_for_table_metadata() {
  local db_id="$1"
  local target_table="$2"
  local output_file="$3"
  local attempts="${4:-60}"
  local sleep_seconds="${5:-2}"
  for _ in $(seq 1 "$attempts"); do
    curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database/$db_id/metadata" >"$output_file"
    if TARGET_TABLE="$target_table" TARGET_FILE="$output_file" python - <<'PY'
import json
import os
obj = json.load(open(os.environ["TARGET_FILE"]))
target = os.environ["TARGET_TABLE"]
for table in obj.get("tables", []):
    if table.get("name") == target:
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

echo "[3/5] Validate Doris connection"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/database/validate" \
  -d "{\"details\":{\"engine\":\"doris\",\"details\":{\"host\":\"$DORIS_HOST\",\"port\":$DORIS_PORT,\"catalog\":\"$DORIS_CATALOG\",\"dbname\":\"$DORIS_DB\",\"user\":\"$DORIS_USER\",\"password\":\"$DORIS_PASSWORD\",\"ssl\":false}}}"
echo

echo "[4/6] Resolve database, table, and field ids for MBQL validation"
TMP_DB_LIST="/tmp/metabase-doris-driver-databases.json"
TMP_DB_META="/tmp/metabase-doris-driver-metadata.json"

DB_ID="$(find_database_id "$METABASE_DB_NAME" "$TMP_DB_LIST")"

if [[ -z "$DB_ID" ]]; then
  echo "Database '$METABASE_DB_NAME' not found in Metabase. Creating it now."
  create_database_entry "$METABASE_DB_NAME"
  DB_ID="$(find_database_id "$METABASE_DB_NAME" "$TMP_DB_LIST")"
fi

if [[ -z "$DB_ID" ]]; then
  echo "Could not locate or create database id for $METABASE_DB_NAME" >&2
  exit 1
fi

if ! wait_for_table_metadata "$DB_ID" "$TARGET_TABLE_NAME" "$TMP_DB_META"; then
  echo "Timed out waiting for table '$TARGET_TABLE_NAME' to appear in metadata for database id $DB_ID" >&2
  exit 1
fi

read -r TABLE_ID CATEGORY_FIELD_ID AMOUNT_FIELD_ID EVENT_TIME_FIELD_ID <<<"$(
  TARGET_TABLE_NAME="$TARGET_TABLE_NAME" GROUP_FIELD_NAME="$GROUP_FIELD_NAME" METRIC_FIELD_NAME="$METRIC_FIELD_NAME" TIME_FIELD_NAME="$TIME_FIELD_NAME" python - <<'PY'
import json
import os

obj=json.load(open("/tmp/metabase-doris-driver-metadata.json"))
target_table=os.environ["TARGET_TABLE_NAME"]
group_field=os.environ["GROUP_FIELD_NAME"]
metric_field=os.environ["METRIC_FIELD_NAME"]
time_field=os.environ["TIME_FIELD_NAME"]
for t in obj.get("tables", []):
    if t.get("name") == target_table:
        field_map={f["name"]: f["id"] for f in t.get("fields", [])}
        print(t["id"], field_map[group_field], field_map[metric_field], field_map[time_field])
        break
PY
)"

echo "database_id=$DB_ID table_id=$TABLE_ID category_field_id=$CATEGORY_FIELD_ID amount_field_id=$AMOUNT_FIELD_ID event_time_field_id=$EVENT_TIME_FIELD_ID"

echo "[5/6] Execute native query"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"native\",\"native\":{\"query\":\"select count(*) as row_count, sum(${METRIC_FIELD_NAME}) as total_amount from ${TARGET_TABLE_NAME}\",\"template-tags\":{}},\"middleware\":{\"js-int-to-string?\":true}}"
echo

echo "[6/6] Execute grouped MBQL query"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"query\",\"query\":{\"source-table\":$TABLE_ID,\"aggregation\":[[\"sum\",[\"field\",$AMOUNT_FIELD_ID,null]]],\"breakout\":[[\"field\",$CATEGORY_FIELD_ID,null]],\"order-by\":[[\"asc\",[\"field\",$CATEGORY_FIELD_ID,null]]]},\"middleware\":{\"js-int-to-string?\":true}}"
echo
