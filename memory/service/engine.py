import psycopg2
import psycopg2.extras
import json
from typing import Optional
from models import Memory, Trait, MemoryStats, GraphNode, GraphEdge
from providers import get_embedding


def _connect(connstr: str):
    return psycopg2.connect(connstr, connect_timeout=30)


async def ingest(connstr: str, content: str, role: str, memory_type: str,
                 importance: float, metadata: dict) -> Memory:
    embedding = await get_embedding(content)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                VALUES (%s, %s, %s, %s::vector, %s, now())
                RETURNING id, content, memory_type, importance, access_count, metadata, event_time, created_at
            """, (content, memory_type, importance, json.dumps(embedding), json.dumps(metadata)))
            row = cur.fetchone()
            conn.commit()
            return Memory(**row)
    finally:
        conn.close()


async def recall(connstr: str, query: str, top_k: int,
                 memory_types: Optional[list[str]]) -> list[Memory]:
    """Hybrid search: vector cosine + text search + RRF merge."""
    embedding = await get_embedding(query)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            type_filter = ""
            type_params: list = []
            if memory_types:
                type_filter = "WHERE memory_type = ANY(%s)"
                type_params = [memory_types]

            # Vector search
            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at
                FROM memories {type_filter}
                ORDER BY embedding <=> %s::vector
                LIMIT %s
            """, type_params + [json.dumps(embedding), top_k * 3])
            vector_results = cur.fetchall()

            # Text search
            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at,
                       ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', %s)) AS text_score
                FROM memories
                {type_filter + ' AND' if type_filter else 'WHERE'}
                to_tsvector('simple', content) @@ plainto_tsquery('simple', %s)
                ORDER BY text_score DESC
                LIMIT %s
            """, ([memory_types] if memory_types else []) + [query, query, top_k * 3])
            text_results = cur.fetchall()

            # RRF merge (k=60)
            rrf_scores: dict[int, float] = {}
            all_rows: dict[int, dict] = {}

            for rank, row in enumerate(vector_results):
                mid = row["id"]
                rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                all_rows[mid] = row

            for rank, row in enumerate(text_results):
                mid = row["id"]
                rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                if mid not in all_rows:
                    all_rows[mid] = row

            sorted_ids = sorted(rrf_scores, key=lambda x: rrf_scores[x], reverse=True)[:top_k]

            if sorted_ids:
                cur.execute("""
                    UPDATE memories SET access_count = access_count + 1, last_accessed_at = now()
                    WHERE id = ANY(%s)
                """, (sorted_ids,))
                conn.commit()

            return [Memory(**{k: v for k, v in all_rows[mid].items() if k != "text_score"})
                    for mid in sorted_ids]
    finally:
        conn.close()


async def list_memories(connstr: str, memory_type: Optional[str],
                        offset: int, limit: int) -> dict:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            where = "WHERE memory_type = %s" if memory_type else ""
            params_count = [memory_type] if memory_type else []

            cur.execute(f"SELECT count(*) as total FROM memories {where}", params_count)
            total = cur.fetchone()["total"]

            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at
                FROM memories {where}
                ORDER BY created_at DESC
                LIMIT %s OFFSET %s
            """, (params_count + [limit, offset]))
            rows = cur.fetchall()
            return {"memories": [Memory(**r) for r in rows], "total": total}
    finally:
        conn.close()


async def get_memory(connstr: str, memory_id: int) -> Memory:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at FROM memories WHERE id = %s
            """, (memory_id,))
            row = cur.fetchone()
            if not row:
                raise ValueError(f"Memory not found: {memory_id}")
            return Memory(**row)
    finally:
        conn.close()


async def delete_memory(connstr: str, memory_id: int):
    conn = _connect(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM memories WHERE id = %s", (memory_id,))
            conn.commit()
    finally:
        conn.close()


async def get_stats(connstr: str) -> MemoryStats:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("SELECT memory_type, count(*) as cnt FROM memories GROUP BY memory_type")
            by_type = {r["memory_type"]: r["cnt"] for r in cur.fetchall()}
            total = sum(by_type.values())
            cur.execute("SELECT count(*) as cnt FROM traits")
            trait_count = cur.fetchone()["cnt"]
            return MemoryStats(total=total, by_type=by_type, trait_count=trait_count)
    finally:
        conn.close()


async def list_traits(connstr: str) -> list[Trait]:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, trait_stage, trait_subtype, confidence,
                       reinforcement_count, contradiction_count, context, created_at
                FROM traits ORDER BY
                    CASE trait_stage
                        WHEN 'core' THEN 1 WHEN 'established' THEN 2
                        WHEN 'emerging' THEN 3 WHEN 'candidate' THEN 4
                        WHEN 'trend' THEN 5
                    END, confidence DESC
            """)
            return [Trait(**r) for r in cur.fetchall()]
    finally:
        conn.close()


async def get_graph(connstr: str) -> dict:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("SELECT node_type, node_id, properties FROM graph_nodes")
            nodes = [GraphNode(**r) for r in cur.fetchall()]
            cur.execute("SELECT source_type, source_id, target_type, target_id, edge_type FROM graph_edges")
            edges = [GraphEdge(**r) for r in cur.fetchall()]
            return {"nodes": [n.model_dump() for n in nodes],
                    "edges": [e.model_dump() for e in edges]}
    finally:
        conn.close()
