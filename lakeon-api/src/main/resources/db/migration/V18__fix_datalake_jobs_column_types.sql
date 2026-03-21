-- Fix column types for datalake_jobs that Hibernate ddl-auto:update may have
-- created as VARCHAR(255) instead of TEXT when the entity was added before the
-- Flyway V16 migration existed.
ALTER TABLE datalake_jobs ALTER COLUMN error_message TYPE TEXT;
ALTER TABLE datalake_jobs ALTER COLUMN spec TYPE TEXT USING spec::TEXT;
