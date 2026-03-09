#!/bin/bash
set -uo pipefail

# Install required tools
apt-get update -qq && apt-get install -y -qq jq curl >/dev/null 2>&1

CONFIG=/config/task.json
API_URL=$(jq -r '.callback_url' $CONFIG)
SOURCE_HOST=$(jq -r '.source.host' $CONFIG)
SOURCE_PORT=$(jq -r '.source.port' $CONFIG)
SOURCE_DB=$(jq -r '.source.dbname' $CONFIG)
SOURCE_USER=$(jq -r '.source.user' $CONFIG)
TARGET_HOST=$(jq -r '.target.host' $CONFIG)
TARGET_PORT=$(jq -r '.target.port' $CONFIG)
TARGET_DB=$(jq -r '.target.dbname' $CONFIG)
TARGET_USER=$(jq -r '.target.user' $CONFIG)
TARGET_PASSWORD=$(jq -r '.target.password' $CONFIG)
PUB_NAME=$(jq -r '.publication_name' $CONFIG)
SUB_NAME=$(jq -r '.subscription_name' $CONFIG)
SLOT_NAME=$(jq -r '.slot_name' $CONFIG)
TABLE_COUNT=$(jq '.tables | length' $CONFIG)

export PGPASSWORD_SOURCE=$(cat /secrets/source-password)

callback() {
  local table_task_id=$1 status=$2 row_count=${3:-0} error=${4:-}
  local payload="{\"table_task_id\":\"$table_task_id\",\"status\":\"$status\",\"row_count\":$row_count,\"error_message\":\"$(echo "$error" | head -c 500 | sed 's/"/\\"/g')\"}"
  curl -sf -X PUT "$API_URL" -H "Content-Type: application/json" -d "$payload" || echo "WARN: callback failed for $table_task_id"
}

echo "=== Lakeon PG Sync Setup ==="
echo "Source: $SOURCE_USER@$SOURCE_HOST:$SOURCE_PORT/$SOURCE_DB"
echo "Target: $TARGET_USER@$TARGET_HOST:$TARGET_PORT/$TARGET_DB"
echo "Publication: $PUB_NAME"
echo "Subscription: $SUB_NAME"
echo "Slot: $SLOT_NAME"
echo "Tables: $TABLE_COUNT"
echo ""

# Background keepalive: prevent target compute from auto-suspending during initial sync
(while true; do
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "SELECT 1" >/dev/null 2>&1
  sleep 60
done) &
KEEPALIVE_PID=$!
trap "kill $KEEPALIVE_PID 2>/dev/null" EXIT

# Build table list for Publication
TABLE_LIST=""
for i in $(seq 0 $((TABLE_COUNT - 1))); do
  SCHEMA=$(jq -r ".tables[$i].schema" $CONFIG)
  TABLE=$(jq -r ".tables[$i].table" $CONFIG)
  if [ -n "$TABLE_LIST" ]; then
    TABLE_LIST="$TABLE_LIST, "
  fi
  TABLE_LIST="${TABLE_LIST}\"${SCHEMA}\".\"${TABLE}\""
done

echo "--- Step 1: Verify source wal_level ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
WAL_LEVEL=$(psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" -t -A \
  -c "SHOW wal_level" 2>/dev/null || echo "unknown")
WAL_LEVEL=$(echo "$WAL_LEVEL" | tr -d '[:space:]')
echo "  wal_level: $WAL_LEVEL"

if [ "$WAL_LEVEL" != "logical" ]; then
  echo "FATAL: Source database wal_level must be 'logical', got '$WAL_LEVEL'"
  # Report all tables as failed
  for i in $(seq 0 $((TABLE_COUNT - 1))); do
    TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
    callback "$TASK_ID" "FAILED" 0 "Source wal_level is '$WAL_LEVEL', must be 'logical'"
  done
  exit 1
fi

echo ""
echo "--- Step 2: Pre-create schemas on target ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
SCHEMAS=$(psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" -t -A \
  -c "SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('pg_catalog','information_schema','pg_toast') AND nspname NOT LIKE 'pg_temp_%' AND nspname NOT LIKE 'pg_toast_temp_%'" 2>/dev/null || true)
for S in $SCHEMAS; do
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "CREATE SCHEMA IF NOT EXISTS \"${S}\"" 2>/dev/null && echo "  schema: $S" || true
done

echo ""
echo "--- Step 3: Create Publication on source ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
# Drop existing publication if any (idempotent)
psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" \
  -c "DROP PUBLICATION IF EXISTS ${PUB_NAME}" 2>/dev/null || true

PUB_SQL="CREATE PUBLICATION ${PUB_NAME} FOR TABLE ${TABLE_LIST}"
echo "  SQL: $PUB_SQL"
psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" \
  -c "$PUB_SQL" 2>/tmp/pub_err
PUB_EXIT=$?

if [ $PUB_EXIT -ne 0 ]; then
  PUB_ERR=$(cat /tmp/pub_err 2>/dev/null | tr '\n' ' ')
  echo "FATAL: Failed to create Publication: $PUB_ERR"
  for i in $(seq 0 $((TABLE_COUNT - 1))); do
    TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
    callback "$TASK_ID" "FAILED" 0 "Failed to create Publication: $PUB_ERR"
  done
  exit 1
fi
echo "  Publication created: $PUB_NAME"

echo ""
echo "--- Step 4: Create Subscription on target ---"
SOURCE_CONNSTR="host=${SOURCE_HOST} port=${SOURCE_PORT} dbname=${SOURCE_DB} user=${SOURCE_USER} password=${PGPASSWORD_SOURCE}"

SUB_SQL="CREATE SUBSCRIPTION ${SUB_NAME} CONNECTION '${SOURCE_CONNSTR}' PUBLICATION ${PUB_NAME} WITH (copy_data = true, slot_name = '${SLOT_NAME}', create_slot = true)"
echo "  Creating subscription..."
PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
  -c "$SUB_SQL" 2>/tmp/sub_err
SUB_EXIT=$?

if [ $SUB_EXIT -ne 0 ]; then
  SUB_ERR=$(cat /tmp/sub_err 2>/dev/null | tr '\n' ' ')
  echo "FATAL: Failed to create Subscription: $SUB_ERR"
  # Clean up Publication on source
  export PGPASSWORD="$PGPASSWORD_SOURCE"
  psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" \
    -c "DROP PUBLICATION IF EXISTS ${PUB_NAME}" 2>/dev/null || true
  for i in $(seq 0 $((TABLE_COUNT - 1))); do
    TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
    callback "$TASK_ID" "FAILED" 0 "Failed to create Subscription: $SUB_ERR"
  done
  exit 1
fi
echo "  Subscription created: $SUB_NAME"

echo ""
echo "--- Step 5: Wait for initial data copy to complete ---"
MAX_WAIT=3600  # 1 hour max
ELAPSED=0
POLL_INTERVAL=10

while [ $ELAPSED -lt $MAX_WAIT ]; do
  # Query pg_subscription_rel for table sync states
  # srsubstate: i=initialize, d=data-copy, f=finished-copy, s=synchronized, r=ready
  NOT_READY=$(PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" -t -A \
    -c "SELECT count(*) FROM pg_subscription_rel sr JOIN pg_subscription s ON sr.srsubid = s.oid WHERE s.subname = '${SUB_NAME}' AND sr.srsubstate NOT IN ('r', 's')" 2>/dev/null || echo "unknown")
  NOT_READY=$(echo "$NOT_READY" | tr -d '[:space:]')

  if [ "$NOT_READY" = "0" ]; then
    echo "  All tables synchronized!"
    break
  fi

  echo "  Waiting... ($NOT_READY tables still syncing, ${ELAPSED}s elapsed)"
  sleep $POLL_INTERVAL
  ELAPSED=$((ELAPSED + POLL_INTERVAL))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
  echo "WARNING: Initial sync did not complete within ${MAX_WAIT}s, continuing anyway (sync will catch up)"
fi

echo ""
echo "--- Step 6: Report success ---"
# Report all table tasks as SYNCING (ongoing sync established)
for i in $(seq 0 $((TABLE_COUNT - 1))); do
  TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
  SCHEMA=$(jq -r ".tables[$i].schema" $CONFIG)
  TABLE=$(jq -r ".tables[$i].table" $CONFIG)
  # Get current row count on target
  ROW_COUNT=$(PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" -t -A \
    -c "SELECT count(*) FROM \"${SCHEMA}\".\"${TABLE}\"" 2>/dev/null || echo "0")
  ROW_COUNT=$(echo "$ROW_COUNT" | tr -d '[:space:]')
  [ -z "$ROW_COUNT" ] && ROW_COUNT=0
  callback "$TASK_ID" "SYNCING" "$ROW_COUNT"
  echo "  $SCHEMA.$TABLE: syncing ($ROW_COUNT rows)"
done

echo ""
echo "=== Sync setup complete. Logical replication is now active. ==="
