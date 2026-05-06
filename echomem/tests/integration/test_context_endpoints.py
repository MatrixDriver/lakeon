import time
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": "ok"}, is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_add_url_writes_blob_and_returns_sha(client, httpx_mock):
    httpx_mock.add_response(method="GET", url="https://example.com/post",
                            headers={"content-type": "text/html"},
                            content=b"<html><body><article><p>Body text</p></article></body></html>")
    r = await client.post("/context/add_url", json={"url": "https://example.com/post"})
    assert r.status_code == 200
    body = r.json()
    assert "sha256" in body and len(body["sha256"]) == 64
    assert body["mime"].startswith("text/html")
    assert body["byte_size"] > 0


@pytest.mark.asyncio
async def test_write_then_read_by_path(client):
    r = await client.post("/context/write", json={
        "path": "notes/hello.md", "content": "hello world", "mime": "text/markdown"
    })
    assert r.status_code == 200
    sha = r.json()["sha256"]

    r2 = await client.get("/context/read", params={"path": "notes/hello.md"})
    assert r2.status_code == 200
    assert r2.json()["content"] == "hello world"
    assert r2.json()["sha256"] == sha


@pytest.mark.asyncio
async def test_read_by_sha256(client):
    r = await client.post("/context/write", json={"path": "x.txt", "content": "abc"})
    sha = r.json()["sha256"]
    r2 = await client.get("/context/read", params={"sha256": sha})
    assert r2.status_code == 200
    assert r2.json()["content"] == "abc"


@pytest.mark.asyncio
async def test_read_404_when_path_missing(client):
    r = await client.get("/context/read", params={"path": "nope.txt"})
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_ls_with_prefix(client):
    await client.post("/context/write", json={"path": "a/x.md", "content": "1"})
    await client.post("/context/write", json={"path": "a/y.md", "content": "2"})
    await client.post("/context/write", json={"path": "b/z.md", "content": "3"})
    r = await client.get("/context/ls", params={"prefix": "a/"})
    paths = {item["path"] for item in r.json()["items"]}
    assert paths == {"a/x.md", "a/y.md"}


@pytest.mark.asyncio
async def test_mv_then_resolve(client):
    await client.post("/context/write", json={"path": "draft.md", "content": "v1"})
    r = await client.post("/context/mv", json={"old": "draft.md", "new": "final.md"})
    assert r.status_code == 200
    r2 = await client.get("/context/read", params={"path": "final.md"})
    assert r2.json()["content"] == "v1"
    r3 = await client.get("/context/read", params={"path": "draft.md"})
    assert r3.status_code == 404


@pytest.mark.asyncio
async def test_mv_404_when_old_missing(client):
    r = await client.post("/context/mv", json={"old": "nope", "new": "still-nope"})
    assert r.status_code == 404
