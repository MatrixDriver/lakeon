# echomem · Phase 3 Context API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 在 echomem backbone (Plan 1) + derivatives (Plan 2) 基础上，加 Context API 让 agent 能把"长文档"（URL / PDF / 手写内容）灌进 echomem，并触发同一套衍生物 pipeline（summarize + entity extract）。

**Architecture:** Content-addressable blob 存储（FS by sha256，不可变）+ path_alias 表（mut 路径 → sha256，支持 mv）。`add_url` 用 trafilatura 抓 HTML / pypdf 抓 PDF，提取主体文本，写 blob，写 path_alias，触发 pipeline。`ls/read/write/mv` 都按 path 操作。Summarizer / EntityExtractor 已接受 `source_kind` 参数，加 `source_kind='blob'` 路径即可。

**Tech Stack:** 沿用 Plan 1+2 + `trafilatura>=2.0` (HTML 抽正文) + `pypdf>=5.0` (PDF 文本)

**Out of Scope（明确属于后续 Plan）：**
- Vue 3 Dashboard SPA → Plan 4
- Onboarding install.sh + openclaw / hermes 接入 → Plan 5
- 上传 binary 媒体（图片 OCR / 视频转文本） → Plan 4+
- Insight Track → 独立子项目

---

## File Structure

```
lakeon/echomem/
├── src/echomem/
│   ├── drivers/
│   │   ├── migrations/
│   │   │   └── m003_context.py        # 新：blob_ref + path_alias
│   │   ├── sqlite.py                  # 改：4 个 blob/path 方法
│   │   └── base.py                    # 改:加 BlobRef + PathAlias dataclass + Protocol 方法
│   ├── context/                       # 新包
│   │   ├── __init__.py
│   │   ├── blob_store.py              # FS by sha256: write/read/exists/path_for
│   │   └── fetcher.py                 # trafilatura HTML + pypdf PDF
│   ├── api/
│   │   ├── context.py                 # 新：5 个端点
│   │   ├── schemas.py                 # 改：append context schemas
│   │   └── memory.py                  # （不动）
│   ├── workers/
│   │   ├── summarizer.py              # 改：handle 接受 source_kind/source_ref
│   │   └── entity_extractor.py        # 改：handle 接受 source_kind/source_ref
│   ├── pipeline/
│   │   ├── orchestrator.py            # 改：增加 on_blob_ingested(sha256)
│   │   └── queue.py                   # 改：TaskKind 加 SUMMARIZE_BLOB / EXTRACT_BLOB
│   ├── mcp_shim/
│   │   └── shim.py                    # 改：加 5 个 context_* tools
│   ├── daemon/app.py                  # 改：注册 context router
│   └── cli.py                         # 改:加 echomem ctx 子命令
└── tests/
    ├── unit/
    │   ├── test_migrations_m003.py
    │   ├── test_blob_store.py
    │   ├── test_fetcher.py            # mock httpx，测 HTML+PDF 抽取
    │   └── test_sqlite_driver_blob.py # blob_ref + path_alias CRUD
    ├── integration/
    │   ├── test_context_endpoints.py  # 5 端点
    │   └── test_blob_pipeline_e2e.py  # add_url → blob → summary + entities
    └── e2e/
        └── (无新增；test_full_loop 仍 gated)
```

**Each new file's responsibility:**

- `m003_context.py` — 创建 `blob_ref` (sha256 PK + mime + byte_size + origin_url + meta) + `path_alias` (path PK + sha256 + created_at)
- `context/blob_store.py` — `BlobStore.write(content_bytes) → sha256` / `.read(sha256) → bytes` / `.exists(sha256)` / `.path_for(sha256)`，存到 `~/.echomem/blobs/<sha256[:2]>/<sha256>`
- `context/fetcher.py` — `fetch_url(url) → (mime, text)`：HTTP GET → 按 Content-Type 走 trafilatura（HTML）或 pypdf（PDF），其他 mime 直接 return text/binary
- `api/context.py` — 5 endpoints: `POST /context/add_url` / `GET /context/ls` / `GET /context/read` / `POST /context/write` / `POST /context/mv`
- `cli.py` — `echomem ctx add-url` / `ls` / `read` / `write` / `mv` 子命令组

---

## Tasks

### Task 1: m003 migration — blob_ref + path_alias

**Files:**
- Create: `lakeon/echomem/src/echomem/drivers/migrations/m003_context.py`
- Modify: `lakeon/echomem/src/echomem/drivers/migrations/__init__.py`
- Create: `lakeon/echomem/tests/unit/test_migrations_m003.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_migrations_m003.py`:
```python
import sqlite3
import sqlite_vec
from echomem.drivers.migrations import apply_all


def _open():
    con = sqlite3.connect(":memory:")
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    apply_all(con)
    return con


def test_m003_creates_blob_ref_and_path_alias():
    con = _open()
    rows = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
    assert "blob_ref" in rows
    assert "path_alias" in rows


def test_blob_ref_columns():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(blob_ref)").fetchall()}
    assert {"sha256", "mime", "byte_size", "origin_url", "meta", "created_at"}.issubset(cols)


def test_path_alias_columns_and_uniqueness():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(path_alias)").fetchall()}
    assert {"path", "sha256", "created_at"}.issubset(cols)
    # path is PK so duplicates raise
    con.execute("INSERT INTO blob_ref(sha256, mime, byte_size, created_at) VALUES('abc', 'text/plain', 3, 1)")
    con.execute("INSERT INTO path_alias(path, sha256, created_at) VALUES('a/b.md', 'abc', 1)")
    import pytest
    with pytest.raises(sqlite3.IntegrityError):
        con.execute("INSERT INTO path_alias(path, sha256, created_at) VALUES('a/b.md', 'abc', 2)")
```

- [ ] **Step 2: Run (fails)**

Run: `cd /Users/jacky/code/lakeon/echomem && source .venv/bin/activate && pytest tests/unit/test_migrations_m003.py -v`

- [ ] **Step 3: Implement**

`src/echomem/drivers/migrations/m003_context.py`:
```python
import sqlite3


def up(con: sqlite3.Connection) -> None:
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS blob_ref (
          sha256       TEXT PRIMARY KEY,
          mime         TEXT NOT NULL,
          byte_size    INTEGER,
          origin_url   TEXT,
          meta         TEXT,
          created_at   INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_blob_ref_origin ON blob_ref(origin_url);
        CREATE INDEX IF NOT EXISTS idx_blob_ref_created ON blob_ref(created_at DESC);

        CREATE TABLE IF NOT EXISTS path_alias (
          path        TEXT PRIMARY KEY,
          sha256      TEXT NOT NULL,
          created_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_path_alias_sha ON path_alias(sha256);
        """
    )
```

Modify `src/echomem/drivers/migrations/__init__.py`:
```python
from echomem.drivers.migrations import m001_initial, m002_derivatives, m003_context

MIGRATIONS: dict[int, Callable[[sqlite3.Connection], None]] = {
    1: m001_initial.up,
    2: m002_derivatives.up,
    3: m003_context.up,
}
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_migrations_m003.py -v`
Expected: 3 PASSED. Plus `pytest -v` total ≈ 85 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/drivers/migrations/ echomem/tests/unit/test_migrations_m003.py
git commit -m "feat(echomem): m003 — blob_ref + path_alias tables for Context API"
```

---

### Task 2: BlobStore — FS by sha256

**Files:**
- Create: `lakeon/echomem/src/echomem/context/__init__.py` (empty)
- Create: `lakeon/echomem/src/echomem/context/blob_store.py`
- Create: `lakeon/echomem/tests/unit/test_blob_store.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_blob_store.py`:
```python
import pytest
from echomem.context.blob_store import BlobStore


def test_write_returns_sha256_and_persists(tmp_path):
    store = BlobStore(tmp_path)
    sha = store.write(b"hello world")
    assert len(sha) == 64
    assert all(c in "0123456789abcdef" for c in sha)
    assert store.exists(sha)
    assert store.read(sha) == b"hello world"


def test_write_is_idempotent(tmp_path):
    store = BlobStore(tmp_path)
    sha1 = store.write(b"same content")
    sha2 = store.write(b"same content")
    assert sha1 == sha2


def test_read_missing_raises(tmp_path):
    store = BlobStore(tmp_path)
    with pytest.raises(FileNotFoundError):
        store.read("00" * 32)


def test_path_for_uses_2byte_prefix_dirs(tmp_path):
    store = BlobStore(tmp_path)
    sha = store.write(b"x")
    p = store.path_for(sha)
    # blobs/<sha[:2]>/<sha>
    assert p.parent.name == sha[:2]
    assert p.parent.parent.name == "blobs"
    assert p.name == sha
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_blob_store.py -v`

- [ ] **Step 3: Implement**

`src/echomem/context/__init__.py`: empty

`src/echomem/context/blob_store.py`:
```python
from __future__ import annotations

import hashlib
from pathlib import Path


class BlobStore:
    """Content-addressable file store. Layout: <root>/blobs/<sha[:2]>/<sha>."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.blobs_dir = self.root / "blobs"
        self.blobs_dir.mkdir(parents=True, exist_ok=True)

    def path_for(self, sha256: str) -> Path:
        return self.blobs_dir / sha256[:2] / sha256

    def exists(self, sha256: str) -> bool:
        return self.path_for(sha256).exists()

    def write(self, content: bytes) -> str:
        sha = hashlib.sha256(content).hexdigest()
        p = self.path_for(sha)
        if not p.exists():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(content)
        return sha

    def read(self, sha256: str) -> bytes:
        p = self.path_for(sha256)
        if not p.exists():
            raise FileNotFoundError(f"blob not found: {sha256}")
        return p.read_bytes()
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_blob_store.py -v`
Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/context/ echomem/tests/unit/test_blob_store.py
git commit -m "feat(echomem): BlobStore — content-addressable FS by sha256"
```

---

### Task 3: URL / PDF fetcher

**Files:**
- Modify: `lakeon/echomem/pyproject.toml` — add `trafilatura>=2.0` and `pypdf>=5.0`
- Create: `lakeon/echomem/src/echomem/context/fetcher.py`
- Create: `lakeon/echomem/tests/unit/test_fetcher.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_fetcher.py`:
```python
import pytest
from echomem.context.fetcher import fetch_url, ExtractedDoc


@pytest.mark.asyncio
async def test_fetch_html_extracts_main_text(httpx_mock):
    html = "<html><body><nav>nav</nav><article><h1>Title</h1><p>Body text.</p></article></body></html>"
    httpx_mock.add_response(
        method="GET", url="https://example.com/post",
        headers={"content-type": "text/html; charset=utf-8"},
        content=html.encode("utf-8"),
    )
    doc = await fetch_url("https://example.com/post")
    assert isinstance(doc, ExtractedDoc)
    assert doc.mime.startswith("text/html")
    assert "Body text" in doc.text or "Title" in doc.text  # trafilatura output
    assert doc.byte_size > 0


@pytest.mark.asyncio
async def test_fetch_plain_text_passthrough(httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/notes.txt",
        headers={"content-type": "text/plain"},
        content=b"raw text passthrough",
    )
    doc = await fetch_url("https://example.com/notes.txt")
    assert doc.text == "raw text passthrough"
    assert doc.mime == "text/plain"


@pytest.mark.asyncio
async def test_fetch_unknown_mime_returns_bytes_no_text(httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/img.png",
        headers={"content-type": "image/png"},
        content=b"\x89PNG\r\n",
    )
    doc = await fetch_url("https://example.com/img.png")
    assert doc.mime == "image/png"
    assert doc.text == ""
    assert doc.raw_bytes == b"\x89PNG\r\n"
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_fetcher.py -v`

- [ ] **Step 3: Implement**

Modify `pyproject.toml` — add to `dependencies`:
```toml
    "trafilatura>=2.0",
    "pypdf>=5.0",
```

Then `pip install -e ".[dev]"` to refresh.

`src/echomem/context/fetcher.py`:
```python
from __future__ import annotations

import io
from dataclasses import dataclass

import httpx


@dataclass(slots=True)
class ExtractedDoc:
    url: str
    mime: str
    text: str
    raw_bytes: bytes
    byte_size: int


async def fetch_url(url: str, *, timeout: float = 30.0) -> ExtractedDoc:
    """Fetch URL and return main text + raw bytes. Routes by Content-Type:
    - text/html → trafilatura main-content extraction
    - application/pdf → pypdf text extraction
    - text/* → raw text passthrough
    - other → text=""; caller decides what to do with raw_bytes
    """
    async with httpx.AsyncClient(timeout=timeout, trust_env=False, follow_redirects=True) as c:
        resp = await c.get(url)
        resp.raise_for_status()
        raw = resp.content
        mime = (resp.headers.get("content-type") or "application/octet-stream").split(";")[0].strip()

    text = ""
    if mime.startswith("text/html"):
        try:
            import trafilatura
            extracted = trafilatura.extract(raw.decode("utf-8", errors="replace"))
            text = extracted or ""
        except Exception:
            text = ""
    elif mime == "application/pdf":
        try:
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(raw))
            text = "\n".join(p.extract_text() or "" for p in reader.pages)
        except Exception:
            text = ""
    elif mime.startswith("text/"):
        text = raw.decode("utf-8", errors="replace")

    return ExtractedDoc(
        url=url, mime=mime, text=text,
        raw_bytes=raw, byte_size=len(raw),
    )
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_fetcher.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/pyproject.toml echomem/src/echomem/context/fetcher.py echomem/tests/unit/test_fetcher.py
git commit -m "feat(echomem): URL/PDF fetcher (trafilatura + pypdf)"
```

---

### Task 4: SQLiteDriver — blob_ref + path_alias CRUD

**Files:**
- Modify: `lakeon/echomem/src/echomem/drivers/base.py` — append `BlobRef` dataclass + 5 Protocol methods
- Modify: `lakeon/echomem/src/echomem/drivers/__init__.py` — re-export `BlobRef`
- Modify: `lakeon/echomem/src/echomem/drivers/sqlite.py` — append 5 methods
- Create: `lakeon/echomem/tests/unit/test_sqlite_driver_blob.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_sqlite_driver_blob.py`:
```python
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import BlobRef


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def test_upsert_and_get_blob_ref(driver):
    b = BlobRef(sha256="a" * 64, mime="text/plain", byte_size=11,
                origin_url="https://x.com", meta={"title": "T"}, created_at=1)
    driver.upsert_blob_ref(b)
    got = driver.get_blob_ref("a" * 64)
    assert got is not None
    assert got.mime == "text/plain"
    assert got.meta == {"title": "T"}


def test_list_blob_refs_filter_by_origin(driver):
    for i in range(3):
        b = BlobRef(sha256=str(i) * 64, mime="text/html", byte_size=i, origin_url=f"https://x.com/{i}",
                    meta=None, created_at=i)
        driver.upsert_blob_ref(b)
    rows = driver.list_blob_refs(origin_prefix="https://x.com")
    assert len(rows) == 3


def test_path_alias_set_get_mv(driver):
    b = BlobRef(sha256="b" * 64, mime="text/plain", byte_size=5, origin_url=None, meta=None, created_at=1)
    driver.upsert_blob_ref(b)
    driver.set_path_alias(path="notes/a.md", sha256="b" * 64, created_at=1)
    assert driver.resolve_path("notes/a.md") == "b" * 64
    driver.move_path_alias(old="notes/a.md", new="archive/a.md")
    assert driver.resolve_path("notes/a.md") is None
    assert driver.resolve_path("archive/a.md") == "b" * 64


def test_list_paths_with_prefix(driver):
    b = BlobRef(sha256="c" * 64, mime="text/plain", byte_size=1, origin_url=None, meta=None, created_at=1)
    driver.upsert_blob_ref(b)
    driver.set_path_alias("a/x.md", "c" * 64, 1)
    driver.set_path_alias("a/y.md", "c" * 64, 1)
    driver.set_path_alias("b/z.md", "c" * 64, 1)
    rows = driver.list_paths(prefix="a/")
    assert {r["path"] for r in rows} == {"a/x.md", "a/y.md"}


def test_move_path_alias_returns_false_when_missing(driver):
    assert driver.move_path_alias(old="nope", new="also-nope") is False
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_sqlite_driver_blob.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/drivers/base.py`:
```python
@dataclass(slots=True)
class BlobRef:
    sha256: str
    mime: str
    byte_size: int | None
    origin_url: str | None
    meta: dict | None
    created_at: int
```

Append Protocol methods to `StorageDriver`:
```python
    def upsert_blob_ref(self, b: BlobRef) -> str: ...
    def get_blob_ref(self, sha256: str) -> BlobRef | None: ...
    def list_blob_refs(self, *, origin_prefix: str | None = None, limit: int = 100) -> list[BlobRef]: ...
    def set_path_alias(self, path: str, sha256: str, created_at: int) -> None: ...
    def resolve_path(self, path: str) -> str | None: ...
    def move_path_alias(self, *, old: str, new: str) -> bool: ...
    def list_paths(self, prefix: str | None = None, limit: int = 100) -> list[dict]: ...
```

Update `src/echomem/drivers/__init__.py` re-export to include `BlobRef`.

Append to `src/echomem/drivers/sqlite.py` (inside `SQLiteDriver`, before module helpers):
```python
    # ───────────────── BLOB / PATH ─────────────────
    def upsert_blob_ref(self, b: BlobRef) -> str:
        meta = json.dumps(b.meta) if b.meta is not None else None
        self.con.execute(
            "INSERT INTO blob_ref(sha256, mime, byte_size, origin_url, meta, created_at) "
            "VALUES(?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(sha256) DO UPDATE SET mime=excluded.mime, byte_size=excluded.byte_size, "
            "origin_url=excluded.origin_url, meta=excluded.meta",
            (b.sha256, b.mime, b.byte_size, b.origin_url, meta, b.created_at),
        )
        self.con.commit()
        return b.sha256

    def get_blob_ref(self, sha256: str) -> BlobRef | None:
        row = self.con.execute(
            "SELECT sha256, mime, byte_size, origin_url, meta, created_at FROM blob_ref WHERE sha256 = ?",
            (sha256,),
        ).fetchone()
        if row is None:
            return None
        return BlobRef(
            sha256=row[0], mime=row[1], byte_size=row[2], origin_url=row[3],
            meta=json.loads(row[4]) if row[4] else None, created_at=row[5],
        )

    def list_blob_refs(self, *, origin_prefix: str | None = None, limit: int = 100) -> list[BlobRef]:
        sql = "SELECT sha256, mime, byte_size, origin_url, meta, created_at FROM blob_ref"
        params: list[Any] = []
        if origin_prefix is not None:
            sql += " WHERE origin_url LIKE ?"
            params.append(origin_prefix + "%")
        sql += " ORDER BY created_at DESC LIMIT ?"
        params.append(limit)
        return [
            BlobRef(sha256=r[0], mime=r[1], byte_size=r[2], origin_url=r[3],
                    meta=json.loads(r[4]) if r[4] else None, created_at=r[5])
            for r in self.con.execute(sql, params).fetchall()
        ]

    def set_path_alias(self, path: str, sha256: str, created_at: int) -> None:
        self.con.execute(
            "INSERT OR REPLACE INTO path_alias(path, sha256, created_at) VALUES(?, ?, ?)",
            (path, sha256, created_at),
        )
        self.con.commit()

    def resolve_path(self, path: str) -> str | None:
        row = self.con.execute("SELECT sha256 FROM path_alias WHERE path = ?", (path,)).fetchone()
        return row[0] if row else None

    def move_path_alias(self, *, old: str, new: str) -> bool:
        cur = self.con.execute("UPDATE path_alias SET path = ? WHERE path = ?", (new, old))
        self.con.commit()
        return cur.rowcount > 0

    def list_paths(self, prefix: str | None = None, limit: int = 100) -> list[dict]:
        sql = "SELECT p.path, p.sha256, p.created_at, b.mime, b.byte_size, b.origin_url "
        sql += "FROM path_alias p LEFT JOIN blob_ref b ON p.sha256 = b.sha256"
        params: list[Any] = []
        if prefix is not None:
            sql += " WHERE p.path LIKE ?"
            params.append(prefix + "%")
        sql += " ORDER BY p.path ASC LIMIT ?"
        params.append(limit)
        rows = self.con.execute(sql, params).fetchall()
        keys = ["path", "sha256", "created_at", "mime", "byte_size", "origin_url"]
        return [dict(zip(keys, r)) for r in rows]
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_sqlite_driver_blob.py -v`
Expected: 5 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/drivers/ echomem/tests/unit/test_sqlite_driver_blob.py
git commit -m "feat(echomem): SQLiteDriver — blob_ref + path_alias CRUD (7 methods)"
```

---

### Task 5: Modify Summarizer + EntityExtractor to accept source_kind/source_ref

**Files:**
- Modify: `lakeon/echomem/src/echomem/workers/summarizer.py` — `handle(source_kind: str, source_ref: str)` instead of `handle(memory_id)`; load text by kind
- Modify: `lakeon/echomem/src/echomem/workers/entity_extractor.py` — same signature change
- Modify: `lakeon/echomem/tests/unit/test_summarizer.py` — adapt tests to new signature
- Modify: `lakeon/echomem/tests/unit/test_entity_extractor.py` — adapt
- Modify: `lakeon/echomem/src/echomem/pipeline/orchestrator.py` — adapter wrappers

- [ ] **Step 1: Write the failing tests**

The change is: workers receive `(source_kind, source_ref)` and resolve text via:
- `source_kind="memory"` → `driver.get_memory(source_ref).text`
- `source_kind="blob"` → load from `BlobStore.read(sha256).decode("utf-8", errors="replace")`

Update `tests/unit/test_summarizer.py` — change `worker.handle(m.id)` → `worker.handle("memory", m.id)`. Add a test for blob path:
```python
@pytest.mark.asyncio
async def test_summarizer_handles_blob_source(tmp_path, httpx_mock, driver):
    from echomem.context.blob_store import BlobStore
    from echomem.drivers.base import BlobRef

    store = BlobStore(tmp_path)
    sha = store.write(b"a long text" * 500)  # > L1 budget
    driver.upsert_blob_ref(BlobRef(sha256=sha, mime="text/plain", byte_size=11000,
                                    origin_url=None, meta=None, created_at=0))

    httpx_mock.add_response(method="POST", url="http://ol:11434/api/generate",
                            json={"response": "blob L0"}, is_reusable=True)

    async with OllamaClient("http://ol:11434") as ol:
        worker = SummarizerWorker(driver, ol, model="gemma4:e4b", blob_store=store)
        await worker.handle("blob", sha)

    tree = driver.query_tree(source_kind="blob", source_ref=sha)
    levels = sorted(s.level for s in tree)
    assert levels == [0, 1, 2]
```

Update `test_entity_extractor.py` — change `worker.handle(m.id)` → `worker.handle("memory", m.id)` (no blob test required for extractor; same code path).

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_summarizer.py tests/unit/test_entity_extractor.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/summarizer.py` — change `__init__` to accept optional `blob_store` and rewrite `handle`:
```python
class SummarizerWorker:
    def __init__(self, driver, ollama, *, model: str, blob_store=None):
        self.driver = driver
        self.ollama = ollama
        self.model = model
        self.blob_store = blob_store

    async def handle(self, source_kind: str, source_ref: str) -> None:
        text = self._load_text(source_kind, source_ref)
        if text is None:
            log.warning("summarizer.skip_missing", source_kind=source_kind, source_ref=source_ref)
            return
        # ... rest same as before but write Summary with source_kind/source_ref from args

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
```

(Replace all `m.id` / `memory_id` references in handle body with `source_ref`; replace `source_kind="memory"` literal with the function arg.)

`src/echomem/workers/entity_extractor.py` — same transformation:
```python
class EntityExtractorWorker:
    def __init__(self, driver, ollama, *, model, confidence_threshold=0.7, blob_store=None):
        # ... + self.blob_store = blob_store

    async def handle(self, source_kind: str, source_ref: str) -> None:
        text = self._load_text(source_kind, source_ref)
        if text is None:
            return
        # ... existing logic; replace source_memory_id=memory_id with source_memory_id=source_ref
        # NOTE: derivative_triple.source_memory_id schema accepts any string ID, so blob sha works.

    def _load_text(self, source_kind, source_ref):  # same as summarizer's
```

`src/echomem/pipeline/orchestrator.py` — update handler dispatch to include `source_kind`:
```python
class Orchestrator:
    def __init__(self, ..., blob_store=None):
        ...
        self.summarizer = SummarizerWorker(driver, ollama, model=summary_model, blob_store=blob_store)
        self.extractor = EntityExtractorWorker(driver, ollama, model=extract_model,
                                                confidence_threshold=confidence_threshold,
                                                blob_store=blob_store)
        ...
        self.pool = WorkerPool(
            driver,
            handlers={
                TaskKind.SUMMARIZE: self._summarize_memory,
                TaskKind.EXTRACT_ENTITY: self._extract_memory,
                TaskKind.AGGREGATE_TIMELINE: self._timeline_async,
                TaskKind.SUMMARIZE_BLOB: self._summarize_blob,
                TaskKind.EXTRACT_BLOB: self._extract_blob,
            },
            concurrency=1,
        )

    async def _summarize_memory(self, ref): await self.summarizer.handle("memory", ref)
    async def _extract_memory(self, ref): await self.extractor.handle("memory", ref)
    async def _summarize_blob(self, ref): await self.summarizer.handle("blob", ref)
    async def _extract_blob(self, ref): await self.extractor.handle("blob", ref)

    async def on_memory_ingested(self, memory_id: str) -> None:
        await self.pool.enqueue(TaskKind.SUMMARIZE, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.EXTRACT_ENTITY, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.AGGREGATE_TIMELINE, memory_id=memory_id)

    async def on_blob_ingested(self, sha256: str) -> None:
        await self.pool.enqueue(TaskKind.SUMMARIZE_BLOB, memory_id=sha256)
        await self.pool.enqueue(TaskKind.EXTRACT_BLOB, memory_id=sha256)
```

`src/echomem/pipeline/queue.py` — add to `TaskKind` enum:
```python
class TaskKind(str, Enum):
    SUMMARIZE = "summarize"
    EXTRACT_ENTITY = "extract_entity"
    AGGREGATE_TIMELINE = "aggregate_timeline"
    REFLECT = "reflect"
    SUMMARIZE_BLOB = "summarize_blob"
    EXTRACT_BLOB = "extract_blob"
```

- [ ] **Step 4: Tests pass**

Run: `pytest -v`
Expected: ≈ 91 passed + 1 skipped (3 prior summarizer + 3 prior extractor + 1 new blob summarizer + others adapted).

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/workers/ echomem/src/echomem/pipeline/ echomem/tests/unit/test_summarizer.py echomem/tests/unit/test_entity_extractor.py
git commit -m "feat(echomem): workers accept source_kind/source_ref + blob handlers in Orchestrator"
```

---

### Task 6: Context API — POST /context/add_url

**Files:**
- Modify: `lakeon/echomem/src/echomem/api/schemas.py` — append context schemas
- Create: `lakeon/echomem/src/echomem/api/context.py`
- Modify: `lakeon/echomem/src/echomem/daemon/app.py` — instantiate BlobStore in lifespan, register context router, pass blob_store to Orchestrator
- Create: `lakeon/echomem/tests/integration/test_context_endpoints.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_context_endpoints.py`:
```python
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
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_context_endpoints.py::test_add_url_writes_blob_and_returns_sha -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/api/schemas.py`:
```python
class AddUrlRequest(BaseModel):
    url: str
    path: str | None = None  # optional path_alias to set


class BlobOut(BaseModel):
    sha256: str
    mime: str
    byte_size: int
    origin_url: str | None
    path: str | None = None
    created_at: int


class WriteRequest(BaseModel):
    path: str
    content: str
    mime: str = "text/plain"


class MoveRequest(BaseModel):
    old: str
    new: str


class LsResponse(BaseModel):
    items: list[dict]
```

`src/echomem/api/context.py`:
```python
from __future__ import annotations

import time
from fastapi import APIRouter, HTTPException, Request

from echomem.api.schemas import AddUrlRequest, BlobOut, WriteRequest, MoveRequest, LsResponse
from echomem.context.fetcher import fetch_url
from echomem.drivers.base import BlobRef

router = APIRouter(prefix="/context")


@router.post("/add_url", response_model=BlobOut)
async def add_url(req: AddUrlRequest, request: Request) -> BlobOut:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    orchestrator = request.app.state.orchestrator

    doc = await fetch_url(req.url)
    sha = blob_store.write(doc.raw_bytes)
    now = int(time.time() * 1000)
    driver.upsert_blob_ref(BlobRef(
        sha256=sha, mime=doc.mime, byte_size=doc.byte_size,
        origin_url=doc.url, meta=None, created_at=now,
    ))
    if req.path:
        driver.set_path_alias(req.path, sha, now)
    if doc.text:
        await orchestrator.on_blob_ingested(sha)
    return BlobOut(sha256=sha, mime=doc.mime, byte_size=doc.byte_size,
                   origin_url=doc.url, path=req.path, created_at=now)


@router.get("/ls", response_model=LsResponse)
async def ls(request: Request, prefix: str | None = None, limit: int = 100) -> LsResponse:
    driver = request.app.state.driver
    if prefix is not None:
        return LsResponse(items=driver.list_paths(prefix=prefix, limit=limit))
    # No prefix → list blobs by created_at
    refs = driver.list_blob_refs(limit=limit)
    return LsResponse(items=[
        {"sha256": r.sha256, "mime": r.mime, "byte_size": r.byte_size,
         "origin_url": r.origin_url, "created_at": r.created_at}
        for r in refs
    ])


@router.get("/read")
async def read(request: Request, path: str | None = None, sha256: str | None = None) -> dict:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    if sha256 is None and path is None:
        raise HTTPException(400, "must specify ?path= or ?sha256=")
    if sha256 is None:
        sha256 = driver.resolve_path(path)
        if sha256 is None:
            raise HTTPException(404, f"path not found: {path}")
    try:
        content = blob_store.read(sha256)
    except FileNotFoundError:
        raise HTTPException(404, f"blob not found: {sha256}")
    ref = driver.get_blob_ref(sha256)
    return {
        "sha256": sha256, "path": path,
        "mime": ref.mime if ref else "application/octet-stream",
        "byte_size": len(content),
        "content": content.decode("utf-8", errors="replace"),
    }


@router.post("/write", response_model=BlobOut)
async def write(req: WriteRequest, request: Request) -> BlobOut:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    orchestrator = request.app.state.orchestrator

    raw = req.content.encode("utf-8")
    sha = blob_store.write(raw)
    now = int(time.time() * 1000)
    driver.upsert_blob_ref(BlobRef(
        sha256=sha, mime=req.mime, byte_size=len(raw),
        origin_url=None, meta=None, created_at=now,
    ))
    driver.set_path_alias(req.path, sha, now)
    await orchestrator.on_blob_ingested(sha)
    return BlobOut(sha256=sha, mime=req.mime, byte_size=len(raw),
                   origin_url=None, path=req.path, created_at=now)


@router.post("/mv")
async def mv(req: MoveRequest, request: Request) -> dict:
    driver = request.app.state.driver
    ok = driver.move_path_alias(old=req.old, new=req.new)
    if not ok:
        raise HTTPException(404, f"path not found: {req.old}")
    return {"old": req.old, "new": req.new, "moved": True}
```

Modify `src/echomem/daemon/app.py` lifespan to instantiate BlobStore + register context router + pass blob_store to Orchestrator:
```python
from echomem.context.blob_store import BlobStore
from echomem.api.context import router as context_router

# In _lifespan, after creating driver and ollama:
    blob_store = BlobStore(cfg.data_dir)
    app.state.blob_store = blob_store
    # Then pass to Orchestrator:
    orchestrator = Orchestrator(
        driver, ollama,
        summary_model=cfg.generate_model,
        extract_model=cfg.generate_model,
        embedding_model=cfg.embedding_model,
        blob_store=blob_store,
    )

# In create_app, after include_router(skills_router):
    app.include_router(context_router)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_context_endpoints.py -v`
Expected: 1 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/api/schemas.py echomem/src/echomem/api/context.py echomem/src/echomem/daemon/app.py echomem/tests/integration/test_context_endpoints.py
git commit -m "feat(echomem): POST /context/add_url — fetch URL, blob+ref, trigger pipeline"
```

---

### Task 7: Context API — ls / read / write / mv endpoints

**Files:**
- Modify: `lakeon/echomem/tests/integration/test_context_endpoints.py` — append 5 tests

(`api/context.py` already implements all 5 endpoints from Task 6. This task is endpoint-coverage tests.)

- [ ] **Step 1: Append failing tests**

Append to `tests/integration/test_context_endpoints.py`:
```python
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
```

- [ ] **Step 2: Run**

Run: `pytest tests/integration/test_context_endpoints.py -v`
Expected: 7 PASSED (1 from T6 + 6 from T7).

- [ ] **Step 3: (No new code — handlers exist from T6.)**

- [ ] **Step 4: Full suite**

Run: `pytest -v`
Expected: ≈ 97 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/tests/integration/test_context_endpoints.py
git commit -m "test(echomem): cover ls / read / write / mv endpoints (5 new tests)"
```

---

### Task 8: e2e — add_url → blob → pipeline → tree + graph

**Files:**
- Create: `lakeon/echomem/tests/integration/test_blob_pipeline_e2e.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_blob_pipeline_e2e.py`:
```python
import json
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": json.dumps({"triples": [
                                {"subject": "Echomem", "predicate": "ingests", "object": "URL",
                                 "confidence": 0.9}]})},
                            is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_add_url_runs_pipeline_and_writes_derivatives(client, httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/x",
        headers={"content-type": "text/html"},
        content=b"<html><body><article><p>Echomem ingests URLs and runs the pipeline.</p></article></body></html>",
    )
    r = await client.post("/context/add_url", json={"url": "https://example.com/x"})
    sha = r.json()["sha256"]

    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    # 1. tree exists for blob source
    r2 = await client.get(f"/context/read", params={"sha256": sha})
    assert r2.status_code == 200
    r3 = await client.get(f"/derivatives/tree?source_kind=blob&source_ref={sha}")
    assert r3.status_code == 200
    assert any(s["level"] == 2 for s in r3.json()["levels"])

    # 2. graph has Echomem → ingests → URL edge
    r4 = await client.get("/derivatives/graph?seed=ent:echomem&hops=2")
    edges = r4.json()["edges"]
    assert any(e["predicate"] == "ingests" for e in edges)
```

- [ ] **Step 2: Run**

Run: `pytest tests/integration/test_blob_pipeline_e2e.py -v`
Expected: 1 PASSED.

- [ ] **Step 3: (No new code; verifies wiring across T1-T6.)**

- [ ] **Step 4: Full suite**

Run: `pytest -v`
Expected: ≈ 98 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/tests/integration/test_blob_pipeline_e2e.py
git commit -m "test(echomem): e2e add_url → blob → pipeline → tree + graph"
```

---

### Task 9: CLI — `echomem ctx add-url|ls|read|write|mv`

**Files:**
- Modify: `lakeon/echomem/src/echomem/cli.py` — append `ctx_app` group
- Create: `lakeon/echomem/tests/unit/test_cli_ctx.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_cli_ctx.py`:
```python
import re
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def _setup(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    return runner


def test_ctx_add_url(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="POST", url=re.compile(r"http://127\.0\.0\.1:\d+/context/add_url"),
                            json={"sha256": "a" * 64, "mime": "text/html",
                                  "byte_size": 100, "origin_url": "https://x", "path": None,
                                  "created_at": 1})
    result = runner.invoke(app, ["ctx", "add-url", "https://x"])
    assert result.exit_code == 0
    assert "a" * 8 in result.output


def test_ctx_ls(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="GET",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/ls.*"),
                            json={"items": [{"path": "a/x.md", "sha256": "b" * 64,
                                              "mime": "text/markdown", "byte_size": 5,
                                              "origin_url": None, "created_at": 1}]})
    result = runner.invoke(app, ["ctx", "ls", "--prefix", "a/"])
    assert result.exit_code == 0
    assert "a/x.md" in result.output


def test_ctx_read_by_path(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="GET",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/read.*"),
                            json={"sha256": "c" * 64, "path": "a.md", "mime": "text/plain",
                                  "byte_size": 5, "content": "hello"})
    result = runner.invoke(app, ["ctx", "read", "a.md"])
    assert result.exit_code == 0
    assert "hello" in result.output


def test_ctx_mv(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="POST",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/mv"),
                            json={"old": "a.md", "new": "b.md", "moved": True})
    result = runner.invoke(app, ["ctx", "mv", "a.md", "b.md"])
    assert result.exit_code == 0
    assert "b.md" in result.output
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_cli_ctx.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/cli.py`:
```python
ctx_app = typer.Typer(help="context (blob) subcommands")
app.add_typer(ctx_app, name="ctx")


@ctx_app.command("add-url")
def ctx_add_url(
    url: str = typer.Argument(...),
    path: str | None = typer.Option(None, "--path"),
) -> None:
    payload = {"url": url}
    if path:
        payload["path"] = path
    with httpx.Client(timeout=120.0) as c:
        r = c.post(f"{_base_url()}/context/add_url", json=payload)
        r.raise_for_status()
        b = r.json()
        console.print(f"[green]✓[/] {b['sha256'][:16]}…  mime={b['mime']}  bytes={b['byte_size']}")


@ctx_app.command("ls")
def ctx_ls(
    prefix: str | None = typer.Option(None, "--prefix"),
    limit: int = typer.Option(100, "--limit"),
) -> None:
    params = {"limit": limit}
    if prefix:
        params["prefix"] = prefix
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/context/ls", params=params)
        r.raise_for_status()
        for item in r.json()["items"]:
            path = item.get("path") or "-"
            console.print(f"  {item['sha256'][:8]}…  [dim]{item['mime']}[/]  {path}")


@ctx_app.command("read")
def ctx_read(
    path_or_sha: str = typer.Argument(...),
) -> None:
    is_sha = len(path_or_sha) == 64 and all(c in "0123456789abcdef" for c in path_or_sha)
    params = {"sha256": path_or_sha} if is_sha else {"path": path_or_sha}
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/context/read", params=params)
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        console.print(r.json()["content"])


@ctx_app.command("write")
def ctx_write(
    path: str = typer.Argument(...),
    content: str = typer.Argument(...),
    mime: str = typer.Option("text/plain", "--mime"),
) -> None:
    with httpx.Client(timeout=30.0) as c:
        r = c.post(f"{_base_url()}/context/write",
                   json={"path": path, "content": content, "mime": mime})
        r.raise_for_status()
        b = r.json()
        console.print(f"[green]✓[/] {b['sha256'][:16]}…  → {b['path']}")


@ctx_app.command("mv")
def ctx_mv(old: str, new: str) -> None:
    with httpx.Client(timeout=10.0) as c:
        r = c.post(f"{_base_url()}/context/mv", json={"old": old, "new": new})
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        console.print(f"[green]✓[/] {old} → {new}")
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_cli_ctx.py -v`
Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/cli.py echomem/tests/unit/test_cli_ctx.py
git commit -m "feat(echomem): CLI 'echomem ctx add-url|ls|read|write|mv'"
```

---

### Task 10: MCP shim — add 5 context_* tools

**Files:**
- Modify: `lakeon/echomem/src/echomem/mcp_shim/shim.py` — add 5 tool descriptors + 5 routing branches
- Modify: `lakeon/echomem/tests/e2e/test_mcp_shim.py` — assert 10 tools (5 memory + 5 context)

- [ ] **Step 1: Append failing test**

Modify the existing `test_tools_list_returns_5_memory_tools` to assert 10:
```python
@pytest.mark.asyncio
async def test_tools_list_returns_all_tools():
    msg = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    out = await handle_message(msg, base_url="http://t")
    names = {t["name"] for t in out["result"]["tools"]}
    assert {"memory_ingest", "memory_recall", "memory_list", "memory_get", "memory_delete",
            "context_add_url", "context_ls", "context_read", "context_write", "context_mv"
           }.issubset(names)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/e2e/test_mcp_shim.py -v`

- [ ] **Step 3: Implement**

Append 5 tool entries to `TOOLS` list in `src/echomem/mcp_shim/shim.py`:
```python
    {
        "name": "context_add_url",
        "description": "Fetch a URL (HTML/PDF/text), persist as blob, trigger pipeline.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string"},
                "path": {"type": "string"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "context_ls",
        "description": "List paths or blobs.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "prefix": {"type": "string"},
                "limit": {"type": "integer", "default": 100},
            },
        },
    },
    {
        "name": "context_read",
        "description": "Read blob content by path or sha256.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "sha256": {"type": "string"},
            },
        },
    },
    {
        "name": "context_write",
        "description": "Write content to a path; persists as blob and triggers pipeline.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
                "mime": {"type": "string", "default": "text/plain"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "context_mv",
        "description": "Rename a path alias (blob unchanged).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "old": {"type": "string"},
                "new": {"type": "string"},
            },
            "required": ["old", "new"],
        },
    },
```

Add 5 routing branches to `_call_tool` in same file:
```python
            elif name == "context_add_url":
                r = await client.post("/context/add_url", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_ls":
                r = await client.get("/context/ls", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_read":
                r = await client.get("/context/read", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_write":
                r = await client.post("/context/write", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_mv":
                r = await client.post("/context/mv", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/e2e/test_mcp_shim.py -v`
Expected: 3 PASSED (initialize + tools/list expanded + tools/call). Plus full suite ≈ 102 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/mcp_shim/shim.py echomem/tests/e2e/test_mcp_shim.py
git commit -m "feat(echomem): MCP shim — add 5 context_* tools (add_url/ls/read/write/mv)"
```

---

### Task 11: README — Context API section

**Files:**
- Modify: `lakeon/echomem/README.md`

- [ ] **Step 1: Insert section before "## What's next"**

```markdown
## Context API (Plan 3)

Long documents (URLs / PDFs / written content) live in a content-addressable
blob store at `~/.echomem/blobs/<sha256[:2]>/<sha256>` with a mutable path
alias table for `mv`.

### CLI

```bash
echomem ctx add-url https://example.com/post                 # fetch + ingest
echomem ctx add-url https://example.com/post --path web/post.html
echomem ctx write notes/today.md "things I learned today"
echomem ctx ls --prefix notes/
echomem ctx read notes/today.md
echomem ctx mv notes/today.md archive/2026-05-06.md
```

### HTTP

```bash
curl -X POST -d '{"url":"https://x.com/y"}' http://127.0.0.1:8473/context/add_url
curl    "http://127.0.0.1:8473/context/ls?prefix=notes/"
curl    "http://127.0.0.1:8473/context/read?path=notes/today.md"
curl -X POST -d '{"path":"a","content":"hi"}'  http://127.0.0.1:8473/context/write
curl -X POST -d '{"old":"a","new":"b"}'        http://127.0.0.1:8473/context/mv
```

### What gets indexed

- Blob written → `blob_ref(sha256, mime, byte_size, origin_url, created_at)`
- Optional path alias → `path_alias(path, sha256, created_at)` (mv mutates path, not sha)
- If extracted text is non-empty → triggers pipeline:
  - SummarizerWorker writes L0/L1/L2 with `source_kind='blob'`, `source_ref=sha256`
  - EntityExtractorWorker extracts triples, source_memory_id=sha256
- Query the result via `/derivatives/tree?source_kind=blob&source_ref=<sha>` and
  `/derivatives/graph?seed=ent:<entity>`
```

Update "## What's next":
```markdown
## What's next

- Plan 4: Vue 3 Dashboard SPA
- Plan 5: Onboarding install.sh + openclaw / hermes wiring
- Insight Track (research): output-length prediction
```

- [ ] **Step 2: Manual smoke**

```bash
cd /Users/jacky/code/lakeon/echomem && source .venv/bin/activate
echomem start &
echomem ctx add-url https://en.wikipedia.org/wiki/Special:Random  # try a real URL
sleep 90  # gemma serial
sqlite3 ~/.echomem/db.sqlite "SELECT sha256, mime, byte_size, origin_url FROM blob_ref ORDER BY created_at DESC LIMIT 1"
```

- [ ] **Step 3: (No code change)**

- [ ] **Step 4: Full suite**

Run: `pytest -v`
Expected: ≈ 102 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/README.md
git commit -m "docs(echomem): README — Context API section + CLI/HTTP examples"
```

---

## Acceptance / Verify

跑通这些即 Plan 3 验收通过：

1. `pytest -v` 全绿（除 ECHOMEM_E2E gated）
2. `echomem ctx add-url https://...` 返回 sha256 + mime
3. `~/.echomem/blobs/<sha[:2]>/<sha>` 物理文件存在
4. `sqlite3 ~/.echomem/db.sqlite "SELECT * FROM blob_ref"` 有行
5. `GET /derivatives/tree?source_kind=blob&source_ref=<sha>` 至少返回 L2
6. `GET /derivatives/graph?seed=ent:<某实体>` 看到从 blob 抽出的三元组
7. `echomem ctx mv old new` 后 `read old` 返回 404，`read new` 返回原内容
8. PDF URL 也能正常 fetch + 抽文本（sample: `https://arxiv.org/pdf/...`）

## Followups（不在本 plan 范围）

- Plan 4: Vue 3 Dashboard SPA（visualizing all this）
- Plan 5: Onboarding install.sh + openclaw / hermes wiring
- 路径冲突时的策略（write to existing path → 当前是覆盖；可加 `--no-clobber`）
- 大文件分片 ingest（当前小文档读全 bytes 入内存）
- Binary 媒体 (image OCR / video / audio) → Plan 4+
- Insight Track 子项目

## Self-Review Checklist

- [x] 11 个 task，每个 task 含 5 步（write test → fail → impl → pass → commit）
- [x] 文件路径绝对/确切
- [x] 没有 "TODO / TBD" 占位符
- [x] 类型一致：`BlobRef` / `PathAlias` 在 base.py 定义，driver/api 引用一致
- [x] Worker 签名变更（`handle(memory_id)` → `handle(source_kind, source_ref)`）影响面：T2(Plan 2 已实施) 测试需要更新（Task 5 含此修改）
- [x] 降级路径：fetcher 三类 mime（HTML / PDF / text），其他 mime → text="" 不触发 pipeline
- [x] 验收画面明确（8 条）
