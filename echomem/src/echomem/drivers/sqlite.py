from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any

import sqlite_vec

from echomem.drivers.base import Memory, RecallHit, Summary, Entity, Triple, Event, Skill, Subgraph
from echomem.drivers.migrations import apply_all


class SQLiteDriver:
    def __init__(self, path: Path, embedding_dim: int = 1024):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.embedding_dim = embedding_dim
        self.con = sqlite3.connect(self.path, check_same_thread=False)
        self.con.execute("PRAGMA journal_mode=WAL")
        self.con.execute("PRAGMA synchronous=NORMAL")
        self.con.execute("PRAGMA busy_timeout=5000")
        self.con.enable_load_extension(True)
        sqlite_vec.load(self.con)
        self.con.enable_load_extension(False)
        apply_all(self.con)

    def upsert_memory(self, mem: Memory) -> str:
        self.con.execute(
            """
            INSERT INTO memory(id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at)
            VALUES(:id, :agent_id, :source_kind, :source_ref, :text, :meta, :created_at, :updated_at, :deleted_at)
            ON CONFLICT(id) DO UPDATE SET
              agent_id    = excluded.agent_id,
              source_kind = excluded.source_kind,
              source_ref  = excluded.source_ref,
              text        = excluded.text,
              meta        = excluded.meta,
              updated_at  = excluded.updated_at,
              deleted_at  = excluded.deleted_at
            """,
            {
                "id": mem.id,
                "agent_id": mem.agent_id,
                "source_kind": mem.source_kind,
                "source_ref": mem.source_ref,
                "text": mem.text,
                "meta": json.dumps(mem.meta) if mem.meta is not None else None,
                "created_at": mem.created_at,
                "updated_at": mem.updated_at,
                "deleted_at": mem.deleted_at,
            },
        )
        if mem.embedding is not None:
            self._upsert_vec(mem.id, mem.embedding)
        self._upsert_fts(mem.id, mem.text)
        self.con.commit()
        return mem.id

    def _upsert_vec(self, memory_id: str, embedding: list[float]) -> None:
        from sqlite_vec import serialize_float32

        if len(embedding) != self.embedding_dim:
            raise ValueError(
                f"embedding dim {len(embedding)} != configured {self.embedding_dim}"
            )
        # vec0 不支持 ON CONFLICT — 用 delete+insert
        self.con.execute("DELETE FROM memory_vec WHERE memory_id = ?", (memory_id,))
        self.con.execute(
            "INSERT INTO memory_vec(memory_id, embedding) VALUES(?, ?)",
            (memory_id, serialize_float32(embedding)),
        )

    def _upsert_fts(self, memory_id: str, text: str) -> None:
        self.con.execute("DELETE FROM memory_fts WHERE memory_id = ?", (memory_id,))
        self.con.execute(
            "INSERT INTO memory_fts(memory_id, text) VALUES(?, ?)", (memory_id, text)
        )

    def get_memory(self, memory_id: str) -> Memory | None:
        row = self.con.execute(
            "SELECT id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at "
            "FROM memory WHERE id = ? AND deleted_at IS NULL",
            (memory_id,),
        ).fetchone()
        if row is None:
            return None
        return _row_to_memory(row)

    def recall(
        self,
        query_embedding: list[float],
        query_text: str,
        k: int = 10,
        agent_id: str | None = None,
    ) -> list[RecallHit]:
        from sqlite_vec import serialize_float32

        # 阶段 1：向量召回 candidate
        vec_rows = self.con.execute(
            """
            SELECT v.memory_id, v.distance
            FROM (
              SELECT memory_id, distance FROM memory_vec
              WHERE embedding MATCH ?
              ORDER BY distance LIMIT ?
            ) v
            """,
            (serialize_float32(query_embedding), max(k * 4, 16)),
        ).fetchall()

        # 阶段 2：FTS 召回 candidate（如有 query_text）
        fts_rows: list[tuple[str, float]] = []
        if query_text and query_text.strip():
            fts_rows = self.con.execute(
                """
                SELECT memory_id, bm25(memory_fts) AS rank
                FROM memory_fts
                WHERE memory_fts MATCH ?
                ORDER BY rank LIMIT ?
                """,
                (query_text, max(k * 4, 16)),
            ).fetchall()

        # 阶段 3：Reciprocal Rank Fusion
        rrf_k = 60
        scores: dict[str, float] = {}
        for rank, (mid, _dist) in enumerate(vec_rows):
            scores[mid] = scores.get(mid, 0.0) + 1.0 / (rrf_k + rank + 1)
        for rank, (mid, _bm) in enumerate(fts_rows):
            scores[mid] = scores.get(mid, 0.0) + 1.0 / (rrf_k + rank + 1)

        if not scores:
            return []

        # 阶段 4：拉真实 memory；过滤 agent / soft-deleted
        ids = list(scores.keys())
        placeholders = ",".join(["?"] * len(ids))
        params: list[Any] = list(ids)
        sql = (
            "SELECT id, agent_id, source_kind, source_ref, text, meta "
            "FROM memory "
            f"WHERE id IN ({placeholders}) AND deleted_at IS NULL"
        )
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        rows = self.con.execute(sql, params).fetchall()

        results: list[RecallHit] = []
        for row in rows:
            mid = row[0]
            results.append(
                RecallHit(
                    memory_id=mid,
                    text=row[4],
                    score=scores[mid],
                    source_kind=row[2],
                    source_ref=row[3],
                    meta=json.loads(row[5]) if row[5] else None,
                )
            )
        results.sort(key=lambda h: h.score, reverse=True)
        return results[:k]

    def list_memories(
        self,
        agent_id: str | None = None,
        limit: int = 50,
        before: int | None = None,
    ) -> list[Memory]:
        sql = (
            "SELECT id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at "
            "FROM memory WHERE deleted_at IS NULL"
        )
        params: list[Any] = []
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        if before is not None:
            sql += " AND created_at < ?"
            params.append(before)
        sql += " ORDER BY created_at DESC LIMIT ?"
        params.append(limit)
        return [_row_to_memory(r) for r in self.con.execute(sql, params).fetchall()]

    def delete_memory(self, memory_id: str) -> bool:
        cur = self.con.execute(
            "UPDATE memory SET deleted_at = strftime('%s','now') * 1000 "
            "WHERE id = ? AND deleted_at IS NULL",
            (memory_id,),
        )
        self.con.commit()
        return cur.rowcount > 0

    def close(self) -> None:
        self.con.close()

    # ───────────────── SUMMARY ─────────────────
    def upsert_summary(self, s: Summary) -> str:
        self.con.execute(
            """
            INSERT INTO derivative_summary(id, source_kind, source_ref, level, parent_id,
                                           text, token_estimate, created_at, rationale)
            VALUES(:id, :sk, :sr, :lv, :pid, :tx, :te, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              level = excluded.level, parent_id = excluded.parent_id,
              text = excluded.text, token_estimate = excluded.token_estimate,
              rationale = excluded.rationale
            """,
            {"id": s.id, "sk": s.source_kind, "sr": s.source_ref, "lv": s.level,
             "pid": s.parent_id, "tx": s.text, "te": s.token_estimate,
             "ca": s.created_at, "ra": s.rationale},
        )
        self.con.commit()
        return s.id

    def query_tree(self, source_kind: str, source_ref: str) -> list[Summary]:
        rows = self.con.execute(
            "SELECT id, source_kind, source_ref, level, parent_id, text, token_estimate, created_at, rationale "
            "FROM derivative_summary WHERE source_kind = ? AND source_ref = ? ORDER BY level ASC",
            (source_kind, source_ref),
        ).fetchall()
        return [Summary(*r) for r in rows]

    # ───────────────── ENTITY / TRIPLE ─────────────────
    def upsert_entity(self, e: Entity) -> str:
        meta = json.dumps(e.meta) if e.meta is not None else None
        self.con.execute(
            """
            INSERT INTO derivative_entity(id, name, kind, meta, first_seen_at, last_seen_at)
            VALUES(:id, :name, :kind, :meta, :fs, :ls)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name, kind = excluded.kind, meta = excluded.meta,
              last_seen_at = excluded.last_seen_at
            """,
            {"id": e.id, "name": e.name, "kind": e.kind, "meta": meta,
             "fs": e.first_seen_at, "ls": e.last_seen_at},
        )
        self.con.commit()
        return e.id

    def upsert_triple(self, t: Triple) -> str:
        self.con.execute(
            """
            INSERT OR REPLACE INTO derivative_triple
            (id, subject_id, predicate, object_id, source_memory_id, confidence, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """,
            (t.id, t.subject_id, t.predicate, t.object_id, t.source_memory_id, t.confidence, t.created_at),
        )
        self.con.commit()
        return t.id

    def upsert_pending_triple(self, *, id, subject_text, predicate, object_text,
                              source_memory_id, confidence, created_at):
        self.con.execute(
            "INSERT OR REPLACE INTO derivative_triple_pending"
            "(id, subject_text, predicate, object_text, source_memory_id, confidence, created_at) "
            "VALUES(?, ?, ?, ?, ?, ?, ?)",
            (id, subject_text, predicate, object_text, source_memory_id, confidence, created_at),
        )
        self.con.commit()
        return id

    def list_pending_triples(self, limit: int = 100) -> list[dict]:
        rows = self.con.execute(
            "SELECT id, subject_text, predicate, object_text, source_memory_id, confidence, created_at "
            "FROM derivative_triple_pending ORDER BY created_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
        keys = ["id", "subject_text", "predicate", "object_text", "source_memory_id", "confidence", "created_at"]
        return [dict(zip(keys, r)) for r in rows]

    def query_subgraph(self, seed_id: str, hops: int = 2) -> Subgraph:
        # BFS over derivative_triple
        visited: set[str] = set()
        frontier: set[str] = {seed_id}
        edges: list[tuple[str, str, dict]] = []
        for _ in range(hops):
            if not frontier:
                break
            placeholders = ",".join("?" * len(frontier))
            params = list(frontier) + list(frontier)
            rows = self.con.execute(
                f"SELECT subject_id, predicate, object_id, confidence "
                f"FROM derivative_triple "
                f"WHERE subject_id IN ({placeholders}) OR object_id IN ({placeholders})",
                params,
            ).fetchall()
            visited |= frontier
            new_frontier: set[str] = set()
            for s, p, o, conf in rows:
                edges.append((s, o, {"predicate": p, "confidence": conf}))
                for nid in (s, o):
                    if nid not in visited:
                        new_frontier.add(nid)
            frontier = new_frontier
        visited |= frontier  # include last frontier so all touched nodes are fetched
        # fetch entity nodes
        if not visited:
            return Subgraph(nodes=[], edges=[])
        ph = ",".join("?" * len(visited))
        node_rows = self.con.execute(
            f"SELECT id, name, kind, meta, first_seen_at, last_seen_at "
            f"FROM derivative_entity WHERE id IN ({ph})",
            list(visited),
        ).fetchall()
        nodes = [
            Entity(id=r[0], name=r[1], kind=r[2],
                   meta=json.loads(r[3]) if r[3] else None,
                   first_seen_at=r[4], last_seen_at=r[5])
            for r in node_rows
        ]
        return Subgraph(nodes=nodes, edges=edges)

    # ───────────────── EVENT (timeline) ─────────────────
    def upsert_event(self, e: Event) -> str:
        self.con.execute(
            """
            INSERT INTO derivative_event(id, window_start, window_end, agent_id, title, summary,
                                          member_memory_ids, created_at, rationale)
            VALUES(:id, :ws, :we, :ag, :t, :s, :mm, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              window_end = excluded.window_end, title = excluded.title,
              summary = excluded.summary, member_memory_ids = excluded.member_memory_ids,
              rationale = excluded.rationale
            """,
            {"id": e.id, "ws": e.window_start, "we": e.window_end, "ag": e.agent_id,
             "t": e.title, "s": e.summary,
             "mm": json.dumps(e.member_memory_ids), "ca": e.created_at, "ra": e.rationale},
        )
        self.con.commit()
        return e.id

    def query_timeline(self, start_ms: int, end_ms: int, agent_id: str | None = None) -> list[Event]:
        sql = ("SELECT id, window_start, window_end, agent_id, title, summary, member_memory_ids, "
               "created_at, rationale FROM derivative_event "
               "WHERE window_start >= ? AND window_start < ?")
        params: list[Any] = [start_ms, end_ms]
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        sql += " ORDER BY window_start DESC"
        rows = self.con.execute(sql, params).fetchall()
        return [
            Event(id=r[0], window_start=r[1], window_end=r[2], agent_id=r[3],
                  title=r[4], summary=r[5],
                  member_memory_ids=json.loads(r[6]) if r[6] else [],
                  created_at=r[7], rationale=r[8])
            for r in rows
        ]

    # ───────────────── SKILL ─────────────────
    def upsert_skill(self, s: Skill) -> str:
        from sqlite_vec import serialize_float32

        self.con.execute(
            """
            INSERT INTO derivative_skill(id, name, trigger_pattern, trigger_emb, steps,
                                         agent_scope, source, observed_count, success_count,
                                         last_used_at, created_at, rationale)
            VALUES(:id, :name, :tp, :te, :st, :sc, :sr, :oc, :sk, :lu, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name, trigger_pattern = excluded.trigger_pattern,
              trigger_emb = excluded.trigger_emb, steps = excluded.steps,
              agent_scope = excluded.agent_scope, source = excluded.source,
              observed_count = excluded.observed_count, success_count = excluded.success_count,
              last_used_at = excluded.last_used_at, rationale = excluded.rationale
            """,
            {"id": s.id, "name": s.name, "tp": s.trigger_pattern,
             "te": serialize_float32(s.trigger_emb) if s.trigger_emb else None,
             "st": json.dumps(s.steps), "sc": s.agent_scope, "sr": s.source,
             "oc": s.observed_count, "sk": s.success_count,
             "lu": s.last_used_at, "ca": s.created_at, "ra": s.rationale},
        )
        # skill_vec sync
        if s.trigger_emb is not None:
            if len(s.trigger_emb) != self.embedding_dim:
                raise ValueError(
                    f"skill trigger_emb dim {len(s.trigger_emb)} != configured {self.embedding_dim}"
                )
            self.con.execute("DELETE FROM skill_vec WHERE skill_id = ?", (s.id,))
            self.con.execute(
                "INSERT INTO skill_vec(skill_id, embedding) VALUES(?, ?)",
                (s.id, serialize_float32(s.trigger_emb)),
            )
        self.con.commit()
        return s.id

    def query_skills(self, query_emb: list[float], k: int = 5) -> list[Skill]:
        from sqlite_vec import serialize_float32

        vec_rows = self.con.execute(
            "SELECT skill_id, distance FROM skill_vec WHERE embedding MATCH ? "
            "ORDER BY distance LIMIT ?",
            (serialize_float32(query_emb), max(k * 2, 8)),
        ).fetchall()
        if not vec_rows:
            return []
        ids = [r[0] for r in vec_rows]
        ph = ",".join("?" * len(ids))
        rows = self.con.execute(
            f"SELECT id, name, trigger_pattern, steps, agent_scope, source, "
            f"observed_count, success_count, last_used_at, created_at, rationale "
            f"FROM derivative_skill WHERE id IN ({ph})",
            ids,
        ).fetchall()
        order = {sid: i for i, sid in enumerate(ids)}
        rows.sort(key=lambda r: order.get(r[0], 1_000_000))
        return [
            Skill(
                id=r[0], name=r[1], trigger_pattern=r[2], trigger_emb=None,
                steps=json.loads(r[3]) if r[3] else [],
                agent_scope=r[4], source=r[5],
                observed_count=r[6], success_count=r[7],
                last_used_at=r[8], created_at=r[9], rationale=r[10],
            )
            for r in rows[:k]
        ]


def _row_to_memory(row: tuple[Any, ...]) -> Memory:
    meta = json.loads(row[5]) if row[5] is not None else None
    return Memory(
        id=row[0],
        agent_id=row[1],
        source_kind=row[2],
        source_ref=row[3],
        text=row[4],
        meta=meta,
        created_at=row[6],
        updated_at=row[7],
        deleted_at=row[8],
    )
