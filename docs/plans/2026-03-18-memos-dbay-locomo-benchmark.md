# MemOS + dbay LoCoMo Benchmark Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete MemOS PostgreSQL backend, connect to dbay CCE, run LoCoMo10 benchmark to validate token reduction ≥72% with accuracy parity.

**Architecture:** Fork MemOS → fix `postgres.py` (4 missing methods + `db_name` attr + serverless tuning) → start MemOS API locally pointing to dbay CCE PG → run 5-stage LoCoMo eval pipeline with DeepSeek V3 via SiliconFlow.

**Tech Stack:** Python 3.10+, MemOS (Poetry), psycopg2, pgvector, FastAPI, DeepSeek V3 (SiliconFlow API, model: `deepseek-ai/DeepSeek-V3`), dbay.cloud Serverless PG (CCE)

**Important Notes:**
- Verify SiliconFlow model name: `deepseek-ai/DeepSeek-V3` (not V3.2)
- Verify embedding dimension: BGE-M3 default is 1024 (not 768) — check MemOS embedder config
- Tasks 1-4 modify the same file — execute sequentially, not in parallel by separate agents
- Unit tests are structural only (mocked); Task 5 is the real integration test against dbay

**Working directory:** `~/code/MemOS` (branch: `feat/dbay-postgres-backend`)

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/memos/graph_dbs/postgres.py` | Modify | Add 4 missing methods + serverless tuning |
| `tests/test_postgres_backend.py` | Create | Unit tests for new methods |
| `evaluation/scripts/locomo/.env` | Create | LLM config for SiliconFlow DeepSeek V3.2 |
| `config/dbay-postgres.yaml` | Create | MemOS config pointing to dbay CCE |

---

## Task 1: Fix `get_subgraph` Return Type

The current `get_subgraph` returns `list[str]` but `tree.py:314-324` expects `{"core_node": dict, "neighbors": list[dict], "edges": list[dict]}`.

**Files:**
- Modify: `src/memos/graph_dbs/postgres.py:567-589`
- Test: `tests/test_postgres_backend.py`

- [ ] **Step 1: Write failing test**

```python
# tests/test_postgres_backend.py
import json
import pytest
from unittest.mock import MagicMock, patch

from memos.configs.graph_db import PostgresGraphDBConfig


@pytest.fixture
def mock_config():
    return PostgresGraphDBConfig(
        host="localhost",
        port=5432,
        user="test",
        password="test",
        db_name="test",
        schema_name="memos_test",
        user_name="test_user",
    )


class TestGetSubgraph:
    """get_subgraph must return dict with core_node, neighbors, edges."""

    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_get_subgraph_returns_dict(self, mock_pool_cls, mock_config):
        """get_subgraph should return {core_node, neighbors, edges} dict."""
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        # Mock cursor for _init_schema (no-op)
        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        # Mock: center node exists, has one neighbor via edge
        call_count = [0]
        def mock_fetchall():
            call_count[0] += 1
            if call_count[0] == 1:
                # Subgraph CTE returns node IDs
                return [("center_1", 0), ("neighbor_1", 1)]
            elif call_count[0] == 2:
                # Center node query
                return [("center_1", "center memory", {}, "2024-01-01T00:00:00", "2024-01-01T00:00:00")]
            elif call_count[0] == 3:
                # Neighbor nodes query
                return [("neighbor_1", "neighbor memory", {}, "2024-01-01T00:00:00", "2024-01-01T00:00:00")]
            elif call_count[0] == 4:
                # Edges query
                return [("center_1", "neighbor_1", "RELATES_TO")]
            return []

        mock_cursor.fetchall = mock_fetchall
        mock_cursor.fetchone = MagicMock(return_value=None)

        result = db.get_subgraph(center_id="center_1", depth=2, user_name="test_user")

        assert isinstance(result, dict)
        assert "core_node" in result
        assert "neighbors" in result
        assert "edges" in result
        assert isinstance(result["neighbors"], list)
        assert isinstance(result["edges"], list)

    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_get_subgraph_empty_returns_none_core(self, mock_pool_cls, mock_config):
        """get_subgraph with non-existent center returns None core_node."""
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        mock_cursor.fetchall.return_value = []

        result = db.get_subgraph(center_id="nonexistent", depth=2, user_name="test_user")

        assert result == {"core_node": None, "neighbors": [], "edges": []}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestGetSubgraph -v`
Expected: FAIL — `get_subgraph` returns `list[str]` not `dict`

- [ ] **Step 3: Implement get_subgraph with dict return**

Replace the existing `get_subgraph` in `postgres.py:567-589` with:

```python
def get_subgraph(
    self,
    center_id: str,
    depth: int = 2,
    center_status: str = "activated",
    user_name: str | None = None,
) -> dict[str, Any]:
    """Get subgraph around center node.

    Returns:
        {
            "core_node": {...} or None,
            "neighbors": [...],
            "edges": [...]
        }
    """
    user_name = user_name or self.user_name

    if not 1 <= depth <= 5:
        raise ValueError("depth must be 1-5")

    conn = self._get_conn()
    try:
        with conn.cursor() as cur:
            # Step 1: Get all node IDs in subgraph via recursive CTE
            cur.execute(
                f"""
                WITH RECURSIVE subgraph AS (
                    SELECT %s::text as node_id, 0 as level
                    UNION
                    SELECT CASE WHEN e.source_id = s.node_id THEN e.target_id ELSE e.source_id END,
                           s.level + 1
                    FROM {self.schema}.edges e
                    JOIN subgraph s ON (e.source_id = s.node_id OR e.target_id = s.node_id)
                    WHERE s.level < %s
                )
                SELECT DISTINCT node_id, level FROM subgraph
            """,
                (center_id, depth),
            )
            subgraph_rows = cur.fetchall()

            if not subgraph_rows:
                return {"core_node": None, "neighbors": [], "edges": []}

            all_ids = [row[0] for row in subgraph_rows]

            # Step 2: Fetch center node
            cur.execute(
                f"""
                SELECT id, memory, properties, created_at, updated_at
                FROM {self.schema}.memories
                WHERE id = %s AND user_name = %s
            """,
                (center_id, user_name),
            )
            center_rows = cur.fetchall()
            core_node = self._parse_row(center_rows[0]) if center_rows else None

            # Step 3: Fetch neighbor nodes (exclude center)
            neighbor_ids = [nid for nid in all_ids if nid != center_id]
            neighbors = []
            if neighbor_ids:
                cur.execute(
                    f"""
                    SELECT id, memory, properties, created_at, updated_at
                    FROM {self.schema}.memories
                    WHERE id = ANY(%s) AND user_name = %s
                """,
                    (neighbor_ids, user_name),
                )
                neighbors = [self._parse_row(row) for row in cur.fetchall()]

            # Step 4: Fetch edges between subgraph nodes
            cur.execute(
                f"""
                SELECT source_id, target_id, edge_type
                FROM {self.schema}.edges
                WHERE source_id = ANY(%s) AND target_id = ANY(%s)
            """,
                (all_ids, all_ids),
            )
            edges = [
                {"source": row[0], "target": row[1], "type": row[2]}
                for row in cur.fetchall()
            ]

            return {"core_node": core_node, "neighbors": neighbors, "edges": edges}
    finally:
        self._put_conn(conn)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestGetSubgraph -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd ~/code/MemOS
git add src/memos/graph_dbs/postgres.py tests/test_postgres_backend.py
git commit -m "fix: get_subgraph returns dict with core_node/neighbors/edges

Align with PolarDB backend and tree.py call site expectations."
```

---

## Task 2: Add `search_by_fulltext`

Called by `tree.py:295` with `query_words=list[str], top_k=int, user_name=str`.
PolarDB uses Apache AGE + jieba tsvector. We use standard PG `tsvector/tsquery`.

**Files:**
- Modify: `src/memos/graph_dbs/postgres.py`
- Test: `tests/test_postgres_backend.py`

- [ ] **Step 1: Write failing test**

```python
# Append to tests/test_postgres_backend.py

class TestSearchByFulltext:
    """search_by_fulltext must return list[dict] with id and score."""

    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_search_by_fulltext_returns_results(self, mock_pool_cls, mock_config):
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        # Mock search results
        mock_cursor.fetchall.return_value = [
            ("node_1", 0.85),
            ("node_2", 0.72),
        ]

        results = db.search_by_fulltext(
            query_words=["hello", "world"],
            top_k=5,
            user_name="test_user",
        )

        assert isinstance(results, list)
        assert len(results) == 2
        assert results[0]["id"] == "node_1"
        assert "score" in results[0]

    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_search_by_fulltext_empty_query(self, mock_pool_cls, mock_config):
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        results = db.search_by_fulltext(query_words=[], top_k=5, user_name="test_user")

        assert results == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestSearchByFulltext -v`
Expected: FAIL — `search_by_fulltext` method doesn't exist

- [ ] **Step 3: Implement search_by_fulltext**

Add after `search_by_embedding` method (~line 664):

```python
def search_by_fulltext(
    self,
    query_words: list[str],
    top_k: int = 10,
    scope: str | None = None,
    status: str | None = None,
    threshold: float | None = None,
    search_filter: dict | None = None,
    user_name: str | None = None,
    filter: dict | None = None,
    knowledgebase_ids: list[str] | None = None,
    return_fields: list[str] | None = None,
    **kwargs,
) -> list[dict]:
    """Full-text search using PostgreSQL tsvector/tsquery.

    Uses simple text matching with ts_rank for scoring.
    Falls back to ILIKE matching when tsvector is not available
    on the memory column.
    """
    if not query_words:
        return []

    user_name = user_name or self.user_name

    # Build WHERE clause
    conditions = []
    params = []

    if user_name:
        conditions.append("user_name = %s")
        params.append(user_name)

    if scope:
        conditions.append("properties->>'memory_type' = %s")
        params.append(scope)

    if status:
        conditions.append("properties->>'status' = %s")
        params.append(status)
    else:
        conditions.append(
            "(properties->>'status' = 'activated' OR properties->>'status' IS NULL)"
        )

    if search_filter:
        for k, v in search_filter.items():
            conditions.append(f"properties->>'{k}' = %s")
            params.append(str(v))

    # Build tsquery from words (OR matching)
    tsquery_string = " | ".join(query_words)

    where_clause = " AND ".join(conditions) if conditions else "TRUE"

    conn = self._get_conn()
    try:
        with conn.cursor() as cur:
            # Use ts_rank with to_tsvector on memory column
            cur.execute(
                f"""
                SELECT id, ts_rank(
                    to_tsvector('simple', COALESCE(memory, '')),
                    to_tsquery('simple', %s)
                ) AS rank
                FROM {self.schema}.memories
                WHERE {where_clause}
                AND to_tsvector('simple', COALESCE(memory, '')) @@ to_tsquery('simple', %s)
                ORDER BY rank DESC
                LIMIT %s
            """,
                (tsquery_string, *params, tsquery_string, top_k),
            )

            results = []
            for row in cur.fetchall():
                score = float(row[1])
                if threshold is None or score >= threshold:
                    results.append({"id": row[0], "score": score})
            return results
    finally:
        self._put_conn(conn)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestSearchByFulltext -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd ~/code/MemOS
git add src/memos/graph_dbs/postgres.py tests/test_postgres_backend.py
git commit -m "feat: add search_by_fulltext using PG tsvector/tsquery

Uses 'simple' config for language-agnostic matching.
Returns list[dict] with id and score, matching PolarDB interface."
```

---

## Task 3: Add `delete_node_by_prams` and `drop_database`

Called by `tree.py:407` and `tree.py:489`.

**Files:**
- Modify: `src/memos/graph_dbs/postgres.py`
- Test: `tests/test_postgres_backend.py`

- [ ] **Step 1: Write failing tests**

```python
# Append to tests/test_postgres_backend.py

class TestDeleteNodeByPrams:
    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_delete_by_memory_ids(self, mock_pool_cls, mock_config):
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_cursor.rowcount = 3
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        result = db.delete_node_by_prams(memory_ids=["id1", "id2", "id3"])

        assert result == 3
        assert mock_cursor.execute.called

    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_delete_no_params_returns_zero(self, mock_pool_cls, mock_config):
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        result = db.delete_node_by_prams()
        assert result == 0


class TestDropDatabase:
    @patch("psycopg2.pool.ThreadedConnectionPool")
    def test_drop_database(self, mock_pool_cls, mock_config):
        from memos.graph_dbs.postgres import PostgresGraphDB

        mock_pool = MagicMock()
        mock_pool_cls.return_value = mock_pool
        mock_conn = MagicMock()
        mock_conn.closed = 0
        mock_pool.getconn.return_value = mock_conn

        mock_cursor = MagicMock()
        mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        db = PostgresGraphDB(mock_config)

        # Should not raise
        db.drop_database()
        assert mock_cursor.execute.called
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestDeleteNodeByPrams tests/test_postgres_backend.py::TestDropDatabase -v`
Expected: FAIL — methods don't exist

- [ ] **Step 3: Implement both methods**

First, add `self.db_name = config.db_name` in `__init__` (after `self.user_name = config.user_name` line 57).
This is needed because `tree.py:490` accesses `self.graph_store.db_name`.

Then add to `postgres.py` before the `close()` method:

```python
def delete_node_by_prams(
    self,
    writable_cube_ids: list[str] | None = None,
    memory_ids: list[str] | None = None,
    file_ids: list[str] | None = None,
    filter: dict | None = None,
) -> int:
    """Delete nodes by memory_ids, file_ids, or filter.

    Returns:
        int: Number of nodes deleted.
    """
    conditions = []
    params = []

    if memory_ids:
        conditions.append("id = ANY(%s)")
        params.append(memory_ids)

    if file_ids:
        file_conditions = []
        for fid in file_ids:
            file_conditions.append("properties->'file_ids' @> %s::jsonb")
            params.append(json.dumps([fid]))
        conditions.append(f"({' OR '.join(file_conditions)})")

    if filter:
        for key, value in filter.items():
            if isinstance(value, str):
                conditions.append(f"properties->>'{key}' = %s")
                params.append(value)
            elif isinstance(value, list):
                conditions.append(f"properties->>'{key}' = ANY(%s)")
                params.append(value)
            else:
                conditions.append(f"properties->>'{key}' = %s")
                params.append(str(value))

    if writable_cube_ids:
        conditions.append("user_name = ANY(%s)")
        params.append(writable_cube_ids)

    if not conditions:
        logger.warning("[delete_node_by_prams] No conditions provided, skipping")
        return 0

    where_clause = " AND ".join(conditions)

    conn = self._get_conn()
    try:
        with conn.cursor() as cur:
            # First delete edges for these nodes
            cur.execute(
                f"""
                DELETE FROM {self.schema}.edges
                WHERE source_id IN (
                    SELECT id FROM {self.schema}.memories WHERE {where_clause}
                ) OR target_id IN (
                    SELECT id FROM {self.schema}.memories WHERE {where_clause}
                )
            """,
                params + params,
            )

            # Then delete nodes
            cur.execute(
                f"""
                DELETE FROM {self.schema}.memories
                WHERE {where_clause}
            """,
                params,
            )
            deleted_count = cur.rowcount
            logger.info(f"[delete_node_by_prams] Deleted {deleted_count} nodes")
            return deleted_count
    finally:
        self._put_conn(conn)

def drop_database(self) -> None:
    """Drop the schema and all its data."""
    conn = self._get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(f"DROP SCHEMA IF EXISTS {self.schema} CASCADE")
            logger.info(f"Schema '{self.schema}' dropped")
    finally:
        self._put_conn(conn)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py::TestDeleteNodeByPrams tests/test_postgres_backend.py::TestDropDatabase -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd ~/code/MemOS
git add src/memos/graph_dbs/postgres.py tests/test_postgres_backend.py
git commit -m "feat: add delete_node_by_prams and drop_database

Complete the PostgreSQL backend to match tree.py call expectations."
```

---

## Task 4: Serverless PG Tuning

**Files:**
- Modify: `src/memos/graph_dbs/postgres.py`

- [ ] **Step 1: Change IVFFlat to HNSW index**

In `_init_schema`, replace line 161-165:

```python
# Old:
CREATE INDEX IF NOT EXISTS idx_memories_embedding
ON {self.schema}.memories USING ivfflat(embedding vector_cosine_ops)
WITH (lists = 100)

# New (drop old index first for existing deployments):
DROP INDEX IF EXISTS {self.schema}.idx_memories_embedding
# Then create HNSW:
CREATE INDEX IF NOT EXISTS idx_memories_embedding
ON {self.schema}.memories USING hnsw(embedding vector_cosine_ops)
```

Note: IVFFlat with lists=100 also fails on empty tables (<100 rows). HNSW has no such limitation.

- [ ] **Step 2: Tune connection pool for serverless**

In `__init__`, change pool params:

```python
# Old:
minconn=2,
maxconn=config.maxconn,

# New:
minconn=1,
maxconn=min(config.maxconn, 10),
```

- [ ] **Step 3: Add pool recreation on total failure**

In `_get_conn`, after 3 retries, recreate pool:

```python
# After the for loop's raise, add pool recreation logic
def _get_conn(self):
    """Get connection from pool with health check and pool recreation."""
    if self._pool_closed:
        raise RuntimeError("Connection pool is closed")

    for attempt in range(3):
        conn = None
        try:
            conn = self.pool.getconn()
            if conn.closed != 0:
                self.pool.putconn(conn, close=True)
                continue
            conn.autocommit = True
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
            return conn
        except Exception as e:
            if conn:
                with suppress(Exception):
                    self.pool.putconn(conn, close=True)
            if attempt == 2:
                # Try recreating the pool (serverless PG may have suspended)
                logger.warning("All connections failed, recreating pool...")
                try:
                    import psycopg2.pool
                    with suppress(Exception):
                        self.pool.closeall()
                    self.pool = psycopg2.pool.ThreadedConnectionPool(
                        minconn=1,
                        maxconn=min(self.config.maxconn, 10),
                        host=self.config.host,
                        port=self.config.port,
                        user=self.config.user,
                        password=self.config.password,
                        dbname=self.config.db_name,
                        connect_timeout=30,
                        keepalives_idle=30,
                        keepalives_interval=10,
                        keepalives_count=5,
                    )
                    conn = self.pool.getconn()
                    conn.autocommit = True
                    return conn
                except Exception as e2:
                    raise RuntimeError(f"Failed to get connection after pool recreation: {e2}") from e
            time.sleep(0.1 * (attempt + 1))
    raise RuntimeError("Failed to get healthy connection")
```

- [ ] **Step 4: Run all tests**

Run: `cd ~/code/MemOS && python -m pytest tests/test_postgres_backend.py -v`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd ~/code/MemOS
git add src/memos/graph_dbs/postgres.py
git commit -m "perf: serverless PG tuning - HNSW index, pool recreation, lower connections

- IVFFlat → HNSW for better cold-start performance
- minconn=1, maxconn capped at 10
- Pool auto-recreation when all connections stale (Neon suspend/resume)"
```

---

## Task 5: Create dbay Database & Test Connection

**Files:**
- Create: `config/dbay-postgres.yaml` (MemOS config)

- [ ] **Step 1: Create database on dbay CCE**

```bash
# Use the dbay API to create a database for MemOS benchmark
# Or connect to an existing dbay database
# Record the connection details: host, port, user, password, db_name
```

- [ ] **Step 2: Enable pgvector extension**

Connect to the dbay database and run:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 3: Create MemOS config file**

```yaml
# ~/code/MemOS/config/dbay-postgres.yaml
text_mem:
  backend: "tree_text"
  config:
    graph_db:
      backend: "postgres"
      config:
        host: "<dbay-host>"  # from dbay database connection info
        port: 5432
        user: "<dbay-user>"
        password: "<dbay-password>"
        db_name: "<dbay-dbname>"
        schema_name: "memos"
        user_name: "default"
        embedding_dimension: 1024  # BGE-M3 default dense dimension
        maxconn: 5
    embedder:
      backend: "openai"
      config:
        api_key: "${SILICONFLOW_API_KEY}"
        base_url: "https://api.siliconflow.cn/v1"
        model: "BAAI/bge-m3"  # verify dimension matches MemOS embedder output
    extractor_llm:
      backend: "openai"
      config:
        api_key: "${SILICONFLOW_API_KEY}"
        base_url: "https://api.siliconflow.cn/v1"
        model: "deepseek-ai/DeepSeek-V3"
    dispatcher_llm:
      backend: "openai"
      config:
        api_key: "${SILICONFLOW_API_KEY}"
        base_url: "https://api.siliconflow.cn/v1"
        model: "deepseek-ai/DeepSeek-V3"
```

- [ ] **Step 4: Test connection**

```bash
cd ~/code/MemOS
python -c "
from memos.graph_dbs.postgres import PostgresGraphDB
from memos.configs.graph_db import PostgresGraphDBConfig

config = PostgresGraphDBConfig(
    host='<dbay-host>',
    port=5432,
    user='<dbay-user>',
    password='<dbay-password>',
    db_name='<dbay-dbname>',
    schema_name='memos_test',
    user_name='test',
)
db = PostgresGraphDB(config)
db.add_node('test_1', 'hello world', {'memory_type': 'WorkingMemory', 'status': 'activated'})
node = db.get_node('test_1')
print(f'Node: {node}')
db.delete_node('test_1')
print('Connection test passed!')
db.close()
"
```

- [ ] **Step 5: Commit config**

```bash
cd ~/code/MemOS
git add config/dbay-postgres.yaml
git commit -m "feat: add dbay.cloud Serverless PG config for MemOS"
```

---

## Task 6: Configure Eval Pipeline for SiliconFlow

The eval pipeline uses OpenAI-compatible API. We need `.env` for SiliconFlow + DeepSeek V3.2.

**Files:**
- Create: `evaluation/scripts/locomo/.env`

- [ ] **Step 1: Create .env file**

```bash
# evaluation/scripts/locomo/.env

# MemOS API (local)
MEMOS_URL=http://localhost:8000
MEMOS_KEY=test-key

# Chat model (for locomo_responses.py)
CHAT_MODEL=deepseek-ai/DeepSeek-V3
CHAT_MODEL_API_KEY=${SILICONFLOW_API_KEY}
CHAT_MODEL_BASE_URL=https://api.siliconflow.cn/v1

# Eval model (for locomo_eval.py judge)
EVAL_MODEL=deepseek-ai/DeepSeek-V3
OPENAI_API_KEY=${SILICONFLOW_API_KEY}
OPENAI_BASE_URL=https://api.siliconflow.cn/v1
```

Note: read actual `SILICONFLOW_API_KEY` from `~/code/lakeon/.env`

- [ ] **Step 2: Verify SiliconFlow API works**

```bash
cd ~/code/MemOS
python -c "
import os
from openai import OpenAI
from dotenv import load_dotenv
load_dotenv(os.path.expanduser('~/code/lakeon/.env'))
client = OpenAI(
    api_key=os.getenv('SILICONFLOW_API_KEY'),
    base_url='https://api.siliconflow.cn/v1'
)
r = client.chat.completions.create(
    model='deepseek-ai/DeepSeek-V3',
    messages=[{'role': 'user', 'content': 'Say hello'}],
    max_tokens=10
)
print(r.choices[0].message.content)
"
```

- [ ] **Step 3: Commit**

```bash
cd ~/code/MemOS
echo "evaluation/scripts/locomo/.env" >> .gitignore
git add .gitignore
git commit -m "chore: add .env to gitignore for eval pipeline"
```

---

## Task 7: Start MemOS API & Run LoCoMo Eval

- [ ] **Step 1: Install MemOS dependencies**

```bash
cd ~/code/MemOS
pip install -e ".[tree-mem]"
pip install psycopg2-binary
pip install pandas tiktoken nltk rouge-score bert-score sentence-transformers tqdm python-dotenv
```

- [ ] **Step 2: Start MemOS API locally**

```bash
cd ~/code/MemOS
# Configure to use postgres backend with dbay
export MEMOS_CONFIG=config/dbay-postgres.yaml
memos serve --port 8000
```

- [ ] **Step 3: Run ingestion (Stage 1)**

```bash
cd ~/code/MemOS/evaluation
python scripts/locomo/locomo_ingestion.py --lib memos-api --version dbay-v1 --workers 4
```

Expected: 10 users × ~35 sessions ingested to MemOS → stored in dbay PG

- [ ] **Step 4: Run search (Stage 2)**

```bash
cd ~/code/MemOS/evaluation
python scripts/locomo/locomo_search.py --lib memos-api --version dbay-v1 --workers 5 --top_k 15
```

Expected: Search results saved to `results/locomo/memos-api-dbay-v1/`

- [ ] **Step 5: Run responses (Stage 3)**

```bash
cd ~/code/MemOS/evaluation
python scripts/locomo/locomo_responses.py --lib memos-api --version dbay-v1
```

Expected: LLM answers generated using DeepSeek V3.2 + search context

- [ ] **Step 6: Run evaluation (Stage 4)**

```bash
cd ~/code/MemOS/evaluation
python scripts/locomo/locomo_eval.py --lib memos-api --version dbay-v1 --num_runs 3 --options lexical
```

Expected: LLM judge scores + NLP metrics + context_tokens

- [ ] **Step 7: Run metrics (Stage 5)**

```bash
cd ~/code/MemOS/evaluation
python scripts/locomo/locomo_metric.py --lib memos-api --version dbay-v1
```

Expected: Final scores in `results/locomo/memos-api-dbay-v1/memos-api_locomo_grades.json`

Key metrics to record:
- `llm_judge_score` — accuracy (compare to MemOS official)
- `context_tokens` — total and per-question average (compute token reduction vs baseline)
- `search_duration_ms` — retrieval latency

- [ ] **Step 8: Record and analyze results**

Compare with baselines:
- MemOS official: 72% token reduction
- Full-context baseline: 15.6M tokens
- OpenViking: 83% token reduction

Save results to `~/code/lakeon/docs/results/locomo-dbay-benchmark-results.md`

---

## Dependencies Between Tasks

```
Task 1 (get_subgraph) ──┐
Task 2 (fulltext)    ───┼── Task 5 (dbay DB) ── Task 7 (eval pipeline)
Task 3 (delete/drop) ───┤
Task 4 (tuning)      ───┘
                         Task 6 (env config) ──┘
```

Tasks 1-4 are independent and can be parallelized.
Task 5 & 6 are independent and can be parallelized.
Task 7 depends on all previous tasks.
