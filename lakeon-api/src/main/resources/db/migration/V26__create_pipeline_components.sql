CREATE TABLE pipeline_components (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    category VARCHAR(20) NOT NULL,
    data_type VARCHAR(20) NOT NULL,
    description TEXT,
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_comp_tenant ON pipeline_components(tenant_id);
CREATE INDEX idx_comp_category ON pipeline_components(category);
CREATE INDEX idx_comp_data_type ON pipeline_components(data_type);

CREATE TABLE pipeline_component_versions (
    id VARCHAR(64) PRIMARY KEY,
    component_id VARCHAR(64) NOT NULL REFERENCES pipeline_components(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    entrypoint VARCHAR(256) NOT NULL,
    params_schema TEXT,
    input_schema TEXT,
    output_schema TEXT,
    output_branches TEXT,
    requires_gpu BOOLEAN DEFAULT FALSE,
    requires_model VARCHAR(128),
    execution_mode VARCHAR(20) DEFAULT 'FUNCTION',
    status VARCHAR(16) DEFAULT 'DRAFT',
    changelog TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(component_id, version)
);
CREATE INDEX idx_compv_component ON pipeline_component_versions(component_id);
CREATE INDEX idx_compv_status ON pipeline_component_versions(status);
