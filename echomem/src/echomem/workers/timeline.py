from __future__ import annotations

import math
import struct

from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Event
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.timeline")

WINDOW_MS = 30 * 60 * 1000  # 30 minutes
SIMILARITY_THRESHOLD = 0.7


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def _load_memory_embedding(driver: SQLiteDriver, memory_id: str) -> list[float] | None:
    row = driver.con.execute(
        "SELECT embedding FROM memory_vec WHERE memory_id = ?", (memory_id,)
    ).fetchone()
    if row is None or row[0] is None:
        return None
    blob: bytes = row[0]
    n = len(blob) // 4
    return list(struct.unpack(f"{n}f", blob))


class TimelineWorker:
    """Aggregate memories into Episodic events.
    Pure-Python; no LLM calls. Uses memory_vec embeddings to gauge topic similarity."""

    def __init__(self, driver: SQLiteDriver):
        self.driver = driver

    def handle(self, memory_id: str) -> None:
        m = self.driver.get_memory(memory_id)
        if m is None:
            return

        emb = _load_memory_embedding(self.driver, memory_id) or []
        # find candidate event in same agent + within window of m.created_at
        candidate = self._find_open_event(m.agent_id, m.created_at)
        if candidate is not None and self._is_similar(candidate, emb):
            self._extend(candidate, m.id, m.created_at)
        else:
            self._open_new_event(m.agent_id, m.id, m.created_at, m.text)

    def _find_open_event(self, agent_id: str, ts: int) -> Event | None:
        rows = self.driver.query_timeline(
            start_ms=ts - WINDOW_MS, end_ms=ts + 1, agent_id=agent_id
        )
        return rows[0] if rows else None

    def _is_similar(self, ev: Event, emb: list[float]) -> bool:
        if not emb or not ev.member_memory_ids:
            return True  # no signal → join (cheap heuristic)
        # take the first member's embedding as the event centroid (cheap)
        first_emb = _load_memory_embedding(self.driver, ev.member_memory_ids[0])
        if not first_emb:
            return True
        return _cosine(emb, first_emb) >= SIMILARITY_THRESHOLD

    def _extend(self, ev: Event, memory_id: str, ts: int) -> None:
        members = list(ev.member_memory_ids) + [memory_id]
        new_we = max(ev.window_end, ts)
        updated = Event(
            id=ev.id, window_start=ev.window_start, window_end=new_we,
            agent_id=ev.agent_id, title=ev.title, summary=ev.summary,
            member_memory_ids=members, created_at=ev.created_at,
            rationale=(ev.rationale or "") + f"; appended {memory_id}",
        )
        self.driver.upsert_event(updated)

    def _open_new_event(self, agent_id: str, memory_id: str, ts: int, sample_text: str) -> None:
        title = sample_text[:60].replace("\n", " ")
        ev = Event(
            id=new_id(), window_start=ts, window_end=ts, agent_id=agent_id,
            title=title or "(untitled)", summary=None,
            member_memory_ids=[memory_id], created_at=ts,
            rationale="opened new event (no nearby similar event)",
        )
        self.driver.upsert_event(ev)
