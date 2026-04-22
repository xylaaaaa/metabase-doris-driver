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

curl_no_proxy() {
  env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY curl -sS "$@"
}

echo "[1/5] Metabase health"
curl_no_proxy "$METABASE_URL/api/health"
echo

echo "[2/5] Login"
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

curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database" >"$TMP_DB_LIST"

DB_ID="$(
  python - <<'PY'
import json, sys
payload=json.load(open("/tmp/metabase-doris-driver-databases.json"))
for db in payload.get("data", []):
    if db.get("name") == "Local Doris V1 Test":
        print(db["id"])
        break
PY
)"

if [[ -z "$DB_ID" ]]; then
  echo "Could not locate database id for Local Doris V1 Test" >&2
  exit 1
fi

curl_no_proxy -H "Cookie: $COOKIE" "$METABASE_URL/api/database/$DB_ID/metadata" >"$TMP_DB_META"

read -r TABLE_ID CATEGORY_FIELD_ID AMOUNT_FIELD_ID EVENT_TIME_FIELD_ID <<<"$(
  python - <<'PY'
import json
obj=json.load(open("/tmp/metabase-doris-driver-metadata.json"))
for t in obj.get("tables", []):
    if t.get("name") == "metabase_v1_orders":
        field_map={f["name"]: f["id"] for f in t.get("fields", [])}
        print(t["id"], field_map["category"], field_map["amount"], field_map["event_time"])
        break
PY
)"

echo "database_id=$DB_ID table_id=$TABLE_ID category_field_id=$CATEGORY_FIELD_ID amount_field_id=$AMOUNT_FIELD_ID event_time_field_id=$EVENT_TIME_FIELD_ID"

echo "[5/6] Execute native query"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"native\",\"native\":{\"query\":\"select count(*) as row_count, sum(amount) as total_amount from metabase_v1_orders\",\"template-tags\":{}},\"middleware\":{\"js-int-to-string?\":true}}"
echo

echo "[6/6] Execute grouped MBQL query"
curl_no_proxy \
  -H "Cookie: $COOKIE" \
  -H "Content-Type: application/json" \
  -X POST "$METABASE_URL/api/dataset" \
  -d "{\"database\":$DB_ID,\"type\":\"query\",\"query\":{\"source-table\":$TABLE_ID,\"aggregation\":[[\"sum\",[\"field\",$AMOUNT_FIELD_ID,null]]],\"breakout\":[[\"field\",$CATEGORY_FIELD_ID,null]],\"order-by\":[[\"asc\",[\"field\",$CATEGORY_FIELD_ID,null]]]},\"middleware\":{\"js-int-to-string?\":true}}"
echo
