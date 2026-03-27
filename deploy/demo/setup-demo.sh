#!/usr/bin/env bash
set -euo pipefail

# Setup demo tenant and data for trial users.
# Prerequisites: API is running and accessible.
#
# Usage:
#   API_URL=https://api.dbay.cloud:8443 API_KEY=lk_xxx ./deploy/demo/setup-demo.sh
#
# Or for local:
#   API_URL=http://localhost:8090 API_KEY=lk_xxx ./deploy/demo/setup-demo.sh

API_URL="${API_URL:?Set API_URL (e.g. http://localhost:8090)}"
API_KEY="${API_KEY:?Set API_KEY for an admin or the demo tenant}"

echo "=== DBay Demo Data Setup ==="
echo "API: $API_URL"

# 1. Check connectivity
echo ""
echo "--- Checking API connectivity ---"
curl -sf "$API_URL/actuator/health" > /dev/null || { echo "ERROR: API not reachable at $API_URL"; exit 1; }
echo "API is healthy"

# 2. Get tenant info
echo ""
echo "--- Tenant info ---"
TENANT_INFO=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/tenants/me")
TENANT_ID=$(echo "$TENANT_INFO" | jq -r '.id')
TENANT_NAME=$(echo "$TENANT_INFO" | jq -r '.name')
echo "Tenant: $TENANT_NAME ($TENANT_ID)"

# 3. Create demo database (if not exists)
echo ""
echo "--- Creating demo database ---"
DB_RESPONSE=$(curl -sf -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "demo-ecommerce"}' \
  "$API_URL/api/v1/databases" 2>/dev/null || echo '{"error":"exists"}')
DB_ID=$(echo "$DB_RESPONSE" | jq -r '.id // empty')
if [ -z "$DB_ID" ]; then
  echo "Database may already exist, listing..."
  DB_ID=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/databases" | jq -r '.[0].id')
fi
echo "Database ID: $DB_ID"

# 4. Wait for database to be ready
echo ""
echo "--- Waiting for database to be ready ---"
for i in $(seq 1 30); do
  STATUS=$(curl -sf -H "Authorization: Bearer $API_KEY" "$API_URL/api/v1/databases/$DB_ID" | jq -r '.status')
  echo "  Status: $STATUS"
  if [ "$STATUS" = "RUNNING" ] || [ "$STATUS" = "SUSPENDED" ]; then
    break
  fi
  sleep 2
done

# 5. Run init SQL via query endpoint
echo ""
echo "--- Loading demo data ---"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_FILE="$SCRIPT_DIR/init-demo-db.sql"

if [ ! -f "$SQL_FILE" ]; then
  echo "ERROR: $SQL_FILE not found"
  exit 1
fi

# Execute SQL statements one by one (split on semicolons, skip empty)
# For simplicity, send the whole script as one query
SQL_CONTENT=$(cat "$SQL_FILE")
RESPONSE=$(curl -sf -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg sql "$SQL_CONTENT" '{sql: $sql}')" \
  "$API_URL/api/v1/databases/$DB_ID/query" 2>&1 || true)
echo "Query response: $(echo "$RESPONSE" | head -c 200)"

# 6. Verify data
echo ""
echo "--- Verifying data ---"
for table in customers products orders order_items reviews; do
  COUNT=$(curl -sf -H "Authorization: Bearer $API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"sql\": \"SELECT COUNT(*) as count FROM $table\"}" \
    "$API_URL/api/v1/databases/$DB_ID/query" | jq -r '.rows[0][0]')
  echo "  $table: $COUNT rows"
done

echo ""
echo "=== Demo setup complete ==="
echo ""
echo "Set this in your deployment config:"
echo "  LAKEON_DEMO_TENANT_ID=$TENANT_ID"
