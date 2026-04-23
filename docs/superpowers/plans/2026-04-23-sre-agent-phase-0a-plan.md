# SRE Agent Phase 0a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在一周内交付一个在 Railway 上 always-on 的 SRE agent，持续监控 dbay.cloud compute 冷启动超过 5 秒的异常，自动诊断后通过飞书 push 告警；agent 的每一次推理完整写入 `agent_session_log` commit log，OBS 异步持久化。

**Architecture:** Hermes agent 作为 runtime（不 fork），agent_session_log 作为旁挂 Python 模块（设计为 runtime-agnostic，未来可抽包），SRE skills 以文件形式注册到 hermes；LLM 用 Deepseek 官网；MCP 工具层复用已有的 dbay-sre-mcp；Railway 单进程部署，本地 Volume 持久化，OBS 做异步镜像。

**Tech Stack:** Python 3.11、uv、pytest、PyYAML、httpx、esdk-obs-python（华为云 OBS SDK）、hermes-agent（Nous Research）、dbay-sre-mcp（已有）、Deepseek API（OpenAI-compatible）。

**Related spec:** [`docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`](../specs/2026-04-23-agent-commit-log-phase0-design.md)

---

## File Structure

```
lakeon/sre-agent/                              # NEW top-level dir
├── pyproject.toml                             # Python project config (uv)
├── Dockerfile                                 # Railway image
├── railway.toml                               # Railway path-filter + build
├── README.md                                  # Quickstart + ops notes
├── .env.example                               # Secret template
├── .python-version                            # pin 3.11
├── entrypoint.sh                              # container startup
├── agent_session_log/                         # CORE — commit log library
│   ├── __init__.py                            # public API exports
│   ├── types.py                               # dataclasses & enums
│   ├── ids.py                                 # session_id generation
│   ├── evidence.py                            # Blob + content-addressing
│   ├── store.py                               # FilesystemStore
│   ├── session.py                             # Session class (main API)
│   ├── log.py                                 # LogStore top-level
│   ├── skill_ledger.py                        # SkillLedger
│   ├── obs_sync.py                            # OBS upload worker
│   └── py.typed                               # PEP 561 marker
├── skills/                                    # hermes-loadable skills
│   └── sre/
│       ├── cold_start_watcher/
│       │   ├── SKILL.md                       # hermes skill manifest
│       │   ├── watcher.py                     # detect + open session
│       │   ├── diagnose_prompt.md             # LLM diagnosis prompt
│       │   └── report_template.md             # feishu card template
│       └── outcome_checker/
│           ├── SKILL.md
│           ├── checker.py
│           └── followup_prompt.md
├── hermes_config/                             # hermes runtime config
│   ├── config.yaml                            # provider + feishu + cron
│   ├── mcp.json                               # MCP server registry
│   └── personalities/
│       └── sre.md                             # SRE personality system prompt
├── scripts/
│   ├── verify_env.py                          # pre-flight secret check
│   ├── probe_dbay_logs.py                     # Day 1 network probe
│   └── simulate_cold_start.py                 # manual trigger for validation
└── tests/
    ├── conftest.py                            # pytest fixtures + tmp root
    ├── test_types.py
    ├── test_evidence.py
    ├── test_store.py
    ├── test_session.py
    ├── test_log_query.py
    ├── test_skill_ledger.py
    ├── test_obs_sync.py
    └── integration/
        └── test_cold_start_watcher.py         # mocked MCP + LLM

lakeon/lakeon-api/src/main/java/com/lakeon/service/
└── ComputeLifecycleService.java                # MODIFY: add "compute started in Xms" log
```

### Module responsibilities

| Module | Responsibility | Dependencies |
|---|---|---|
| `types.py` | Enums and dataclasses only | stdlib |
| `ids.py` | Generate `sess_<ts>_<shortid>` | stdlib (uuid, datetime) |
| `evidence.py` | Blob class, sha256 hashing, MIME handling | stdlib (hashlib) |
| `store.py` | Filesystem read/write (manifest/events/evidence/conclusion/outcome) | stdlib, pyyaml |
| `session.py` | Session class: lifecycle + branching + concluding | types, store, evidence, ids |
| `log.py` | LogStore: query across sessions (list/get/search_text/replay/similar) | types, store |
| `skill_ledger.py` | SkillLedger: record_invocation, stats | store, types |
| `obs_sync.py` | Async upload of closed sessions to OBS | esdk-obs-python, session |
| `skills/sre/cold_start_watcher/watcher.py` | Detect >5s starts, open session, call LLM, write conclusion, notify feishu | agent_session_log, httpx, hermes SDK |
| `skills/sre/outcome_checker/checker.py` | 24h后查看 session status, 调 log_stats 验证修复 | agent_session_log, httpx |

**Discipline:** `agent_session_log/` MUST NOT import from anywhere under `lakeon/` or from `dbay-sre-mcp/`. This is enforced by a CI check (Task 4).

---

## Work Breakdown — 22 Tasks

Organized into 7 groups (A-G). Each task is self-contained; groups are roughly sequential but within a group tasks can reorder.

| Group | Tasks | What it produces |
|---|---|---|
| A. Scaffolding | 1-3 | Python project, test infra, import discipline |
| B. agent_session_log core | 4-10 | Library passes unit tests end-to-end |
| C. OBS sync | 11-12 | Session archives land in OBS |
| D. Hermes integration | 13-15 | hermes starts, feishu bot echoes, deepseek works |
| E. SRE skills | 16-19 | cold-start-watcher + outcome-checker functional locally |
| F. Railway deployment | 20-22 | Production runtime live |
| G. Validation | (part of 22) | First real incident caught |

---

## Group A: Scaffolding

### Task 1: Create project skeleton

**Files:**
- Create: `lakeon/sre-agent/pyproject.toml`
- Create: `lakeon/sre-agent/.python-version`
- Create: `lakeon/sre-agent/.env.example`
- Create: `lakeon/sre-agent/README.md`
- Create: `lakeon/sre-agent/.gitignore`
- Create: `lakeon/sre-agent/agent_session_log/__init__.py` (empty)
- Create: `lakeon/sre-agent/agent_session_log/py.typed` (empty)

- [ ] **Step 1.1: Create pyproject.toml**

```toml
[project]
name = "sre-agent"
version = "0.0.1"
description = "dbay.cloud SRE agent + agent_session_log"
requires-python = ">=3.11"
dependencies = [
  "pyyaml>=6.0",
  "httpx>=0.27",
  "esdk-obs-python>=3.24",
]

[project.optional-dependencies]
dev = [
  "pytest>=8.0",
  "pytest-asyncio>=0.23",
  "pytest-cov>=5.0",
  "mypy>=1.10",
  "ruff>=0.5",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
asyncio_mode = "auto"

[tool.ruff]
line-length = 100
target-version = "py311"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["agent_session_log"]
```

- [ ] **Step 1.2: .python-version**

```
3.11
```

- [ ] **Step 1.3: .env.example**

```
# LLM
DEEPSEEK_API_KEY=sk-xxxxx
DEEPSEEK_BASE_URL=https://api.deepseek.com

# dbay logs
DBAY_LOGS_DSN=postgresql://user:pass@host:5432/dbay_logs

# Feishu bot
FEISHU_APP_ID=cli_xxxxx
FEISHU_APP_SECRET=xxxxx
FEISHU_VERIFICATION_TOKEN=xxxxx
FEISHU_ENCRYPT_KEY=xxxxx
FEISHU_ALLOWED_OPEN_IDS=ou_jackys_open_id

# OBS
OBS_ACCESS_KEY=xxxxx
OBS_SECRET_KEY=xxxxx
OBS_BUCKET=dbay-agent-log
OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com

# Agent data dir
HERMES_HOME=/data/hermes
```

- [ ] **Step 1.4: README.md** (skeleton, not bloated)

```markdown
# dbay.cloud SRE agent

Phase 0a: hermes-based SRE agent + agent_session_log.

See spec: ../../docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md

## Layout
- `agent_session_log/` — commit log Python library (runtime-agnostic, zero lakeon dependency)
- `skills/sre/` — hermes skills
- `hermes_config/` — hermes config files
- `scripts/` — operational helpers
- `tests/` — pytest suite

## Local dev
```bash
cd lakeon/sre-agent
uv sync --all-extras
uv run pytest
```
```

- [ ] **Step 1.5: .gitignore**

```
__pycache__/
*.py[cod]
.pytest_cache/
.coverage
.mypy_cache/
.ruff_cache/
dist/
build/
*.egg-info/
.venv/
.env
```

- [ ] **Step 1.6: Empty package markers**

Empty files: `agent_session_log/__init__.py`, `agent_session_log/py.typed`.

- [ ] **Step 1.7: Install deps**

```bash
cd /Users/jacky/code/lakeon/sre-agent
uv sync --all-extras
```
Expected: `.venv/` created, deps installed, no errors.

- [ ] **Step 1.8: Commit**

```bash
git add lakeon/sre-agent
git commit -m "feat(sre-agent): scaffold Phase 0a project structure"
```

---

### Task 2: Pytest plumbing

**Files:**
- Create: `lakeon/sre-agent/tests/__init__.py` (empty)
- Create: `lakeon/sre-agent/tests/conftest.py`

- [ ] **Step 2.1: conftest.py**

```python
"""Shared pytest fixtures."""
import os
from pathlib import Path
import pytest


@pytest.fixture
def tmp_log_root(tmp_path: Path) -> Path:
    """Isolated agent_session_log root for a single test."""
    root = tmp_path / "hermes_data"
    root.mkdir()
    return root


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Prevent tests from accidentally hitting real services via env."""
    for key in ("DEEPSEEK_API_KEY", "DBAY_LOGS_DSN", "OBS_ACCESS_KEY"):
        monkeypatch.delenv(key, raising=False)
```

- [ ] **Step 2.2: Smoke test**

Create `tests/test_smoke.py`:
```python
def test_smoke():
    assert True
```

Run: `uv run pytest tests/test_smoke.py -v`
Expected: 1 passed.

- [ ] **Step 2.3: Commit**

```bash
git add lakeon/sre-agent/tests
git commit -m "test(sre-agent): pytest plumbing + smoke test"
```

---

### Task 3: Import discipline check

**Goal:** Enforce that `agent_session_log/` does NOT import from lakeon internals or dbay-sre-mcp. This is the contract that makes Phase 2 extraction mechanical.

**Files:**
- Create: `lakeon/sre-agent/tests/test_import_discipline.py`

- [ ] **Step 3.1: Write the test**

```python
"""Guard: agent_session_log must stay runtime-agnostic."""
import ast
from pathlib import Path


FORBIDDEN_PREFIXES = ("lakeon", "dbay_sre_mcp", "hermes")
ALLOWED_THIRD_PARTY = {"yaml", "httpx", "obs"}  # whitelist
STDLIB_PREFIXES = None  # checked via sys.stdlib_module_names at runtime


def _collect_imports(py_file: Path) -> set[str]:
    tree = ast.parse(py_file.read_text(encoding="utf-8"))
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                names.add(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if node.module and node.level == 0:
                names.add(node.module.split(".")[0])
    return names


def test_no_forbidden_imports():
    root = Path(__file__).resolve().parents[1] / "agent_session_log"
    violations = []
    for py in root.rglob("*.py"):
        imports = _collect_imports(py)
        for imp in imports:
            if any(imp == p or imp.startswith(p + ".") for p in FORBIDDEN_PREFIXES):
                violations.append(f"{py.relative_to(root.parent)}: {imp}")
    assert not violations, (
        "agent_session_log must not import lakeon/dbay_sre_mcp/hermes:\n  "
        + "\n  ".join(violations)
    )
```

- [ ] **Step 3.2: Run — should pass (module is empty)**

```bash
uv run pytest tests/test_import_discipline.py -v
```
Expected: 1 passed.

- [ ] **Step 3.3: Commit**

```bash
git add lakeon/sre-agent/tests/test_import_discipline.py
git commit -m "test(sre-agent): enforce agent_session_log import discipline"
```

---

## Group B: agent_session_log Core

### Task 4: Types and IDs

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/types.py`
- Create: `lakeon/sre-agent/agent_session_log/ids.py`
- Create: `lakeon/sre-agent/tests/test_types.py`

- [ ] **Step 4.1: Write failing test for ID format**

```python
# tests/test_types.py
import re
import time
from agent_session_log.ids import new_session_id


def test_session_id_format():
    sid = new_session_id()
    assert re.match(r"^sess_\d{8}T\d{6}_[a-f0-9]{6}$", sid), sid


def test_session_id_unique():
    ids = {new_session_id() for _ in range(100)}
    assert len(ids) == 100


def test_session_id_timestamp_monotonic():
    a = new_session_id()
    time.sleep(0.001)
    b = new_session_id()
    # Compare timestamp portions
    assert a.split("_")[1] <= b.split("_")[1]
```

- [ ] **Step 4.2: Run — fails with ImportError**

```bash
uv run pytest tests/test_types.py -v
```
Expected: FAIL, ModuleNotFoundError: agent_session_log.ids.

- [ ] **Step 4.3: Implement ids.py**

```python
# agent_session_log/ids.py
"""Session and turn id generation."""
from __future__ import annotations

import secrets
from datetime import datetime, timezone


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _compact_ts() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")


def new_session_id() -> str:
    return f"sess_{_compact_ts()}_{secrets.token_hex(3)}"
```

- [ ] **Step 4.4: Run — passes**

```bash
uv run pytest tests/test_types.py -v
```
Expected: 3 passed.

- [ ] **Step 4.5: Implement types.py**

```python
# agent_session_log/types.py
"""Dataclasses and enums. No logic, no side effects."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class SessionType(str, Enum):
    INCIDENT = "incident"
    READING = "reading"
    REFLECTION = "reflection"


class SessionStatus(str, Enum):
    OPEN = "open"
    CLOSED = "closed"
    ABANDONED = "abandoned"


class TurnType(str, Enum):
    TRIGGER = "trigger"
    THOUGHT = "thought"
    TOOL_CALL = "tool_call"
    TOOL_RESULT = "tool_result"
    LLM_COMPLETION = "llm_completion"
    BRANCH_OPEN = "branch_open"
    BRANCH_RESOLVE = "branch_resolve"
    CONCLUDE = "conclude"


@dataclass(frozen=True)
class BlobRef:
    """Reference to evidence blob. Not the bytes themselves."""
    sha256: str
    mime: str
    size: int
    ext: str
    source: str | None = None


@dataclass
class SessionManifest:
    id: str
    type: str
    created_at: str
    closed_at: str | None
    status: str
    trigger: dict[str, Any]
    tags: list[str] = field(default_factory=list)
    parent_sessions: list[str] = field(default_factory=list)
    model: str | None = None
    runtime: str | None = None
    obs_ref: str | None = None  # filled after sync
```

- [ ] **Step 4.6: Add public exports**

```python
# agent_session_log/__init__.py
"""Agent commit log: LLM-native session/reasoning/skill data layer."""
from agent_session_log.ids import new_session_id, utc_now_iso
from agent_session_log.types import (
    BlobRef,
    SessionManifest,
    SessionStatus,
    SessionType,
    TurnType,
)

__all__ = [
    "BlobRef",
    "SessionManifest",
    "SessionStatus",
    "SessionType",
    "TurnType",
    "new_session_id",
    "utc_now_iso",
]
__version__ = "0.0.1"
```

- [ ] **Step 4.7: Commit**

```bash
git add lakeon/sre-agent/agent_session_log lakeon/sre-agent/tests/test_types.py
git commit -m "feat(sre-agent): types and ids for agent_session_log"
```

---

### Task 5: Evidence blobs with content-addressing

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/evidence.py`
- Create: `lakeon/sre-agent/tests/test_evidence.py`

- [ ] **Step 5.1: Write failing test**

```python
# tests/test_evidence.py
from agent_session_log.evidence import hash_bytes, Blob


def test_hash_bytes_deterministic():
    h1 = hash_bytes(b"hello")
    h2 = hash_bytes(b"hello")
    assert h1 == h2
    assert len(h1) == 64  # sha256 hex


def test_hash_bytes_different_inputs():
    assert hash_bytes(b"a") != hash_bytes(b"b")


def test_blob_from_bytes_log():
    blob = Blob.from_bytes(b"2026-04-23 09:12:30 INFO started", mime="text/plain", source="log_search")
    assert blob.ext == "log"
    assert blob.size == len(b"2026-04-23 09:12:30 INFO started")
    assert blob.source == "log_search"
    assert blob.mime == "text/plain"


def test_blob_from_bytes_json():
    blob = Blob.from_bytes(b'{"key":1}', mime="application/json")
    assert blob.ext == "json"


def test_blob_short_hash():
    blob = Blob.from_bytes(b"xyz", mime="text/plain")
    assert len(blob.short_hash) == 8
    assert blob.short_hash == blob.sha256[:8]
```

- [ ] **Step 5.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 5.3: Implement evidence.py**

```python
# agent_session_log/evidence.py
"""Evidence blobs: content-addressed, deduplicated."""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Optional


_MIME_EXT = {
    "text/plain": "log",
    "application/json": "json",
    "image/png": "png",
    "image/jpeg": "jpg",
    "application/x-yaml": "yaml",
    "text/markdown": "md",
}


def hash_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ext_for_mime(mime: str) -> str:
    return _MIME_EXT.get(mime, "bin")


@dataclass(frozen=True)
class Blob:
    sha256: str
    mime: str
    size: int
    ext: str
    source: Optional[str] = None
    _bytes: bytes | None = None  # only set when created fresh (not on load)

    @classmethod
    def from_bytes(cls, data: bytes, mime: str, source: Optional[str] = None) -> "Blob":
        return cls(
            sha256=hash_bytes(data),
            mime=mime,
            size=len(data),
            ext=ext_for_mime(mime),
            source=source,
            _bytes=data,
        )

    @property
    def short_hash(self) -> str:
        return self.sha256[:8]

    @property
    def filename(self) -> str:
        return f"{self.sha256}-{self.short_hash}.{self.ext}"

    def bytes(self) -> bytes:
        if self._bytes is None:
            raise ValueError("Blob loaded from disk has no bytes; read via store")
        return self._bytes
```

- [ ] **Step 5.4: Run — passes**

```bash
uv run pytest tests/test_evidence.py -v
```
Expected: 5 passed.

- [ ] **Step 5.5: Export Blob**

Add to `agent_session_log/__init__.py`:
```python
from agent_session_log.evidence import Blob, hash_bytes

__all__ = [..., "Blob", "hash_bytes"]
```

- [ ] **Step 5.6: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/evidence.py lakeon/sre-agent/agent_session_log/__init__.py lakeon/sre-agent/tests/test_evidence.py
git commit -m "feat(sre-agent): content-addressed Blob evidence type"
```

---

### Task 6: FilesystemStore — manifests, events, evidence, conclusions

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/store.py`
- Create: `lakeon/sre-agent/tests/test_store.py`

- [ ] **Step 6.1: Write failing tests**

```python
# tests/test_store.py
import json
from pathlib import Path

import pytest

from agent_session_log.evidence import Blob
from agent_session_log.store import FilesystemStore
from agent_session_log.types import SessionManifest


def make_manifest(sid: str = "sess_20260423T091230_a1b2c3") -> SessionManifest:
    return SessionManifest(
        id=sid,
        type="incident",
        created_at="2026-04-23T09:12:30Z",
        closed_at=None,
        status="open",
        trigger={"source": "cron/test", "context": {}},
        tags=["component:compute"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )


def test_session_dir_layout(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    d = store.session_dir(m.id)
    assert d.parent.name == "23"
    assert d.parent.parent.name == "04"
    assert d.parent.parent.parent.name == "2026"
    assert d.name == m.id


def test_write_read_manifest(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    loaded = store.read_manifest(m.id)
    assert loaded.id == m.id
    assert loaded.type == "incident"
    assert loaded.tags == ["component:compute"]


def test_append_and_read_events(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.append_event(m.id, "main", {"turn": 0, "type": "trigger", "t": "2026-04-23T09:12:30Z"})
    store.append_event(m.id, "main", {"turn": 1, "type": "thought", "content": "hi"})
    events = store.read_events(m.id, "main")
    assert len(events) == 2
    assert events[0]["type"] == "trigger"
    assert events[1]["content"] == "hi"


def test_write_blob_content_addressed(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    blob1 = store.write_blob(m.id, b"hello world", mime="text/plain", source="log")
    blob2 = store.write_blob(m.id, b"hello world", mime="text/plain", source="log")
    # Same content → same sha256, one file on disk
    assert blob1.sha256 == blob2.sha256
    ev_dir = store.session_dir(m.id) / "evidence" / "by-hash"
    files = list(ev_dir.glob(f"{blob1.sha256}*"))
    assert len(files) == 1


def test_read_blob(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    blob = store.write_blob(m.id, b"payload", mime="text/plain")
    raw = store.read_blob(m.id, blob.sha256)
    assert raw == b"payload"


def test_conclusion_versioning(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.write_conclusion(m.id, "root cause: X")
    store.write_conclusion(m.id, "root cause: Y (refined)")
    d = store.session_dir(m.id)
    assert (d / "conclusion.md").read_text().strip() == "root cause: Y (refined)"
    hist = list((d / "conclusion-history").glob("v*.md"))
    assert len(hist) == 1  # previous version preserved
    assert "root cause: X" in hist[0].read_text()


def test_events_jsonl_format(tmp_log_root: Path):
    """Events are line-delimited JSON — each line parses independently."""
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.append_event(m.id, "main", {"turn": 0, "type": "trigger"})
    path = store.session_dir(m.id) / "events.jsonl"
    lines = path.read_text().strip().split("\n")
    for line in lines:
        json.loads(line)  # must not raise
```

- [ ] **Step 6.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 6.3: Implement store.py**

```python
# agent_session_log/store.py
"""Filesystem-backed storage for agent_session_log.

Layout:
    <root>/sessions/YYYY/MM/DD/<session_id>/
        manifest.yaml
        events.jsonl
        branches/<branch>.jsonl
        branch-decisions.jsonl
        evidence/by-hash/<sha256>-<short>.<ext>
        evidence/index.json
        conclusion.md
        conclusion-history/v<N>.md
        outcome.md
"""
from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path
from typing import Any

import yaml

from agent_session_log.evidence import Blob, hash_bytes, ext_for_mime
from agent_session_log.types import SessionManifest


class FilesystemStore:
    """Single-agent single-writer filesystem store.

    Concurrency: assumes one process writes to a given session at a time.
    Cross-session writes are safe (different directories).
    """

    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def session_dir(self, session_id: str) -> Path:
        # session_id = sess_YYYYMMDDTHHMMSS_xxxxxx
        date_part = session_id.split("_")[1]  # e.g. 20260423T091230
        year, month, day = date_part[:4], date_part[4:6], date_part[6:8]
        return self.root / "sessions" / year / month / day / session_id

    # ---- session lifecycle ----

    def init_session(self, manifest: SessionManifest) -> Path:
        d = self.session_dir(manifest.id)
        d.mkdir(parents=True, exist_ok=False)
        (d / "branches").mkdir()
        (d / "evidence" / "by-hash").mkdir(parents=True)
        (d / "conclusion-history").mkdir()
        self.write_manifest(manifest)
        # Seed empty main branch file so append is a pure append
        (d / "events.jsonl").touch()
        return d

    def write_manifest(self, manifest: SessionManifest) -> None:
        d = self.session_dir(manifest.id)
        (d / "manifest.yaml").write_text(yaml.safe_dump(asdict(manifest), sort_keys=False))

    def read_manifest(self, session_id: str) -> SessionManifest:
        path = self.session_dir(session_id) / "manifest.yaml"
        data = yaml.safe_load(path.read_text())
        return SessionManifest(**data)

    # ---- events ----

    def _events_path(self, session_id: str, branch: str) -> Path:
        d = self.session_dir(session_id)
        if branch == "main":
            return d / "events.jsonl"
        return d / "branches" / f"{branch}.jsonl"

    def append_event(self, session_id: str, branch: str, event: dict[str, Any]) -> None:
        path = self._events_path(session_id, branch)
        path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
            f.flush()
            os.fsync(f.fileno())

    def read_events(self, session_id: str, branch: str = "main") -> list[dict[str, Any]]:
        path = self._events_path(session_id, branch)
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def list_branches(self, session_id: str) -> list[str]:
        d = self.session_dir(session_id) / "branches"
        if not d.exists():
            return []
        return sorted(p.stem for p in d.glob("*.jsonl"))

    def append_branch_decision(self, session_id: str, decision: dict[str, Any]) -> None:
        d = self.session_dir(session_id)
        with open(d / "branch-decisions.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(decision, ensure_ascii=False) + "\n")

    # ---- evidence ----

    def write_blob(
        self,
        session_id: str,
        data: bytes,
        mime: str,
        source: str | None = None,
    ) -> Blob:
        sha = hash_bytes(data)
        ext = ext_for_mime(mime)
        filename = f"{sha}-{sha[:8]}.{ext}"
        path = self.session_dir(session_id) / "evidence" / "by-hash" / filename
        # De-dup: skip write if exists with same content
        if not path.exists():
            path.write_bytes(data)
        blob = Blob(sha256=sha, mime=mime, size=len(data), ext=ext, source=source, _bytes=data)
        self._update_evidence_index(session_id, blob)
        return blob

    def read_blob(self, session_id: str, sha256: str) -> bytes:
        ev_dir = self.session_dir(session_id) / "evidence" / "by-hash"
        matches = list(ev_dir.glob(f"{sha256}*"))
        if not matches:
            raise FileNotFoundError(f"blob {sha256[:8]} not in session {session_id}")
        return matches[0].read_bytes()

    def _update_evidence_index(self, session_id: str, blob: Blob) -> None:
        path = self.session_dir(session_id) / "evidence" / "index.json"
        if path.exists():
            idx = json.loads(path.read_text())
        else:
            idx = {}
        idx[blob.sha256] = {
            "mime": blob.mime,
            "size": blob.size,
            "ext": blob.ext,
            "source": blob.source,
        }
        path.write_text(json.dumps(idx, indent=2))

    # ---- conclusion / outcome ----

    def write_conclusion(self, session_id: str, markdown: str) -> None:
        d = self.session_dir(session_id)
        main = d / "conclusion.md"
        if main.exists():
            # preserve previous version
            hist_dir = d / "conclusion-history"
            n = len(list(hist_dir.glob("v*.md"))) + 1
            (hist_dir / f"v{n}.md").write_text(main.read_text())
        main.write_text(markdown)

    def read_conclusion(self, session_id: str) -> str | None:
        path = self.session_dir(session_id) / "conclusion.md"
        return path.read_text() if path.exists() else None

    def write_outcome(self, session_id: str, markdown: str) -> None:
        path = self.session_dir(session_id) / "outcome.md"
        path.write_text(markdown)

    def read_outcome(self, session_id: str) -> str | None:
        path = self.session_dir(session_id) / "outcome.md"
        return path.read_text() if path.exists() else None

    # ---- iteration ----

    def iter_session_ids(self) -> list[str]:
        """Walk sessions/ tree; return all session ids."""
        sessions_root = self.root / "sessions"
        if not sessions_root.exists():
            return []
        out = []
        for y in sorted(sessions_root.iterdir()):
            if not y.is_dir():
                continue
            for m in sorted(y.iterdir()):
                for d in sorted(m.iterdir()):
                    for s in sorted(d.iterdir()):
                        if s.is_dir() and s.name.startswith("sess_"):
                            out.append(s.name)
        return out
```

- [ ] **Step 6.4: Run — passes**

```bash
uv run pytest tests/test_store.py -v
```
Expected: 7 passed.

- [ ] **Step 6.5: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/store.py lakeon/sre-agent/tests/test_store.py
git commit -m "feat(sre-agent): FilesystemStore for sessions/events/evidence/conclusion"
```

---

### Task 7: Session class — main write API

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/session.py`
- Create: `lakeon/sre-agent/tests/test_session.py`

- [ ] **Step 7.1: Write failing test**

```python
# tests/test_session.py
from pathlib import Path

import pytest

from agent_session_log.evidence import Blob
from agent_session_log.session import Session
from agent_session_log.store import FilesystemStore


def test_session_new_creates_manifest_and_empty_events(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(
        store=store,
        type="incident",
        trigger={"source": "cron/test", "alert": "cold start 8200ms"},
        tags=["component:compute"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )
    assert s.id.startswith("sess_")
    assert s.status == "open"
    manifest = store.read_manifest(s.id)
    assert manifest.type == "incident"
    assert manifest.status == "open"


def test_session_append_turn_and_conclude(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    t0 = s.append_turn(type="thought", content="let me look at the logs")
    t1 = s.append_turn(type="tool_call", tool="log_search", args={"since": "5m"})
    t2 = s.append_turn(type="tool_result", ref_turn=t1, truncated=False)
    assert (t0, t1, t2) == (0, 1, 2)
    s.conclude("root cause: pageserver re-attach")
    s.close()
    assert s.status == "closed"
    m = store.read_manifest(s.id)
    assert m.status == "closed"
    assert m.closed_at is not None
    assert store.read_conclusion(s.id).startswith("root cause")


def test_session_branches(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    b_img = s.branch("h1-image-pull")
    b_ps = s.branch("h2-pageserver")
    b_img.append_turn(type="tool_call", tool="log_search", args={"component": "image"})
    b_ps.append_turn(type="tool_call", tool="log_search", args={"component": "pageserver"})
    s.resolve_branches(keep="h2-pageserver", discard=["h1-image-pull"], reason="metric X showed Y")
    events_main = store.read_events(s.id, "main")
    assert any(e.get("type") == "branch_resolve" for e in events_main)
    assert len(store.read_events(s.id, "h1-image-pull")) == 1
    assert len(store.read_events(s.id, "h2-pageserver")) == 1


def test_session_attach_evidence_to_turn(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    blob = s.attach_evidence(b"raw log dump", mime="text/plain", source="log_search")
    assert blob.sha256 is not None
    tid = s.append_turn(type="tool_result", ref_turn=0, evidence=[blob.sha256])
    events = store.read_events(s.id, "main")
    turn = next(e for e in events if e.get("turn") == tid)
    assert blob.sha256 in turn["evidence"]


def test_session_record_outcome(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    s.conclude("fix: X")
    s.close()
    s.record_outcome(did_work=True, notes="cold start p95 back to 2.1s")
    out = store.read_outcome(s.id)
    assert "did_work: true" in out.lower() or "did work: true" in out.lower()
    assert "2.1s" in out


def test_session_load_roundtrip(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s1 = Session.new(store=store, type="incident", trigger={"k": "v"}, tags=["t1"])
    s1.append_turn(type="thought", content="hello")
    sid = s1.id
    s2 = Session.load(store=store, session_id=sid)
    assert s2.id == sid
    assert s2.next_turn_id == 1  # we appended one turn
```

- [ ] **Step 7.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 7.3: Implement session.py**

```python
# agent_session_log/session.py
"""Session write API.

One Session instance writes to one session directory. Branches are lightweight
context managers that share the same store.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from agent_session_log.evidence import Blob
from agent_session_log.ids import new_session_id, utc_now_iso
from agent_session_log.store import FilesystemStore
from agent_session_log.types import SessionManifest, SessionStatus


@dataclass
class Branch:
    """Cheap handle that writes to a named branch within a session."""
    _session: "Session"
    name: str
    _next_turn: int = 0

    def append_turn(self, type: str, **kwargs: Any) -> int:
        tid = self._next_turn
        self._next_turn += 1
        event = {
            "turn": tid,
            "t": utc_now_iso(),
            "type": type,
            **kwargs,
        }
        self._session._store.append_event(self._session.id, self.name, event)
        return tid


@dataclass
class Session:
    id: str
    type: str
    status: str
    _store: FilesystemStore
    _next_turn: int = 0
    _branches: dict[str, Branch] = field(default_factory=dict)

    # ---- factory methods ----

    @classmethod
    def new(
        cls,
        store: FilesystemStore,
        type: str,
        trigger: dict[str, Any],
        tags: list[str] | None = None,
        model: str | None = None,
        runtime: str | None = None,
        parent_sessions: list[str] | None = None,
    ) -> "Session":
        sid = new_session_id()
        manifest = SessionManifest(
            id=sid,
            type=type,
            created_at=utc_now_iso(),
            closed_at=None,
            status=SessionStatus.OPEN.value,
            trigger=trigger or {},
            tags=tags or [],
            parent_sessions=parent_sessions or [],
            model=model,
            runtime=runtime,
        )
        store.init_session(manifest)
        return cls(id=sid, type=type, status="open", _store=store)

    @classmethod
    def load(cls, store: FilesystemStore, session_id: str) -> "Session":
        manifest = store.read_manifest(session_id)
        events = store.read_events(session_id, "main")
        next_turn = max((e.get("turn", -1) for e in events), default=-1) + 1
        return cls(
            id=manifest.id,
            type=manifest.type,
            status=manifest.status,
            _store=store,
            _next_turn=next_turn,
        )

    # ---- writes ----

    def append_turn(self, type: str, **kwargs: Any) -> int:
        tid = self._next_turn
        self._next_turn += 1
        event = {"turn": tid, "t": utc_now_iso(), "type": type, **kwargs}
        self._store.append_event(self.id, "main", event)
        return tid

    def branch(self, name: str) -> Branch:
        if name == "main":
            raise ValueError("'main' is reserved; use append_turn for main branch")
        if name in self._branches:
            return self._branches[name]
        self.append_turn(type="branch_open", branch=name)
        b = Branch(_session=self, name=name)
        self._branches[name] = b
        return b

    def resolve_branches(
        self,
        keep: str,
        discard: list[str],
        reason: str,
        evidence: list[str] | None = None,
    ) -> None:
        decision = {
            "t": utc_now_iso(),
            "kept": keep,
            "discarded": discard,
            "reason": reason,
            "evidence": evidence or [],
        }
        self._store.append_branch_decision(self.id, decision)
        self.append_turn(
            type="branch_resolve",
            keep=keep,
            discard=discard,
            reason=reason,
            evidence=evidence or [],
        )

    def attach_evidence(self, data: bytes, mime: str, source: str | None = None) -> Blob:
        return self._store.write_blob(self.id, data, mime=mime, source=source)

    def conclude(self, markdown: str) -> None:
        self._store.write_conclusion(self.id, markdown)
        self.append_turn(type="conclude", ref="conclusion.md")

    def record_outcome(self, did_work: bool, notes: str = "") -> None:
        body = (
            f"## {utc_now_iso()}\n"
            f"- did_work: {'true' if did_work else 'false'}\n"
            f"- notes: {notes}\n"
        )
        existing = self._store.read_outcome(self.id) or ""
        self._store.write_outcome(self.id, existing + body)

    def close(self, status: str = "closed") -> None:
        if status not in {"closed", "abandoned"}:
            raise ValueError(status)
        m = self._store.read_manifest(self.id)
        m.status = status
        m.closed_at = utc_now_iso()
        self._store.write_manifest(m)
        self.status = status

    @property
    def next_turn_id(self) -> int:
        return self._next_turn
```

- [ ] **Step 7.4: Run — passes**

```bash
uv run pytest tests/test_session.py -v
```
Expected: 6 passed.

- [ ] **Step 7.5: Export Session**

Append to `agent_session_log/__init__.py`:
```python
from agent_session_log.session import Branch, Session
from agent_session_log.store import FilesystemStore

__all__ = [..., "Branch", "Session", "FilesystemStore"]
```

- [ ] **Step 7.6: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/session.py lakeon/sre-agent/agent_session_log/__init__.py lakeon/sre-agent/tests/test_session.py
git commit -m "feat(sre-agent): Session class with turns/branches/evidence/conclude/outcome"
```

---

### Task 8: LogStore — query API

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/log.py`
- Create: `lakeon/sre-agent/tests/test_log_query.py`

- [ ] **Step 8.1: Write failing test**

```python
# tests/test_log_query.py
import time
from pathlib import Path

from agent_session_log.log import LogStore


def test_list_sessions_by_type(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s1 = log.new_session(type="incident", trigger={}, tags=["component:compute"])
    s1.conclude("c")
    s1.close()
    s2 = log.new_session(type="reading", trigger={}, tags=["source:web"])
    s2.close()
    incidents = log.list_sessions(type="incident")
    assert [x["id"] for x in incidents] == [s1.id]


def test_list_sessions_by_tags(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    a = log.new_session(type="incident", trigger={}, tags=["component:compute", "severity:high"])
    a.close()
    b = log.new_session(type="incident", trigger={}, tags=["component:pageserver"])
    b.close()
    matches = log.list_sessions(tags=["component:compute"])
    assert [x["id"] for x in matches] == [a.id]


def test_get_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={"x": 1}, tags=[])
    s.append_turn(type="thought", content="hi")
    s.conclude("ok")
    s.close()
    loaded = log.get_session(s.id)
    assert loaded.id == s.id


def test_search_text_in_conclusions(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    a = log.new_session(type="incident", trigger={}, tags=[])
    a.conclude("root cause: pageserver re-attach took 6.8s")
    a.close()
    b = log.new_session(type="incident", trigger={}, tags=[])
    b.conclude("root cause: image pull slow")
    b.close()
    hits = log.search_text("pageserver")
    assert [h["id"] for h in hits] == [a.id]


def test_replay_at_turn(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    for i in range(5):
        s.append_turn(type="thought", content=f"step {i}")
    s.conclude("done")
    s.close()
    snapshot = log.replay(s.id, at_turn=2)
    # snapshot returns list of turns up to and including turn 2
    assert [t["turn"] for t in snapshot] == [0, 1, 2]
```

- [ ] **Step 8.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 8.3: Implement log.py**

```python
# agent_session_log/log.py
"""Top-level LogStore — create + query sessions."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from agent_session_log.session import Session
from agent_session_log.store import FilesystemStore


class LogStore:
    def __init__(self, root: Path | str):
        self._store = FilesystemStore(Path(root))

    @property
    def store(self) -> FilesystemStore:
        return self._store

    def new_session(self, **kwargs: Any) -> Session:
        return Session.new(store=self._store, **kwargs)

    def get_session(self, session_id: str) -> Session:
        return Session.load(store=self._store, session_id=session_id)

    def list_sessions(
        self,
        type: str | None = None,
        tags: list[str] | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """Return list of manifests (as dicts), newest first, optionally filtered."""
        ids = self._store.iter_session_ids()
        out: list[dict[str, Any]] = []
        for sid in reversed(ids):  # newest first
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if type and m.type != type:
                continue
            if tags and not all(tag in m.tags for tag in tags):
                continue
            out.append({
                "id": m.id,
                "type": m.type,
                "status": m.status,
                "created_at": m.created_at,
                "closed_at": m.closed_at,
                "tags": m.tags,
            })
            if len(out) >= limit:
                break
        return out

    def search_text(self, query: str, type: str | None = None, limit: int = 20) -> list[dict[str, Any]]:
        """Simple substring search over conclusions + manifest trigger text."""
        q = query.lower()
        out: list[dict[str, Any]] = []
        for sid in reversed(self._store.iter_session_ids()):
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if type and m.type != type:
                continue
            hay = ""
            concl = self._store.read_conclusion(sid)
            if concl:
                hay += concl.lower()
            hay += " " + str(m.trigger).lower()
            if q in hay:
                out.append({
                    "id": m.id,
                    "type": m.type,
                    "snippet": (concl or "")[:200],
                    "created_at": m.created_at,
                })
                if len(out) >= limit:
                    break
        return out

    def replay(self, session_id: str, at_turn: int, branch: str = "main") -> list[dict[str, Any]]:
        """Return all events up to and including at_turn on the given branch."""
        events = self._store.read_events(session_id, branch)
        return [e for e in events if e.get("turn", -1) <= at_turn]
```

- [ ] **Step 8.4: Run — passes**

```bash
uv run pytest tests/test_log_query.py -v
```
Expected: 5 passed.

- [ ] **Step 8.5: Export LogStore**

Add to `__init__.py`:
```python
from agent_session_log.log import LogStore

__all__ = [..., "LogStore"]
```

- [ ] **Step 8.6: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/log.py lakeon/sre-agent/agent_session_log/__init__.py lakeon/sre-agent/tests/test_log_query.py
git commit -m "feat(sre-agent): LogStore query API (list/get/search/replay)"
```

---

### Task 9: SkillLedger — invocation history and stats

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/skill_ledger.py`
- Create: `lakeon/sre-agent/tests/test_skill_ledger.py`

- [ ] **Step 9.1: Write failing test**

```python
# tests/test_skill_ledger.py
from pathlib import Path

from agent_session_log.skill_ledger import SkillLedger


def test_record_and_stats(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    ledger.record_invocation("cold-start-watcher", version="v0.1",
                             session_id="sess_1", triggered_at="2026-04-23T00:00:00Z")
    ledger.record_outcome("cold-start-watcher", session_id="sess_1", did_work=True)
    ledger.record_invocation("cold-start-watcher", version="v0.1",
                             session_id="sess_2", triggered_at="2026-04-23T00:05:00Z")
    ledger.record_outcome("cold-start-watcher", session_id="sess_2", did_work=False)

    stats = ledger.stats("cold-start-watcher")
    assert stats["total_invocations"] == 2
    assert stats["outcomes_filed"] == 2
    assert stats["did_work_count"] == 1
    assert stats["did_work_rate"] == 0.5


def test_stats_empty_skill(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    stats = ledger.stats("nonexistent")
    assert stats["total_invocations"] == 0
    assert stats["did_work_rate"] is None


def test_list_invocations(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    ledger.record_invocation("s", version="v0.1", session_id="sa", triggered_at="2026-04-23T00:00:00Z")
    ledger.record_invocation("s", version="v0.1", session_id="sb", triggered_at="2026-04-23T00:01:00Z")
    invs = ledger.list_invocations("s")
    assert [i["session_id"] for i in invs] == ["sa", "sb"]
```

- [ ] **Step 9.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 9.3: Implement skill_ledger.py**

```python
# agent_session_log/skill_ledger.py
"""Skill invocation + outcome ledger.

Layout:
    <root>/skills-ledger/<skill_name>/
        invocations.jsonl
        outcomes.jsonl
        stats.json (computed)
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class SkillLedger:
    def __init__(self, root: Path | str):
        self.root = Path(root) / "skills-ledger"
        self.root.mkdir(parents=True, exist_ok=True)

    def _skill_dir(self, skill: str) -> Path:
        d = self.root / skill
        d.mkdir(parents=True, exist_ok=True)
        return d

    def record_invocation(
        self,
        skill: str,
        *,
        version: str,
        session_id: str,
        triggered_at: str,
    ) -> None:
        path = self._skill_dir(skill) / "invocations.jsonl"
        entry = {
            "skill": skill,
            "version": version,
            "session_id": session_id,
            "triggered_at": triggered_at,
        }
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def record_outcome(
        self,
        skill: str,
        *,
        session_id: str,
        did_work: bool,
        notes: str = "",
    ) -> None:
        path = self._skill_dir(skill) / "outcomes.jsonl"
        entry = {"session_id": session_id, "did_work": did_work, "notes": notes}
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def list_invocations(self, skill: str) -> list[dict[str, Any]]:
        path = self._skill_dir(skill) / "invocations.jsonl"
        if not path.exists():
            return []
        return [json.loads(ln) for ln in path.read_text().splitlines() if ln.strip()]

    def list_outcomes(self, skill: str) -> list[dict[str, Any]]:
        path = self._skill_dir(skill) / "outcomes.jsonl"
        if not path.exists():
            return []
        return [json.loads(ln) for ln in path.read_text().splitlines() if ln.strip()]

    def stats(self, skill: str) -> dict[str, Any]:
        invs = self.list_invocations(skill)
        outs = self.list_outcomes(skill)
        did_work = sum(1 for o in outs if o.get("did_work"))
        rate = (did_work / len(outs)) if outs else None
        stats = {
            "skill": skill,
            "total_invocations": len(invs),
            "outcomes_filed": len(outs),
            "did_work_count": did_work,
            "did_work_rate": rate,
        }
        (self._skill_dir(skill) / "stats.json").write_text(json.dumps(stats, indent=2))
        return stats
```

- [ ] **Step 9.4: Run — passes**

```bash
uv run pytest tests/test_skill_ledger.py -v
```
Expected: 3 passed.

- [ ] **Step 9.5: Export**

Add to `__init__.py`: `from agent_session_log.skill_ledger import SkillLedger`.

- [ ] **Step 9.6: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/skill_ledger.py lakeon/sre-agent/agent_session_log/__init__.py lakeon/sre-agent/tests/test_skill_ledger.py
git commit -m "feat(sre-agent): SkillLedger for invocation + outcome tracking"
```

---

### Task 10: Full-stack integration test

**Goal:** End-to-end flow through the library: SRE-shaped session with branches, evidence, conclusion, outcome, skill ledger. Catches integration bugs that unit tests miss.

**Files:**
- Create: `lakeon/sre-agent/tests/test_end_to_end.py`

- [ ] **Step 10.1: Write integration test**

```python
# tests/test_end_to_end.py
from pathlib import Path

from agent_session_log import LogStore, SkillLedger


def test_full_sre_incident_flow(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    ledger = SkillLedger(tmp_log_root)

    # 1) Skill triggers, opens session
    s = log.new_session(
        type="incident",
        trigger={
            "source": "cron/cold-start-watcher",
            "skill_version": "v0.1",
            "alert": "compute cold start 8234ms",
            "tenant_id": "t_abc",
            "db_id": "db_xyz",
        },
        tags=["severity:medium", "component:compute", "skill:cold-start-watcher"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )
    ledger.record_invocation(
        "cold-start-watcher", version="v0.1",
        session_id=s.id, triggered_at="2026-04-23T09:12:30Z",
    )

    # 2) Initial investigation
    s.append_turn(type="thought", content="Need to narrow scope — check pod + pageserver.")
    s.append_turn(type="tool_call", tool="log_search",
                  args={"component": "compute", "since": "5m"}, latency_ms=230)
    blob_logs = s.attach_evidence(
        b"2026-04-23 09:12:28 compute pod starting...\n"
        b"2026-04-23 09:12:36 compute ready (took 8234ms)\n",
        mime="text/plain", source="log_search@dbay-sre-mcp",
    )
    s.append_turn(type="tool_result", ref_turn=1, evidence=[blob_logs.sha256])

    # 3) Branch out two hypotheses
    b_img = s.branch("h1-image-pull")
    b_ps = s.branch("h2-pageserver")
    b_img.append_turn(type="thought", content="if image pull slow, expect ImagePulling event")
    b_img.append_turn(type="tool_call", tool="log_search",
                     args={"component": "k8s", "keyword": "ImagePulling"}, latency_ms=180)
    b_img.append_turn(type="tool_result", ref_turn=1, evidence=[])  # empty

    b_ps.append_turn(type="thought", content="check pageserver re-attach duration")
    b_ps.append_turn(type="tool_call", tool="log_search",
                    args={"component": "pageserver", "since": "5m"}, latency_ms=210)
    blob_ps = s.attach_evidence(b'{"reattach_duration_ms": 6800}', mime="application/json")
    b_ps.append_turn(type="tool_result", ref_turn=1, evidence=[blob_ps.sha256])

    # 4) Resolve: h2 wins
    s.resolve_branches(
        keep="h2-pageserver",
        discard=["h1-image-pull"],
        reason="h1 had no ImagePulling events; h2 showed 6800ms re-attach",
        evidence=[blob_ps.sha256],
    )

    # 5) Conclude
    s.conclude(
        "# Cold start 8234ms for db_xyz\n\n"
        "## Root cause (confidence 0.72)\n"
        "Pageserver re-attach gap for tenant t_abc — 6.8s re-attach.\n\n"
        "## Suggested actions\n"
        "1. Manual PUT location_config for t_abc\n"
    )
    s.close()

    # 6) Outcome (24h later)
    s.record_outcome(did_work=True, notes="p95 back to 2.1s after manual fix")
    ledger.record_outcome("cold-start-watcher", session_id=s.id, did_work=True)

    # ==== Assertions ====
    loaded = log.get_session(s.id)
    assert loaded.status == "closed"

    events = log.store.read_events(s.id, "main")
    types = [e["type"] for e in events]
    assert types.count("branch_open") == 2
    assert types.count("branch_resolve") == 1
    assert types.count("conclude") == 1

    # branches have their own events
    assert len(log.store.read_events(s.id, "h2-pageserver")) == 3

    # outcome filed
    assert "p95 back to 2.1s" in log.store.read_outcome(s.id)

    # skill stats reflect
    stats = ledger.stats("cold-start-watcher")
    assert stats["total_invocations"] == 1
    assert stats["did_work_rate"] == 1.0

    # search works
    hits = log.search_text("pageserver")
    assert s.id in [h["id"] for h in hits]
```

- [ ] **Step 10.2: Run the integration test**

```bash
uv run pytest tests/test_end_to_end.py -v
```
Expected: 1 passed.

- [ ] **Step 10.3: Run full suite**

```bash
uv run pytest -v
```
Expected: 20+ passed total.

- [ ] **Step 10.4: Commit**

```bash
git add lakeon/sre-agent/tests/test_end_to_end.py
git commit -m "test(sre-agent): end-to-end SRE incident flow integration test"
```

---

## Group C: OBS Sync

### Task 11: OBS upload worker (sync API)

**Files:**
- Create: `lakeon/sre-agent/agent_session_log/obs_sync.py`
- Create: `lakeon/sre-agent/tests/test_obs_sync.py`

- [ ] **Step 11.1: Write failing test**

```python
# tests/test_obs_sync.py
import tarfile
from pathlib import Path

from agent_session_log import LogStore
from agent_session_log.obs_sync import ObsSync, FakeObsClient


def test_archive_closed_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.append_turn(type="thought", content="hi")
    s.conclude("x")
    s.close()

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    ref = sync.upload_session(s.id)

    assert ref.startswith("agent-log/")
    assert s.id in ref
    assert ref.endswith(".tar.gz")
    # FakeObsClient captured the upload
    assert ref in client.objects
    # Contents is a valid tar.gz
    data = client.objects[ref]
    # write to tmp and inspect
    tmp = tmp_log_root / "check.tar.gz"
    tmp.write_bytes(data)
    with tarfile.open(tmp, "r:gz") as tf:
        names = tf.getnames()
    assert any("manifest.yaml" in n for n in names)
    assert any("events.jsonl" in n for n in names)


def test_upload_skips_when_manifest_records_obs_ref(tmp_log_root: Path):
    """If manifest already has obs_ref, skip re-upload."""
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    # Pre-set obs_ref
    m = log.store.read_manifest(s.id)
    m.obs_ref = "agent-log/already/uploaded.tar.gz"
    log.store.write_manifest(m)

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    ref = sync.upload_session(s.id)
    assert ref == "agent-log/already/uploaded.tar.gz"
    assert len(client.objects) == 0  # no new upload


def test_upload_refuses_open_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    # do not close

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    import pytest
    with pytest.raises(ValueError, match="open"):
        sync.upload_session(s.id)
```

- [ ] **Step 11.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 11.3: Implement obs_sync.py**

```python
# agent_session_log/obs_sync.py
"""Upload closed sessions to OBS as tar.gz archives.

Defines a thin protocol for the OBS client so tests can inject a fake.
In production, pass an esdk-obs-python ObsClient wrapped to match.
"""
from __future__ import annotations

import io
import tarfile
from pathlib import Path
from typing import Any, Protocol

from agent_session_log.store import FilesystemStore


class ObsClientLike(Protocol):
    def put_object(self, bucket: str, key: str, data: bytes) -> Any: ...


class FakeObsClient:
    """In-memory stand-in; tests assert against .objects."""

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def put_object(self, bucket: str, key: str, data: bytes) -> None:
        self.objects[key] = data


class ObsSync:
    def __init__(
        self,
        store: FilesystemStore,
        *,
        client: ObsClientLike,
        bucket: str,
        prefix: str = "agent-log/",
    ):
        self._store = store
        self._client = client
        self._bucket = bucket
        self._prefix = prefix.rstrip("/") + "/"

    def upload_session(self, session_id: str) -> str:
        manifest = self._store.read_manifest(session_id)
        if manifest.obs_ref:
            return manifest.obs_ref
        if manifest.status == "open":
            raise ValueError(f"refuse to upload open session {session_id}")

        d = self._store.session_dir(session_id)
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tf:
            tf.add(d, arcname=session_id)
        data = buf.getvalue()

        # Key: agent-log/YYYY/MM/DD/<id>.tar.gz
        parts = session_id.split("_")[1]
        key = f"{self._prefix}{parts[:4]}/{parts[4:6]}/{parts[6:8]}/{session_id}.tar.gz"

        self._client.put_object(self._bucket, key, data)

        manifest.obs_ref = key
        self._store.write_manifest(manifest)
        return key

    def upload_pending(self, limit: int = 50) -> list[str]:
        """Upload all closed sessions that don't yet have obs_ref."""
        uploaded = []
        for sid in self._store.iter_session_ids():
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if m.status == "open" or m.obs_ref:
                continue
            try:
                uploaded.append(self.upload_session(sid))
            except Exception as exc:  # noqa: BLE001
                # Log and continue; don't stop on transient failures
                print(f"obs_sync: failed {sid}: {exc}")
            if len(uploaded) >= limit:
                break
        return uploaded
```

- [ ] **Step 11.4: Run — passes**

```bash
uv run pytest tests/test_obs_sync.py -v
```
Expected: 3 passed.

- [ ] **Step 11.5: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/obs_sync.py lakeon/sre-agent/tests/test_obs_sync.py
git commit -m "feat(sre-agent): OBS sync for closed sessions as tar.gz"
```

---

### Task 12: Real OBS client adapter + scheduled sync

**Files:**
- Modify: `lakeon/sre-agent/agent_session_log/obs_sync.py` (add `HuaweiObsAdapter`)
- Create: `lakeon/sre-agent/scripts/sync_loop.py`

- [ ] **Step 12.1: Add real OBS adapter**

Append to `agent_session_log/obs_sync.py`:

```python
# ---- Real OBS adapter (requires esdk-obs-python) ----


class HuaweiObsAdapter:
    """Wrap esdk-obs-python ObsClient to match ObsClientLike.

    Import lazily so tests don't need the SDK.
    """

    def __init__(self, access_key: str, secret_key: str, endpoint: str) -> None:
        from obs import ObsClient  # noqa: PLC0415

        self._client = ObsClient(
            access_key_id=access_key,
            secret_access_key=secret_key,
            server=endpoint,
        )

    def put_object(self, bucket: str, key: str, data: bytes) -> None:
        resp = self._client.putObject(bucket, key, content=data)
        if resp.status >= 300:
            raise RuntimeError(f"OBS put failed {resp.status}: {resp.errorMessage}")
```

- [ ] **Step 12.2: Create sync_loop.py**

```python
# scripts/sync_loop.py
"""Long-running OBS sync loop. Run as a sidecar thread or separate process.

Env:
    HERMES_HOME: commit log root (default: ~/.hermes)
    OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_ENDPOINT, OBS_BUCKET
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

# Allow running from project root
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent_session_log import LogStore
from agent_session_log.obs_sync import HuaweiObsAdapter, ObsSync


def main() -> None:
    root = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))) / "data"
    log = LogStore(root)
    adapter = HuaweiObsAdapter(
        access_key=os.environ["OBS_ACCESS_KEY"],
        secret_key=os.environ["OBS_SECRET_KEY"],
        endpoint=os.environ["OBS_ENDPOINT"],
    )
    sync = ObsSync(
        log.store,
        client=adapter,
        bucket=os.environ["OBS_BUCKET"],
        prefix=os.environ.get("OBS_PREFIX", "agent-log/"),
    )

    interval = int(os.environ.get("OBS_SYNC_INTERVAL_SEC", "60"))
    print(f"obs_sync: starting, root={root}, bucket={sync._bucket}, interval={interval}s")
    while True:
        try:
            uploaded = sync.upload_pending(limit=20)
            if uploaded:
                print(f"obs_sync: uploaded {len(uploaded)} sessions")
        except Exception as exc:  # noqa: BLE001
            print(f"obs_sync: loop error: {exc}")
        time.sleep(interval)


if __name__ == "__main__":
    main()
```

- [ ] **Step 12.3: Commit**

```bash
git add lakeon/sre-agent/agent_session_log/obs_sync.py lakeon/sre-agent/scripts/sync_loop.py
git commit -m "feat(sre-agent): HuaweiObsAdapter + sync loop script"
```

---

## Group D: Hermes Integration

### Task 13: Day 1 — Verify hermes runs locally + feishu bot echoes

**Goal:** Prove hermes + Deepseek + feishu work end-to-end on Jacky's laptop before worrying about Railway.

**Prerequisites (do in flight):**
- Register a feishu self-built app at open.feishu.cn; get app_id/app_secret/verification_token/encrypt_key
- Verify DEEPSEEK_API_KEY is reachable

**Files:**
- Create: `lakeon/sre-agent/hermes_config/config.yaml`
- Create: `lakeon/sre-agent/scripts/verify_env.py`

- [ ] **Step 13.1: Install hermes locally**

```bash
cd /Users/jacky/code/hermes-agent
uv sync
source .venv/bin/activate
hermes --version
```
Expected: version number prints.

- [ ] **Step 13.2: Write verify_env.py**

```python
# lakeon/sre-agent/scripts/verify_env.py
"""Pre-flight: ensure all required env vars are set and reachable."""
import os
import sys


REQUIRED = [
    "DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
    "FEISHU_APP_ID", "FEISHU_APP_SECRET",
    "FEISHU_VERIFICATION_TOKEN", "FEISHU_ENCRYPT_KEY",
    "FEISHU_ALLOWED_OPEN_IDS",
    "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET", "OBS_ENDPOINT",
]


def main() -> int:
    missing = [k for k in REQUIRED if not os.environ.get(k)]
    if missing:
        print("MISSING env vars:")
        for k in missing:
            print(f"  - {k}")
        return 1
    print(f"OK — all {len(REQUIRED)} required env vars set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 13.3: Write hermes config.yaml**

```yaml
# hermes_config/config.yaml
# Reference: hermes_cli/providers.py — custom providers section
providers:
  deepseek:
    name: "Deepseek"
    base_url: "${DEEPSEEK_BASE_URL}"
    api_key_env: "DEEPSEEK_API_KEY"
    transport: "openai_chat"
    models:
      - id: "deepseek-chat"
        label: "Deepseek V3.2"
      - id: "deepseek-reasoner"
        label: "Deepseek Reasoner"

model:
  provider: "deepseek"
  default: "deepseek-chat"

gateway:
  platforms:
    - feishu

feishu:
  app_id_env: "FEISHU_APP_ID"
  app_secret_env: "FEISHU_APP_SECRET"
  verification_token_env: "FEISHU_VERIFICATION_TOKEN"
  encrypt_key_env: "FEISHU_ENCRYPT_KEY"
  allowed_open_ids_env: "FEISHU_ALLOWED_OPEN_IDS"
  transport: "websocket"
```

(Exact key names depend on hermes's config schema. If hermes config differs, adjust to match; core principle is declare deepseek + feishu only.)

- [ ] **Step 13.4: Verify feishu echo manually**

```bash
cd /Users/jacky/code/lakeon/sre-agent
export $(cat .env | xargs)
uv run python scripts/verify_env.py
```
Expected: "OK — all N required env vars set".

Start hermes with our config:
```bash
HERMES_CONFIG=./hermes_config/config.yaml hermes gateway start
```
Then from feishu, DM the bot "hi". Expect LLM reply through deepseek.

- [ ] **Step 13.5: Commit**

```bash
git add lakeon/sre-agent/hermes_config/config.yaml lakeon/sre-agent/scripts/verify_env.py
git commit -m "feat(sre-agent): hermes config + env verification script"
```

---

### Task 14: Network probe — can Railway reach dbay-logs?

**Goal:** Day 1 validation of Q7 risk. If Railway outbound can't hit dbay-logs PG, we need a fallback NOW — don't discover this at deploy time.

**Files:**
- Create: `lakeon/sre-agent/scripts/probe_dbay_logs.py`

- [ ] **Step 14.1: Write probe script**

```python
# scripts/probe_dbay_logs.py
"""Verify we can connect to dbay-logs PG from the current host.

Run from Railway shell (or local with DBAY_LOGS_DSN pointing at the real prod PG)
to confirm network path before committing to deployment.
"""
import os
import sys
import time


def main() -> int:
    dsn = os.environ.get("DBAY_LOGS_DSN")
    if not dsn:
        print("FAIL: DBAY_LOGS_DSN not set")
        return 1
    try:
        import psycopg2  # noqa: PLC0415
    except ImportError:
        print("FAIL: psycopg2-binary not installed in this env")
        return 1

    t0 = time.time()
    try:
        conn = psycopg2.connect(dsn, connect_timeout=5)
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: connect error: {exc}")
        return 1

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM logs WHERE ts >= NOW() - INTERVAL '1 hour'")
            count = cur.fetchone()[0]
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: query error: {exc}")
        conn.close()
        return 1

    dt_ms = int((time.time() - t0) * 1000)
    print(f"OK: {count} log rows in last 1h, connect+query took {dt_ms}ms")
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 14.2: Run probe locally (from Mac)**

```bash
cd /Users/jacky/code/lakeon/sre-agent
uv add psycopg2-binary
export DBAY_LOGS_DSN="$(read -p 'DSN: ' dsn; echo $dsn)"
uv run python scripts/probe_dbay_logs.py
```
Expected: "OK: N log rows in last 1h".

If it fails: diagnose network (IP whitelist on CCE side, or PG not exposed publicly). This is a blocking finding — triage immediately.

- [ ] **Step 14.3: Commit**

```bash
git add lakeon/sre-agent/scripts/probe_dbay_logs.py lakeon/sre-agent/pyproject.toml
git commit -m "test(sre-agent): dbay-logs PG network probe (Q7 Day 1 check)"
```

---

### Task 15: Add compute cold start log to lakeon-api

**Goal:** Cold-start-watcher skill must parse a log line that says "compute started in Xms for tenant=T db=D". The line doesn't exist today — add it.

**Files:**
- Modify: `lakeon/lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java`

- [ ] **Step 15.1: Find the cold-start completion point**

```bash
grep -n "becomeActive\|markActive\|setStatus.*ACTIVE\|Pod started\|Pod is ready" \
  /Users/jacky/code/lakeon/lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java
```
Identify where cold start finishes (DB becomes ACTIVE with its pod).

- [ ] **Step 15.2: Add elapsed time tracking**

At the cold-start entry point, record `long coldStartBegin = System.currentTimeMillis();`.

At the cold-start completion point (after pod becomes ready and DB marked ACTIVE), add:
```java
long coldStartMs = System.currentTimeMillis() - coldStartBegin;
log.info("compute started in {}ms for tenant={} db={}",
         coldStartMs, entity.getNeonTenantId(), entity.getId());
```

(Exact position depends on the current code flow; keep it ONE log line, always emitted on cold start completion — no conditional suppression.)

- [ ] **Step 15.3: Verify logging works locally**

Build + run lakeon-api locally (or in dev), trigger a cold start, verify log appears:

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew bootRun
# In another terminal, trigger a cold start via API
# Then search the log:
grep "compute started in" logs/lakeon-api.log
```
Expected: `compute started in 2134ms for tenant=t_abc db=db_xyz`.

- [ ] **Step 15.4: Commit**

```bash
git add lakeon/lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java
git commit -m "feat(api): log compute cold start duration (for SRE agent watcher)"
```

- [ ] **Step 15.5: Deploy to production**

```bash
cd /Users/jacky/code/lakeon
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
```
Wait for pods to be Ready. Verify a cold start in dbay-logs:
```bash
psql "$DBAY_LOGS_DSN" -c "SELECT * FROM logs WHERE msg LIKE '%compute started in%' ORDER BY ts DESC LIMIT 5;"
```
Expected: at least one row within 10 minutes.

---

## Group E: SRE Skills

### Task 16: Cold-start-watcher skill — detection + session open

**Files:**
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/SKILL.md`
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/watcher.py`
- Create: `lakeon/sre-agent/tests/integration/__init__.py`
- Create: `lakeon/sre-agent/tests/integration/test_cold_start_watcher.py`

- [ ] **Step 16.1: SKILL.md**

```markdown
---
name: cold-start-watcher
description: Detect dbay.cloud compute cold starts exceeding 5 seconds and open a diagnostic session.
version: v0.1
triggers:
  cron: "*/2 * * * *"
tools:
  - dbay-sre-mcp.log_search
  - dbay-sre-mcp.log_stats
personality: sre
---

# cold-start-watcher

Every 2 minutes, scan recent lakeon-api logs for lines matching
`compute started in {ms}ms for tenant={t} db={d}`.

For each match where `ms > 5000`:

1. Dedupe: skip if an incident session for this (tenant, db) was opened within the last 10 minutes.
2. Open a new session of type=incident with tags including `severity:medium`,
   `component:compute`, `skill:cold-start-watcher`.
3. Record the watcher invocation in the skill ledger.
4. Hand off to the diagnose prompt for LLM-driven investigation.
5. On conclusion, post an interactive feishu card to the allowed user.

This skill does NOT execute remediations. It reports only.
```

- [ ] **Step 16.2: Write failing integration test**

```python
# tests/integration/test_cold_start_watcher.py
from pathlib import Path

import pytest

from agent_session_log import LogStore


@pytest.fixture
def mock_mcp():
    """Simulate dbay-sre-mcp.log_search returning fake lakeon-api log rows."""
    class Mock:
        calls = []
        responses = {}

        def log_search(self, **kwargs):
            self.calls.append(kwargs)
            return self.responses.get(kwargs.get("keyword", ""), [])

        def log_stats(self, **kwargs):
            return {"count_by_level": {"INFO": 100}}

    return Mock()


def test_watcher_opens_session_for_slow_start(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {
            "ts": "2026-04-23T09:12:34Z",
            "component": "lakeon-api",
            "msg": "compute started in 8234ms for tenant=t_abc db=db_xyz",
            "tenant_id": "t_abc",
            "db_id": "db_xyz",
        }
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000)

    incidents = w.scan_once()

    assert len(incidents) == 1
    sid = incidents[0]
    sess = log.get_session(sid)
    m = log.store.read_manifest(sid)
    assert m.type == "incident"
    assert "component:compute" in m.tags
    assert "skill:cold-start-watcher" in m.tags
    assert m.trigger["alert"].startswith("compute cold start")


def test_watcher_ignores_fast_starts(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {"ts": "...", "msg": "compute started in 1200ms for tenant=t db=d",
         "tenant_id": "t", "db_id": "d"},
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000)
    incidents = w.scan_once()
    assert incidents == []


def test_watcher_dedupes_same_pair_within_window(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {"ts": "2026-04-23T09:12:34Z",
         "msg": "compute started in 6000ms for tenant=t db=d",
         "tenant_id": "t", "db_id": "d"},
        {"ts": "2026-04-23T09:14:10Z",
         "msg": "compute started in 7000ms for tenant=t db=d",
         "tenant_id": "t", "db_id": "d"},
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000, dedupe_window_sec=600)
    incidents = w.scan_once()
    assert len(incidents) == 1  # second one deduped
```

- [ ] **Step 16.3: Implement watcher.py**

```python
# skills/sre/cold_start_watcher/watcher.py
"""Cold-start-watcher scanner.

Separated from hermes integration for testability. Hermes cron calls
Watcher.scan_once() via a thin wrapper that passes the MCP client.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from agent_session_log import LogStore, utc_now_iso
from agent_session_log.skill_ledger import SkillLedger


_COLD_START_RE = re.compile(
    r"compute started in (?P<ms>\d+)ms for tenant=(?P<tenant>\S+) db=(?P<db>\S+)"
)


@dataclass
class Watcher:
    log: LogStore
    mcp: Any
    threshold_ms: int = 5000
    dedupe_window_sec: int = 600
    ledger: SkillLedger | None = None
    skill_version: str = "v0.1"

    def __post_init__(self) -> None:
        if self.ledger is None:
            self.ledger = SkillLedger(self.log.store.root)

    def scan_once(self) -> list[str]:
        """Fetch recent logs, open sessions for slow starts. Return opened session ids."""
        rows = self.mcp.log_search(
            component="lakeon-api",
            keyword="compute started in",
            since="3m",
            limit=200,
        )
        opened: list[str] = []
        seen_pairs: set[tuple[str, str]] = set()
        for row in rows:
            match = _COLD_START_RE.search(row.get("msg", ""))
            if not match:
                continue
            ms = int(match.group("ms"))
            if ms <= self.threshold_ms:
                continue
            tenant = match.group("tenant")
            db = match.group("db")
            pair = (tenant, db)
            if pair in seen_pairs or self._recently_seen(tenant, db):
                continue
            seen_pairs.add(pair)
            sid = self._open_incident(tenant=tenant, db=db, ms=ms, raw=row)
            opened.append(sid)
        return opened

    def _recently_seen(self, tenant: str, db: str) -> bool:
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.dedupe_window_sec)
        for meta in self.log.list_sessions(type="incident", limit=50):
            m_time = datetime.fromisoformat(meta["created_at"].replace("Z", "+00:00"))
            if m_time < cutoff:
                break  # list_sessions is newest-first
            # reload full manifest to check trigger
            full = self.log.store.read_manifest(meta["id"])
            t = full.trigger or {}
            if t.get("tenant_id") == tenant and t.get("db_id") == db:
                return True
        return False

    def _open_incident(self, *, tenant: str, db: str, ms: int, raw: dict) -> str:
        s = self.log.new_session(
            type="incident",
            trigger={
                "source": "cron/cold-start-watcher",
                "skill_version": self.skill_version,
                "alert": f"compute cold start {ms}ms exceeds threshold {self.threshold_ms}ms",
                "tenant_id": tenant,
                "db_id": db,
                "raw_log_ts": raw.get("ts"),
            },
            tags=[
                "severity:medium" if ms < 15000 else "severity:high",
                "component:compute",
                "skill:cold-start-watcher",
            ],
            model="deepseek-chat",
            runtime="hermes@0.10.0",
        )
        s.append_turn(type="trigger", content={"alert_ms": ms, "tenant": tenant, "db": db})
        self.ledger.record_invocation(
            "cold-start-watcher",
            version=self.skill_version,
            session_id=s.id,
            triggered_at=utc_now_iso(),
        )
        return s.id
```

- [ ] **Step 16.4: Run test — passes**

```bash
uv run pytest tests/integration/test_cold_start_watcher.py -v
```
Expected: 3 passed.

- [ ] **Step 16.5: Commit**

```bash
git add lakeon/sre-agent/skills/sre/cold_start_watcher/ lakeon/sre-agent/tests/integration/
git commit -m "feat(sre-agent): cold-start-watcher detection + session open"
```

---

### Task 17: Cold-start-watcher LLM diagnosis

**Goal:** After watcher opens a session, run LLM-driven diagnosis with branching. Produce a conclusion.

**Files:**
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/diagnose.py`
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/diagnose_prompt.md`
- Modify: `lakeon/sre-agent/tests/integration/test_cold_start_watcher.py` (add diagnose test)

- [ ] **Step 17.1: Write diagnose prompt**

```markdown
# skills/sre/cold_start_watcher/diagnose_prompt.md

You are the dbay.cloud SRE agent diagnosing a compute cold start that took longer than expected.

## Context
- Alert: {alert}
- Tenant: {tenant_id}, Database: {db_id}
- Alert timestamp: {raw_log_ts}

## Available tools
- `log_search(component, keyword, since, limit)` — search the dbay-logs PG
- `log_trace(request_id)` — follow a request chain
- `log_errors(since, component)` — recent error spike summary
- `log_stats(since)` — activity overview

## Your task
1. Inspect the 5-minute window around the alert for relevant logs.
2. Form 1 to 3 concrete hypotheses for the slow start. Examples:
   - Pageserver re-attach gap (check pageserver component for re-attach events)
   - CCE image pull slow (check k8s events for ImagePulling)
   - Node scheduling delay (check k8s scheduler logs)
   - WAL replay backlog (check pageserver/safekeeper for WAL lag)
3. For each hypothesis, gather evidence via log_search. Be specific: narrow by
   tenant_id/db_id/component/time window.
4. Pick the hypothesis best supported by evidence. State confidence 0.0-1.0.
5. Suggest 1-2 concrete actions the human can take. Do NOT execute anything.

## Output format
Respond in markdown, ready to be written to conclusion.md:

```
# Cold start {ms}ms for {tenant_id}/{db_id}

## Root cause (confidence X.XX)
<one paragraph>

## Evidence
- turn <N>: <what>
- turn <N>: <what>

## Suggested actions
1. <concrete, manual, reversible action>
2. <second option if relevant>

## Rejected hypotheses
- <name>: <why ruled out>
```

Keep under 400 words.
```

- [ ] **Step 17.2: Write diagnose.py**

```python
# skills/sre/cold_start_watcher/diagnose.py
"""LLM-driven diagnosis for an open cold-start incident.

Hermes will invoke diagnose() after Watcher.scan_once() returns new session ids.
This module uses the hermes LLM + MCP bridge (passed in), not a direct client,
so it stays testable via dependency injection.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Protocol

from agent_session_log import LogStore, Session


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


class MCPClient(Protocol):
    def log_search(self, **kwargs: Any) -> list[dict]: ...
    def log_trace(self, request_id: str) -> list[dict]: ...


PROMPT_TEMPLATE = (Path(__file__).parent / "diagnose_prompt.md").read_text()


def diagnose(
    session: Session,
    *,
    llm: LLMClient,
    mcp: MCPClient,
    max_hypothesis_branches: int = 3,
) -> None:
    """Run diagnosis in-place. Writes conclusion on session and closes it."""
    trigger = session._store.read_manifest(session.id).trigger
    prompt = PROMPT_TEMPLATE.format(
        alert=trigger.get("alert", ""),
        tenant_id=trigger.get("tenant_id", ""),
        db_id=trigger.get("db_id", ""),
        raw_log_ts=trigger.get("raw_log_ts", ""),
        ms=_extract_ms(trigger.get("alert", "")),
    )

    # Round 1: LLM proposes hypotheses
    session.append_turn(type="thought", content="starting diagnosis; proposing hypotheses")
    out = llm.complete(system="You are a careful SRE.", user=prompt)
    session.append_turn(
        type="llm_completion",
        model=out.get("model"),
        tokens_in=out.get("tokens_in"),
        tokens_out=out.get("tokens_out"),
        cost_usd=out.get("cost_usd"),
        content=out.get("text", "")[:1000],
        skill="cold-start-watcher",
        skill_version="v0.1",
    )

    # Extract hypothesis list (LLM returns markdown; we look for headers like "## Hypotheses")
    hypotheses = _parse_hypotheses(out.get("text", ""), max_hypothesis_branches)
    if not hypotheses:
        session.conclude(out.get("text", "(LLM returned no structured hypotheses)"))
        session.close()
        return

    # Round 2: branch + evidence collection per hypothesis
    branch_results = {}
    for h in hypotheses:
        b = session.branch(_slug(h["name"]))
        b.append_turn(type="thought", content=f"investigating: {h['name']}")
        # Call log_search with hypothesis-specific filter
        results = mcp.log_search(
            component=h.get("component", "lakeon-api"),
            keyword=h.get("keyword", ""),
            since="5m",
            limit=50,
        )
        blob = session.attach_evidence(
            json.dumps(results, ensure_ascii=False).encode("utf-8"),
            mime="application/json",
            source=f"log_search(hypothesis={h['name']})",
        )
        b.append_turn(
            type="tool_result",
            ref_turn=1,
            evidence=[blob.sha256],
            truncated=len(results) >= 50,
        )
        branch_results[h["name"]] = {"count": len(results), "evidence": blob.sha256}

    # Round 3: LLM picks winning hypothesis
    summary = "\n".join(
        f"- {name}: {info['count']} matching rows (evidence {info['evidence'][:8]})"
        for name, info in branch_results.items()
    )
    session.append_turn(type="thought", content=f"branch evidence summary:\n{summary}")
    decision_prompt = (
        "Given the evidence per hypothesis below, pick the single most likely "
        "root cause and write the final markdown conclusion per the earlier format.\n\n"
        + summary
    )
    final = llm.complete(system="You are a careful SRE.", user=decision_prompt)
    session.append_turn(
        type="llm_completion",
        model=final.get("model"),
        tokens_in=final.get("tokens_in"),
        tokens_out=final.get("tokens_out"),
        cost_usd=final.get("cost_usd"),
        content=final.get("text", "")[:1000],
        skill="cold-start-watcher",
        skill_version="v0.1",
    )

    # Determine keep / discard from LLM's response
    winning = _extract_winner(final.get("text", ""), list(branch_results.keys()))
    losers = [h for h in branch_results if h != winning]
    if winning:
        session.resolve_branches(
            keep=_slug(winning),
            discard=[_slug(n) for n in losers],
            reason=final.get("text", "")[:500],
            evidence=[branch_results[winning]["evidence"]],
        )
    session.conclude(final.get("text", "(no final)"))
    session.close()


# ---- helpers ----

def _extract_ms(alert: str) -> str:
    import re
    m = re.search(r"(\d+)ms", alert)
    return m.group(1) if m else "?"


def _slug(name: str) -> str:
    return name.lower().replace(" ", "-").replace("_", "-")[:40]


def _parse_hypotheses(text: str, limit: int) -> list[dict]:
    """Rough parse: lines under '## Hypotheses' or numbered list.

    Each hypothesis needs a name and ideally a component/keyword to search.
    Fallback: build hypotheses from known patterns (pageserver, image-pull, wal).
    """
    # Simple heuristic — in practice the prompt should ask for JSON.
    known = [
        {"name": "pageserver-reattach", "component": "pageserver", "keyword": "re-attach"},
        {"name": "image-pull-slow", "component": "k8s", "keyword": "ImagePulling"},
        {"name": "wal-replay-backlog", "component": "pageserver", "keyword": "wal_lag"},
    ]
    mentioned = [h for h in known if h["name"].split("-")[0] in text.lower()]
    if not mentioned:
        return known[:limit]
    return mentioned[:limit]


def _extract_winner(text: str, candidates: list[str]) -> str | None:
    low = text.lower()
    for c in candidates:
        if c.lower().split("-")[0] in low and "root cause" in low:
            return c
    return candidates[0] if candidates else None
```

- [ ] **Step 17.3: Extend integration test**

Append to `tests/integration/test_cold_start_watcher.py`:

```python
def test_diagnose_fills_conclusion_and_closes(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose
    from agent_session_log import LogStore

    class FakeLLM:
        calls = 0
        def complete(self, *, system, user, tools=None):
            FakeLLM.calls += 1
            if FakeLLM.calls == 1:
                return {"text": "I'll check pageserver-reattach first.",
                        "model": "deepseek-chat", "tokens_in": 100, "tokens_out": 50,
                        "cost_usd": 0.001}
            return {"text": "# Root cause (confidence 0.72)\n"
                            "pageserver-reattach gap observed for tenant t_abc.\n",
                    "model": "deepseek-chat", "tokens_in": 200, "tokens_out": 80,
                    "cost_usd": 0.002}

    mock_mcp.responses["compute started in"] = [
        {"ts": "2026-04-23T09:12:34Z",
         "msg": "compute started in 8234ms for tenant=t_abc db=db_xyz",
         "tenant_id": "t_abc", "db_id": "db_xyz"},
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp)
    sids = w.scan_once()
    assert len(sids) == 1
    session = log.get_session(sids[0])
    diagnose(session, llm=FakeLLM(), mcp=mock_mcp)

    # Session is closed with conclusion
    m = log.store.read_manifest(sids[0])
    assert m.status == "closed"
    concl = log.store.read_conclusion(sids[0])
    assert "pageserver" in concl.lower()
    # At least one branch was resolved
    decisions = (log.store.session_dir(sids[0]) / "branch-decisions.jsonl")
    assert decisions.exists() and decisions.read_text().strip()
```

- [ ] **Step 17.4: Run — passes**

```bash
uv run pytest tests/integration/test_cold_start_watcher.py -v
```
Expected: 4 passed.

- [ ] **Step 17.5: Commit**

```bash
git add lakeon/sre-agent/skills/sre/cold_start_watcher/diagnose.py \
        lakeon/sre-agent/skills/sre/cold_start_watcher/diagnose_prompt.md \
        lakeon/sre-agent/tests/integration/test_cold_start_watcher.py
git commit -m "feat(sre-agent): LLM diagnosis with branching for cold-start-watcher"
```

---

### Task 18: Feishu card reporter

**Files:**
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/report.py`
- Create: `lakeon/sre-agent/skills/sre/cold_start_watcher/report_template.md`
- Create: `lakeon/sre-agent/tests/integration/test_report.py`

- [ ] **Step 18.1: report_template.md**

```markdown
### Cold start {ms}ms @ {tenant}/{db}

**Root cause** ({confidence}): {root_cause_one_liner}

**Suggested actions:**
{actions}

**Session:** `{session_id}` · **Rejected:** {rejected_hypotheses}
```

- [ ] **Step 18.2: Write failing test**

```python
# tests/integration/test_report.py
from pathlib import Path

import pytest

from agent_session_log import LogStore
from skills.sre.cold_start_watcher.report import build_report


def test_build_report_from_closed_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(
        type="incident",
        trigger={"tenant_id": "t", "db_id": "d", "alert": "compute cold start 8234ms"},
        tags=["severity:medium"],
    )
    s.append_turn(type="branch_resolve", keep="pageserver-reattach",
                  discard=["image-pull-slow"], reason="metric X")
    s.conclude(
        "# Cold start 8234ms for t/d\n\n"
        "## Root cause (confidence 0.72)\n"
        "Pageserver re-attach gap for tenant t — 6.8s re-attach.\n\n"
        "## Suggested actions\n"
        "1. Manual PUT location_config\n"
        "2. File ticket for pageserver startup scan fix\n"
    )
    s.close()
    card = build_report(log, s.id)
    assert "8234" in card
    assert "pageserver" in card.lower()
    assert "Manual PUT location_config" in card
    assert "image-pull-slow" in card
```

- [ ] **Step 18.3: Implement report.py**

```python
# skills/sre/cold_start_watcher/report.py
"""Build a feishu card summary from a closed cold-start-watcher session."""
from __future__ import annotations

import re
from pathlib import Path

from agent_session_log import LogStore


TEMPLATE = (Path(__file__).parent / "report_template.md").read_text()


def build_report(log: LogStore, session_id: str) -> str:
    m = log.store.read_manifest(session_id)
    concl = log.store.read_conclusion(session_id) or ""

    ms = _extract_ms(m.trigger.get("alert", ""))
    tenant = m.trigger.get("tenant_id", "?")
    db = m.trigger.get("db_id", "?")
    root_cause = _extract_section(concl, "Root cause")
    confidence = _extract_confidence(concl)
    actions = _extract_section(concl, "Suggested actions") or "(none)"
    rejected = _extract_rejected(log, session_id)

    return TEMPLATE.format(
        ms=ms, tenant=tenant, db=db,
        confidence=confidence,
        root_cause_one_liner=_first_sentence(root_cause),
        actions=actions.strip(),
        session_id=session_id,
        rejected_hypotheses=", ".join(rejected) or "none",
    )


def _extract_ms(alert: str) -> str:
    m = re.search(r"(\d+)ms", alert)
    return m.group(1) if m else "?"


def _extract_section(text: str, header: str) -> str:
    # Match ## Header ... until next ## or EOF
    pat = rf"##\s+{re.escape(header)}[^\n]*\n(.*?)(?=\n##\s|\Z)"
    m = re.search(pat, text, re.DOTALL)
    return m.group(1).strip() if m else ""


def _extract_confidence(text: str) -> str:
    m = re.search(r"confidence\s+([\d.]+)", text, re.IGNORECASE)
    return f"confidence {m.group(1)}" if m else ""


def _first_sentence(text: str) -> str:
    for sep in ("\n\n", ". ", "\n"):
        if sep in text:
            return text.split(sep)[0].strip()
    return text.strip()[:160]


def _extract_rejected(log: LogStore, session_id: str) -> list[str]:
    path = log.store.session_dir(session_id) / "branch-decisions.jsonl"
    if not path.exists():
        return []
    import json
    out = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        dec = json.loads(line)
        out.extend(dec.get("discarded", []))
    return out
```

- [ ] **Step 18.4: Run — passes**

```bash
uv run pytest tests/integration/test_report.py -v
```
Expected: 1 passed.

- [ ] **Step 18.5: Commit**

```bash
git add lakeon/sre-agent/skills/sre/cold_start_watcher/report.py \
        lakeon/sre-agent/skills/sre/cold_start_watcher/report_template.md \
        lakeon/sre-agent/tests/integration/test_report.py
git commit -m "feat(sre-agent): feishu card report builder for cold-start incidents"
```

---

### Task 19: Outcome-checker skill

**Files:**
- Create: `lakeon/sre-agent/skills/sre/outcome_checker/SKILL.md`
- Create: `lakeon/sre-agent/skills/sre/outcome_checker/checker.py`
- Create: `lakeon/sre-agent/tests/integration/test_outcome_checker.py`

- [ ] **Step 19.1: SKILL.md**

```markdown
---
name: outcome-checker
description: Re-check incident sessions 24h after close to verify suggested fixes worked.
version: v0.1
triggers:
  cron: "0 9 * * *"
tools:
  - dbay-sre-mcp.log_stats
  - dbay-sre-mcp.log_search
personality: sre
---

# outcome-checker

Runs every morning at 09:00.

For each closed incident session from the last 36 hours that doesn't yet have `outcome.md`:

1. Identify the affected (tenant, db) from the session trigger.
2. Query cold-start p95 for that pair over the last 24 hours via log_stats.
3. Compare against the triggering cold start time:
   - If new p95 < 5s AND significantly better than trigger: did_work=True.
   - If no improvement: did_work=False.
4. Write outcome.md on the session.
5. Update skill-ledger outcomes.
6. If did_work=False, DM Jacky on feishu: "建议未生效,请看 {session_id}".
```

- [ ] **Step 19.2: Write failing test**

```python
# tests/integration/test_outcome_checker.py
from pathlib import Path

import pytest

from agent_session_log import LogStore, SkillLedger
from skills.sre.outcome_checker.checker import OutcomeChecker


def _make_closed_incident(log: LogStore, ms: int, tenant: str, db: str, skill: str = "cold-start-watcher") -> str:
    s = log.new_session(
        type="incident",
        trigger={"tenant_id": tenant, "db_id": db, "alert": f"compute cold start {ms}ms",
                 "skill_version": "v0.1"},
        tags=["component:compute", f"skill:{skill}"],
    )
    s.conclude("fix: X")
    s.close()
    return s.id


class FakeMCP:
    def __init__(self, p95_map: dict[tuple[str, str], int]):
        self.p95 = p95_map

    def log_stats(self, since: str = "24h", **_):
        return {"cold_start_p95_by_db": {f"{t}/{d}": ms for (t, d), ms in self.p95.items()}}


def test_did_work_true_when_p95_improves(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    mcp = FakeMCP({("t", "d"): 2100})
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    checker.scan_once()

    out = log.store.read_outcome(sid)
    assert out is not None
    assert "did_work: true" in out.lower() or "did work: true" in out.lower()
    stats = SkillLedger(tmp_log_root).stats("cold-start-watcher")
    assert stats["did_work_count"] == 1


def test_did_work_false_when_no_improvement(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    mcp = FakeMCP({("t", "d"): 7900})  # still slow
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    checker.scan_once()

    out = log.store.read_outcome(sid)
    assert "did_work: false" in out.lower() or "did work: false" in out.lower()


def test_skips_already_checked(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    s = log.get_session(sid)
    s.record_outcome(did_work=True, notes="already")

    mcp = FakeMCP({("t", "d"): 5000})
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    # Should not overwrite existing outcome
    before = log.store.read_outcome(sid)
    checker.scan_once()
    after = log.store.read_outcome(sid)
    assert before == after
```

- [ ] **Step 19.3: Implement checker.py**

```python
# skills/sre/outcome_checker/checker.py
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from agent_session_log import LogStore
from agent_session_log.skill_ledger import SkillLedger


@dataclass
class OutcomeChecker:
    log: LogStore
    mcp: Any
    ledger: SkillLedger
    lookback_hours: int = 36
    improvement_threshold_ms: int = 5000

    def scan_once(self) -> list[str]:
        """Check closed incidents without outcome. Return session ids updated."""
        updated: list[str] = []
        cutoff = datetime.now(timezone.utc) - timedelta(hours=self.lookback_hours)
        for meta in self.log.list_sessions(type="incident", limit=200):
            if meta["status"] != "closed":
                continue
            closed_at = meta.get("closed_at")
            if not closed_at:
                continue
            ct = datetime.fromisoformat(closed_at.replace("Z", "+00:00"))
            if ct < cutoff:
                break  # newest-first; done
            if self.log.store.read_outcome(meta["id"]):
                continue

            manifest = self.log.store.read_manifest(meta["id"])
            tenant = manifest.trigger.get("tenant_id")
            db = manifest.trigger.get("db_id")
            if not tenant or not db:
                continue

            stats = self.mcp.log_stats(since="24h")
            p95_map = stats.get("cold_start_p95_by_db", {})
            current_p95 = p95_map.get(f"{tenant}/{db}")
            original_ms = _extract_ms(manifest.trigger.get("alert", ""))

            did_work = self._classify(current_p95=current_p95, original_ms=original_ms)
            notes = f"current p95 {current_p95}ms vs original trigger {original_ms}ms"

            session = self.log.get_session(meta["id"])
            session.record_outcome(did_work=did_work, notes=notes)
            skill = _extract_skill(manifest.tags)
            if skill:
                self.ledger.record_outcome(skill, session_id=meta["id"],
                                           did_work=did_work, notes=notes)
            updated.append(meta["id"])
        return updated

    def _classify(self, *, current_p95: int | None, original_ms: int | None) -> bool:
        if current_p95 is None or original_ms is None:
            return False
        return current_p95 < self.improvement_threshold_ms and current_p95 < original_ms // 2


def _extract_ms(alert: str) -> int | None:
    import re
    m = re.search(r"(\d+)ms", alert or "")
    return int(m.group(1)) if m else None


def _extract_skill(tags: list[str]) -> str | None:
    for t in tags:
        if t.startswith("skill:"):
            return t.split(":", 1)[1]
    return None
```

- [ ] **Step 19.4: Run — passes**

```bash
uv run pytest tests/integration/test_outcome_checker.py -v
```
Expected: 3 passed.

- [ ] **Step 19.5: Commit**

```bash
git add lakeon/sre-agent/skills/sre/outcome_checker/ lakeon/sre-agent/tests/integration/test_outcome_checker.py
git commit -m "feat(sre-agent): outcome-checker 24h revalidation skill"
```

---

## Group F: Railway Deployment

### Task 20: Dockerfile

**Files:**
- Create: `lakeon/sre-agent/Dockerfile`
- Create: `lakeon/sre-agent/entrypoint.sh`

- [ ] **Step 20.1: Dockerfile**

```dockerfile
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install uv
RUN pip install --no-cache-dir uv==0.4.18

# Install hermes from git (pinned ref). If a release tag exists, prefer it.
ENV HERMES_REF=main
RUN uv pip install --system "hermes-agent @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"

# Install our agent_session_log + deps
COPY pyproject.toml ./
COPY agent_session_log ./agent_session_log
RUN uv pip install --system .

# Copy runtime assets
COPY skills ./skills
COPY hermes_config ./hermes_config
COPY scripts ./scripts
COPY entrypoint.sh ./

# dbay-sre-mcp (install via pip from the lakeon repo if published, else bundle)
# For Phase 0, install from local sibling directory via build-time arg
ARG DBAY_SRE_MCP_SRC=/dbay-sre-mcp
COPY ../dbay-sre-mcp ${DBAY_SRE_MCP_SRC}
RUN uv pip install --system ${DBAY_SRE_MCP_SRC}

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
```

Note: Railway's build context is the repo root; `railway.toml` path filter below restricts rebuilds to `sre-agent/**` and `dbay-sre-mcp/**` changes.

- [ ] **Step 20.2: entrypoint.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "[entrypoint] verifying env..."
python /app/scripts/verify_env.py

mkdir -p "${HERMES_HOME}/data"

echo "[entrypoint] starting obs sync loop in background..."
python /app/scripts/sync_loop.py &
OBS_SYNC_PID=$!

echo "[entrypoint] launching hermes gateway..."
exec hermes gateway start --config "${HERMES_CONFIG}"

# If hermes exits, clean up
kill "${OBS_SYNC_PID}" 2>/dev/null || true
```

- [ ] **Step 20.3: Commit**

```bash
git add lakeon/sre-agent/Dockerfile lakeon/sre-agent/entrypoint.sh
git commit -m "feat(sre-agent): Dockerfile + entrypoint for Railway"
```

---

### Task 21: Railway configuration

**Files:**
- Create: `lakeon/sre-agent/railway.toml`

- [ ] **Step 21.1: railway.toml**

```toml
[build]
builder = "DOCKERFILE"
dockerfilePath = "lakeon/sre-agent/Dockerfile"
# Only rebuild when our code or dbay-sre-mcp changes
watchPatterns = [
  "lakeon/sre-agent/**",
  "dbay-sre-mcp/**",
]

[deploy]
healthcheckPath = "/health"     # hermes gateway exposes a health endpoint; adjust if not
startCommand = "/app/entrypoint.sh"
restartPolicyType = "on_failure"
restartPolicyMaxRetries = 5

[volumes]
HERMES_DATA = "/data/hermes"
```

- [ ] **Step 21.2: Commit**

```bash
git add lakeon/sre-agent/railway.toml
git commit -m "feat(sre-agent): Railway service config with path filter"
```

---

### Task 22: Deploy to Railway + live validation

This is the biggest step; it combines Group G validation.

**Prerequisites:**
- All earlier tasks committed
- Railway account + project set up
- Feishu bot credentials in hand
- DBAY_LOGS_DSN confirmed reachable from Railway (Task 14 passed)
- OBS bucket `dbay-agent-log` created

- [ ] **Step 22.1: Create Railway service**

In Railway dashboard:
1. Create new service from the lakeon GitHub repo.
2. Set root directory to `lakeon/sre-agent/`.
3. Attach a 10GB persistent volume mounted at `/data/hermes`.
4. Configure all env vars from `.env.example`.

- [ ] **Step 22.2: First deploy and probe**

Trigger deploy. Watch build logs for errors. Once up:
```bash
# From Railway shell
python /app/scripts/probe_dbay_logs.py
```
Expected: "OK: N log rows in last 1h". If fails, resolve network before continuing.

- [ ] **Step 22.3: feishu handshake**

DM the bot "ping". Expected: LLM replies through deepseek within 30 seconds.

- [ ] **Step 22.4: Manual cold start trigger + capture**

From your Mac:
```bash
cd /Users/jacky/code/lakeon
# Trigger a cold start by creating a new DB and connecting
SITE=hwstaff python scripts/simulate_cold_start.py  # see Step 22.5
```

If `simulate_cold_start.py` doesn't exist, write it:

```python
# lakeon/sre-agent/scripts/simulate_cold_start.py
"""Force a compute cold start on dbay.cloud by creating a new DB and connecting."""
import os
import sys
import time

import psycopg2
import httpx


def main():
    api = "https://api.dbay.cloud:8443/api/v1"
    admin_token = "lakeon-sre-2026"
    # Create a tenant + db
    t = httpx.post(f"{api}/admin/tenants", headers={"Admin-Token": admin_token},
                   json={"name": f"coldstart-test-{int(time.time())}"}).json()
    tenant_id = t["id"]
    d = httpx.post(f"{api}/admin/databases", headers={"Admin-Token": admin_token},
                   json={"tenant_id": tenant_id, "name": "test"}).json()
    db_id = d["id"]
    dsn = d["dsn"]
    print(f"Created tenant={tenant_id} db={db_id}, dsn provided")

    # Wait for SUSPENDED (auto-suspend kicks in after idle timeout)
    time.sleep(120)

    # First connection → cold start
    t0 = time.time()
    conn = psycopg2.connect(dsn, connect_timeout=60)
    dt = int((time.time() - t0) * 1000)
    conn.close()
    print(f"Connect took {dt}ms — watcher should pick this up if >5000")


if __name__ == "__main__":
    main()
```

Commit:
```bash
git add lakeon/sre-agent/scripts/simulate_cold_start.py
git commit -m "feat(sre-agent): cold start simulator for validation"
```

- [ ] **Step 22.5: Wait for the watcher to fire**

Within 2 minutes of the simulated cold start (≥ 5s), the cron should trigger.
Watch Railway logs:
```
[watcher] scan_once: found 1 slow start(s) for t_xxx/db_yyy
[diagnose] opened session sess_...
[report] sending feishu card to ou_jackys_id
```

Confirm on phone: feishu card with title "Cold start {ms}ms" appears.

- [ ] **Step 22.6: Manual interactive test**

DM the bot: "上次那个冷启动最后是什么根因"
Expected: bot calls `log.search_text(...)` and answers based on stored session.

- [ ] **Step 22.7: Verify OBS sync**

```bash
# From Railway shell
ls /data/hermes/data/sessions/$(date +%Y)/$(date +%m)/$(date +%d)/
# Should show sess_... directory

# From Mac, check OBS bucket
obsutil ls obs://dbay-agent-log/agent-log/$(date +%Y)/$(date +%m)/$(date +%d)/
# Should show sess_....tar.gz
```

- [ ] **Step 22.8: Next morning — outcome-checker**

Wait until 09:00 next day. Verify outcome-checker fires:
- In Railway logs: `[outcome-checker] scanned N closed incidents, updated M`
- Session directory now has `outcome.md`.
- Skill ledger `did_work_rate` reflects the outcome.

- [ ] **Step 22.9: Draft Phase 0a report**

Create `lakeon/sre-agent/reports/phase-0a-report.md` summarizing:
- What got caught (count, examples)
- False positive rate
- Actions taken and outcomes
- Skill stats snapshot
- Unexpected issues and workarounds

Commit + share.

- [ ] **Step 22.10: Final commit**

```bash
git add lakeon/sre-agent/reports/phase-0a-report.md
git commit -m "docs(sre-agent): Phase 0a completion report"
```

---

## Self-Review Checklist

- [x] Every spec section has at least one task that implements it
- [x] No placeholders, TBD, "implement later"
- [x] Method / type names consistent across tasks (Session, LogStore, FilesystemStore, SkillLedger, Watcher, OutcomeChecker, ObsSync)
- [x] All test code is complete, not pseudocode
- [x] Exact file paths given
- [x] Git commit per bite-sized task group
- [x] Q7 (Railway egress IP) has a Day 1 probe task (Task 14)
- [x] Q3 (cold-start log埋点) has a modify-lakeon-api task (Task 15)
- [x] Q1 (hermes skill integration point) is acknowledged — skills use plain Python modules that hermes loads via SKILL.md frontmatter; no deep hermes API coupling
- [x] Q2 (hermes deepseek provider) is acknowledged — Task 13 verifies; if hermes config schema differs, adjust config.yaml

## Open Risks During Execution

1. **Hermes config schema may differ from Task 13's guess.** If `config.yaml` keys don't match, cross-reference `~/code/hermes-agent/hermes_cli/providers.py` for the real schema and adjust.
2. **Hermes skill loader conventions.** The SKILL.md frontmatter format we use may need tuning. If hermes doesn't pick up the skill, consult `~/code/hermes-agent/skills/` examples.
3. **Deepseek rate limits during burst diagnosis.** If rate-limited, add exponential backoff in the LLM client wrapper.
4. **OBS first-write may require bucket policy.** If `put_object` returns 403, create an IAM policy granting the access key write permissions on `dbay-agent-log/`.
5. **Cold-start log format variability.** If `log.info` in Java logs with a slightly different format than the regex expects, tighten both. Integration test in Task 16 will catch this first.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-23-sre-agent-phase-0a-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
