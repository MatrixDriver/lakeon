-- AgentFS now lives in a per-tenant Lakebase database (auto-provisioned).
-- This table maps tenant → their AgentFS database.
--
-- The original V36 agent_files table in this metadata DB is kept for now
-- (legacy / temporary fallback) but new writes go to the per-tenant DB.

CREATE TABLE agentfs_assignments (
    tenant_id   VARCHAR(32)  PRIMARY KEY,
    database_id VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PROVISIONING',
                                          -- PROVISIONING / READY / ERROR
    error       TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ready_at    TIMESTAMPTZ
);

CREATE INDEX idx_agentfs_assignments_db ON agentfs_assignments(database_id);
