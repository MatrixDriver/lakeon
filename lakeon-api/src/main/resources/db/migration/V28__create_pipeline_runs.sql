CREATE TABLE pipeline_runs (
    id VARCHAR(64) PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id),
    pipeline_version INTEGER NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    input_dataset_id VARCHAR(64),
    input_dataset_version INTEGER,
    output_dataset_version_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_run_pipeline ON pipeline_runs(pipeline_id);
CREATE INDEX idx_run_tenant ON pipeline_runs(tenant_id);
CREATE INDEX idx_run_status ON pipeline_runs(status);

CREATE TABLE pipeline_step_runs (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    step_id VARCHAR(128) NOT NULL,
    component_id VARCHAR(64),
    component_version INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    input_ref TEXT,
    output_ref TEXT,
    checkpoint_path VARCHAR(512),
    metrics TEXT,
    error TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sr_run ON pipeline_step_runs(run_id);
CREATE INDEX idx_sr_status ON pipeline_step_runs(status);
