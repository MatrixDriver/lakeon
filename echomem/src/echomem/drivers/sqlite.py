from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any

import sqlite_vec

from echomem.drivers.base import Memory, RecallHit
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

    def close(self) -> None:
        self.con.close()


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
