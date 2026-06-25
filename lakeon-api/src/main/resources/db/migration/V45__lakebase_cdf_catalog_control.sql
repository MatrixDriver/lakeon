CREATE TABLE IF NOT EXISTS lakebase_cdf_streams (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    database_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(128) NOT NULL,
    source_schema VARCHAR(128) NOT NULL,
    source_table VARCHAR(128) NOT NULL,
    target_namespace VARCHAR(128) NOT NULL,
    target_table VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'APPEND_CHANGELOG',
    status VARCHAR(32) NOT NULL DEFAULT 'PAUSED',
    slot_name VARCHAR(128) NOT NULL,
    publication_name VARCHAR(128) NOT NULL,
    export_status VARCHAR(32) NOT NULL DEFAULT 'NOT_MATERIALIZED',
    backfill_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    backfill_lsn VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_lakebase_cdf_stream_target
        UNIQUE (tenant_id, database_id, branch_id, target_namespace, target_table)
);

CREATE INDEX IF NOT EXISTS idx_lakebase_cdf_streams_tenant_db
    ON lakebase_cdf_streams(tenant_id, database_id);
