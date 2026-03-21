-- Change spec column from JSONB to TEXT for compatibility with JPA string mapping
ALTER TABLE datalake_jobs ALTER COLUMN spec TYPE TEXT USING spec::TEXT;
