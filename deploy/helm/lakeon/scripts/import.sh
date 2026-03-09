#!/bin/bash
set -uo pipefail

# Install required tools (compute-node image is Debian but lacks jq/curl)
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
CONFLICT=$(jq -r '.conflict_strategy' $CONFIG)
TABLE_COUNT=$(jq '.tables | length' $CONFIG)

export PGPASSWORD_SOURCE=$(cat /secrets/source-password)

callback() {
  local table_task_id=$1 status=$2 row_count=${3:-0} error=${4:-}
  local payload="{\"table_task_id\":\"$table_task_id\",\"status\":\"$status\",\"row_count\":$row_count,\"error_message\":\"$(echo "$error" | head -c 500 | sed 's/"/\\"/g')\"}"
  curl -skf -X PUT "$API_URL" -H "Content-Type: application/json" -d "$payload" || echo "WARN: callback failed for $table_task_id"
}

echo "=== Lakeon PG Import ==="
echo "Source: $SOURCE_USER@$SOURCE_HOST:$SOURCE_PORT/$SOURCE_DB"
echo "Target: $TARGET_USER@$TARGET_HOST:$TARGET_PORT/$TARGET_DB"
echo "Strategy: $CONFLICT"
echo "Tables: $TABLE_COUNT"
echo ""

# Background keepalive: prevent target compute from auto-suspending during import
(while true; do
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "SELECT 1" >/dev/null 2>&1
  sleep 60
done) &
KEEPALIVE_PID=$!
trap "kill $KEEPALIVE_PID 2>/dev/null" EXIT

# Pre-create extensions from source on target (skip internal/unavailable ones gracefully)
echo "--- Syncing extensions ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
EXTENSIONS=$(psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" -t -A \
  -c "SELECT extname FROM pg_extension WHERE extname != 'plpgsql'" 2>/dev/null || true)
for EXT in $EXTENSIONS; do
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "CREATE EXTENSION IF NOT EXISTS \"${EXT}\" CASCADE" 2>/dev/null && echo "  extension: $EXT" || echo "  extension: $EXT (skipped, not available)"
done
echo ""

# Pre-create custom enum types from source on target
echo "--- Syncing custom types ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" -t -A -F $'\t' -c "
SELECT n.nspname, t.typname,
  string_agg(quote_literal(e.enumlabel), ', ' ORDER BY e.enumsortorder)
FROM pg_type t
JOIN pg_namespace n ON t.typnamespace = n.oid
JOIN pg_enum e ON e.enumtypid = t.oid
WHERE n.nspname NOT IN ('pg_catalog','information_schema')
GROUP BY n.nspname, t.typname
ORDER BY n.nspname, t.typname
" 2>/dev/null | while IFS=$'\t' read -r SCHEMA TNAME VALS; do
  [ -z "$TNAME" ] && continue
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "DO \$\$ BEGIN CREATE TYPE \"${SCHEMA}\".\"${TNAME}\" AS ENUM (${VALS}); EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;" \
    2>/dev/null && echo "  type: ${SCHEMA}.${TNAME}" || true
done
echo ""

# Pre-create all user schemas from source database in target
echo "--- Syncing schemas ---"
export PGPASSWORD="$PGPASSWORD_SOURCE"
SCHEMAS=$(psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_USER" -d "$SOURCE_DB" -t -A \
  -c "SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('pg_catalog','information_schema','pg_toast') AND nspname NOT LIKE 'pg_temp_%' AND nspname NOT LIKE 'pg_toast_temp_%'" 2>/dev/null || true)
for S in $SCHEMAS; do
  PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
    -c "CREATE SCHEMA IF NOT EXISTS \"${S}\"" 2>/dev/null && echo "  schema: $S" || true
done
echo ""

for i in $(seq 0 $((TABLE_COUNT - 1))); do
  TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
  SCHEMA=$(jq -r ".tables[$i].schema" $CONFIG)
  TABLE=$(jq -r ".tables[$i].table" $CONFIG)

  echo "--- [$((i+1))/$TABLE_COUNT] $SCHEMA.$TABLE ---"
  callback "$TASK_ID" "RUNNING"

  # Always dump full schema+data in custom format
  DUMP_ARGS="-h $SOURCE_HOST -p $SOURCE_PORT -U $SOURCE_USER -d $SOURCE_DB -t ${SCHEMA}.${TABLE} -Fc"
  RESTORE_ARGS="-h $TARGET_HOST -p $TARGET_PORT -U $TARGET_USER -d $TARGET_DB --no-owner --no-acl --section=pre-data --section=data"

  if [ "$CONFLICT" = "REPLACE" ] || [ "$CONFLICT" = "replace" ]; then
    # REPLACE: explicitly drop table first (CASCADE handles FK deps),
    # then pg_restore creates it fresh. Don't use --clean (breaks with per-table dumps).
    PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
      -c "DROP TABLE IF EXISTS \"${SCHEMA}\".\"${TABLE}\" CASCADE" 2>/dev/null || true
  fi
  # APPEND: no drop, pg_restore will create table if not exists;
  # if table already exists, CREATE will error but COPY still succeeds.

  export PGPASSWORD="$PGPASSWORD_SOURCE"
  pg_dump $DUMP_ARGS 2>/tmp/dump_err | PGPASSWORD="$TARGET_PASSWORD" pg_restore $RESTORE_ARGS 2>/tmp/restore_err
  RESTORE_EXIT=$?

  # Check for real dump errors (not restore warnings)
  DUMP_ERR=$(cat /tmp/dump_err 2>/dev/null | grep -i "error" || true)
  if [ -n "$DUMP_ERR" ]; then
    callback "$TASK_ID" "FAILED" 0 "$DUMP_ERR"
    echo "  FAILED (dump): $DUMP_ERR"
    continue
  fi

  # Verify by counting rows in target — this is the real success check
  ROW_COUNT=$(PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" -t -A \
    -c "SELECT count(*) FROM \"${SCHEMA}\".\"${TABLE}\"" 2>/dev/null || echo "")
  ROW_COUNT=$(echo "$ROW_COUNT" | tr -d '[:space:]')

  if [ -n "$ROW_COUNT" ] && [ "$ROW_COUNT" != "" ]; then
    callback "$TASK_ID" "COMPLETED" "${ROW_COUNT}"
    echo "  OK: ${ROW_COUNT} rows"
  else
    ERR=$(cat /tmp/dump_err /tmp/restore_err 2>/dev/null | grep -v "^$" | tail -5 | tr '\n' ' ')
    callback "$TASK_ID" "FAILED" 0 "${ERR:-unknown error}"
    echo "  FAILED: ${ERR:-unknown error}"
  fi
done

echo ""
echo "=== Import complete ==="
