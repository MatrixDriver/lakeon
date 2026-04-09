-- V34__create_oauth_connections.sql

-- Add email and avatar to tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);

-- OAuth provider connections
CREATE TABLE oauth_connections (
    id VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url VARCHAR(512),
    access_token VARCHAR(512),
    refresh_token VARCHAR(512),
    scope VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_oauth_provider_user UNIQUE(provider, provider_user_id)
);

CREATE INDEX idx_oauth_connections_tenant ON oauth_connections(tenant_id);
