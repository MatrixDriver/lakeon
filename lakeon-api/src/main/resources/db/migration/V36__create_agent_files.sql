-- AgentFS: POSIX-like file store for AI agent working directories.
--
-- One store per tenant (implicit). Paths are absolute within the store
-- (e.g. "/CLAUDE.md", "/memory/user.md", "/projects/X/session.jsonl").
--
-- No embedding / no BM25 here. Semantic Memory is derived asynchronously
-- from changes to this table (CDC consumers).

CREATE TABLE agent_files (
    tenant_id   VARCHAR(32)  NOT NULL,
    path        TEXT         NOT NULL,
    kind        VARCHAR(8)   NOT NULL,            -- 'file' | 'dir'
    size        BIGINT       NOT NULL DEFAULT 0,
    mtime_ns    BIGINT       NOT NULL,
    etag        VARCHAR(64)  NOT NULL,            -- sha256 of (data) or random for dirs
    properties  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    data        BYTEA,                            -- NULL for dirs
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, path)
);

-- Prefix scans: list by parent dir
CREATE INDEX idx_agent_files_tenant_path ON agent_files(tenant_id, path text_pattern_ops);

-- Quick lookups by kind (stats)
CREATE INDEX idx_agent_files_tenant_kind ON agent_files(tenant_id, kind);

-- Enforce a directory parent exists? Skip for simplicity — rely on service layer.
