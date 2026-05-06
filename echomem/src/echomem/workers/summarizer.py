from __future__ import annotations

import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Summary
from echomem.ollama_client import OllamaClient
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.summarizer")

# Token estimates are character-based heuristics; close enough for tier sizing.
L0_MAX_TOKENS = 100
L1_MAX_TOKENS = 500
CHARS_PER_TOKEN = 4  # rough heuristic

L0_PROMPT = (
    "Summarize the following text in at most {max_chars} characters. "
    "Be terse and concrete. Return only the summary, no preamble.\n\n"
    "Text:\n{text}\n\nSummary:"
)
L1_PROMPT = (
    "Summarize the following text in at most {max_chars} characters. "
    "Cover the main points with enough detail to be useful, but do not include "
    "every detail. Return only the summary, no preamble.\n\n"
    "Text:\n{text}\n\nSummary:"
)


def _truncate(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 1].rstrip() + "…"


class SummarizerWorker:
    def __init__(self, driver: SQLiteDriver, ollama: OllamaClient, *, model: str,
                 blob_store=None):
        self.driver = driver
        self.ollama = ollama
        self.model = model
        self.blob_store = blob_store

    def _load_text(self, source_kind: str, source_ref: str) -> str | None:
        if source_kind == "memory":
            m = self.driver.get_memory(source_ref)
            return m.text if m else None
        if source_kind == "blob":
            if self.blob_store is None:
                log.warning("summarizer.no_blob_store")
                return None
            try:
                return self.blob_store.read(source_ref).decode("utf-8", errors="replace")
            except FileNotFoundError:
                return None
        log.warning("summarizer.unknown_kind", source_kind=source_kind)
        return None

    async def handle(self, source_kind: str, source_ref: str) -> None:
        text = self._load_text(source_kind, source_ref)
        if text is None:
            log.warning("summarizer.skip_missing", source_kind=source_kind, source_ref=source_ref)
            return

        now = int(time.time() * 1000)

        # L2: original chunk; no LLM call
        l2 = Summary(
            id=new_id(), source_kind=source_kind, source_ref=source_ref, level=2,
            parent_id=None, text=text,
            token_estimate=len(text) // CHARS_PER_TOKEN,
            created_at=now, rationale="L2 = original chunk",
        )
        self.driver.upsert_summary(l2)

        # L0: ≤ 100 tokens (~400 chars)
        l0_text, l0_rationale = await self._gen_or_truncate(
            text, L0_PROMPT, L0_MAX_TOKENS * CHARS_PER_TOKEN, "L0"
        )
        l0 = Summary(
            id=new_id(), source_kind=source_kind, source_ref=source_ref, level=0,
            parent_id=None, text=l0_text,
            token_estimate=len(l0_text) // CHARS_PER_TOKEN,
            created_at=now, rationale=l0_rationale,
        )
        self.driver.upsert_summary(l0)

        # L1: ≤ 500 tokens (~2000 chars). Skip when original text is already
        # shorter than the L1 budget — generating L1 would just echo the source
        # at the cost of a 1-3 min CPU-only gemma call. The L0 + L2 pair is
        # already enough for short memories.
        if len(text) > L1_MAX_TOKENS * CHARS_PER_TOKEN:
            l1_text, l1_rationale = await self._gen_or_truncate(
                text, L1_PROMPT, L1_MAX_TOKENS * CHARS_PER_TOKEN, "L1"
            )
            l1 = Summary(
                id=new_id(), source_kind=source_kind, source_ref=source_ref, level=1,
                parent_id=l0.id, text=l1_text,
                token_estimate=len(l1_text) // CHARS_PER_TOKEN,
                created_at=now, rationale=l1_rationale,
            )
            self.driver.upsert_summary(l1)

        log.info("summarized", source_kind=source_kind, source_ref=source_ref)

    async def _gen_or_truncate(self, text: str, prompt_tpl: str, max_chars: int, tier: str):
        try:
            prompt = prompt_tpl.format(max_chars=max_chars, text=text)
            # num_predict caps gemma output so it can't blow past httpx timeout on
            # mixed-language prompts where the model fails to emit EOS naturally.
            # Token budget ≈ max_chars / 4 + headroom.
            num_predict = max(max_chars // 3, 128)
            out = await self.ollama.generate(
                prompt, model=self.model, options={"num_predict": num_predict}
            )
            return out.strip(), f"{tier} from gemma"
        except Exception as e:
            kind = type(e).__name__
            log.warning("summarizer.fallback", tier=tier, err_type=kind, err=str(e) or "<empty>")
            return _truncate(text, max_chars), f"{tier} fallback (truncate-prefix)"
