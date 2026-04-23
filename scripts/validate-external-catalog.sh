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

echo "[1/6] Metabase health"
curl_no_proxy "$METABASE_URL/api/health"
echo

echo "[2/6] Login"
COOKIE="$(
  curl_no_proxy -D - -o /dev/null \
    -H "Content-Type: application/json" \
    -X POST "$METABASE_URL/api/session" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
  | awk '/Set-Cookie: metabase.SESSION=/{print $2}' \
  | cut -d';' -f1
)"

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

curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database" >"$TMP_DB_LIST"

DB_ID="$(
  python - <<'PY'
import json, os
payload = json.load(open("/tmp/metabase-doris-driver-ext-databases.json"))
target = os.environ["METABASE_DB_NAME"]
for db in payload.get("data", []):
    if db.get("name") == target:
        print(db["id"])
        break
PY
)"

if [[ -z "$DB_ID" ]]; then
  echo "Could not locate Metabase database entry named: $METABASE_DB_NAME" >&2
  echo "Create the database entry in Metabase first, then rerun this script." >&2
  exit 1
fi

curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database/$DB_ID/metadata" >"$TMP_DB_META"

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
