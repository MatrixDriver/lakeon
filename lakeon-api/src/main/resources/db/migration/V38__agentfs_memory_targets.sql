-- Maps tenant → their AgentFS-derivation target memory_base.
-- Parallel to agentfs_assignments (which maps tenant → agentfs database).
-- Separate table (not a column on memory_bases) keeps memory_bases schema
-- free of AgentFS-specific concerns.

CREATE TABLE IF NOT EXISTS agentfs_memory_targets (
    tenant_id       VARCHAR(32) PRIMARY KEY,
    memory_base_id  VARCHAR(32) NOT NULL,
    auto_created    BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agentfs_memory_targets_base
    ON agentfs_memory_targets(memory_base_id);
