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
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops);
"""

def write_chunks(connstr, document_id, chunks, embeddings):
    conn = psycopg2.connect(connstr)
    try:
        conn.autocommit = False
        with conn.cursor() as cur:
            cur.execute(SETUP_SQL)
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
            values = []
            for chunk, embedding in zip(chunks, embeddings):
                values.append((
                    document_id, chunk["chunk_index"], chunk["content"],
                    str(embedding), json.dumps(chunk["metadata"]),
                ))
            execute_values(
                cur,
                """INSERT INTO knowledge_chunks (document_id, chunk_index, content, embedding, metadata)
                   VALUES %s""",
                values,
                template="(%s, %s, %s, %s::vector, %s::jsonb)",
            )
            conn.commit()
            logger.info(f"Wrote {len(values)} chunks for document {document_id}")
    except Exception:
        conn.rollback()
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
                conn.commit()
        except Exception:
            pass
        raise
    finally:
        conn.close()

def delete_chunks(connstr, document_id):
    conn = psycopg2.connect(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
            conn.commit()
    finally:
        conn.close()
