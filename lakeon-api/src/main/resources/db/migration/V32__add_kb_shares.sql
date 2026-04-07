CREATE TABLE kb_shares (
    id VARCHAR(32) PRIMARY KEY,
    kb_id VARCHAR(32) NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    invited_by VARCHAR(64) NOT NULL REFERENCES tenants(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(kb_id, tenant_id)
);

CREATE INDEX idx_kb_shares_tenant ON kb_shares(tenant_id);
CREATE INDEX idx_kb_shares_kb ON kb_shares(kb_id);
