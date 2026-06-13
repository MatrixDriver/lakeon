CREATE TABLE IF NOT EXISTS agentfs_folders (
    id                  VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    directory_kind      VARCHAR(32) NOT NULL,
    storage_policy      VARCHAR(32) NOT NULL,
    processing_profile  VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_agentfs_folders_tenant_name UNIQUE (tenant_id, display_name)
);

CREATE INDEX IF NOT EXISTS idx_agentfs_folders_tenant
    ON agentfs_folders(tenant_id);

CREATE INDEX IF NOT EXISTS idx_agentfs_folders_processing
    ON agentfs_folders(processing_profile);
