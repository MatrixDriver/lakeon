# echomem · Phase 1 Backbone — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 起一根能端到端跑通的 echomem"骨干"——daemon + Memory API + SQLite 存储 + Ollama embedder + Claude Code MCP shim + CLI——让"在 CC 让它记一条事，在另一个进程里能 recall"成立。

**Architecture:** 单进程 FastAPI daemon 持有 SQLite（含 sqlite-vec + FTS5）；Memory API 为 daemon 暴露 5 个 REST 端点；Embedder worker 走 Ollama HTTP；Claude Code 通过 stdio MCP shim → daemon HTTP；CLI（typer）直连 daemon HTTP。所有上层只见 StorageDriver Protocol，未来切 PG/Cloud 不动业务代码。

**Tech Stack:** Python 3.11+ · FastAPI + uvicorn · sqlite-vec · typer · httpx · pydantic v2 · pytest + pytest-asyncio · hatchling

**Out of Scope（明确属于后续 Plan）：**
- 衍生物 pipeline（Summarizer / EntityExtractor / Reflector + 4 种 view） → Plan 2
- Context API + FS blobs（add_url / ls / read / write / mv） → Plan 3
- Dashboard（Vue 3 SPA） → Plan 4
- Onboarding install.sh + openclaw / hermes 接入 → Plan 5
- Insight Track（输出长度预测） → 独立研究子项目

---

## File Structure

```
lakeon/echomem/                          # 新增子目录
├── pyproject.toml                       # hatchling, Python 3.11+
├── README.md                            # 简短说明 + 开发指引
├── src/echomem/
│   ├── __init__.py                      # 导出 __version__
│   ├── __main__.py                      # python -m echomem → CLI
│   ├── cli.py                           # typer CLI: init / start / mem ...
│   ├── config.py                        # ~/.echomem/config.toml 模型
│   ├── logging.py                       # 统一 setup_logging()
│   ├── ulid.py                          # ULID 生成器（薄 wrapper）
│   ├── ollama_client.py                 # Ollama HTTP 客户端
│   ├── daemon/
│   │   ├── __init__.py
│   │   └── app.py                       # FastAPI app factory + lifespan
│   ├── api/
│   │   ├── __init__.py
│   │   ├── schemas.py                   # Pydantic models for request/response
│   │   ├── health.py                    # GET /health
│   │   └── memory.py                    # 5 个 memory 端点
│   ├── drivers/
│   │   ├── __init__.py
│   │   ├── base.py                      # StorageDriver Protocol
│   │   ├── sqlite.py                    # SQLiteDriver 实现
│   │   └── migrations/
│   │       ├── __init__.py              # forward-only migrator
│   │       └── m001_initial.py          # memory + memory_vec + memory_fts
│   ├── workers/
│   │   ├── __init__.py
│   │   └── embedder.py                  # Embedder worker
│   └── mcp_shim/
│       ├── __init__.py
│       ├── __main__.py                  # python -m echomem.mcp_shim
│       └── shim.py                      # stdio JSON-RPC ↔ daemon HTTP
└── tests/
    ├── conftest.py                      # 公共 fixture: tmp config / daemon / store
    ├── unit/
    │   ├── test_config.py
    │   ├── test_ulid.py
    │   ├── test_ollama_client.py
    │   ├── test_sqlite_driver.py
    │   └── test_migrations.py
    ├── integration/
    │   ├── test_daemon_lifecycle.py
    │   ├── test_memory_endpoints.py
    │   └── test_embedder_pipeline.py
    └── e2e/
        └── test_mcp_shim.py
```

**Each file's responsibility:**

- `pyproject.toml` — package metadata + deps + entry points (`echomem`, `echomem-mcp-shim`)
- `config.py` — load / write `~/.echomem/config.toml`，单一数据源（端口、Ollama URL、模型名）
- `ulid.py` — `new()` 返回字符串 ULID（用 python-ulid 或 stdlib uuid7 fallback）
- `ollama_client.py` — `OllamaClient.embed(text)` / `.generate(prompt)`，httpx async
- `daemon/app.py` — `create_app(config)` 返回 FastAPI 实例，含 lifespan（开关 driver、worker pool）
- `api/schemas.py` — 所有端点的请求/响应模型集中放一处
- `api/memory.py` — `ingest`/`recall`/`list`/`delete`/`get` 5 handler
- `drivers/base.py` — `StorageDriver` Protocol；其他 driver 实现它
- `drivers/sqlite.py` — `SQLiteDriver`：连接、加载 sqlite-vec、跑 migration、CRUD + hybrid recall
- `drivers/migrations/__init__.py` — forward-only `apply(conn)`
- `drivers/migrations/m001_initial.py` — `up(conn)` 创建表
- `workers/embedder.py` — `EmbedderWorker.handle(memory_id, text)` 调 Ollama → 写 vec
- `mcp_shim/shim.py` — 读 stdin JSON-RPC、转 daemon HTTP、回 stdout
- `cli.py` — typer commands: `init` / `start` / `status` / `mem ingest|recall|list|delete|get`

---

## Tasks

### Task 1: Bootstrap echomem package + 第一个测试通过

**Files:**
- Create: `lakeon/echomem/pyproject.toml`
- Create: `lakeon/echomem/src/echomem/__init__.py`
- Create: `lakeon/echomem/tests/__init__.py`
- Create: `lakeon/echomem/tests/unit/__init__.py`
- Create: `lakeon/echomem/tests/unit/test_smoke.py`

- [ ] **Step 1: Write the failing test**

`lakeon/echomem/tests/unit/test_smoke.py`:
```python
def test_can_import_echomem():
    import echomem
    assert echomem.__version__
```

- [ ] **Step 2: Create pyproject.toml**

`lakeon/echomem/pyproject.toml`:
```toml
[project]
name = "echomem"
version = "0.1.0"
description = "Local-first agent memory hub for Claude Code / openclaw / hermes"
readme = "README.md"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.32.0",
    "httpx>=0.27.0",
    "pydantic>=2.9.0",
    "pydantic-settings>=2.6.0",
    "typer>=0.12.0",
    "rich>=13.0.0",
    "sqlite-vec>=0.1.7",
    "python-ulid>=3.0.0",
    "structlog>=24.4.0",
    "tomli-w>=1.0.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0.0",
    "pytest-asyncio>=0.24.0",
    "pytest-httpx>=0.32.0",
    "ruff>=0.7.0",
]

[project.scripts]
echomem = "echomem.cli:app"
echomem-mcp-shim = "echomem.mcp_shim.__main__:main"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/echomem"]

[tool.pytest.ini_options]
testpaths = ["tests"]
asyncio_mode = "auto"
addopts = "-ra -q"
```

- [ ] **Step 3: Create package init**

`lakeon/echomem/src/echomem/__init__.py`:
```python
__version__ = "0.1.0"
```

- [ ] **Step 4: Install dev deps and run tests**

```bash
cd lakeon/echomem
python -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest -v
```
Expected: `test_can_import_echomem PASSED`.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/pyproject.toml echomem/src/echomem/__init__.py echomem/tests/
git commit -m "feat(echomem): bootstrap package skeleton"
```

---

### Task 2: Config 模型 + `~/.echomem/config.toml` 加载

**Files:**
- Create: `lakeon/echomem/src/echomem/config.py`
- Create: `lakeon/echomem/tests/unit/test_config.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_config.py`:
```python
from pathlib import Path
import tomli_w
from echomem.config import EchomemConfig, load_config, default_config_path


def test_default_when_no_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg = load_config()
    assert cfg.host == "127.0.0.1"
    assert 1024 <= cfg.port <= 65535
    assert cfg.data_dir == tmp_path / ".echomem"
    assert cfg.ollama_url == "http://localhost:11434"
    assert cfg.embedding_model == "qwen3-embedding:0.6b"
    assert cfg.embedding_dim == 1024


def test_load_from_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg_dir = tmp_path / ".echomem"
    cfg_dir.mkdir()
    (cfg_dir / "config.toml").write_bytes(
        tomli_w.dumps(
            {
                "daemon": {"host": "0.0.0.0", "port": 7777},
                "ollama": {"url": "http://1.2.3.4:11434", "embedding_model": "x:y"},
            }
        ).encode()
    )
    cfg = load_config()
    assert cfg.host == "0.0.0.0"
    assert cfg.port == 7777
    assert cfg.ollama_url == "http://1.2.3.4:11434"
    assert cfg.embedding_model == "x:y"


def test_default_config_path(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    assert default_config_path() == tmp_path / ".echomem" / "config.toml"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd lakeon/echomem && pytest tests/unit/test_config.py -v`
Expected: `ImportError: cannot import name 'EchomemConfig' from 'echomem.config'`.

- [ ] **Step 3: Implement config**

`src/echomem/config.py`:
```python
from __future__ import annotations

import os
import tomllib
from pathlib import Path
from typing import Any
from pydantic import BaseModel, Field


def default_data_dir() -> Path:
    return Path(os.environ.get("HOME", "/tmp")) / ".echomem"


def default_config_path() -> Path:
    return default_data_dir() / "config.toml"


def _pick_port() -> int:
    env = os.environ.get("ECHOMEM_PORT")
    return int(env) if env else 8473  # echomem 默认端口


class EchomemConfig(BaseModel):
    host: str = "127.0.0.1"
    port: int = Field(default_factory=_pick_port)
    data_dir: Path = Field(default_factory=default_data_dir)
    ollama_url: str = "http://localhost:11434"
    embedding_model: str = "qwen3-embedding:0.6b"
    embedding_dim: int = 1024
    log_level: str = "INFO"


def load_config(path: Path | None = None) -> EchomemConfig:
    file = path or default_config_path()
    if not file.exists():
        return EchomemConfig()
    raw = tomllib.loads(file.read_text("utf-8"))
    daemon = raw.get("daemon", {})
    ollama = raw.get("ollama", {})
    storage = raw.get("storage", {})
    log = raw.get("log", {})
    return EchomemConfig(
        host=daemon.get("host", "127.0.0.1"),
        port=daemon.get("port", _pick_port()),
        data_dir=Path(storage.get("data_dir", default_data_dir())),
        ollama_url=ollama.get("url", "http://localhost:11434"),
        embedding_model=ollama.get("embedding_model", "qwen3-embedding:0.6b"),
        embedding_dim=ollama.get("embedding_dim", 1024),
        log_level=log.get("level", "INFO"),
    )


def write_config(cfg: EchomemConfig, path: Path | None = None) -> Path:
    import tomli_w

    file = path or default_config_path()
    file.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {
        "daemon": {"host": cfg.host, "port": cfg.port},
        "ollama": {
            "url": cfg.ollama_url,
            "embedding_model": cfg.embedding_model,
            "embedding_dim": cfg.embedding_dim,
        },
        "storage": {"data_dir": str(cfg.data_dir)},
        "log": {"level": cfg.log_level},
    }
    file.write_text(tomli_w.dumps(payload), encoding="utf-8")
    return file
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/unit/test_config.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/config.py echomem/tests/unit/test_config.py
git commit -m "feat(echomem): config model + ~/.echomem/config.toml load/write"
```

---

### Task 3: ULID + Logging utilities

**Files:**
- Create: `lakeon/echomem/src/echomem/ulid.py`
- Create: `lakeon/echomem/src/echomem/logging.py`
- Create: `lakeon/echomem/tests/unit/test_ulid.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_ulid.py`:
```python
import re
from echomem.ulid import new


def test_new_returns_26_char_string():
    v = new()
    assert isinstance(v, str)
    assert len(v) == 26
    assert re.fullmatch(r"[0-9A-HJKMNP-TV-Z]+", v)


def test_new_is_unique():
    assert len({new() for _ in range(100)}) == 100


def test_new_is_lexicographic():
    a = new()
    b = new()
    assert a <= b  # ULIDs are time-ordered
```

- [ ] **Step 2: Run test (fails — no module)**

Run: `pytest tests/unit/test_ulid.py -v`

- [ ] **Step 3: Implement**

`src/echomem/ulid.py`:
```python
from ulid import ULID


def new() -> str:
    """Return a fresh ULID as a 26-char Crockford-Base32 string."""
    return str(ULID())
```

`src/echomem/logging.py`:
```python
from __future__ import annotations

import logging
import sys
import structlog


def setup_logging(level: str = "INFO") -> None:
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stderr,
        level=getattr(logging, level.upper(), logging.INFO),
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(colors=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, level.upper(), logging.INFO)
        ),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)
```

- [ ] **Step 4: Run tests pass**

Run: `pytest tests/unit/test_ulid.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/ulid.py echomem/src/echomem/logging.py echomem/tests/unit/test_ulid.py
git commit -m "feat(echomem): ulid + structured logging utilities"
```

---

### Task 4: StorageDriver Protocol（接口先行）

**Files:**
- Create: `lakeon/echomem/src/echomem/drivers/__init__.py`
- Create: `lakeon/echomem/src/echomem/drivers/base.py`
- Create: `lakeon/echomem/tests/unit/test_storage_protocol.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_storage_protocol.py`:
```python
from echomem.drivers.base import StorageDriver, Memory, RecallHit


def test_protocol_has_required_methods():
    assert hasattr(StorageDriver, "upsert_memory")
    assert hasattr(StorageDriver, "get_memory")
    assert hasattr(StorageDriver, "list_memories")
    assert hasattr(StorageDriver, "delete_memory")
    assert hasattr(StorageDriver, "recall")


def test_memory_dataclass_round_trip():
    m = Memory(
        id="01HX",
        agent_id="cc",
        source_kind="explicit",
        source_ref=None,
        text="hello",
        meta={"k": "v"},
        created_at=1,
        updated_at=1,
        deleted_at=None,
    )
    assert m.text == "hello"
    assert m.meta == {"k": "v"}


def test_recall_hit_dataclass():
    h = RecallHit(memory_id="01HX", text="t", score=0.9, source_kind="explicit", source_ref=None)
    assert h.score == 0.9
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_storage_protocol.py -v`

- [ ] **Step 3: Implement**

`src/echomem/drivers/__init__.py`:
```python
from echomem.drivers.base import StorageDriver, Memory, RecallHit

__all__ = ["StorageDriver", "Memory", "RecallHit"]
```

`src/echomem/drivers/base.py`:
```python
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable


@dataclass(slots=True)
class Memory:
    id: str
    agent_id: str
    source_kind: str  # 'explicit' | 'session' | 'document'
    source_ref: str | None
    text: str
    meta: dict | None
    created_at: int
    updated_at: int
    deleted_at: int | None = None
    embedding: list[float] | None = field(default=None, repr=False)


@dataclass(slots=True)
class RecallHit:
    memory_id: str
    text: str
    score: float
    source_kind: str
    source_ref: str | None
    meta: dict | None = None


@runtime_checkable
class StorageDriver(Protocol):
    def upsert_memory(self, mem: Memory) -> str: ...

    def get_memory(self, memory_id: str) -> Memory | None: ...

    def list_memories(
        self,
        agent_id: str | None = None,
        limit: int = 50,
        before: int | None = None,
    ) -> list[Memory]: ...

    def delete_memory(self, memory_id: str) -> bool: ...

    def recall(
        self,
        query_embedding: list[float],
        query_text: str,
        k: int = 10,
        agent_id: str | None = None,
    ) -> list[RecallHit]: ...

    def close(self) -> None: ...
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_storage_protocol.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/ echomem/tests/unit/test_storage_protocol.py
git commit -m "feat(echomem): StorageDriver Protocol + Memory/RecallHit dataclasses"
```

---

### Task 5: SQLite migration framework + 001_initial（建 3 张表）

**Files:**
- Create: `lakeon/echomem/src/echomem/drivers/migrations/__init__.py`
- Create: `lakeon/echomem/src/echomem/drivers/migrations/m001_initial.py`
- Create: `lakeon/echomem/tests/unit/test_migrations.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_migrations.py`:
```python
import sqlite3
import sqlite_vec
from echomem.drivers.migrations import apply_all, MIGRATIONS


def _open():
    con = sqlite3.connect(":memory:")
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    return con


def test_apply_all_creates_expected_tables():
    con = _open()
    apply_all(con)
    rows = {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','virtualtable','view')"
        ).fetchall()
    }
    assert "memory" in rows
    assert "memory_vec" in rows
    assert "memory_fts" in rows
    assert "schema_version" in rows


def test_apply_all_is_idempotent():
    con = _open()
    apply_all(con)
    apply_all(con)
    v = con.execute("SELECT MAX(version) FROM schema_version").fetchone()[0]
    assert v == max(MIGRATIONS.keys())


def test_memory_table_columns():
    con = _open()
    apply_all(con)
    cols = {r[1] for r in con.execute("PRAGMA table_info(memory)").fetchall()}
    expected = {
        "id",
        "agent_id",
        "source_kind",
        "source_ref",
        "text",
        "meta",
        "created_at",
        "updated_at",
        "deleted_at",
    }
    assert expected.issubset(cols)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_migrations.py -v`

- [ ] **Step 3: Implement**

`src/echomem/drivers/migrations/__init__.py`:
```python
from __future__ import annotations

import sqlite3
from typing import Callable

from echomem.drivers.migrations import m001_initial

MIGRATIONS: dict[int, Callable[[sqlite3.Connection], None]] = {
    1: m001_initial.up,
}


def apply_all(con: sqlite3.Connection) -> None:
    con.execute(
        "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)"
    )
    applied = {
        r[0] for r in con.execute("SELECT version FROM schema_version").fetchall()
    }
    for version in sorted(MIGRATIONS):
        if version in applied:
            continue
        MIGRATIONS[version](con)
        con.execute(
            "INSERT INTO schema_version(version, applied_at) VALUES (?, strftime('%s','now') * 1000)",
            (version,),
        )
        con.commit()
```

`src/echomem/drivers/migrations/m001_initial.py`:
```python
import sqlite3


def up(con: sqlite3.Connection) -> None:
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS memory (
          id          TEXT PRIMARY KEY,
          agent_id    TEXT NOT NULL,
          source_kind TEXT NOT NULL DEFAULT 'explicit',
          source_ref  TEXT,
          text        TEXT NOT NULL,
          meta        TEXT,
          created_at  INTEGER NOT NULL,
          updated_at  INTEGER NOT NULL,
          deleted_at  INTEGER
        );

        CREATE INDEX IF NOT EXISTS idx_memory_created_at ON memory(created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_memory_agent_id   ON memory(agent_id);
        """
    )
    # sqlite-vec virtual table — embedding 维度跟随 EchomemConfig.embedding_dim (默认 1024)
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(memory_id TEXT PRIMARY KEY, embedding float[1024])"
    )
    # FTS5 contentless table (不强绑 memory rowid 避免 trigger 复杂度)
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(memory_id UNINDEXED, text, tokenize='porter')"
    )
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_migrations.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/migrations/ echomem/tests/unit/test_migrations.py
git commit -m "feat(echomem): forward-only migration framework + initial schema"
```

---

### Task 6: SQLiteDriver — upsert_memory & get_memory

**Files:**
- Create: `lakeon/echomem/src/echomem/drivers/sqlite.py`
- Create: `lakeon/echomem/tests/unit/test_sqlite_driver.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_sqlite_driver.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=1024)
    yield d
    d.close()


def _make_mem(text="hello world", emb=None, agent="cc", mid="01HXEXAMPLEMEMORY00001"):
    now = int(time.time() * 1000)
    return Memory(
        id=mid,
        agent_id=agent,
        source_kind="explicit",
        source_ref=None,
        text=text,
        meta={"tag": "smoke"},
        created_at=now,
        updated_at=now,
        deleted_at=None,
        embedding=emb or [0.0] * 1024,
    )


def test_upsert_then_get(driver):
    m = _make_mem()
    driver.upsert_memory(m)
    got = driver.get_memory(m.id)
    assert got is not None
    assert got.text == "hello world"
    assert got.meta == {"tag": "smoke"}
    assert got.agent_id == "cc"


def test_get_returns_none_when_missing(driver):
    assert driver.get_memory("01HXNOTEXISTNOTEXISTNOTEX") is None


def test_upsert_overwrites(driver):
    m = _make_mem(text="v1")
    driver.upsert_memory(m)
    m2 = _make_mem(text="v2")
    driver.upsert_memory(m2)
    got = driver.get_memory(m.id)
    assert got.text == "v2"


def test_upsert_skips_vec_when_embedding_none(driver):
    m = _make_mem()
    m.embedding = None
    driver.upsert_memory(m)
    got = driver.get_memory(m.id)
    assert got is not None
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_sqlite_driver.py -v`

- [ ] **Step 3: Implement**

`src/echomem/drivers/sqlite.py`:
```python
from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any

import sqlite_vec

from echomem.drivers.base import Memory, RecallHit
from echomem.drivers.migrations import apply_all


class SQLiteDriver:
    def __init__(self, path: Path, embedding_dim: int = 1024):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.embedding_dim = embedding_dim
        self.con = sqlite3.connect(self.path, check_same_thread=False)
        self.con.execute("PRAGMA journal_mode=WAL")
        self.con.execute("PRAGMA synchronous=NORMAL")
        self.con.execute("PRAGMA busy_timeout=5000")
        self.con.enable_load_extension(True)
        sqlite_vec.load(self.con)
        self.con.enable_load_extension(False)
        apply_all(self.con)

    def upsert_memory(self, mem: Memory) -> str:
        self.con.execute(
            """
            INSERT INTO memory(id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at)
            VALUES(:id, :agent_id, :source_kind, :source_ref, :text, :meta, :created_at, :updated_at, :deleted_at)
            ON CONFLICT(id) DO UPDATE SET
              agent_id    = excluded.agent_id,
              source_kind = excluded.source_kind,
              source_ref  = excluded.source_ref,
              text        = excluded.text,
              meta        = excluded.meta,
              updated_at  = excluded.updated_at,
              deleted_at  = excluded.deleted_at
            """,
            {
                "id": mem.id,
                "agent_id": mem.agent_id,
                "source_kind": mem.source_kind,
                "source_ref": mem.source_ref,
                "text": mem.text,
                "meta": json.dumps(mem.meta) if mem.meta is not None else None,
                "created_at": mem.created_at,
                "updated_at": mem.updated_at,
                "deleted_at": mem.deleted_at,
            },
        )
        if mem.embedding is not None:
            self._upsert_vec(mem.id, mem.embedding)
        self._upsert_fts(mem.id, mem.text)
        self.con.commit()
        return mem.id

    def _upsert_vec(self, memory_id: str, embedding: list[float]) -> None:
        from sqlite_vec import serialize_float32

        if len(embedding) != self.embedding_dim:
            raise ValueError(
                f"embedding dim {len(embedding)} != configured {self.embedding_dim}"
            )
        # vec0 不支持 ON CONFLICT — 用 delete+insert
        self.con.execute("DELETE FROM memory_vec WHERE memory_id = ?", (memory_id,))
        self.con.execute(
            "INSERT INTO memory_vec(memory_id, embedding) VALUES(?, ?)",
            (memory_id, serialize_float32(embedding)),
        )

    def _upsert_fts(self, memory_id: str, text: str) -> None:
        self.con.execute("DELETE FROM memory_fts WHERE memory_id = ?", (memory_id,))
        self.con.execute(
            "INSERT INTO memory_fts(memory_id, text) VALUES(?, ?)", (memory_id, text)
        )

    def get_memory(self, memory_id: str) -> Memory | None:
        row = self.con.execute(
            "SELECT id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at "
            "FROM memory WHERE id = ? AND deleted_at IS NULL",
            (memory_id,),
        ).fetchone()
        if row is None:
            return None
        return _row_to_memory(row)

    def close(self) -> None:
        self.con.close()


def _row_to_memory(row: tuple[Any, ...]) -> Memory:
    meta = json.loads(row[5]) if row[5] is not None else None
    return Memory(
        id=row[0],
        agent_id=row[1],
        source_kind=row[2],
        source_ref=row[3],
        text=row[4],
        meta=meta,
        created_at=row[6],
        updated_at=row[7],
        deleted_at=row[8],
    )
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_sqlite_driver.py -v`
Expected: 4 PASSED.

> 维度约定：本 plan 全文 embedding 维度统一 1024（匹配 `qwen3-embedding:0.6b` 默认 + Task 5 migration 的 `float[1024]`）。Plan 2 引入参数化 dim 时再做 schema 演进。

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/sqlite.py echomem/tests/unit/test_sqlite_driver.py
git commit -m "feat(echomem): SQLiteDriver upsert_memory + get_memory + WAL"
```

---

### Task 7: SQLiteDriver — recall (hybrid vec + FTS via RRF)

**Files:**
- Modify: `lakeon/echomem/src/echomem/drivers/sqlite.py` (add recall)
- Modify: `lakeon/echomem/tests/unit/test_sqlite_driver.py` (add tests)

- [ ] **Step 1: Write the failing tests**

Append to `tests/unit/test_sqlite_driver.py`:
```python
def _emb(seed: int, dim: int = 1024) -> list[float]:
    # 简单确定性 embedding：第 seed 维 = 1.0，其余 0.0；用于近邻测试
    v = [0.0] * dim
    v[seed % dim] = 1.0
    return v


def test_recall_by_vector_similarity(driver):
    for seed, txt in [(0, "alpha"), (1, "beta"), (2, "gamma")]:
        mid = f"01HXSEED{seed:018d}"
        m = Memory(
            id=mid,
            agent_id="cc",
            source_kind="explicit",
            source_ref=None,
            text=txt,
            meta=None,
            created_at=seed,
            updated_at=seed,
            deleted_at=None,
            embedding=_emb(seed),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(1), query_text="", k=2)
    assert len(hits) >= 1
    assert hits[0].text == "beta"


def test_recall_by_fts(driver):
    for i, txt in enumerate(["alpha bravo", "charlie delta", "echo foxtrot"]):
        m = Memory(
            id=f"01HXFTS{i:020d}",
            agent_id="cc",
            source_kind="explicit",
            source_ref=None,
            text=txt,
            meta=None,
            created_at=i,
            updated_at=i,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(99), query_text="bravo", k=3)
    assert any(h.text == "alpha bravo" for h in hits)


def test_recall_filter_by_agent(driver):
    for i, agent in enumerate(["cc", "openclaw", "hermes"]):
        m = Memory(
            id=f"01HXAGT{i:020d}",
            agent_id=agent,
            source_kind="explicit",
            source_ref=None,
            text=f"text {agent}",
            meta=None,
            created_at=i,
            updated_at=i,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(0), query_text="text", k=10, agent_id="cc")
    assert all(h.source_kind == "explicit" for h in hits)
    # 验证只命中 cc
    for h in hits:
        got = driver.get_memory(h.memory_id)
        assert got.agent_id == "cc"
```

(Replace earlier `embedding_dim=8` references with 1024 to be consistent.)

- [ ] **Step 2: Run (fails — no recall method)**

Run: `pytest tests/unit/test_sqlite_driver.py -v`

- [ ] **Step 3: Implement recall**

Append to `src/echomem/drivers/sqlite.py`:
```python
    def recall(
        self,
        query_embedding: list[float],
        query_text: str,
        k: int = 10,
        agent_id: str | None = None,
    ) -> list[RecallHit]:
        from sqlite_vec import serialize_float32

        # 阶段 1：向量召回 candidate
        vec_rows = self.con.execute(
            """
            SELECT v.memory_id, v.distance
            FROM (
              SELECT memory_id, distance FROM memory_vec
              WHERE embedding MATCH ?
              ORDER BY distance LIMIT ?
            ) v
            """,
            (serialize_float32(query_embedding), max(k * 4, 16)),
        ).fetchall()

        # 阶段 2：FTS 召回 candidate（如有 query_text）
        fts_rows: list[tuple[str, float]] = []
        if query_text and query_text.strip():
            fts_rows = self.con.execute(
                """
                SELECT memory_id, bm25(memory_fts) AS rank
                FROM memory_fts
                WHERE memory_fts MATCH ?
                ORDER BY rank LIMIT ?
                """,
                (query_text, max(k * 4, 16)),
            ).fetchall()

        # 阶段 3：Reciprocal Rank Fusion
        rrf_k = 60
        scores: dict[str, float] = {}
        for rank, (mid, _dist) in enumerate(vec_rows):
            scores[mid] = scores.get(mid, 0.0) + 1.0 / (rrf_k + rank + 1)
        for rank, (mid, _bm) in enumerate(fts_rows):
            scores[mid] = scores.get(mid, 0.0) + 1.0 / (rrf_k + rank + 1)

        if not scores:
            return []

        # 阶段 4：拉真实 memory；过滤 agent / soft-deleted
        ids = list(scores.keys())
        placeholders = ",".join(["?"] * len(ids))
        params: list[Any] = list(ids)
        sql = (
            "SELECT id, agent_id, source_kind, source_ref, text, meta "
            "FROM memory "
            f"WHERE id IN ({placeholders}) AND deleted_at IS NULL"
        )
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        rows = self.con.execute(sql, params).fetchall()

        results: list[RecallHit] = []
        for row in rows:
            mid = row[0]
            results.append(
                RecallHit(
                    memory_id=mid,
                    text=row[4],
                    score=scores[mid],
                    source_kind=row[2],
                    source_ref=row[3],
                    meta=json.loads(row[5]) if row[5] else None,
                )
            )
        results.sort(key=lambda h: h.score, reverse=True)
        return results[:k]
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_sqlite_driver.py -v`
Expected: 7 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/sqlite.py echomem/tests/unit/test_sqlite_driver.py
git commit -m "feat(echomem): hybrid recall via vec + FTS5 + RRF fusion"
```

---

### Task 8: SQLiteDriver — list_memories & delete_memory

**Files:**
- Modify: `lakeon/echomem/src/echomem/drivers/sqlite.py`
- Modify: `lakeon/echomem/tests/unit/test_sqlite_driver.py`

- [ ] **Step 1: Write the failing tests**

Append to `tests/unit/test_sqlite_driver.py`:
```python
def test_list_returns_recent_first(driver):
    for i in range(3):
        m = Memory(
            id=f"01HXLST{i:020d}",
            agent_id="cc",
            source_kind="explicit",
            source_ref=None,
            text=f"item {i}",
            meta=None,
            created_at=i * 1000,
            updated_at=i * 1000,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)

    items = driver.list_memories(limit=10)
    assert [m.text for m in items] == ["item 2", "item 1", "item 0"]


def test_list_filter_by_agent(driver):
    for i, agent in enumerate(["cc", "openclaw", "cc"]):
        m = Memory(
            id=f"01HXLSA{i:020d}",
            agent_id=agent,
            source_kind="explicit",
            source_ref=None,
            text=f"t{i}",
            meta=None,
            created_at=i,
            updated_at=i,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)
    items = driver.list_memories(agent_id="cc")
    assert {m.text for m in items} == {"t0", "t2"}


def test_delete_marks_soft_deleted_then_recall_skips(driver):
    m = _make_mem(mid="01HXDEL000000000000000001")
    driver.upsert_memory(m)
    assert driver.delete_memory(m.id) is True
    assert driver.get_memory(m.id) is None
    hits = driver.recall([0.0] * 1024, query_text="hello", k=10)
    assert all(h.memory_id != m.id for h in hits)


def test_delete_returns_false_when_missing(driver):
    assert driver.delete_memory("01HXNOPENOPENOPENOPENOPENN") is False
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_sqlite_driver.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/drivers/sqlite.py`:
```python
    def list_memories(
        self,
        agent_id: str | None = None,
        limit: int = 50,
        before: int | None = None,
    ) -> list[Memory]:
        sql = (
            "SELECT id, agent_id, source_kind, source_ref, text, meta, created_at, updated_at, deleted_at "
            "FROM memory WHERE deleted_at IS NULL"
        )
        params: list[Any] = []
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        if before is not None:
            sql += " AND created_at < ?"
            params.append(before)
        sql += " ORDER BY created_at DESC LIMIT ?"
        params.append(limit)
        return [_row_to_memory(r) for r in self.con.execute(sql, params).fetchall()]

    def delete_memory(self, memory_id: str) -> bool:
        cur = self.con.execute(
            "UPDATE memory SET deleted_at = strftime('%s','now') * 1000 "
            "WHERE id = ? AND deleted_at IS NULL",
            (memory_id,),
        )
        self.con.commit()
        return cur.rowcount > 0
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_sqlite_driver.py -v`
Expected: 11 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/sqlite.py echomem/tests/unit/test_sqlite_driver.py
git commit -m "feat(echomem): list_memories + delete_memory (soft delete)"
```

---

### Task 9: Ollama HTTP client (embed + generate)

**Files:**
- Create: `lakeon/echomem/src/echomem/ollama_client.py`
- Create: `lakeon/echomem/tests/unit/test_ollama_client.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_ollama_client.py`:
```python
import pytest
import httpx
from echomem.ollama_client import OllamaClient


@pytest.mark.asyncio
async def test_embed_returns_floats(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.1, 0.2, 0.3]},
    )
    async with OllamaClient("http://localhost:11434") as c:
        v = await c.embed("hello", model="qwen3-embedding:0.6b")
    assert v == [0.1, 0.2, 0.3]


@pytest.mark.asyncio
async def test_embed_raises_on_5xx(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        status_code=500,
        json={"error": "boom"},
    )
    async with OllamaClient("http://localhost:11434") as c:
        with pytest.raises(httpx.HTTPStatusError):
            await c.embed("hello", model="qwen3-embedding:0.6b")
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_ollama_client.py -v`

- [ ] **Step 3: Implement**

`src/echomem/ollama_client.py`:
```python
from __future__ import annotations

import httpx


class OllamaClient:
    def __init__(self, base_url: str, timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(base_url=self.base_url, timeout=timeout)

    async def embed(self, text: str, *, model: str) -> list[float]:
        resp = await self._client.post(
            "/api/embeddings", json={"model": model, "prompt": text}
        )
        resp.raise_for_status()
        return resp.json()["embedding"]

    async def generate(
        self, prompt: str, *, model: str, options: dict | None = None
    ) -> str:
        body: dict = {"model": model, "prompt": prompt, "stream": False}
        if options:
            body["options"] = options
        resp = await self._client.post("/api/generate", json=body)
        resp.raise_for_status()
        return resp.json()["response"]

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> "OllamaClient":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.aclose()
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_ollama_client.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/ollama_client.py echomem/tests/unit/test_ollama_client.py
git commit -m "feat(echomem): Ollama HTTP client (embed + generate)"
```

---

### Task 10: Embedder worker

**Files:**
- Create: `lakeon/echomem/src/echomem/workers/__init__.py`
- Create: `lakeon/echomem/src/echomem/workers/embedder.py`
- Create: `lakeon/echomem/tests/integration/__init__.py`
- Create: `lakeon/echomem/tests/integration/test_embedder_pipeline.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_embedder_pipeline.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.embedder import EmbedderWorker


@pytest.mark.asyncio
async def test_embedder_writes_vec(tmp_path, httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
    )
    driver = SQLiteDriver(tmp_path / "db.sqlite")
    now = int(time.time() * 1000)
    m = Memory(
        id="01HXEMBED0000000000000000",
        agent_id="cc",
        source_kind="explicit",
        source_ref=None,
        text="hi",
        meta=None,
        created_at=now,
        updated_at=now,
        embedding=None,
    )
    driver.upsert_memory(m)

    async with OllamaClient("http://ol:11434") as ol:
        worker = EmbedderWorker(driver, ol, model="qwen3-embedding:0.6b")
        await worker.handle(m.id, m.text)

    rows = driver.con.execute(
        "SELECT memory_id FROM memory_vec WHERE memory_id = ?", (m.id,)
    ).fetchall()
    assert len(rows) == 1
    driver.close()
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_embedder_pipeline.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/__init__.py`: empty

`src/echomem/workers/embedder.py`:
```python
from __future__ import annotations

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.logging import get_logger

log = get_logger("echomem.embedder")


class EmbedderWorker:
    def __init__(self, driver: SQLiteDriver, ollama: OllamaClient, *, model: str):
        self.driver = driver
        self.ollama = ollama
        self.model = model

    async def handle(self, memory_id: str, text: str) -> None:
        embedding = await self.ollama.embed(text, model=self.model)
        # 不调 upsert_memory（那要完整 Memory）— 直接写 vec 表
        self.driver._upsert_vec(memory_id, embedding)
        self.driver.con.commit()
        log.info("embedded", memory_id=memory_id, dim=len(embedding))
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_embedder_pipeline.py -v`
Expected: 1 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/workers/ echomem/tests/integration/
git commit -m "feat(echomem): EmbedderWorker — Ollama → memory_vec"
```

---

### Task 11: FastAPI app + lifespan + /health

**Files:**
- Create: `lakeon/echomem/src/echomem/daemon/__init__.py`
- Create: `lakeon/echomem/src/echomem/daemon/app.py`
- Create: `lakeon/echomem/src/echomem/api/__init__.py`
- Create: `lakeon/echomem/src/echomem/api/health.py`
- Create: `lakeon/echomem/tests/integration/test_daemon_lifecycle.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_daemon_lifecycle.py`:
```python
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.mark.asyncio
async def test_health_returns_200_and_version(tmp_path, httpx_mock):
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "version" in body
    assert body["embedding_dim"] == 1024
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_daemon_lifecycle.py -v`

- [ ] **Step 3: Implement**

`src/echomem/daemon/__init__.py`: empty

`src/echomem/api/__init__.py`: empty

`src/echomem/api/health.py`:
```python
from fastapi import APIRouter, Request

from echomem import __version__

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict:
    cfg = request.app.state.config
    return {
        "status": "ok",
        "version": __version__,
        "embedding_dim": cfg.embedding_dim,
        "embedding_model": cfg.embedding_model,
    }
```

`src/echomem/daemon/app.py`:
```python
from __future__ import annotations

from contextlib import asynccontextmanager
from fastapi import FastAPI

from echomem.config import EchomemConfig
from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.api.health import router as health_router


@asynccontextmanager
async def _lifespan(app: FastAPI):
    cfg: EchomemConfig = app.state.config
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    driver = SQLiteDriver(cfg.data_dir / "db.sqlite", embedding_dim=cfg.embedding_dim)
    ollama = OllamaClient(cfg.ollama_url)
    app.state.driver = driver
    app.state.ollama = ollama
    try:
        yield
    finally:
        await ollama.aclose()
        driver.close()


def create_app(config: EchomemConfig) -> FastAPI:
    app = FastAPI(title="echomem", version="0.1.0", lifespan=_lifespan)
    app.state.config = config
    app.include_router(health_router)
    return app
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_daemon_lifecycle.py -v`
Expected: 1 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/daemon/ echomem/src/echomem/api/health.py echomem/src/echomem/api/__init__.py echomem/tests/integration/test_daemon_lifecycle.py
git commit -m "feat(echomem): FastAPI app factory + lifespan + /health"
```

---

### Task 12: Memory API — POST /memory/ingest

**Files:**
- Create: `lakeon/echomem/src/echomem/api/schemas.py`
- Create: `lakeon/echomem/src/echomem/api/memory.py`
- Modify: `lakeon/echomem/src/echomem/daemon/app.py`
- Create: `lakeon/echomem/tests/integration/test_memory_endpoints.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_memory_endpoints.py`:
```python
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
        is_reusable=True,
    )
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_ingest_returns_id_and_persists(client):
    r = await client.post(
        "/memory/ingest",
        json={"text": "hello world", "agent_id": "cc"},
    )
    assert r.status_code == 200
    body = r.json()
    assert "id" in body and len(body["id"]) == 26
    assert body["agent_id"] == "cc"


@pytest.mark.asyncio
async def test_ingest_validates_required_fields(client):
    r = await client.post("/memory/ingest", json={"agent_id": "cc"})
    assert r.status_code == 422
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_memory_endpoints.py -v`

- [ ] **Step 3: Implement**

`src/echomem/api/schemas.py`:
```python
from __future__ import annotations

from pydantic import BaseModel, Field


class IngestRequest(BaseModel):
    text: str = Field(min_length=1)
    agent_id: str = Field(min_length=1)
    source_kind: str = "explicit"
    source_ref: str | None = None
    meta: dict | None = None


class IngestResponse(BaseModel):
    id: str
    agent_id: str
    created_at: int


class RecallRequest(BaseModel):
    query: str = Field(min_length=1)
    k: int = 10
    agent_id: str | None = None


class RecallHitOut(BaseModel):
    id: str
    text: str
    score: float
    source_kind: str
    source_ref: str | None
    meta: dict | None


class RecallResponse(BaseModel):
    hits: list[RecallHitOut]


class MemoryOut(BaseModel):
    id: str
    agent_id: str
    source_kind: str
    source_ref: str | None
    text: str
    meta: dict | None
    created_at: int
    updated_at: int


class ListResponse(BaseModel):
    items: list[MemoryOut]
```

`src/echomem/api/memory.py`:
```python
from __future__ import annotations

import time
from fastapi import APIRouter, HTTPException, Request

from echomem.api.schemas import (
    IngestRequest,
    IngestResponse,
    ListResponse,
    MemoryOut,
    RecallRequest,
    RecallResponse,
    RecallHitOut,
)
from echomem.drivers.base import Memory
from echomem.ulid import new as new_id
from echomem.workers.embedder import EmbedderWorker

router = APIRouter(prefix="/memory")


@router.post("/ingest", response_model=IngestResponse)
async def ingest(req: IngestRequest, request: Request) -> IngestResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config

    now = int(time.time() * 1000)
    mid = new_id()
    embedding = await ollama.embed(req.text, model=cfg.embedding_model)
    mem = Memory(
        id=mid,
        agent_id=req.agent_id,
        source_kind=req.source_kind,
        source_ref=req.source_ref,
        text=req.text,
        meta=req.meta,
        created_at=now,
        updated_at=now,
        deleted_at=None,
        embedding=embedding,
    )
    driver.upsert_memory(mem)
    return IngestResponse(id=mid, agent_id=req.agent_id, created_at=now)
```

Modify `src/echomem/daemon/app.py` — add memory router:
```python
# imports
from echomem.api.memory import router as memory_router

# inside create_app, after include_router(health_router):
    app.include_router(memory_router)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_memory_endpoints.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/api/schemas.py echomem/src/echomem/api/memory.py echomem/src/echomem/daemon/app.py echomem/tests/integration/test_memory_endpoints.py
git commit -m "feat(echomem): POST /memory/ingest"
```

---

### Task 13: Memory API — POST /memory/recall

**Files:**
- Modify: `lakeon/echomem/src/echomem/api/memory.py`
- Modify: `lakeon/echomem/tests/integration/test_memory_endpoints.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/integration/test_memory_endpoints.py`:
```python
@pytest.mark.asyncio
async def test_recall_after_ingest(client):
    await client.post(
        "/memory/ingest", json={"text": "alpha bravo", "agent_id": "cc"}
    )
    await client.post(
        "/memory/ingest", json={"text": "echo foxtrot", "agent_id": "openclaw"}
    )

    r = await client.post(
        "/memory/recall", json={"query": "alpha", "k": 5}
    )
    assert r.status_code == 200
    hits = r.json()["hits"]
    assert any(h["text"] == "alpha bravo" for h in hits)


@pytest.mark.asyncio
async def test_recall_filter_by_agent(client):
    await client.post(
        "/memory/ingest", json={"text": "secret cc", "agent_id": "cc"}
    )
    await client.post(
        "/memory/ingest", json={"text": "secret hermes", "agent_id": "hermes"}
    )

    r = await client.post(
        "/memory/recall", json={"query": "secret", "k": 5, "agent_id": "hermes"}
    )
    hits = r.json()["hits"]
    assert all(h["text"] != "secret cc" for h in hits)
    assert any(h["text"] == "secret hermes" for h in hits)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_memory_endpoints.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/api/memory.py`:
```python
@router.post("/recall", response_model=RecallResponse)
async def recall(req: RecallRequest, request: Request) -> RecallResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config

    embedding = await ollama.embed(req.query, model=cfg.embedding_model)
    hits = driver.recall(
        query_embedding=embedding,
        query_text=req.query,
        k=req.k,
        agent_id=req.agent_id,
    )
    return RecallResponse(
        hits=[
            RecallHitOut(
                id=h.memory_id,
                text=h.text,
                score=h.score,
                source_kind=h.source_kind,
                source_ref=h.source_ref,
                meta=h.meta,
            )
            for h in hits
        ]
    )
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_memory_endpoints.py -v`
Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/api/memory.py echomem/tests/integration/test_memory_endpoints.py
git commit -m "feat(echomem): POST /memory/recall (hybrid via driver)"
```

---

### Task 14: Memory API — list / get / delete

**Files:**
- Modify: `lakeon/echomem/src/echomem/api/memory.py`
- Modify: `lakeon/echomem/tests/integration/test_memory_endpoints.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/integration/test_memory_endpoints.py`:
```python
@pytest.mark.asyncio
async def test_list_recent_first(client):
    for txt in ["one", "two", "three"]:
        await client.post("/memory/ingest", json={"text": txt, "agent_id": "cc"})
    r = await client.get("/memory/list?limit=10")
    items = r.json()["items"]
    assert [i["text"] for i in items] == ["three", "two", "one"]


@pytest.mark.asyncio
async def test_get_returns_memory(client):
    r = await client.post("/memory/ingest", json={"text": "x", "agent_id": "cc"})
    mid = r.json()["id"]
    r2 = await client.get(f"/memory/{mid}")
    assert r2.status_code == 200
    assert r2.json()["text"] == "x"


@pytest.mark.asyncio
async def test_get_404_when_missing(client):
    r = await client.get("/memory/01HXMISSINGMISSINGMISSINGM")
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_delete_then_get_404(client):
    r = await client.post("/memory/ingest", json={"text": "del", "agent_id": "cc"})
    mid = r.json()["id"]
    r2 = await client.delete(f"/memory/{mid}")
    assert r2.status_code == 200
    r3 = await client.get(f"/memory/{mid}")
    assert r3.status_code == 404
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_memory_endpoints.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/api/memory.py`:
```python
@router.get("/list", response_model=ListResponse)
async def list_memories(
    request: Request,
    agent_id: str | None = None,
    limit: int = 50,
    before: int | None = None,
) -> ListResponse:
    driver = request.app.state.driver
    items = driver.list_memories(agent_id=agent_id, limit=limit, before=before)
    return ListResponse(
        items=[
            MemoryOut(
                id=m.id,
                agent_id=m.agent_id,
                source_kind=m.source_kind,
                source_ref=m.source_ref,
                text=m.text,
                meta=m.meta,
                created_at=m.created_at,
                updated_at=m.updated_at,
            )
            for m in items
        ]
    )


@router.get("/{memory_id}", response_model=MemoryOut)
async def get_memory(memory_id: str, request: Request) -> MemoryOut:
    driver = request.app.state.driver
    m = driver.get_memory(memory_id)
    if m is None:
        raise HTTPException(status_code=404, detail="memory not found")
    return MemoryOut(
        id=m.id,
        agent_id=m.agent_id,
        source_kind=m.source_kind,
        source_ref=m.source_ref,
        text=m.text,
        meta=m.meta,
        created_at=m.created_at,
        updated_at=m.updated_at,
    )


@router.delete("/{memory_id}")
async def delete_memory(memory_id: str, request: Request) -> dict:
    driver = request.app.state.driver
    ok = driver.delete_memory(memory_id)
    if not ok:
        raise HTTPException(status_code=404, detail="memory not found")
    return {"id": memory_id, "deleted": True}
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_memory_endpoints.py -v`
Expected: 8 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/api/memory.py echomem/tests/integration/test_memory_endpoints.py
git commit -m "feat(echomem): list / get / delete memory endpoints"
```

---

### Task 15: CLI — `echomem init`

**Files:**
- Create: `lakeon/echomem/src/echomem/cli.py`
- Create: `lakeon/echomem/src/echomem/__main__.py`
- Create: `lakeon/echomem/tests/unit/test_cli_init.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_cli_init.py`:
```python
from typer.testing import CliRunner
from echomem.cli import app


def test_init_creates_data_dir_and_config(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    result = runner.invoke(app, ["init"])
    assert result.exit_code == 0, result.output
    cfg = tmp_path / ".echomem" / "config.toml"
    assert cfg.exists()
    assert (tmp_path / ".echomem" / "blobs").is_dir()
    assert (tmp_path / ".echomem" / "logs").is_dir()


def test_init_idempotent(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["init"])
    assert result.exit_code == 0
    assert "already initialized" in result.output.lower()
```

- [ ] **Step 2: Run (fails — no module)**

Run: `pytest tests/unit/test_cli_init.py -v`

- [ ] **Step 3: Implement**

`src/echomem/__main__.py`:
```python
from echomem.cli import app

if __name__ == "__main__":
    app()
```

`src/echomem/cli.py`:
```python
from __future__ import annotations

import typer
from rich.console import Console

from echomem.config import EchomemConfig, default_config_path, load_config, write_config

app = typer.Typer(help="echomem — local agent memory hub", no_args_is_help=True)
console = Console()


@app.command()
def init() -> None:
    """Create ~/.echomem/ skeleton + default config."""
    cfg_path = default_config_path()
    if cfg_path.exists():
        console.print(f"[yellow]echomem already initialized at[/] {cfg_path}")
        return

    cfg = EchomemConfig()
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    (cfg.data_dir / "blobs").mkdir(exist_ok=True)
    (cfg.data_dir / "logs").mkdir(exist_ok=True)
    (cfg.data_dir / "sessions").mkdir(exist_ok=True)
    (cfg.data_dir / "cache").mkdir(exist_ok=True)
    write_config(cfg)
    console.print(f"[green]initialized echomem[/] at {cfg.data_dir}")
    console.print(f"  config:    {cfg_path}")
    console.print(f"  daemon:    http://{cfg.host}:{cfg.port}")
    console.print(f"  ollama:    {cfg.ollama_url}")
    console.print(f"  embedding: {cfg.embedding_model} (dim={cfg.embedding_dim})")
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_cli_init.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/cli.py echomem/src/echomem/__main__.py echomem/tests/unit/test_cli_init.py
git commit -m "feat(echomem): CLI 'echomem init' bootstraps ~/.echomem"
```

---

### Task 16: CLI — `echomem start` / `echomem status`

**Files:**
- Modify: `lakeon/echomem/src/echomem/cli.py`
- Create: `lakeon/echomem/tests/unit/test_cli_status.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_cli_status.py`:
```python
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def test_status_when_no_daemon(tmp_path, monkeypatch, httpx_mock):
    monkeypatch.setenv("HOME", str(tmp_path))
    httpx_mock.add_exception(ConnectionError("refused"))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["status"])
    assert result.exit_code == 0
    assert "down" in result.output.lower() or "not reachable" in result.output.lower()


def test_status_when_daemon_up(tmp_path, monkeypatch, httpx_mock):
    monkeypatch.setenv("HOME", str(tmp_path))
    httpx_mock.add_response(
        method="GET",
        url__match=r"http://127\.0\.0\.1:\d+/health",
        json={
            "status": "ok",
            "version": "0.1.0",
            "embedding_dim": 1024,
            "embedding_model": "qwen3-embedding:0.6b",
        },
    )
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["status"])
    assert result.exit_code == 0
    assert "ok" in result.output.lower() or "up" in result.output.lower()
```

- [ ] **Step 2: Run (fails — no command)**

Run: `pytest tests/unit/test_cli_status.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/cli.py`:
```python
import sys
import httpx


@app.command()
def start(
    foreground: bool = typer.Option(
        True, "--foreground/--background", help="run in foreground (default)"
    ),
) -> None:
    """Start the echomem daemon."""
    import uvicorn

    cfg = load_config()
    if foreground:
        uvicorn.run(
            "echomem.daemon.app:create_app",
            factory=False,  # we need a module-level callable; provide a wrapper
            host=cfg.host,
            port=cfg.port,
            log_level=cfg.log_level.lower(),
        )
    else:
        # 简单后台：fork + detach (POSIX)
        import os

        pid = os.fork()
        if pid > 0:
            console.print(f"[green]echomem started[/] pid={pid}")
            sys.exit(0)
        os.setsid()
        uvicorn.run(
            "echomem.daemon.app:create_app",
            factory=False,
            host=cfg.host,
            port=cfg.port,
            log_level=cfg.log_level.lower(),
        )


@app.command()
def status() -> None:
    """Probe the daemon's /health and print a one-line summary."""
    cfg = load_config()
    url = f"http://{cfg.host}:{cfg.port}/health"
    try:
        with httpx.Client(timeout=2.0) as c:
            r = c.get(url)
            if r.status_code == 200:
                body = r.json()
                console.print(
                    f"[green]up[/]  version={body['version']}  "
                    f"model={body['embedding_model']}  dim={body['embedding_dim']}"
                )
                return
    except Exception:
        pass
    console.print(f"[red]down[/] (daemon at {url} not reachable)")
```

> Note: `create_app` 接受 config 参数；为了让 uvicorn 用模块路径起，新增一个 module-level 包装：

Append to `src/echomem/daemon/app.py`:
```python
def make_default_app() -> FastAPI:
    """uvicorn entry point: load config from disk."""
    from echomem.config import load_config

    return create_app(load_config())
```

修正 `src/echomem/cli.py` `start` 中的 uvicorn.run 改为：
```python
        uvicorn.run(
            "echomem.daemon.app:make_default_app",
            factory=True,
            host=cfg.host,
            port=cfg.port,
            log_level=cfg.log_level.lower(),
        )
```

(两处 uvicorn.run 都改成 `factory=True` + `make_default_app`。)

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_cli_status.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/cli.py echomem/src/echomem/daemon/app.py echomem/tests/unit/test_cli_status.py
git commit -m "feat(echomem): CLI 'echomem start' + 'echomem status'"
```

---

### Task 17: CLI — `echomem mem ingest|recall|list|delete|get`

**Files:**
- Modify: `lakeon/echomem/src/echomem/cli.py`
- Create: `lakeon/echomem/tests/unit/test_cli_mem.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_cli_mem.py`:
```python
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def _setup(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    return runner


def test_mem_ingest(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="POST",
        url__match=r"http://127\.0\.0\.1:\d+/memory/ingest",
        json={"id": "01HXCLI00000000000000000A", "agent_id": "cc", "created_at": 1},
    )
    result = runner.invoke(app, ["mem", "ingest", "hello", "--agent", "cc"])
    assert result.exit_code == 0
    assert "01HXCLI" in result.output


def test_mem_recall(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="POST",
        url__match=r"http://127\.0\.0\.1:\d+/memory/recall",
        json={
            "hits": [
                {
                    "id": "01HXCLI00000000000000000B",
                    "text": "hello world",
                    "score": 0.9,
                    "source_kind": "explicit",
                    "source_ref": None,
                    "meta": None,
                }
            ]
        },
    )
    result = runner.invoke(app, ["mem", "recall", "hello"])
    assert result.exit_code == 0
    assert "hello world" in result.output


def test_mem_list(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="GET",
        url__match=r"http://127\.0\.0\.1:\d+/memory/list.*",
        json={"items": []},
    )
    result = runner.invoke(app, ["mem", "list"])
    assert result.exit_code == 0
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_cli_mem.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/cli.py`:
```python
mem_app = typer.Typer(help="memory subcommands")
app.add_typer(mem_app, name="mem")


def _base_url() -> str:
    cfg = load_config()
    return f"http://{cfg.host}:{cfg.port}"


@mem_app.command("ingest")
def mem_ingest(
    text: str = typer.Argument(..., help="memory text"),
    agent: str = typer.Option("cli", "--agent", "-a"),
    source_kind: str = typer.Option("explicit", "--kind"),
) -> None:
    with httpx.Client(timeout=30.0) as c:
        r = c.post(
            f"{_base_url()}/memory/ingest",
            json={"text": text, "agent_id": agent, "source_kind": source_kind},
        )
        r.raise_for_status()
        body = r.json()
        console.print(f"[green]✓[/] {body['id']}  agent={body['agent_id']}")


@mem_app.command("recall")
def mem_recall(
    query: str = typer.Argument(...),
    k: int = typer.Option(5, "--k"),
    agent: str | None = typer.Option(None, "--agent"),
) -> None:
    payload = {"query": query, "k": k}
    if agent:
        payload["agent_id"] = agent
    with httpx.Client(timeout=30.0) as c:
        r = c.post(f"{_base_url()}/memory/recall", json=payload)
        r.raise_for_status()
        for h in r.json()["hits"]:
            console.print(f"[cyan]{h['score']:.3f}[/]  {h['id'][:8]}…  {h['text']}")


@mem_app.command("list")
def mem_list(
    agent: str | None = typer.Option(None, "--agent"),
    limit: int = typer.Option(50, "--limit"),
) -> None:
    params = {"limit": limit}
    if agent:
        params["agent_id"] = agent
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/memory/list", params=params)
        r.raise_for_status()
        for m in r.json()["items"]:
            console.print(f"  {m['id'][:8]}…  [dim]{m['agent_id']}[/]  {m['text'][:80]}")


@mem_app.command("get")
def mem_get(memory_id: str) -> None:
    with httpx.Client(timeout=10.0) as c:
        r = c.get(f"{_base_url()}/memory/{memory_id}")
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        m = r.json()
        console.print(m)


@mem_app.command("delete")
def mem_delete(memory_id: str) -> None:
    with httpx.Client(timeout=10.0) as c:
        r = c.delete(f"{_base_url()}/memory/{memory_id}")
        if r.status_code == 404:
            console.print("[red]not found[/]")
            raise typer.Exit(1)
        r.raise_for_status()
        console.print(f"[green]✓[/] deleted {memory_id}")
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_cli_mem.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/cli.py echomem/tests/unit/test_cli_mem.py
git commit -m "feat(echomem): CLI 'echomem mem ingest|recall|list|get|delete'"
```

---

### Task 18: MCP shim — stdio JSON-RPC framework

**Files:**
- Create: `lakeon/echomem/src/echomem/mcp_shim/__init__.py`
- Create: `lakeon/echomem/src/echomem/mcp_shim/__main__.py`
- Create: `lakeon/echomem/src/echomem/mcp_shim/shim.py`
- Create: `lakeon/echomem/tests/e2e/__init__.py`
- Create: `lakeon/echomem/tests/e2e/test_mcp_shim.py`

- [ ] **Step 1: Write the failing test**

`tests/e2e/test_mcp_shim.py`:
```python
import json
import io
import pytest
from echomem.mcp_shim.shim import handle_message


@pytest.mark.asyncio
async def test_initialize_returns_capabilities():
    msg = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
    out = await handle_message(msg, base_url="http://t")
    assert out["jsonrpc"] == "2.0"
    assert out["id"] == 1
    assert "capabilities" in out["result"]
    assert "tools" in out["result"]["capabilities"]


@pytest.mark.asyncio
async def test_tools_list_returns_5_memory_tools():
    msg = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    out = await handle_message(msg, base_url="http://t")
    names = {t["name"] for t in out["result"]["tools"]}
    assert {
        "memory_ingest",
        "memory_recall",
        "memory_list",
        "memory_get",
        "memory_delete",
    }.issubset(names)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/e2e/test_mcp_shim.py -v`

- [ ] **Step 3: Implement**

`src/echomem/mcp_shim/__init__.py`: empty

`src/echomem/mcp_shim/shim.py`:
```python
from __future__ import annotations

import json
from typing import Any

import httpx

PROTOCOL_VERSION = "2025-06-18"

TOOLS: list[dict[str, Any]] = [
    {
        "name": "memory_ingest",
        "description": "Persist a memory; embedding generated server-side.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "agent_id": {"type": "string"},
                "meta": {"type": "object"},
            },
            "required": ["text", "agent_id"],
        },
    },
    {
        "name": "memory_recall",
        "description": "Retrieve memories matching a natural-language query.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "k": {"type": "integer", "default": 10},
                "agent_id": {"type": "string"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "memory_list",
        "description": "List recent memories.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent_id": {"type": "string"},
                "limit": {"type": "integer", "default": 50},
            },
        },
    },
    {
        "name": "memory_get",
        "description": "Get a memory by id.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
    {
        "name": "memory_delete",
        "description": "Soft-delete a memory by id.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
]


async def handle_message(msg: dict, *, base_url: str) -> dict | None:
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "echomem", "version": "0.1.0"},
            },
        }

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}

    if method == "tools/call":
        params = msg.get("params") or {}
        return await _call_tool(msg_id, params, base_url)

    if method in ("notifications/initialized", "notifications/cancelled"):
        return None

    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "error": {"code": -32601, "message": f"Method not found: {method}"},
    }


async def _call_tool(msg_id: Any, params: dict, base_url: str) -> dict:
    name = params.get("name")
    args = params.get("arguments") or {}
    try:
        async with httpx.AsyncClient(base_url=base_url, timeout=60.0) as client:
            if name == "memory_ingest":
                r = await client.post("/memory/ingest", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_recall":
                r = await client.post("/memory/recall", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_list":
                r = await client.get("/memory/list", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_get":
                r = await client.get(f"/memory/{args['id']}")
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_delete":
                r = await client.delete(f"/memory/{args['id']}")
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            else:
                return {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": -32602, "message": f"Unknown tool: {name}"},
                }
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {"content": [{"type": "text", "text": content}]},
        }
    except httpx.HTTPError as e:
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": -32603, "message": f"daemon HTTP error: {e}"},
        }
```

`src/echomem/mcp_shim/__main__.py`:
```python
from __future__ import annotations

import asyncio
import json
import sys

from echomem.config import load_config
from echomem.mcp_shim.shim import handle_message


async def _serve() -> None:
    cfg = load_config()
    base_url = f"http://{cfg.host}:{cfg.port}"
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await loop.connect_read_pipe(lambda: protocol, sys.stdin)

    while True:
        line = await reader.readline()
        if not line:
            break
        try:
            msg = json.loads(line.decode("utf-8"))
        except json.JSONDecodeError:
            continue
        out = await handle_message(msg, base_url=base_url)
        if out is None:
            continue
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


def main() -> None:
    asyncio.run(_serve())


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/e2e/test_mcp_shim.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/mcp_shim/ echomem/tests/e2e/
git commit -m "feat(echomem): MCP stdio shim — initialize + tools/list"
```

---

### Task 19: MCP shim — tools/call routes to daemon HTTP (with running daemon)

**Files:**
- Modify: `lakeon/echomem/tests/e2e/test_mcp_shim.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/e2e/test_mcp_shim.py`:
```python
import json
import asyncio
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app
from echomem.mcp_shim.shim import handle_message


@pytest.mark.asyncio
async def test_tools_call_memory_ingest_via_real_daemon(tmp_path, httpx_mock, monkeypatch):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
        is_reusable=True,
    )
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)

    # mount the FastAPI app on httpx via ASGITransport
    transport = ASGITransport(app=app)

    async with app.router.lifespan_context(app):
        # monkey-patch httpx.AsyncClient inside shim module to use ASGI transport
        import httpx
        original_async_client = httpx.AsyncClient

        def _AsyncClient(*args, **kwargs):
            kwargs["transport"] = transport
            return original_async_client(*args, **kwargs)

        monkeypatch.setattr(httpx, "AsyncClient", _AsyncClient)

        msg = {
            "jsonrpc": "2.0",
            "id": 10,
            "method": "tools/call",
            "params": {
                "name": "memory_ingest",
                "arguments": {"text": "hello via shim", "agent_id": "cc"},
            },
        }
        out = await handle_message(msg, base_url="http://t")

    assert out["jsonrpc"] == "2.0"
    assert "result" in out
    payload = json.loads(out["result"]["content"][0]["text"])
    assert "id" in payload
    assert payload["agent_id"] == "cc"
```

- [ ] **Step 2: Run (PASS — code already supports this)**

Run: `pytest tests/e2e/test_mcp_shim.py -v`
Expected: 3 PASSED.

> If FAIL due to ASGITransport / httpx_mock interaction, adjust test to use `pytest_httpx` exclusively and mock `/memory/ingest` directly. The point is: shim emits a valid JSON-RPC `result` for an ingest call.

- [ ] **Step 3: (No new code — this task is a regression test for routing.)**

- [ ] **Step 4: Re-run all tests**

Run: `pytest -v`
Expected: All green.

- [ ] **Step 5: Commit**

```bash
git add echomem/tests/e2e/test_mcp_shim.py
git commit -m "test(echomem): e2e MCP shim → daemon ingest round-trip"
```

---

### Task 20: 端到端：spawn shim 子进程 + 真 daemon + Ollama mock

**Files:**
- Create: `lakeon/echomem/tests/e2e/test_full_loop.py`

- [ ] **Step 1: Write the failing test**

`tests/e2e/test_full_loop.py`:
```python
"""
模拟 Claude Code 的真实接入：
  - spawn `python -m echomem.mcp_shim` 子进程
  - 通过 stdin 发 initialize → tools/call(memory_ingest) → tools/call(memory_recall)
  - 验证 stdout 是合法 JSON-RPC

需要 ECHOMEM_E2E=1 才跑，避免本地无 daemon 时 CI 红。
"""
import asyncio
import json
import os
import socket
import subprocess
import sys
import time
import pytest
from contextlib import contextmanager


pytestmark = pytest.mark.skipif(
    os.environ.get("ECHOMEM_E2E") != "1",
    reason="set ECHOMEM_E2E=1 to run; requires running Ollama with qwen3-embedding:0.6b",
)


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextmanager
def _daemon(tmp_path, port):
    env = os.environ.copy()
    env["HOME"] = str(tmp_path)
    env["ECHOMEM_PORT"] = str(port)
    # init then start
    subprocess.check_call([sys.executable, "-m", "echomem", "init"], env=env)
    proc = subprocess.Popen(
        [sys.executable, "-m", "echomem", "start", "--foreground"],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # 等待 /health 200
    deadline = time.time() + 15
    import httpx

    while time.time() < deadline:
        try:
            r = httpx.get(f"http://127.0.0.1:{port}/health", timeout=1.0)
            if r.status_code == 200:
                break
        except Exception:
            pass
        time.sleep(0.2)
    else:
        proc.terminate()
        raise RuntimeError("daemon not up")
    try:
        yield
    finally:
        proc.terminate()
        proc.wait(timeout=5)


def test_full_loop(tmp_path):
    port = _free_port()
    with _daemon(tmp_path, port):
        env = os.environ.copy()
        env["HOME"] = str(tmp_path)
        env["ECHOMEM_PORT"] = str(port)
        shim = subprocess.Popen(
            [sys.executable, "-m", "echomem.mcp_shim"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            text=True,
        )

        def send(msg):
            shim.stdin.write(json.dumps(msg) + "\n")
            shim.stdin.flush()

        def recv():
            line = shim.stdout.readline()
            return json.loads(line) if line else None

        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        init = recv()
        assert init["result"]["serverInfo"]["name"] == "echomem"

        send(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "name": "memory_ingest",
                    "arguments": {"text": "alpha bravo", "agent_id": "cc"},
                },
            }
        )
        ing = recv()
        ing_payload = json.loads(ing["result"]["content"][0]["text"])
        assert ing_payload["agent_id"] == "cc"

        send(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "memory_recall",
                    "arguments": {"query": "alpha"},
                },
            }
        )
        rec = recv()
        rec_payload = json.loads(rec["result"]["content"][0]["text"])
        assert any("alpha" in h["text"] for h in rec_payload["hits"])

        shim.stdin.close()
        shim.terminate()
        shim.wait(timeout=5)
```

- [ ] **Step 2: Run with ECHOMEM_E2E=1 (requires Ollama)**

Run:
```bash
ollama serve  # 已起则跳过
ollama pull qwen3-embedding:0.6b
ECHOMEM_E2E=1 pytest tests/e2e/test_full_loop.py -v -s
```
Expected: PASS.

- [ ] **Step 3: (No code change — verifies whole stack.)**

- [ ] **Step 4: Re-run all tests without env (skipped)**

Run: `pytest -v`
Expected: `test_full_loop` SKIPPED, all others PASS.

- [ ] **Step 5: Commit**

```bash
git add echomem/tests/e2e/test_full_loop.py
git commit -m "test(echomem): full-loop e2e — shim+daemon+Ollama (gated by ECHOMEM_E2E=1)"
```

---

### Task 21: README + manual smoke + register with Claude Code

**Files:**
- Create: `lakeon/echomem/README.md`
- Modify: `~/.claude/settings.json` (manual; document only)

- [ ] **Step 1: Write README**

`lakeon/echomem/README.md`:
```markdown
# echomem

> Local-first agent memory hub for Claude Code / openclaw / hermes.
> Phase 1 backbone — see `docs/superpowers/specs/2026-04-30-echomem-design.md` for the full design.

## Status

Phase 1 / Backbone — Memory API only. No derivatives, no Dashboard, no Context API yet.

## Install (dev)

```bash
cd lakeon/echomem
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
```

## Bootstrap

```bash
echomem init           # creates ~/.echomem/{config.toml, blobs/, logs/, sessions/, cache/}
echomem start          # uvicorn on http://127.0.0.1:8473 (or ECHOMEM_PORT)
echomem status         # probe /health
```

## Use from CLI

```bash
echomem mem ingest "hello world" --agent cli
echomem mem recall "hello"
echomem mem list --limit 10
```

## Wire into Claude Code

Add to `~/.claude/settings.json` under `mcpServers`:

```json
{
  "mcpServers": {
    "echomem": {
      "command": "echomem-mcp-shim",
      "args": [],
      "env": {}
    }
  }
}
```

Restart Claude Code. Tools `mcp__echomem__memory_ingest` etc. are available.

## Run tests

```bash
pytest -v                     # unit + integration (mocks Ollama)
ECHOMEM_E2E=1 pytest -v -s    # full loop (requires real Ollama)
```

## What's next

- Plan 2: derivatives pipeline (timeline / hierarchical / graph / procedural)
- Plan 3: Context API + FS blobs (add_url / ls / read / write / mv)
- Plan 4: Dashboard
- Plan 5: Onboarding + openclaw / hermes wiring
```

- [ ] **Step 2: Manual smoke test (your shell, not in tests)**

```bash
ollama pull qwen3-embedding:0.6b
echomem init
echomem start &
sleep 2
echomem mem ingest "spike works" --agent manual
echomem mem recall "spike"
# expect to see the line back
```

- [ ] **Step 3: Wire into Claude Code (manual)**

Edit `~/.claude/settings.json`, add the `echomem` mcpServer entry from README. Restart CC. In a new CC session try:
> "echomem 记一条事：今天我跟 Jacky 走了 echomem Plan 1，验证了端到端"

Then in **another** CC session:
> "echomem recall 一下今天和 Jacky 的事"

Expected: 第二个 session 召回第一个 session 写的那条记忆。

- [ ] **Step 4: Run full test suite**

Run:
```bash
cd lakeon/echomem
pytest -v
```
Expected: all GREEN (e2e gated test SKIPPED).

- [ ] **Step 5: Commit**

```bash
git add echomem/README.md
git commit -m "docs(echomem): README + Claude Code MCP wiring instructions"
```

---

## Acceptance / Verify

跑通这条端到端链路即 Plan 1 验收通过：

1. `cd lakeon/echomem && pytest -v` 全绿（除 ECHOMEM_E2E gated 的 SKIPPED）
2. `echomem init` 产生 `~/.echomem/{config.toml, blobs/, logs/, sessions/, cache/}`
3. `echomem start` 起 daemon，`echomem status` 显示 `up version=0.1.0 model=qwen3-embedding:0.6b dim=1024`
4. `echomem mem ingest "x" --agent cli` 返回 ULID
5. `echomem mem recall "x"` 命中刚才那条
6. CC session A 通过 MCP 调用 memory_ingest → CC session B 调用 memory_recall 命中
7. （可选）`ECHOMEM_E2E=1 pytest -v` 全绿（需 Ollama + 模型已拉）

## Followups（不在本 plan 范围）

- Plan 2: 衍生物 pipeline + 4 种 view + worker async queue
- Plan 3: Context API + FS blobs（add_url / ls / read / write / mv）
- Plan 4: Vue 3 Dashboard SPA
- Plan 5: Onboarding install.sh + openclaw / hermes 接入协议调研
- Insight Track: 独立子项目（hook-based，Phase 2+）

## Self-Review Checklist

- [x] 21 个 task，每个 task 5 步（write test → fail → impl → pass → commit）
- [x] 所有文件路径都是绝对/确切（lakeon/echomem/...）
- [x] 没有 "TODO / TBD / similar to Task N" 占位符
- [x] 类型一致：`Memory` / `RecallHit` / `EchomemConfig` / `StorageDriver` 在所有 task 中签名一致
- [x] 端到端测试覆盖：unit (driver/config/ulid) + integration (daemon/endpoints/embedder) + e2e (shim/full loop)
- [x] 未实现的 spec 范围明确标注 → Plan 2/3/4/5
- [x] 验收画面明确（7 条可勾验收项）
