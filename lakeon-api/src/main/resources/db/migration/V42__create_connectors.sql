CREATE TABLE IF NOT EXISTS connectors (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
    config_json TEXT NOT NULL,
    encrypted_secret_json TEXT,
    last_tested_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_connectors_tenant_type ON connectors (tenant_id, type);
CREATE INDEX IF NOT EXISTS idx_connectors_tenant_updated ON connectors (tenant_id, updated_at DESC);

ALTER TABLE import_tasks ADD COLUMN IF NOT EXISTS connector_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_import_tasks_connector_id ON import_tasks (connector_id);
