"""Integration tests for ingest_idempotent engine helper.

Requires a local Postgres reachable via TEST_CONNSTR env var
(defaults to postgres://postgres@localhost:5432/memory_svc_test).
Postgres must have pgvector extension available.

To set up locally:
    createdb memory_svc_test
    psql memory_svc_test -c 'CREATE EXTENSION vector;'

If the DB isn't available, tests skip gracefully via the fixture.
"""
import sys, os, pytest, asyncio
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import schema
from engine import ingest_idempotent


TEST_CONNSTR = os.environ.get(
    "TEST_CONNSTR",
    "postgres://postgres@localhost:5432/memory_svc_test",
)


@pytest.fixture(scope="module")
def test_connstr():
    """Initialize the test DB schema; assume caller created the DB."""
    try:
        schema.init_schema(TEST_CONNSTR, retries=1)
    except Exception as e:
        pytest.skip(f"test Postgres unavailable: {e}")
    return TEST_CONNSTR


@pytest.fixture(autouse=True)
def clean_memories(test_connstr):
    """Wipe memories before each test."""
    import psycopg2
    conn = psycopg2.connect(test_connstr)
    with conn.cursor() as cur:
        cur.execute("DELETE FROM memories")
    conn.commit()
    conn.close()


@pytest.mark.asyncio
async def test_ingest_idempotent_first_call_returns_memory(test_connstr):
    metadata = {"source_path": "/memory/x.md", "source_etag": "ETA1"}
    m = await ingest_idempotent(
        test_connstr, "body1", "procedural", 0.5, metadata,
        embedding=[0.1] * 1024,
    )
    assert m is not None
    assert m.content == "body1"
    assert m.memory_type == "procedural"


@pytest.mark.asyncio
async def test_ingest_idempotent_duplicate_returns_none(test_connstr):
    metadata = {"source_path": "/memory/x.md", "source_etag": "ETA2"}
    m1 = await ingest_idempotent(
        test_connstr, "body1", "procedural", 0.5, metadata,
        embedding=[0.1] * 1024,
    )
    assert m1 is not None
    m2 = await ingest_idempotent(
        test_connstr, "body1", "procedural", 0.5, metadata,
        embedding=[0.1] * 1024,
    )
    assert m2 is None  # on conflict do nothing


@pytest.mark.asyncio
async def test_ingest_idempotent_different_etag_inserts_new(test_connstr):
    meta1 = {"source_path": "/memory/y.md", "source_etag": "E1"}
    meta2 = {"source_path": "/memory/y.md", "source_etag": "E2"}
    m1 = await ingest_idempotent(test_connstr, "v1", "fact", 0.5, meta1, embedding=[0.1]*1024)
    m2 = await ingest_idempotent(test_connstr, "v2", "fact", 0.5, meta2, embedding=[0.1]*1024)
    assert m1 is not None and m2 is not None
    assert m1.id != m2.id


@pytest.mark.asyncio
async def test_delete_by_source_path_removes_matching(test_connstr):
    from engine import delete_by_source_path
    meta = {"source_path": "/memory/gone.md", "source_etag": "E1"}
    await ingest_idempotent(test_connstr, "body", "fact", 0.5, meta, embedding=[0.1]*1024)
    n = await delete_by_source_path(test_connstr, "/memory/gone.md")
    assert n == 1
    # deleting again returns 0 (no match)
    n2 = await delete_by_source_path(test_connstr, "/memory/gone.md")
    assert n2 == 0


@pytest.mark.asyncio
async def test_delete_by_source_path_leaves_other_paths(test_connstr):
    from engine import delete_by_source_path
    meta_a = {"source_path": "/memory/a.md", "source_etag": "E1"}
    meta_b = {"source_path": "/memory/b.md", "source_etag": "E1"}
    await ingest_idempotent(test_connstr, "a", "fact", 0.5, meta_a, embedding=[0.1]*1024)
    await ingest_idempotent(test_connstr, "b", "fact", 0.5, meta_b, embedding=[0.1]*1024)
    n = await delete_by_source_path(test_connstr, "/memory/a.md")
    assert n == 1
    # b.md still present
    n2 = await delete_by_source_path(test_connstr, "/memory/b.md")
    assert n2 == 1
