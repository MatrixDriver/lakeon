ALTER TABLE agent_workspace
    ADD COLUMN IF NOT EXISTS database_id VARCHAR(64);

ALTER TABLE agent_workspace
    ADD COLUMN IF NOT EXISTS root_branch_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_workspace_database
    ON agent_workspace(database_id);
