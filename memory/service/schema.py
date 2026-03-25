import psycopg2
import time

SCHEMA_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS memories (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    memory_type VARCHAR(20) NOT NULL,
    importance FLOAT DEFAULT 0.5,
    access_count INT DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    embedding vector(1024),
    metadata JSONB DEFAULT '{}',
    event_time TIMESTAMPTZ,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS traits (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    trait_stage VARCHAR(20) DEFAULT 'trend',
    trait_subtype VARCHAR(20),
    confidence FLOAT DEFAULT 0.0,
    reinforcement_count INT DEFAULT 0,
    contradiction_count INT DEFAULT 0,
    context TEXT,
    evidence_ids INT[],
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS graph_nodes (
    id SERIAL PRIMARY KEY,
    node_type VARCHAR(50) NOT NULL,
    node_id VARCHAR(200) NOT NULL,
    properties JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(node_type, node_id)
);

CREATE TABLE IF NOT EXISTS graph_edges (
    id SERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    edge_type VARCHAR(100) NOT NULL,
    properties JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS raw_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reflection_watermark (
    id              SERIAL PRIMARY KEY,
    last_reflected  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_embedding ON memories USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_traits_stage ON traits(trait_stage);
CREATE INDEX IF NOT EXISTS idx_graph_edges_source ON graph_edges(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_graph_edges_target ON graph_edges(target_type, target_id);
"""


def init_schema(connstr: str, retries: int = 10, delay: float = 3.0):
    for attempt in range(retries):
        try:
            conn = psycopg2.connect(connstr, connect_timeout=30)
            conn.autocommit = True
            with conn.cursor() as cur:
                cur.execute(SCHEMA_SQL)
                cur.execute("""
                    ALTER TABLE memories DROP CONSTRAINT IF EXISTS memories_memory_type_check;
                    ALTER TABLE memories ADD CONSTRAINT memories_memory_type_check
                      CHECK (memory_type IN ('fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'));
                """)
            conn.close()
            return
        except psycopg2.OperationalError:
            if attempt < retries - 1:
                time.sleep(delay)
            else:
                raise
