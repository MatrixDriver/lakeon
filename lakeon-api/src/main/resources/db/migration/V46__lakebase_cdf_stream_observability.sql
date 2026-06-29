ALTER TABLE lakebase_cdf_streams
    ADD COLUMN IF NOT EXISTS last_commit_lsn VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_snapshot_id BIGINT,
    ADD COLUMN IF NOT EXISTS observed_lag_ms BIGINT;
