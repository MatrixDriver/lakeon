#!/bin/bash
set -uo pipefail

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
CONFLICT=$(jq -r '.conflict_strategy' $CONFIG)
TABLE_COUNT=$(jq '.tables | length' $CONFIG)

export PGPASSWORD_SOURCE=$(cat /secrets/source-password)

callback() {
  local table_task_id=$1 status=$2 row_count=${3:-0} error=${4:-}
  local payload="{\"table_task_id\":\"$table_task_id\",\"status\":\"$status\",\"row_count\":$row_count,\"error_message\":\"$(echo "$error" | head -c 500 | sed 's/"/\\"/g')\"}"
  curl -sf -X PUT "$API_URL" -H "Content-Type: application/json" -d "$payload" || echo "WARN: callback failed for $table_task_id"
}

echo "=== Lakeon PG Import ==="
echo "Source: $SOURCE_USER@$SOURCE_HOST:$SOURCE_PORT/$SOURCE_DB"
echo "Target: $TARGET_USER@$TARGET_HOST:$TARGET_PORT/$TARGET_DB"
echo "Strategy: $CONFLICT"
echo "Tables: $TABLE_COUNT"
echo ""

for i in $(seq 0 $((TABLE_COUNT - 1))); do
  TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
  SCHEMA=$(jq -r ".tables[$i].schema" $CONFIG)
  TABLE=$(jq -r ".tables[$i].table" $CONFIG)

  echo "--- [$((i+1))/$TABLE_COUNT] $SCHEMA.$TABLE ---"
  callback "$TASK_ID" "running"

  DUMP_ARGS="-h $SOURCE_HOST -p $SOURCE_PORT -U $SOURCE_USER -d $SOURCE_DB -t ${SCHEMA}.${TABLE} -Fc"
  RESTORE_ARGS="-h $TARGET_HOST -p $TARGET_PORT -U $TARGET_USER -d $TARGET_DB --no-owner --no-acl"

  if [ "$CONFLICT" = "REPLACE" ] || [ "$CONFLICT" = "replace" ]; then
    DUMP_ARGS="$DUMP_ARGS --clean --if-exists"
  else
    DUMP_ARGS="$DUMP_ARGS --data-only"
  fi

  export PGPASSWORD="$PGPASSWORD_SOURCE"
  if pg_dump $DUMP_ARGS 2>/tmp/dump_err | PGPASSWORD="$TARGET_PASSWORD" pg_restore $RESTORE_ARGS 2>/tmp/restore_err; then
    ROW_COUNT=$(PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" -t -A -c "SELECT count(*) FROM \"${SCHEMA}\".\"${TABLE}\"" 2>/dev/null || echo "0")
    ROW_COUNT=$(echo "$ROW_COUNT" | tr -d '[:space:]')
    callback "$TASK_ID" "completed" "${ROW_COUNT:-0}"
    echo "  OK: ${ROW_COUNT:-0} rows"
  else
    ERR=$(cat /tmp/dump_err /tmp/restore_err 2>/dev/null | tail -5 | tr '\n' ' ')
    callback "$TASK_ID" "failed" 0 "$ERR"
    echo "  FAILED: $ERR"
  fi
done

echo ""
echo "=== Import complete ==="
