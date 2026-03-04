-- V1: Initial schema for LakeOn Serverless PostgreSQL

CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    api_key VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS database_instances (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    neon_tenant_id VARCHAR(64),
    neon_timeline_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
    compute_size VARCHAR(16) NOT NULL DEFAULT '1cu',
    suspend_timeout VARCHAR(16) NOT NULL DEFAULT '5m',
    storage_limit_gb INTEGER NOT NULL DEFAULT 10,
    db_user VARCHAR(64),
    db_password VARCHAR(256),
    compute_pod_name VARCHAR(128),
    compute_host VARCHAR(256),
    compute_port INTEGER,
    connection_uri VARCHAR(512),
    last_active_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE TABLE IF NOT EXISTS branches (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    database_id VARCHAR(64) NOT NULL REFERENCES database_instances(id),
    neon_timeline_id VARCHAR(64),
    parent_branch_id VARCHAR(64),
    parent_branch_name VARCHAR(255),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
    compute_status VARCHAR(32),
    compute_pod_name VARCHAR(128),
    compute_host VARCHAR(256),
    compute_port INTEGER,
    connection_uri VARCHAR(512),
    last_active_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(database_id, name)
);

CREATE INDEX IF NOT EXISTS idx_branches_neon_timeline ON branches(neon_timeline_id);
CREATE INDEX IF NOT EXISTS idx_instances_neon_tenant ON database_instances(neon_tenant_id);
CREATE INDEX IF NOT EXISTS idx_instances_tenant ON database_instances(tenant_id);
