from __future__ import annotations

import json
import re
import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Entity, Triple
from echomem.ollama_client import OllamaClient
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.entity_extractor")

EXTRACT_PROMPT = """Extract factual (subject, predicate, object) triples from the text.
Each triple must include a confidence score in [0, 1].
Output STRICT JSON, no commentary, in this exact shape:

{{"triples": [{{"subject": "...", "predicate": "...", "object": "...", "confidence": 0.0}}]}}

If you find no clear factual triples, return {{"triples": []}}.

Text:
{text}

JSON:"""


class EntityExtractorWorker:
    def __init__(
        self,
        driver: SQLiteDriver,
        ollama: OllamaClient,
        *,
        model: str,
        confidence_threshold: float = 0.7,
    ):
        self.driver = driver
        self.ollama = ollama
        self.model = model
        self.threshold = confidence_threshold

    @staticmethod
    def _entity_id(name: str) -> str:
        slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
        return f"ent:{slug}" if slug else f"ent:{new_id()}"

    async def handle(self, memory_id: str) -> None:
        m = self.driver.get_memory(memory_id)
        if m is None:
            return

        now = int(time.time() * 1000)
        try:
            raw = await self.ollama.generate(
                EXTRACT_PROMPT.format(text=m.text), model=self.model
            )
        except Exception as e:
            kind = type(e).__name__
            log.warning("extractor.llm_failed", memory_id=memory_id,
                        err_type=kind, err=str(e) or "<empty>")
            return

        triples = self._parse_triples(raw)
        if not triples:
            log.info("extractor.no_triples", memory_id=memory_id)
            return

        for t in triples:
            conf = float(t.get("confidence", 0.0))
            sub = str(t.get("subject", "")).strip()
            pred = str(t.get("predicate", "")).strip()
            obj = str(t.get("object", "")).strip()
            if not (sub and pred and obj):
                continue

            if conf < self.threshold:
                self.driver.upsert_pending_triple(
                    id=new_id(), subject_text=sub, predicate=pred, object_text=obj,
                    source_memory_id=memory_id, confidence=conf, created_at=now,
                )
                continue

            sid = self._entity_id(sub)
            oid = self._entity_id(obj)
            self.driver.upsert_entity(Entity(id=sid, name=sub, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_entity(Entity(id=oid, name=obj, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_triple(Triple(id=new_id(), subject_id=sid, predicate=pred,
                                             object_id=oid, source_memory_id=memory_id,
                                             confidence=conf, created_at=now))

        log.info("extractor.done", memory_id=memory_id, triples=len(triples))

    def _parse_triples(self, raw: str) -> list[dict]:
        # Try to find JSON object in response (gemma sometimes wraps with ``` or extra text)
        m = re.search(r"\{.*\}", raw, re.DOTALL)
        if not m:
            return []
        try:
            data = json.loads(m.group(0))
        except json.JSONDecodeError:
            return []
        return data.get("triples", []) if isinstance(data, dict) else []
