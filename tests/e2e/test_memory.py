"""E2E tests for memory module."""
import json
import time
import pytest


@pytest.fixture(scope="module")
def e2e_client(e2e_tenant):
    return e2e_tenant["client"]


@pytest.fixture(scope="module")
def mem_base(e2e_client):
    """Memory base in Agent-Extract mode (one_llm_mode=True)."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-{int(time.time())}", one_llm_mode=True
    )
    for _ in range(60):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(2)
    yield info
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass


@pytest.fixture(scope="module")
def mem_base_server_mode(e2e_client):
    """Memory base in normal mode (one_llm_mode=False)."""
    base = e2e_client.create_memory_base(
        name=f"e2e-mem-server-{int(time.time())}", one_llm_mode=False
    )
    for _ in range(60):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(2)
    yield info
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass


def test_base_crud(e2e_client):
    """Create / get / list / delete memory base."""
    base = e2e_client.create_memory_base(name=f"crud-test-{int(time.time())}")
    assert base["name"].startswith("crud-test-")
    assert "id" in base

    for _ in range(60):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(2)
    assert info["status"] == "READY"

    bases = e2e_client.list_memory_bases()
    assert any(b["id"] == base["id"] for b in bases)

    e2e_client.delete_memory_base(base["id"])


def test_agent_extract_fact(mem_base, e2e_client):
    """Ingest -> ingest_extracted (fact) in Agent-Extract mode."""
    result = e2e_client.mem_ingest(mem_base["id"], content="User is a software engineer")
    assert result["extraction_required"] is True
    assert "extraction_prompt" in result
    msg_id = result["message_id"]

    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "User is a software engineer", "category": "work", "importance": 0.8}]
    })
    assert counts["facts_stored"] == 1


def test_agent_extract_decision(mem_base, e2e_client):
    """Decision with metadata.rationale stored correctly."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Chose asyncpg over SQLAlchemy")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "decisions": [{"content": "Chose asyncpg over SQLAlchemy", "rationale": "Full async project", "project": "lakeon"}]
    })
    assert counts["decisions_stored"] == 1

    memories = e2e_client.mem_list(mem_base["id"], memory_type="decision")
    assert memories["total"] >= 1
    decision = next(m for m in memories["memories"] if "asyncpg" in m["content"])
    assert decision["metadata"]["rationale"] == "Full async project"
    assert decision["metadata"]["project"] == "lakeon"


def test_agent_extract_rejection(mem_base, e2e_client):
    """Rejection with metadata.reason stored."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Rejected Redis")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "rejections": [{"content": "Rejected Redis caching", "reason": "Too much ops overhead", "project": "lakeon"}]
    })
    assert counts["rejections_stored"] == 1


def test_agent_extract_convention(mem_base, e2e_client):
    """Convention with metadata.scope stored."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Use HTTPException for errors")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "conventions": [{"content": "All API errors use HTTPException", "scope": "architecture", "project": "lakeon"}]
    })
    assert counts["conventions_stored"] == 1


def test_agent_extract_all_6_types(mem_base, e2e_client):
    """Ingest_extracted with all 6 types, verify counts."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Full extraction test")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "Fact one"}],
        "episodes": [{"content": "Episode one"}],
        "procedural": [{"content": "Run pytest"}],
        "decisions": [{"content": "Decision one", "rationale": "Because"}],
        "rejections": [{"content": "Rejection one", "reason": "Bad fit"}],
        "conventions": [{"content": "Convention one", "scope": "style"}],
    })
    assert counts["facts_stored"] == 1
    assert counts["episodes_stored"] == 1
    assert counts["procedural_stored"] == 1
    assert counts["decisions_stored"] == 1
    assert counts["rejections_stored"] == 1
    assert counts["conventions_stored"] == 1


def test_recall_basic(mem_base, e2e_client):
    """Recall returns relevant memories."""
    result = e2e_client.mem_recall(mem_base["id"], query="asyncpg database choice")
    assert "memories" in result
    assert len(result["memories"]) > 0


def test_recall_type_filter(mem_base, e2e_client):
    """memory_types filter works."""
    result = e2e_client.mem_recall(mem_base["id"], query="asyncpg",
                                    memory_types=["decision"])
    for m in result["memories"]:
        assert m["memory_type"] == "decision"


def test_list_type_filter(mem_base, e2e_client):
    """List with memory_type filter."""
    result = e2e_client.mem_list(mem_base["id"], memory_type="rejection")
    assert result["total"] >= 1
    for m in result["memories"]:
        assert m["memory_type"] == "rejection"


def test_delete_memory(mem_base, e2e_client):
    """Delete a single memory."""
    result = e2e_client.mem_ingest(mem_base["id"], content="Temporary memory")
    msg_id = result["message_id"]
    counts = e2e_client.mem_ingest_extracted(mem_base["id"], msg_id, {
        "facts": [{"content": "To be deleted"}]
    })
    assert counts["facts_stored"] == 1

    memories = e2e_client.mem_list(mem_base["id"])
    target = next(m for m in memories["memories"] if m["content"] == "To be deleted")
    e2e_client.mem_delete(mem_base["id"], target["id"])

    memories_after = e2e_client.mem_list(mem_base["id"])
    assert not any(m["id"] == target["id"] for m in memories_after["memories"])


def test_stats(mem_base, e2e_client):
    """Stats by_type counts."""
    stats = e2e_client.mem_stats(mem_base["id"])
    assert stats["total"] > 0
    assert "decision" in stats["by_type"]


def test_digest_agent_extract(mem_base, e2e_client):
    """Digest in Agent-Extract mode returns prompt + unreflected memories."""
    result = e2e_client.mem_digest(mem_base["id"])
    assert result["one_llm_mode"] is True
    assert result["unreflected_count"] > 0
    assert "reflection_prompt" in result
    assert len(result["memories"]) > 0


def test_digest_extracted(mem_base, e2e_client):
    """digest_extracted stores traits."""
    result = e2e_client.mem_digest_extracted(mem_base["id"], {
        "traits": [
            {"content": "Prefers async libraries over ORMs", "category": "pattern", "importance": 8}
        ]
    })
    assert result["traits_stored"] == 1


@pytest.mark.llm
def test_server_extract(mem_base_server_mode, e2e_client):
    """Ingest in normal mode: async extraction produces memories."""
    result = e2e_client.mem_ingest(mem_base_server_mode["id"],
                                    content="I work as a data engineer at Google")
    assert result["extraction_required"] is False
    assert result["status"] == "extracting"

    for _ in range(60):
        time.sleep(2)
        stats = e2e_client.mem_stats(mem_base_server_mode["id"])
        if stats["total"] > 0:
            break
    assert stats["total"] > 0


@pytest.mark.llm
def test_server_extract_decision(mem_base_server_mode, e2e_client):
    """Server extracts decision type from conversation."""
    result = e2e_client.mem_ingest(
        mem_base_server_mode["id"],
        content="我们讨论了用 asyncpg 还是 SQLAlchemy，最终决定用 asyncpg，因为项目是全异步的",
        auto_extract=True,
    )
    assert result["status"] == "extracting"

    for _ in range(60):
        time.sleep(2)
        memories = e2e_client.mem_list(mem_base_server_mode["id"], memory_type="decision")
        if memories["total"] >= 1:
            break
    assert memories["total"] >= 1
    assert any("asyncpg" in m["content"] for m in memories["memories"])


@pytest.mark.llm
def test_digest_server(mem_base_server_mode, e2e_client):
    """Digest in normal mode generates traits automatically."""
    result = e2e_client.mem_digest(mem_base_server_mode["id"])
    assert result["one_llm_mode"] is False
    assert "traits_generated" in result


@pytest.fixture
def tenant_b():
    """Create a second tenant for isolation tests. Always cleaned up."""
    from tests.e2e.conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN
    from dbay_cli.client import DbayClient
    ts = int(time.time())
    client_b, tenant_b = _create_tenant_with_invite(
        ENDPOINT, ADMIN_TOKEN,
        f"e2e-memb-{ts}", f"E2eTest@{ts}", f"Tenant B {ts}"
    )
    yield client_b, tenant_b
    # Guaranteed cleanup
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    try:
        admin.admin_batch_delete_tenants([tenant_b["id"]])
    except Exception:
        pass


def test_multi_tenant_isolation(e2e_client, mem_base, tenant_b):
    """Tenant A cannot access tenant B's memories."""
    client_b, _ = tenant_b

    from dbay_cli.client import DbayApiError
    with pytest.raises(DbayApiError) as exc:
        client_b.get_memory_base(mem_base["id"])
    assert exc.value.status_code == 404
