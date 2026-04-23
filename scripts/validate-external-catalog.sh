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
EXTERNAL_TABLE_NAME="${EXTERNAL_TABLE_NAME:-}"
EXTERNAL_GROUP_FIELD="${EXTERNAL_GROUP_FIELD:-}"
EXTERNAL_METRIC_FIELD="${EXTERNAL_METRIC_FIELD:-}"
EXTERNAL_TIME_FIELD="${EXTERNAL_TIME_FIELD:-}"

for required in DORIS_CATALOG DORIS_DB METABASE_DB_NAME EXTERNAL_TABLE_NAME EXTERNAL_GROUP_FIELD EXTERNAL_METRIC_FIELD; do
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
    -d "$body" >/tmp/metabase-doris-driver-ext-create.json
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

wait_for_field_metadata() {
  local target_table="$1"
  local group_field="$2"
  local metric_field="$3"
  local time_field="${4:-}"
  local output_file="$5"
  local attempts="${6:-30}"
  local sleep_seconds="${7:-2}"
  for _ in $(seq 1 "$attempts"); do
    if TARGET_TABLE="$target_table" TARGET_GROUP_FIELD="$group_field" TARGET_METRIC_FIELD="$metric_field" TARGET_TIME_FIELD="$time_field" TARGET_FILE="$output_file" python - <<'PY'
import json
import os

obj = json.load(open(os.environ["TARGET_FILE"]))
target_table = os.environ["TARGET_TABLE"]
group_field = os.environ["TARGET_GROUP_FIELD"]
metric_field = os.environ["TARGET_METRIC_FIELD"]
time_field = os.environ.get("TARGET_TIME_FIELD", "")

for table in obj.get("tables", []):
    if table.get("name") != target_table:
        continue
    fields = {f["name"] for f in table.get("fields", [])}
    if group_field not in fields or metric_field not in fields:
        raise SystemExit(1)
    if time_field and time_field not in fields:
        raise SystemExit(1)
    raise SystemExit(0)
raise SystemExit(1)
PY
    then
      return 0
    fi
    sleep "$sleep_seconds"
    curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database/$DB_ID/metadata" >"$output_file"
  done
  return 1
}

echo "[1/6] Metabase health"
curl_no_proxy "$METABASE_URL/api/health"
echo

echo "[2/6] Login"
COOKIE="$(get_cookie)"

if [[ -z "$COOKIE" ]]; then
  echo "Failed to obtain metabase session cookie" >&2
  exit 1
fi

echo "session cookie acquired"

echo "[3/6] Validate Doris external catalog connection"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/database/validate" \
  -d "{\"details\":{\"engine\":\"doris\",\"details\":{\"host\":\"$DORIS_HOST\",\"port\":$DORIS_PORT,\"catalog\":\"$DORIS_CATALOG\",\"dbname\":\"$DORIS_DB\",\"user\":\"$DORIS_USER\",\"password\":\"$DORIS_PASSWORD\",\"ssl\":false}}}"
echo

echo "[4/6] Resolve existing Metabase database and metadata"
TMP_DB_LIST="/tmp/metabase-doris-driver-ext-databases.json"
TMP_DB_META="/tmp/metabase-doris-driver-ext-metadata.json"

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

if ! wait_for_table_metadata "$DB_ID" "$EXTERNAL_TABLE_NAME" "$TMP_DB_META"; then
  echo "Timed out waiting for table '$EXTERNAL_TABLE_NAME' to appear in metadata for database id $DB_ID" >&2
  exit 1
fi

if ! wait_for_field_metadata "$EXTERNAL_TABLE_NAME" "$EXTERNAL_GROUP_FIELD" "$EXTERNAL_METRIC_FIELD" "$EXTERNAL_TIME_FIELD" "$TMP_DB_META"; then
  echo "Timed out waiting for fields '$EXTERNAL_GROUP_FIELD', '$EXTERNAL_METRIC_FIELD' and optional '$EXTERNAL_TIME_FIELD' to appear in metadata for table '$EXTERNAL_TABLE_NAME'" >&2
  exit 1
fi

read -r TABLE_ID GROUP_FIELD_ID METRIC_FIELD_ID TIME_FIELD_ID <<<"$(
  python - <<'PY'
import json, os
obj = json.load(open("/tmp/metabase-doris-driver-ext-metadata.json"))
table_name = os.environ["EXTERNAL_TABLE_NAME"]
group_field = os.environ["EXTERNAL_GROUP_FIELD"]
metric_field = os.environ["EXTERNAL_METRIC_FIELD"]
time_field = os.environ.get("EXTERNAL_TIME_FIELD", "")

for table in obj.get("tables", []):
    if table.get("name") == table_name:
        fields = {f["name"]: f["id"] for f in table.get("fields", [])}
        print(
            table["id"],
            fields[group_field],
            fields[metric_field],
            fields.get(time_field, "") if time_field else ""
        )
        break
PY
)"

if [[ -z "$TABLE_ID" || -z "$GROUP_FIELD_ID" || -z "$METRIC_FIELD_ID" ]]; then
  echo "Could not resolve target table/field ids from metadata for table: $EXTERNAL_TABLE_NAME" >&2
  exit 1
fi

echo "database_id=$DB_ID table_id=$TABLE_ID group_field_id=$GROUP_FIELD_ID metric_field_id=$METRIC_FIELD_ID time_field_id=${TIME_FIELD_ID:-<none>}"

echo "[5/6] Execute native query"
NATIVE_SQL="select count(*) as row_count, sum(${EXTERNAL_METRIC_FIELD}) as total_metric from ${EXTERNAL_TABLE_NAME}"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"native\",\"native\":{\"query\":\"$NATIVE_SQL\",\"template-tags\":{}},\"middleware\":{\"js-int-to-string?\":true}}"
echo

echo "[6/6] Execute grouped MBQL query"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"query\",\"query\":{\"source-table\":$TABLE_ID,\"aggregation\":[[\"sum\",[\"field\",$METRIC_FIELD_ID,null]]],\"breakout\":[[\"field\",$GROUP_FIELD_ID,null]],\"order-by\":[[\"asc\",[\"field\",$GROUP_FIELD_ID,null]]]},\"middleware\":{\"js-int-to-string?\":true}}"
echo

if [[ -n "$EXTERNAL_TIME_FIELD" && -n "${TIME_FIELD_ID:-}" ]]; then
  echo "[optional] Execute temporal breakout"
  curl_no_proxy \
    -H "Cookie: $COOKIE" \
    -H "Content-Type: application/json" \
    -X POST "$METABASE_URL/api/dataset" \
    -d "{\"database\":$DB_ID,\"type\":\"query\",\"query\":{\"source-table\":$TABLE_ID,\"aggregation\":[[\"count\"]],\"breakout\":[[\"field\",$TIME_FIELD_ID,{\"temporal-unit\":\"day\"}]],\"order-by\":[[\"asc\",[\"field\",$TIME_FIELD_ID,{\"temporal-unit\":\"day\"}]]]},\"middleware\":{\"js-int-to-string?\":true}}"
  echo
fi
