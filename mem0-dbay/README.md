# mem0-dbay

PostgreSQL + pgvector graph store backend for [Mem0](https://github.com/mem0ai/mem0). Powered by [dbay.cloud](https://dbay.cloud).

**One database replaces three.** No more Neo4j + Qdrant + SQLite — just PostgreSQL.

## Install

```bash
pip install mem0-dbay
```

## Quick Start

```python
import mem0_dbay  # registers 'dbay' graph provider

from mem0 import Memory

config = {
    "graph_store": {
        "provider": "dbay",
        "config": {
            "connection_string": "postgresql://user:pass@localhost:5432/mydb"
        }
    },
    "vector_store": {
        "provider": "pgvector",
        "config": {
            "connection_string": "postgresql://user:pass@localhost:5432/mydb"
        }
    }
}

m = Memory.from_config(config)

# Add memories with graph relationships
m.add("Alice works at Google and loves Python", user_id="alice")

# Search — vector similarity + graph relationship expansion + BM25 reranking
results = m.search("Where does Alice work?", user_id="alice")
```

## With dbay.cloud

Replace `localhost` with your [dbay.cloud](https://dbay.cloud) connection string:

```python
"connection_string": "postgresql://user:pass@xxx.dbay.cloud:4432/memories?sslmode=require"
```

Benefits over self-hosted PostgreSQL:
- **Scale-to-zero** — idle databases cost nothing
- **Git-style branching** — A/B test memory strategies with copy-on-write branches
- **Zero ops** — no PostgreSQL to manage, no pgvector to install
- **pg_search (BM25)** — full-text search pre-installed

## How It Works

Instead of Neo4j's property graph + Cypher, this plugin uses two PostgreSQL tables:

| Component | Neo4j | mem0-dbay |
|-----------|-------|-----------|
| Nodes (entities) | Neo4j nodes with `:__Entity__` label | `graph_nodes` table with pgvector embeddings |
| Edges (relationships) | Neo4j relationships with dynamic types | `graph_edges` table with `relationship` column |
| Vector search | `vector.similarity.cosine()` | pgvector `<=>` operator |
| Graph traversal | Cypher `MATCH (n)-[r]->(m)` | SQL `JOIN graph_edges ON ...` |

Mem0 only uses 1-hop graph traversals — no need for a dedicated graph database.

## Why Not Neo4j?

Mem0's graph memory uses Neo4j for simple entity-relationship storage with vector similarity search. The actual operations are:
- Insert/merge nodes with embeddings
- Insert/merge edges
- Find similar nodes by cosine similarity
- Expand 1-hop relationships from matched nodes

All of these map naturally to PostgreSQL + pgvector. No Cypher, no APOC, no graph algorithms needed.

**Before (3 databases):**
```
Mem0 → PostgreSQL (pgvector) — vector store
     → Neo4j                 — graph store
     → SQLite                — history
```

**After (1 database):**
```
Mem0 → PostgreSQL (pgvector) — everything
```

## License

MIT
