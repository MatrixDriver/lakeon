"""Integration tests for POST /lbfs/derive endpoint."""
import sys, os, pytest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fastapi.testclient import TestClient
from main import app
import schema

client = TestClient(app)

TEST_CONNSTR = os.environ.get(
    "TEST_CONNSTR",
    "postgres://postgres@localhost:5432/memory_svc_test",
)


@pytest.fixture(scope="module")
def test_connstr():
    try:
        schema.init_schema(TEST_CONNSTR, retries=1)
    except Exception as e:
        pytest.skip(f"test Postgres unavailable: {e}")
    return TEST_CONNSTR


@pytest.fixture(autouse=True)
def clean_memories(test_connstr):
    import psycopg2
    conn = psycopg2.connect(test_connstr)
    with conn.cursor() as cur:
        cur.execute("DELETE FROM memories")
    conn.commit()
    conn.close()


@pytest.fixture(autouse=True)
def mock_embedding(monkeypatch):
    """Stub out get_embedding to avoid hitting the external embedding API."""
    async def fake_embedding(text: str):
        return [0.1] * 1024
    import engine
    monkeypatch.setattr(engine, "get_embedding", fake_embedding)


def _req(op, path, **kw):
    base = {
        "tenant_id": "tn_abc", "op": op, "path": path,
        "source_etag": "e1", "source_agent": "claude",
    }
    base.update(kw)
    return base


def test_derive_create_returns_200(test_connstr):
    r = client.post("/lbfs/derive",
        headers={"x-database-connstr": test_connstr},
        json=_req("create", "/memory/foo.md", content="body", memory_type="procedural"))
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ingested"
    assert body["memory_id"] is not None


def test_derive_duplicate_is_idempotent_noop(test_connstr):
    req = _req("create", "/memory/dup.md", content="body", memory_type="procedural")
    r1 = client.post("/lbfs/derive", headers={"x-database-connstr": test_connstr}, json=req)
    r2 = client.post("/lbfs/derive", headers={"x-database-connstr": test_connstr}, json=req)
    assert r1.status_code == 200 and r1.json()["status"] == "ingested"
    assert r2.status_code == 200 and r2.json()["status"] == "idempotent_noop"
    # only one row
    import psycopg2
    conn = psycopg2.connect(test_connstr)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM memories WHERE metadata->>'source_path'='/memory/dup.md'")
        assert cur.fetchone()[0] == 1
    conn.close()


def test_derive_delete_removes_row(test_connstr):
    # seed
    client.post("/lbfs/derive", headers={"x-database-connstr": test_connstr},
      json=_req("create", "/memory/rm.md", content="b", memory_type="procedural"))
    # delete
    r = client.post("/lbfs/derive", headers={"x-database-connstr": test_connstr},
      json=_req("delete", "/memory/rm.md"))
    assert r.status_code == 200
    assert r.json()["status"] == "deleted"
    import psycopg2
    conn = psycopg2.connect(test_connstr)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM memories WHERE metadata->>'source_path'='/memory/rm.md'")
        assert cur.fetchone()[0] == 0
    conn.close()


def test_derive_create_without_content_returns_400(test_connstr):
    r = client.post("/lbfs/derive",
        headers={"x-database-connstr": test_connstr},
        json=_req("create", "/memory/missing.md"))  # no content / memory_type
    assert r.status_code == 400


def test_derive_metadata_includes_source_fields(test_connstr):
    req = _req("create", "/memory/mf.md",
               content="c", memory_type="fact",
               source_frontmatter={"type": "feedback", "name": "x"})
    r = client.post("/lbfs/derive",
        headers={"x-database-connstr": test_connstr}, json=req)
    assert r.status_code == 200
    import psycopg2, json as js
    conn = psycopg2.connect(test_connstr)
    with conn.cursor() as cur:
        cur.execute("SELECT metadata FROM memories WHERE metadata->>'source_path'='/memory/mf.md'")
        md = cur.fetchone()[0]
    assert md["source_system"] == "lbfs"
    assert md["source_path"] == "/memory/mf.md"
    assert md["source_etag"] == "e1"
    assert md["source_agent"] == "claude"
    assert md["source_frontmatter"]["type"] == "feedback"
    conn.close()
