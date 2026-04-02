ALTER TABLE documents ADD COLUMN IF NOT EXISTS folder VARCHAR(512) DEFAULT '';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb;
CREATE INDEX IF NOT EXISTS idx_documents_folder ON documents (kb_id, folder);
CREATE INDEX IF NOT EXISTS idx_documents_metadata ON documents USING GIN (metadata);
