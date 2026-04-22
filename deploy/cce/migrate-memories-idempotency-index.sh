#!/usr/bin/env bash
# Add idx_memories_source_idempotent UNIQUE index on every existing
# memory_base DB. Enables INSERT ON CONFLICT DO NOTHING for AgentFS
# derivation flow. Idempotent via IF NOT EXISTS.
#
# Usage:
#   SITE=hwstaff ./deploy/cce/migrate-memories-idempotency-index.sh
#
# NOTE: passes DB password via --env=PGPASSWORD=... to kubectl run.
# This leaks to kubectl audit logs. For recurring use, switch to
# Secret-mounted Job.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

SQL="CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_source_idempotent
       ON memories ((metadata->>'source_path'), (metadata->>'source_etag'))
       WHERE metadata ? 'source_path';"

BASES=$(KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-probe --rm -i --restart=Never --image=postgres:16 \
  --env="PGPASSWORD=$RDS_PASSWORD" -- \
  psql -h "$RDS_PRIVATE_IP" -U lakeon -d lakeon -t -A -c \
  "SELECT mb.id, di.name, di.compute_host, di.compute_port
     FROM memory_bases mb JOIN database_instances di ON di.id = mb.database_id
    WHERE mb.status='READY';")

total=0; ok_count=0; fail_count=0
while IFS='|' read -r base_id db_name host port; do
  [ -z "$base_id" ] && continue
  total=$((total+1))
  port=${port:-55433}
  pod_name="psql-idx-${base_id}-$(date +%s)"
  echo ">>> base $base_id → $db_name @ $host:$port (pod=$pod_name)"
  if KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run "$pod_name" \
    --rm -i --restart=Never --image=postgres:16 \
    --env="PGPASSWORD=cloud-admin-internal" -- \
    psql -h "$host" -p "$port" -U cloud_admin -d "$db_name" -c "$SQL"; then
    ok_count=$((ok_count+1))
  else
    echo "!!! FAILED: base $base_id"
    fail_count=$((fail_count+1))
  fi
done < <(echo "$BASES")
echo "=== summary: total=$total ok=$ok_count fail=$fail_count ==="
[ "$fail_count" -gt 0 ] && exit 1 || exit 0
