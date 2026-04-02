-- V31: Add FILE_UPLOAD source type for datasets and file_count column

-- Add file_count column
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS file_count INTEGER;

-- Drop old check constraint (if exists) and recreate with FILE_UPLOAD
ALTER TABLE datasets DROP CONSTRAINT IF EXISTS datasets_source_type_check;
ALTER TABLE datasets ADD CONSTRAINT datasets_source_type_check
    CHECK (source_type IN ('DB_EXPORT', 'JOB_OUTPUT', 'PIPELINE_OUTPUT', 'FILE_UPLOAD'));
