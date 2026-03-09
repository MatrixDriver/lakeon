-- Multi API key support: each tenant can have multiple named API keys
CREATE TABLE api_keys (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    api_key VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_api_key ON api_keys(api_key);

-- Migrate existing API keys from tenants table into api_keys table
INSERT INTO api_keys (id, tenant_id, name, api_key, created_at)
SELECT 'ak_' || substring(md5(id) from 1 for 8), id, 'Default', api_key, created_at
FROM tenants
WHERE api_key IS NOT NULL;
