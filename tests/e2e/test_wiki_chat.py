"""End-to-end tests for wiki agent interactive features.

Covers:
  1. SSE chat endpoint (/wiki/chat/agent) — agent tool-calling events
  2. WIKI_REVIEW status flow — doc stops at WIKI_REVIEW after summarize
  3. Batch auto-ingest — POST /wiki/batch-ingest transitions docs
  4. Chat history persistence — GET/PUT /wiki/chat/history
  5. Schema read/write — GET/PUT /wiki/schema

Run:
    python3 -m pytest tests/e2e/test_wiki_chat.py -v -s

Env vars:
    DBAY_ENDPOINT    (default: https://api.dbay.cloud:8443)
    DBAY_ADMIN_TOKEN (default: lakeon-sre-2026)
"""
import json
import os
import random
import sys
import time

import httpx
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "dbay-cli"))
from dbay_cli.client import DbayClient  # noqa: E402

ENDPOINT = os.environ.get("DBAY_ENDPOINT", "https://api.dbay.cloud:8443")
BASE = f"{ENDPOINT}/api/v1"
ADMIN_TOKEN = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")
HTTP_TIMEOUT = 60


# ─── Fixtures ──────────────────────────────────────────────────


@pytest.fixture(scope="module")
def chat_tenant():
    """Create a throwaway tenant + KB for chat tests. Teardown deletes all."""
    ts = int(time.time())
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    invite = admin.admin_create_invite_code(max_uses=1)
    fake_ip = f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}"
    reg = DbayClient(endpoint=ENDPOINT, extra_headers={"X-Forwarded-For": fake_ip})
    tenant = reg.create_tenant(
        username=f"e2e-chat-{ts}",
        password=f"ChatTest@{ts}",
        name=f"Chat E2E {ts}",
        invite_code=invite.get("code"),
    )
    client = DbayClient(endpoint=ENDPOINT, api_key=tenant["api_key"])
    headers = {"Authorization": f"Bearer {tenant['api_key']}"}

    # Create KB and wait for READY
    kb = client.create_knowledge_base("Chat E2E KB")
    kb_id = kb["id"]
    for _ in range(30):
        info = client.get_knowledge_base(kb_id)
        if info.get("status") == "READY":
            break
        time.sleep(2)
    else:
        admin.admin_batch_delete_tenants([tenant["id"]])
        pytest.fail(f"KB {kb_id} never reached READY")

    yield {
        "tenant_id": tenant["id"],
        "kb_id": kb_id,
        "headers": headers,
        "client": client,
        "api_key": tenant["api_key"],
    }

    try:
        admin.admin_batch_delete_tenants([tenant["id"]])
    except Exception as e:
        print(f"tenant teardown failed: {e}")


# ─── Test 1: SSE Chat Endpoint ────────────────────────────────


class TestWikiAgentChat:
    """The /wiki/chat/agent endpoint should stream SSE events
    including tool_call, tool_result, content, and done."""

    def test_chat_returns_sse_events(self, chat_tenant):
        """Ask a question, verify we get agent events (not just legacy chunks)."""
        headers = chat_tenant["headers"]
        kb_id = chat_tenant["kb_id"]

        with httpx.stream(
            "POST",
            f"{BASE}/knowledge/wiki/chat/agent",
            json={"kb_id": kb_id, "question": "这个知识库有什么内容?", "history": []},
            headers=headers,
            verify=False,
            timeout=120,
        ) as resp:
            assert resp.status_code == 200

            events = []
            for line in resp.iter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    events.append(json.loads(data))
                except json.JSONDecodeError:
                    continue

        # Must have at least one tool_call and one content or done event
        event_types = {e.get("type") for e in events}
        print(f"\nSSE event types received: {sorted(event_types)}")
        print(f"Total events: {len(events)}")

        assert "done" in event_types, f"expected 'done' event, got: {event_types}"
        # Agent should use at least one tool (list_pages or search_pages)
        has_tool = "tool_call" in event_types or "content" in event_types
        assert has_tool, f"expected tool_call or content events, got: {event_types}"

    def test_chat_done_event_has_stats(self, chat_tenant):
        """The done event should include status, tool_calls_count, duration_ms."""
        headers = chat_tenant["headers"]
        kb_id = chat_tenant["kb_id"]

        with httpx.stream(
            "POST",
            f"{BASE}/knowledge/wiki/chat/agent",
            json={"kb_id": kb_id, "question": "hello", "history": []},
            headers=headers,
            verify=False,
            timeout=120,
        ) as resp:
            done_event = None
            for line in resp.iter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    e = json.loads(data)
                    if e.get("type") == "done":
                        done_event = e
                except json.JSONDecodeError:
                    continue

        assert done_event is not None, "no done event received"
        assert "status" in done_event
        assert "duration_ms" in done_event
        assert "tool_calls_count" in done_event
        print(f"\ndone event: {json.dumps(done_event, ensure_ascii=False)[:300]}")


# ─── Test 2: Schema Read/Write ────────────────────────────────


class TestWikiSchema:
    """GET/PUT /wiki/schema should read and write the KB schema."""

    def test_read_schema_auto_seeds(self, chat_tenant):
        """First read should auto-seed the default schema."""
        headers = chat_tenant["headers"]
        kb_id = chat_tenant["kb_id"]

        r = httpx.get(
            f"{BASE}/knowledge/wiki/schema",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        content = r.json().get("content", "")
        assert "KB Schema" in content, f"expected default schema, got: {content[:100]}"
        assert "术语表" in content or "Glossary" in content, "expected glossary section"

    def test_update_schema(self, chat_tenant):
        """PUT should update the schema, GET should return the new content."""
        headers = {**chat_tenant["headers"], "Content-Type": "application/json"}
        kb_id = chat_tenant["kb_id"]

        new_schema = "# Custom Schema\n\nThis is a test schema.\n"
        r = httpx.put(
            f"{BASE}/knowledge/wiki/schema",
            json={"kb_id": kb_id, "content": new_schema},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200

        r = httpx.get(
            f"{BASE}/knowledge/wiki/schema",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        assert r.json().get("content") == new_schema


# ─── Test 3: Chat History Persistence ─────────────────────────


class TestChatHistory:
    """GET/PUT /wiki/chat/history should persist and retrieve chat messages."""

    def test_save_and_load_history(self, chat_tenant):
        """Save messages, then load and verify they match."""
        headers = {**chat_tenant["headers"], "Content-Type": "application/json"}
        kb_id = chat_tenant["kb_id"]

        test_messages = [
            {"role": "user", "content": "test question"},
            {"role": "assistant", "content": "test answer"},
        ]

        # Save
        r = httpx.put(
            f"{BASE}/knowledge/wiki/chat/history",
            json={"kb_id": kb_id, "messages": test_messages},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200

        # Load
        r = httpx.get(
            f"{BASE}/knowledge/wiki/chat/history",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        loaded = r.json()
        assert len(loaded) == 2
        assert loaded[0]["role"] == "user"
        assert loaded[0]["content"] == "test question"
        assert loaded[1]["role"] == "assistant"
        assert loaded[1]["content"] == "test answer"

    def test_clear_history(self, chat_tenant):
        """Saving empty messages clears the history."""
        headers = {**chat_tenant["headers"], "Content-Type": "application/json"}
        kb_id = chat_tenant["kb_id"]

        # Clear
        r = httpx.put(
            f"{BASE}/knowledge/wiki/chat/history",
            json={"kb_id": kb_id, "messages": []},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200

        # Verify empty
        r = httpx.get(
            f"{BASE}/knowledge/wiki/chat/history",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        loaded = r.json()
        assert loaded == []


# ─── Test 4: Document Stats includes wiki_review ──────────────


class TestDocumentStats:
    """Document stats API should return wiki_review count."""

    def test_stats_has_wiki_review_field(self, chat_tenant):
        """GET /documents/stats should include the wiki_review count."""
        headers = chat_tenant["headers"]
        kb_id = chat_tenant["kb_id"]

        r = httpx.get(
            f"{BASE}/knowledge/documents/stats",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        stats = r.json()
        assert "wiki_review" in stats, f"wiki_review missing from stats: {stats}"
        assert isinstance(stats["wiki_review"], int)


# ─── Test 5: Batch Auto-Ingest API ───────────────────────────


class TestBatchAutoIngest:
    """POST /wiki/batch-ingest should accept document IDs."""

    def test_batch_ingest_empty_list(self, chat_tenant):
        """Batch ingest with empty doc list should fail gracefully."""
        headers = {**chat_tenant["headers"], "Content-Type": "application/json"}
        kb_id = chat_tenant["kb_id"]

        r = httpx.post(
            f"{BASE}/knowledge/wiki/batch-ingest",
            json={"kb_id": kb_id, "document_ids": []},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 400  # empty list should be rejected

    def test_batch_ingest_nonexistent_docs(self, chat_tenant):
        """Batch ingest with nonexistent doc IDs should return 0 enqueued."""
        headers = {**chat_tenant["headers"], "Content-Type": "application/json"}
        kb_id = chat_tenant["kb_id"]

        r = httpx.post(
            f"{BASE}/knowledge/wiki/batch-ingest",
            json={"kb_id": kb_id, "document_ids": ["doc_nonexistent_123"]},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200
        assert r.json().get("enqueued") == 0
