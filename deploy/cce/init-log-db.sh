#!/usr/bin/env bash
set -euo pipefail

LOG_DB_URI="${LOG_DB_URI:?Set LOG_DB_URI to dbay-logs connection string}"

psql "$LOG_DB_URI" <<'SQL'
CREATE TABLE IF NOT EXISTS logs (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL,
    level       VARCHAR(8) NOT NULL,
    component   VARCHAR(32) NOT NULL,
    request_id  VARCHAR(32),
    tenant_id   VARCHAR(64),
    db_id       VARCHAR(64),
    logger      VARCHAR(128),
    msg         TEXT NOT NULL,
    duration_ms INTEGER,
    extra       JSONB,
    thread      VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_logs_ts ON logs (ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_request_id ON logs (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_logs_tenant_ts ON logs (tenant_id, ts DESC) WHERE tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_logs_level_ts ON logs (level, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_component_ts ON logs (component, ts DESC);
CREATE INDEX IF NOT EXISTS idx_logs_msg_tsvector ON logs USING GIN (to_tsvector('simple', msg));
SQL

echo "✓ dbay-logs schema initialized"
