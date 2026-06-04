CREATE TABLE IF NOT EXISTS agent_app (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    app_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    type VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage_schema_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE agent_task_run
    ADD COLUMN IF NOT EXISTS agent_app_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_app_tenant_created ON agent_app (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_app_tenant_key ON agent_app (tenant_id, app_key);
CREATE INDEX IF NOT EXISTS idx_agent_task_run_agent_app ON agent_task_run (agent_app_id);
