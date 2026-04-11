-- V35__wiki_run_log_agent.sql
ALTER TABLE wiki_run_logs
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tool_calls_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS token_count BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_wiki_run_logs_run_id ON wiki_run_logs(run_id);

COMMENT ON COLUMN wiki_run_logs.run_id IS 'Unique run identifier from wiki agent (ULID)';
COMMENT ON COLUMN wiki_run_logs.tool_calls_count IS 'Number of tool calls the agent made during the run';
COMMENT ON COLUMN wiki_run_logs.token_count IS 'Total LLM tokens consumed (prompt + completion)';
COMMENT ON COLUMN wiki_run_logs.source IS 'Who triggered the run: queue | mcp | manual | curate-auto';
