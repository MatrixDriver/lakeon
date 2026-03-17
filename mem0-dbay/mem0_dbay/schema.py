from mem0_dbay.pg_client import PgClient


def ensure_schema(client: PgClient, embedding_dimension: int = 1536) -> None:
    """Create graph_nodes and graph_edges tables if they don't exist."""
    client.execute("CREATE EXTENSION IF NOT EXISTS vector")

    client.execute(f"""
        CREATE TABLE IF NOT EXISTS graph_nodes (
            id BIGSERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            node_type TEXT,
            user_id TEXT NOT NULL,
            agent_id TEXT,
            run_id TEXT,
            embedding vector({embedding_dimension}),
            mentions INTEGER NOT NULL DEFAULT 1,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    """)

    client.execute("""
        CREATE TABLE IF NOT EXISTS graph_edges (
            id BIGSERIAL PRIMARY KEY,
            source_id BIGINT NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,
            target_id BIGINT NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,
            relationship TEXT NOT NULL,
            mentions INTEGER NOT NULL DEFAULT 1,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE(source_id, target_id, relationship)
        )
    """)

    client.execute("CREATE INDEX IF NOT EXISTS idx_graph_nodes_user_id ON graph_nodes (user_id)")
    client.execute("CREATE INDEX IF NOT EXISTS idx_graph_nodes_name_user ON graph_nodes (name, user_id)")
    client.execute("CREATE INDEX IF NOT EXISTS idx_graph_edges_source ON graph_edges (source_id)")
    client.execute("CREATE INDEX IF NOT EXISTS idx_graph_edges_target ON graph_edges (target_id)")

    # pgvector index — needs rows to exist, so use IF NOT EXISTS
    # ivfflat requires at least some rows; for empty tables hnsw is better
    client.execute(f"""
        CREATE INDEX IF NOT EXISTS idx_graph_nodes_embedding
        ON graph_nodes USING hnsw (embedding vector_cosine_ops)
    """)
