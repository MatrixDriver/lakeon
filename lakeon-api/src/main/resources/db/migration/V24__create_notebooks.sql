CREATE TABLE notebooks (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    image       VARCHAR(32) NOT NULL DEFAULT 'python-data',
    dataset_ids TEXT,
    obs_path    VARCHAR(512),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebooks_tenant ON notebooks(tenant_id);
