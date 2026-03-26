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


async def store_raw_message(connstr: str, content: str, role: str, source: str = None) -> str:
    """Store raw message and return its UUID."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO raw_messages (content, role, source) VALUES (%s, %s, %s) RETURNING id
            """, (content, role, source))
            row = cur.fetchone()
            conn.commit()
            return str(row["id"])
    finally:
        conn.close()


async def ingest_extracted(connstr: str, message_id: str, data: dict) -> dict:
    """Store pre-extracted structured memories. Returns counts per type."""
    from models import IngestExtractedData
    parsed = IngestExtractedData.model_validate(data)

    conn = _connect(connstr)
    counts = {}
    try:
        with conn.cursor() as cur:
            # Validate message_id exists and get source
            try:
                cur.execute("SELECT id, source FROM raw_messages WHERE id = %s", (message_id,))
            except Exception:
                raise ValueError(f"Invalid message_id: {message_id}")
            msg_row = cur.fetchone()
            if not msg_row:
                raise ValueError(f"Message not found: {message_id}")
            msg_source = msg_row[1]  # source column

            type_map = {
                "facts": ("fact", parsed.facts),
                "episodes": ("episode", parsed.episodes),
                "procedural": ("procedural", parsed.procedural),
                "decisions": ("decision", parsed.decisions),
                "rejections": ("rejection", parsed.rejections),
                "conventions": ("convention", parsed.conventions),
            }
            for key, (mem_type, items) in type_map.items():
                count = 0
                for item in items:
                    if not item.content:
                        continue
                    embedding = await get_embedding(item.content)
                    metadata = {k: v for k, v in item.model_dump().items()
                                if k not in ("content", "importance") and v is not None}
                    if msg_source:
                        metadata["source"] = msg_source
                    cur.execute("""
                        INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                        VALUES (%s, %s, %s, %s::vector, %s, now())
                    """, (item.content, mem_type, item.importance, json.dumps(embedding), json.dumps(metadata)))
                    count += 1
                counts[f"{key}_stored"] = count
            conn.commit()
    finally:
        conn.close()
    return counts


async def background_extract(connstr: str, message_id: str, content: str, scene: str = "CHAT_ASSISTANT"):
    """Background task: call LLM to extract memories, then store them."""
    import logging
    from extraction_prompt import build_extraction_prompt
    from llm_client import chat_extract

    logger = logging.getLogger(__name__)
    try:
        prompt = build_extraction_prompt(content, scene=scene)
        result = await chat_extract(prompt)
        counts = await ingest_extracted(connstr, message_id, result)
        logger.info("Background extraction for %s (scene=%s): %s", message_id, scene, counts)
    except Exception as e:
        logger.error("Background extraction failed for %s: %s", message_id, e, exc_info=True)


async def get_unreflected_memories(connstr: str, limit: int = 50) -> tuple[list[dict], int]:
    """Get memories created after the last reflection watermark."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("SELECT last_reflected FROM reflection_watermark ORDER BY id DESC LIMIT 1")
            row = cur.fetchone()
            watermark = row["last_reflected"] if row else None

            if watermark:
                cur.execute("SELECT count(*) as cnt FROM memories WHERE created_at > %s", (watermark,))
            else:
                cur.execute("SELECT count(*) as cnt FROM memories")
            total = cur.fetchone()["cnt"]

            if total == 0:
                return [], 0

            if watermark:
                cur.execute("""
                    SELECT id, content, memory_type, metadata, created_at FROM memories
                    WHERE created_at > %s ORDER BY created_at ASC LIMIT %s
                """, (watermark, limit))
            else:
                cur.execute("""
                    SELECT id, content, memory_type, metadata, created_at FROM memories
                    ORDER BY created_at ASC LIMIT %s
                """, (limit,))
            memories = [dict(r) for r in cur.fetchall()]
            for m in memories:
                m["created_at"] = str(m["created_at"]) if m["created_at"] else None
            return memories, total
    finally:
        conn.close()


async def get_existing_traits(connstr: str, limit: int = 20) -> list[dict]:
    """Get recent traits for dedup context in digest."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, trait_stage FROM traits
                ORDER BY created_at DESC LIMIT %s
            """, (limit,))
            return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()


async def store_digest_traits(connstr: str, traits: list[dict]) -> int:
    """Store digest traits (importance >= 7) and advance watermark."""
    valid = [t for t in traits if t.get("content") and t.get("importance", 0) >= 7]
    if not valid:
        return 0

    conn = _connect(connstr)
    stored = 0
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            for t in valid:
                cur.execute("""
                    INSERT INTO traits (content, trait_stage, trait_subtype, confidence, created_at)
                    VALUES (%s, 'trend', %s, %s, now())
                """, (t["content"], t.get("category", "pattern"), round(t.get("importance", 7) / 10.0, 2)))
                stored += 1

            # Advance watermark
            cur.execute("SELECT max(created_at) as max_ts FROM memories")
            row = cur.fetchone()
            if row and row["max_ts"]:
                cur.execute("INSERT INTO reflection_watermark (last_reflected) VALUES (%s)", (row["max_ts"],))

            conn.commit()
    finally:
        conn.close()
    return stored


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

            # Text search — param order: ts_rank query, [type_filter], WHERE query, limit
            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at,
                       ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', %s)) AS text_score
                FROM memories
                {type_filter + ' AND' if type_filter else 'WHERE'}
                to_tsvector('simple', content) @@ plainto_tsquery('simple', %s)
                ORDER BY text_score DESC
                LIMIT %s
            """, [query] + ([memory_types] if memory_types else []) + [query, top_k * 3])
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
