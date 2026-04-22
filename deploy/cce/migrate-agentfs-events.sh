#!/usr/bin/env bash
# Install agentfs_events table + trigger on existing tenant AgentFS DBs.
# Idempotent via CREATE IF NOT EXISTS / CREATE OR REPLACE / DROP IF EXISTS.
#
# Usage:
#   SITE=hwstaff ./deploy/cce/migrate-agentfs-events.sh
#
# Reads agentfs_assignments + database_instances to discover each tenant's
# agentfs DB, connects via the compute IP:port with cloud_admin, applies schema.
#
# NOTE: This script passes cloud_admin credentials via `kubectl run --env=...`,
# which exposes the password in kubectl audit logs. Acceptable for a one-shot
# migration, but do NOT adapt this pattern for recurring/automated jobs —
# use a Secret-mounted Job manifest instead.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

SQL_FILE=$(mktemp)
trap 'rm -f "$SQL_FILE"' EXIT
cat > "$SQL_FILE" <<'SQL'
CREATE TABLE IF NOT EXISTS agentfs_events (
    id          BIGSERIAL PRIMARY KEY,
    path        TEXT NOT NULL,
    etag        VARCHAR(64),
    event_type  VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agentfs_events_pending
    ON agentfs_events(status, id) WHERE status = 'pending';

CREATE OR REPLACE FUNCTION agentfs_files_event_fn() RETURNS TRIGGER AS $$
BEGIN
  IF (TG_OP = 'INSERT') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'create');
  ELSIF (TG_OP = 'UPDATE') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'update');
  ELSIF (TG_OP = 'DELETE') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (OLD.path, OLD.etag, 'delete');
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS agentfs_files_event_trg ON files;
CREATE TRIGGER agentfs_files_event_trg
  AFTER INSERT OR UPDATE OR DELETE ON files
  FOR EACH ROW EXECUTE FUNCTION agentfs_files_event_fn();
SQL

# Discover READY tenant DBs via psql to metadata
TENANTS=$(KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-probe --rm -i --restart=Never --image=postgres:16 \
  --env="PGPASSWORD=$RDS_PASSWORD" -- \
  psql -h "$RDS_PRIVATE_IP" -U lakeon -d lakeon -t -A -c \
  "SELECT a.tenant_id, di.name, di.compute_host, di.compute_port
     FROM agentfs_assignments a JOIN database_instances di ON di.id = a.database_id
    WHERE a.status='READY' AND di.status IN ('RUNNING','SUSPENDED');")

total=0
ok_count=0
fail_count=0
while IFS='|' read -r tenant_id db_name host port; do
  [ -z "$tenant_id" ] && continue
  total=$((total+1))
  port=${port:-55433}
  pod_name="psql-migrate-${tenant_id}-$(date +%s)"
  echo ">>> $tenant_id → $db_name @ $host:$port (pod=$pod_name)"
  if KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run "$pod_name" \
    --rm -i --restart=Never --image=postgres:16 \
    --env="PGPASSWORD=cloud-admin-internal" -- \
    psql -h "$host" -p "$port" -U cloud_admin -d "$db_name" < "$SQL_FILE"; then
    ok_count=$((ok_count+1))
  else
    echo "!!! FAILED: $tenant_id"
    fail_count=$((fail_count+1))
  fi
done < <(echo "$TENANTS")
echo "=== summary: total=$total ok=$ok_count fail=$fail_count ==="
[ "$fail_count" -gt 0 ] && exit 1 || exit 0
