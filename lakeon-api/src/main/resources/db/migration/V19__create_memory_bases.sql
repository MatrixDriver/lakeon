CREATE TABLE memory_bases (
    id              VARCHAR(32)  PRIMARY KEY,
    tenant_id       VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    type            VARCHAR(16)  NOT NULL DEFAULT 'BUILTIN',
    database_id     VARCHAR(32),
    db_password     VARCHAR(256),
    status          VARCHAR(32)  NOT NULL DEFAULT 'CREATING',
    memory_count    INT          DEFAULT 0,
    trait_count     INT          DEFAULT 0,
    embedding_model VARCHAR(128),
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_memory_bases_tenant_id ON memory_bases(tenant_id);
CREATE INDEX idx_memory_bases_status ON memory_bases(status);
