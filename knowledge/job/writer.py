"""Write chunks to user's PostgreSQL database."""
import json
import logging
from typing import List, Dict, Any
import psycopg2
from psycopg2.extras import execute_values

logger = logging.getLogger(__name__)

SETUP_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id SERIAL PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata JSONB,
    char_offset_start INT,
    char_offset_end INT,
    char_count INT,
    overlap_prev INT DEFAULT 0,
    page_start INT,
    page_end INT,
    bbox JSONB,
    level SMALLINT DEFAULT 0,
    source_chunks INT[],
    edited BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops);

-- BM25 index for hybrid search (pg_search / ParadeDB)
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_chunks_bm25') THEN
        CREATE INDEX idx_chunks_bm25 ON knowledge_chunks
            USING bm25 (id, content)
            WITH (key_field = 'id');
    END IF;
END $$;
"""

MIGRATE_SQL = """
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_offset_start INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_offset_end INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_count INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS overlap_prev INT DEFAULT 0;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS page_start INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS page_end INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS bbox JSONB;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS level SMALLINT DEFAULT 0;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS source_chunks INT[];
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS edited BOOLEAN DEFAULT FALSE;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
"""

def _connect_with_retry(connstr, max_retries=5, delay=3):
    """Connect to PostgreSQL with retries (compute may be waking up)."""
    import time
    for attempt in range(max_retries):
        try:
            return psycopg2.connect(connstr, connect_timeout=30)
        except psycopg2.OperationalError as e:
            if attempt < max_retries - 1:
                logger.warning(f"DB connect attempt {attempt+1}/{max_retries} failed: {e}, retrying in {delay}s")
                time.sleep(delay)
            else:
                raise

def write_chunks(connstr, document_id, chunks, embeddings):
    conn = _connect_with_retry(connstr)
    try:
        conn.autocommit = False
        with conn.cursor() as cur:
            cur.execute(SETUP_SQL)
            cur.execute(MIGRATE_SQL)
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
            values = []
            for chunk, embedding in zip(chunks, embeddings):
                values.append((
                    document_id, chunk["chunk_index"], chunk["content"],
                    str(embedding), json.dumps(chunk["metadata"]),
                    chunk.get("char_offset_start"),
                    chunk.get("char_offset_end"),
                    chunk.get("char_count"),
                    chunk.get("overlap_prev", 0),
                    chunk.get("page_start"),
                    chunk.get("page_end"),
                    json.dumps(chunk["bbox"]) if chunk.get("bbox") is not None else None,
                    chunk.get("level", 0),
                    chunk.get("source_chunks"),
                    chunk.get("edited", False),
                    chunk.get("updated_at"),
                ))
            execute_values(
                cur,
                """INSERT INTO knowledge_chunks
                   (document_id, chunk_index, content, embedding, metadata,
                    char_offset_start, char_offset_end, char_count, overlap_prev,
                    page_start, page_end,
                    bbox, level, source_chunks, edited, updated_at)
                   VALUES %s""",
                values,
                template="(%s, %s, %s, %s::vector, %s::jsonb, %s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s)",
            )
            conn.commit()
            logger.info(f"Wrote {len(values)} chunks for document {document_id}")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

def delete_chunks(connstr, document_id):
    conn = _connect_with_retry(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
            conn.commit()
    finally:
        conn.close()
