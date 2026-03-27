CREATE TABLE notebook_sessions (
    id             VARCHAR(64) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'STARTING',
    pod_name       VARCHAR(128),
    namespace      VARCHAR(128),
    image          VARCHAR(256),
    dataset_ids    TEXT,
    last_active_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebook_sessions_tenant ON notebook_sessions(tenant_id);
CREATE INDEX idx_notebook_sessions_status ON notebook_sessions(status);
