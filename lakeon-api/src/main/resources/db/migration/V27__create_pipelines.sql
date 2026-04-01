CREATE TABLE pipelines (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    data_type VARCHAR(20),
    is_template BOOLEAN DEFAULT FALSE,
    source_template_id VARCHAR(64),
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pipe_tenant ON pipelines(tenant_id);
CREATE INDEX idx_pipe_template ON pipelines(is_template);

CREATE TABLE pipeline_versions (
    id VARCHAR(64) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    dag_yaml TEXT NOT NULL,
    status VARCHAR(16) DEFAULT 'DRAFT',
    changelog TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(pipeline_id, version)
);
CREATE INDEX idx_pipev_pipeline ON pipeline_versions(pipeline_id);
