-- V25__dataset_versioning.sql

-- 扩展 datasets 表
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS source_type VARCHAR(20) DEFAULT 'DB_EXPORT';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS latest_version INTEGER DEFAULT 1;

-- 新建 dataset_versions 表
CREATE TABLE dataset_versions (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    format VARCHAR(16) NOT NULL DEFAULT 'PARQUET',
    obs_path VARCHAR(512),
    row_count BIGINT,
    file_size BIGINT,
    schema_json TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATING',
    source_pipeline_run_id VARCHAR(64),
    source_job_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(dataset_id, version)
);
CREATE INDEX idx_dsv_dataset ON dataset_versions(dataset_id);
CREATE INDEX idx_dsv_status ON dataset_versions(status);

-- 为现有数据集创建 v1 版本记录
INSERT INTO dataset_versions (id, dataset_id, version, format, obs_path, row_count, file_size, schema_json, status, source_job_id, created_at)
SELECT
    'dsv_' || substring(replace(gen_random_uuid()::text, '-', '') from 1 for 12),
    id, 1, 'PARQUET', obs_path, row_count, file_size, schema_json, 'READY', job_id, created_at
FROM datasets
WHERE obs_path IS NOT NULL;
