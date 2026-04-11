"""Async HTTP client for lakeon-api /api/v1/internal/wiki/* endpoints.

All methods POST JSON bodies and return parsed JSON. Caller is responsible
for handling tool-level errors (e.g. `{ok: false, error: "..."}` from write tools).

This client does NOT retry on transient failures; the Python-side agent loop
owns retry policy and can decide per-tool-call whether to abort the run.
"""
from typing import Any

import httpx


class LakeonApiClient:
    def __init__(self, base_url: str, token: str, timeout: float = 30.0):
        self._base = base_url.rstrip("/")
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }
        self._timeout = timeout

    async def _post(self, path: str, body: dict[str, Any]) -> Any:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.post(
                f"{self._base}{path}", json=body, headers=self._headers
            )
            resp.raise_for_status()
            # /runlog returns empty body on 202
            if not resp.content:
                return {}
            return resp.json()

    # ── Read tools ─────────────────────────────────────────────

    async def list_pages(self, tenant_id: str, kb_id: str) -> list[dict]:
        return await self._post(
            "/api/v1/internal/wiki/tool/list_pages",
            {"tenant_id": tenant_id, "kb_id": kb_id},
        )

    async def read_page(self, tenant_id: str, kb_id: str, title: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/read_page",
            {"tenant_id": tenant_id, "kb_id": kb_id, "title": title},
        )

    async def search_pages(
        self, tenant_id: str, kb_id: str, query: str, top_k: int = 5
    ) -> list[dict]:
        return await self._post(
            "/api/v1/internal/wiki/tool/search_pages",
            {"tenant_id": tenant_id, "kb_id": kb_id, "query": query, "top_k": top_k},
        )

    async def read_source(
        self, tenant_id: str, kb_id: str, document_id: str
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/read_source",
            {"tenant_id": tenant_id, "kb_id": kb_id, "document_id": document_id},
        )

    async def get_schema(self, tenant_id: str, kb_id: str) -> str:
        r = await self._post(
            "/api/v1/internal/wiki/tool/get_schema",
            {"tenant_id": tenant_id, "kb_id": kb_id},
        )
        return r.get("schema", "")

    # ── Write tools ────────────────────────────────────────────

    async def create_page(
        self,
        tenant_id: str,
        kb_id: str,
        title: str,
        content: str,
        tags: list[str],
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/create_page",
            {
                "tenant_id": tenant_id,
                "kb_id": kb_id,
                "title": title,
                "content": content,
                "tags": tags,
            },
        )

    async def update_page(
        self,
        tenant_id: str,
        kb_id: str,
        title: str,
        old_text: str,
        new_text: str,
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/update_page",
            {
                "tenant_id": tenant_id,
                "kb_id": kb_id,
                "title": title,
                "old_text": old_text,
                "new_text": new_text,
            },
        )

    async def append_page(
        self, tenant_id: str, kb_id: str, title: str, content: str
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/append_page",
            {
                "tenant_id": tenant_id,
                "kb_id": kb_id,
                "title": title,
                "content": content,
            },
        )

    async def delete_page(self, tenant_id: str, kb_id: str, title: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/delete_page",
            {"tenant_id": tenant_id, "kb_id": kb_id, "title": title},
        )

    async def log_note(self, tenant_id: str, kb_id: str, message: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/log_note",
            {"tenant_id": tenant_id, "kb_id": kb_id, "message": message},
        )

    # ── Run log ────────────────────────────────────────────────

    async def write_runlog(self, payload: dict[str, Any]) -> None:
        """
        Persist an agent run log. Caller passes a dict with camelCase keys
        matching the Java WikiRunLogRequest DTO (tenantId, kbId, runId,
        runType, triggerDoc, pagesCreated, pagesUpdated, pagesDeleted,
        durationMs, status, errorMessage, toolCallsCount, tokenCount, source).
        """
        await self._post("/api/v1/internal/wiki/runlog", payload)
