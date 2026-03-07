-- Schema cache table for storing database structure metadata
CREATE TABLE IF NOT EXISTS schema_cache (
    id BIGSERIAL PRIMARY KEY,
    database_id VARCHAR(64) NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(128),
    table_type VARCHAR(32),
    columns_json TEXT,
    row_count BIGINT,
    table_size_bytes BIGINT,
    last_updated TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_schema_cache UNIQUE (database_id, schema_name, table_name)
);

CREATE INDEX idx_schema_cache_database_id ON schema_cache(database_id);
CREATE INDEX idx_schema_cache_last_updated ON schema_cache(database_id, last_updated);
