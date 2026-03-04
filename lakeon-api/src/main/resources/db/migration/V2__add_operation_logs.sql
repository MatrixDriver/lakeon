-- V2: Add operation_logs table for tracking database operation history

CREATE TABLE IF NOT EXISTS operation_logs (
    id VARCHAR(64) PRIMARY KEY,
    database_id VARCHAR(64) NOT NULL REFERENCES database_instances(id),
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    database_name VARCHAR(255) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    error_message VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_operation_logs_database_id ON operation_logs(database_id);
CREATE INDEX IF NOT EXISTS idx_operation_logs_tenant_id ON operation_logs(tenant_id);
