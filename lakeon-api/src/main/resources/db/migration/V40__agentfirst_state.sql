CREATE TABLE IF NOT EXISTS agent_task_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    goal TEXT NOT NULL,
    harness_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_stage_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    stage_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    branch_id VARCHAR(64),
    context_pack_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_workspace (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_workspace_branch (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    parent_branch_id VARCHAR(64),
    stage_run_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    hypothesis TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS context_node (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    type VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    source_ref VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS context_edge (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    from_node_id VARCHAR(64) NOT NULL,
    to_node_id VARCHAR(64) NOT NULL,
    edge_type VARCHAR(64) NOT NULL,
    confidence DOUBLE PRECISION,
    properties_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS context_snapshot (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64),
    branch_id VARCHAR(64),
    source_versions_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS context_pack (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    stage_run_id VARCHAR(64) NOT NULL,
    selected_nodes_json TEXT,
    permission_filter_json JSONB,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_checkpoint (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64) NOT NULL,
    stage_run_id VARCHAR(64),
    manifest_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_workspace_manifest (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    manifest_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_state_commit (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    stage_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64) NOT NULL,
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_artifact_ref (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    stage_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    ref_uri VARCHAR(1024),
    metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_lineage_edge (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    stage_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64) NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    from_ref VARCHAR(512),
    to_ref VARCHAR(512),
    edge_type VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_evidence_packet (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    claim TEXT,
    status VARCHAR(32) NOT NULL,
    evidence_refs_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_verifier_result (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    evidence_packet_id VARCHAR(64) NOT NULL,
    verifier VARCHAR(128) NOT NULL,
    result VARCHAR(32) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_metric_record (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    value DOUBLE PRECISION,
    unit VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_policy_decision (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    action VARCHAR(128) NOT NULL,
    allowed BOOLEAN NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_budget_ledger (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    cost_type VARCHAR(64) NOT NULL,
    estimate DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_approval_record (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    action VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_audit_event (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(64),
    action VARCHAR(128) NOT NULL,
    result VARCHAR(64) NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_evaluation_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64),
    baseline VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    metrics_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_fault_injection_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64),
    fault_type VARCHAR(128) NOT NULL,
    result VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_ops_metric (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_run_id VARCHAR(64),
    name VARCHAR(128) NOT NULL,
    value DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_task_run_tenant ON agent_task_run (tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_stage_run_task ON agent_stage_run (task_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_workspace_task ON agent_workspace (task_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_workspace_branch_workspace ON agent_workspace_branch (workspace_id);
CREATE INDEX IF NOT EXISTS idx_context_node_tenant ON context_node (tenant_id);
CREATE INDEX IF NOT EXISTS idx_context_pack_task_stage ON context_pack (task_run_id, stage_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_state_commit_branch ON agent_state_commit (branch_id);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_ref_task_branch ON agent_artifact_ref (task_run_id, branch_id);
CREATE INDEX IF NOT EXISTS idx_agent_lineage_edge_artifact ON agent_lineage_edge (artifact_id);
CREATE INDEX IF NOT EXISTS idx_agent_evidence_packet_task ON agent_evidence_packet (task_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_policy_decision_task ON agent_policy_decision (task_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_audit_event_task ON agent_audit_event (task_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_evaluation_run_task ON agent_evaluation_run (task_run_id);
