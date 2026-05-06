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
        blob_store=None,
    ):
        self.driver = driver
        self.ollama = ollama
        self.model = model
        self.threshold = confidence_threshold
        self.blob_store = blob_store

    @staticmethod
    def _entity_id(name: str) -> str:
        # Latin/ASCII slug for English entity names (jacky → ent:jacky).
        ascii_slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
        if ascii_slug:
            return f"ent:{ascii_slug}"
        # CJK / Unicode names: hash the original to get a stable, name-derived id.
        # Avoids using a fresh ULID (which made identical names produce different
        # entities on every extract). 12 hex chars = 48 bits, enough collision
        # resistance for entity-graph scale.
        import hashlib
        digest = hashlib.sha256(name.encode("utf-8")).hexdigest()[:12]
        return f"ent:u_{digest}"

    def _load_text(self, source_kind: str, source_ref: str) -> str | None:
        if source_kind == "memory":
            m = self.driver.get_memory(source_ref)
            return m.text if m else None
        if source_kind == "blob":
            if self.blob_store is None:
                log.warning("extractor.no_blob_store")
                return None
            try:
                return self.blob_store.read(source_ref).decode("utf-8", errors="replace")
            except FileNotFoundError:
                return None
        log.warning("extractor.unknown_kind", source_kind=source_kind)
        return None

    async def handle(self, source_kind: str, source_ref: str) -> None:
        # NOTE: derivative_triple.source_memory_id is a plain TEXT column (no FK),
        # so blob sha256 values are valid there when source_kind="blob".
        text = self._load_text(source_kind, source_ref)
        if text is None:
            return

        now = int(time.time() * 1000)
        try:
            # Cap output: typical extraction yields ≤10 triples × ~30 tokens each,
            # plus JSON overhead. 1024 tokens is generous and prevents runaway
            # generation on mixed-language inputs.
            raw = await self.ollama.generate(
                EXTRACT_PROMPT.format(text=text), model=self.model,
                options={"num_predict": 1024},
            )
        except Exception as e:
            kind = type(e).__name__
            log.warning("extractor.llm_failed", source_kind=source_kind, source_ref=source_ref,
                        err_type=kind, err=str(e) or "<empty>")
            return

        triples = self._parse_triples(raw)
        if not triples:
            log.info("extractor.no_triples", source_kind=source_kind, source_ref=source_ref)
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
                    source_memory_id=source_ref, confidence=conf, created_at=now,
                )
                continue

            sid = self._entity_id(sub)
            oid = self._entity_id(obj)
            self.driver.upsert_entity(Entity(id=sid, name=sub, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_entity(Entity(id=oid, name=obj, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_triple(Triple(id=new_id(), subject_id=sid, predicate=pred,
                                             object_id=oid, source_memory_id=source_ref,
                                             confidence=conf, created_at=now))

        log.info("extractor.done", source_kind=source_kind, source_ref=source_ref,
                 triples=len(triples))

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
