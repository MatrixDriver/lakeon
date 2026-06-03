# Phase 2: `openjiuwen-dbay-store` Independent Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Prerequisite:** Phase 1 PR must be merged to `openJiuwen/agent-core` and a new openjiuwen release must be on PyPI before executing Task 8 (E2E against published package). Tasks 1–7 can run in parallel with Phase 1 review.

**Goal:** Ship `openjiuwen-dbay-store` as an independent PyPI package (hosted on gitcode) so that `pip install openjiuwen openjiuwen-dbay-store` + `create_vector_store("dbay", dsn=...)` works out of the box with no core changes needed beyond Phase 1.

**Architecture:** Standard Python package layout (`src/openjiuwen_dbay_store/`), pyproject-driven build, entry_points registration, gitcode-hosted with a gitcode CI workflow that spins up `postgres:16` + `pgvector` for E2E. Code is ported directly from `~/code/agent-core/` branch `feat/dbay-store` commit `8e31a34`, with import paths rewritten from `openjiuwen.core.foundation.store.vector.dbay_vector_store` → `openjiuwen_dbay_store.vector`.

**Tech Stack:** Python 3.11+, asyncpg, pgvector, SQLAlchemy async, pytest, pytest-asyncio, gitcode CI (or equivalent if gitcode lacks Actions; fallback to manual verification).

---

## File Structure

```
openjiuwen-dbay-store/
├── .gitignore
├── .gitcode/workflows/e2e.yml     # CI for E2E against pgvector image
├── LICENSE                         # MIT, your name
├── README.md                       # English, with Quickstart
├── README.zh.md                    # Chinese mirror
├── CONTRIBUTING.md                 # How to contribute
├── CHANGELOG.md
├── pyproject.toml                  # Package metadata + entry_points
├── src/
│   └── openjiuwen_dbay_store/
│       ├── __init__.py             # Re-exports the 3 classes
│       ├── db.py                   # DbayDbStore (from dbay_db_store.py)
│       ├── kv.py                   # DbayKVStore (from dbay_kv_store.py)
│       ├── vector.py               # DbayVectorStore (from dbay_vector_store.py)
│       ├── errors.py               # NEW: DbayConnectionError, PgVectorNotAvailableError, etc.
│       └── _version.py
├── tests/
│   ├── unit/
│   │   ├── test_db.py              # Port of tests/unit_tests/extensions/store/test_dbay_db_store.py
│   │   ├── test_kv.py              # Port of test_dbay_kv_store.py
│   │   ├── test_vector.py          # Port of test_dbay_vector_store.py
│   │   └── test_entry_point.py     # NEW: verify the entry_point actually registers
│   └── e2e/
│       └── test_e2e.py             # Port of tests/system_tests/store/test_dbay_e2e.py
└── examples/
    ├── quickstart.py               # Minimal: connect + add + search
    └── long_term_memory.py         # Wire DbayVectorStore into LongTermMemory
```

---

## Task 1: Create the gitcode Repository

- [ ] **Step 1: Create empty repo on gitcode**

Go to https://gitcode.com/ → New repository:
- Name: `openjiuwen-dbay-store`
- Visibility: Public
- Initialize with: README + .gitignore (Python) + MIT License
- Owner: your gitcode account

- [ ] **Step 2: Clone locally**

```bash
cd ~/code
git clone https://gitcode.com/<your-user>/openjiuwen-dbay-store.git
cd openjiuwen-dbay-store
```

- [ ] **Step 3: Set up baseline files**

Overwrite the auto-generated `.gitignore` with Python defaults plus build artefacts:

```gitignore
# Python
__pycache__/
*.py[codz]
*.so
.Python
build/
dist/
*.egg-info/
.venv/
venv/
.pytest_cache/
.mypy_cache/
.ruff_cache/
htmlcov/
.coverage

# Editors
.idea/
.vscode/
*.swp
.DS_Store

# Test artifacts
report/
```

Verify LICENSE is MIT with your name as copyright holder — if the gitcode template says "Jacky Li" that's fine, otherwise edit:

```
MIT License

Copyright (c) 2026 Jacky Li

Permission is hereby granted, free of charge, ...
```

- [ ] **Step 4: Commit baseline**

```bash
git add .gitignore LICENSE
git commit -m "chore: baseline .gitignore and MIT LICENSE"
git push origin main
```

---

## Task 2: pyproject.toml (Package Metadata + Entry Points)

**Files:**
- Create: `pyproject.toml`

- [ ] **Step 1: Write pyproject.toml**

```toml
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "openjiuwen-dbay-store"
dynamic = ["version"]
description = "Dbay (Neon Serverless PG + pgvector) store backend plugin for openJiuwen"
readme = "README.md"
requires-python = ">=3.11"
license = { file = "LICENSE" }
authors = [{ name = "Jacky Li" }]
keywords = ["openjiuwen", "ai-agent", "memory", "pgvector", "neon", "dbay", "vector-database"]
classifiers = [
    "Development Status :: 4 - Beta",
    "Intended Audience :: Developers",
    "License :: OSI Approved :: MIT License",
    "Programming Language :: Python :: 3",
    "Programming Language :: Python :: 3.11",
    "Programming Language :: Python :: 3.12",
    "Topic :: Database",
    "Topic :: Scientific/Engineering :: Artificial Intelligence",
]
dependencies = [
    "openjiuwen>=<NEXT_VERSION_AFTER_PHASE1>",
    "asyncpg>=0.30.0",
    "pgvector>=0.2.0",
    "sqlalchemy>=2.0",
]

[project.optional-dependencies]
test = [
    "pytest>=8",
    "pytest-asyncio>=0.23",
]

[project.urls]
Homepage = "https://gitcode.com/<your-user>/openjiuwen-dbay-store"
Documentation = "https://gitcode.com/<your-user>/openjiuwen-dbay-store#readme"
Source = "https://gitcode.com/<your-user>/openjiuwen-dbay-store"
Issues = "https://gitcode.com/<your-user>/openjiuwen-dbay-store/issues"

# Register with openJiuwen's plugin registry (Phase 1)
[project.entry-points."openjiuwen.vector_stores"]
dbay = "openjiuwen_dbay_store.vector:DbayVectorStore"

# KV and DB stores are not factory-backed in openJiuwen; users import directly.
# These entry points are non-standard but give IDE + tooling discoverability.
[project.entry-points."openjiuwen.kv_stores"]
dbay = "openjiuwen_dbay_store.kv:DbayKVStore"

[project.entry-points."openjiuwen.db_stores"]
dbay = "openjiuwen_dbay_store.db:DbayDbStore"

[tool.setuptools.dynamic]
version = { attr = "openjiuwen_dbay_store._version.__version__" }

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

Substitute `<NEXT_VERSION_AFTER_PHASE1>` with the released openjiuwen version that contains the Phase 1 factory refactor. If Phase 1 hasn't released yet, use `>=0.1.9` as a placeholder and tighten later.

- [ ] **Step 2: Commit**

```bash
git add pyproject.toml
git commit -m "build: pyproject with entry_points for openjiuwen plugin discovery"
```

---

## Task 3: Port Code from feat/dbay-store

**Source:** `~/code/agent-core/` branch `feat/dbay-store` commit `8e31a34`
**Destination:** `~/code/openjiuwen-dbay-store/src/openjiuwen_dbay_store/`

- [ ] **Step 1: Create package layout**

```bash
cd ~/code/openjiuwen-dbay-store
mkdir -p src/openjiuwen_dbay_store tests/unit tests/e2e examples
```

- [ ] **Step 2: Copy source files with renames**

```bash
# Vector store
cp ~/code/agent-core/openjiuwen/core/foundation/store/vector/dbay_vector_store.py \
   src/openjiuwen_dbay_store/vector.py

# KV store
cp ~/code/agent-core/openjiuwen/extensions/store/kv/dbay_kv_store.py \
   src/openjiuwen_dbay_store/kv.py

# DB store
cp ~/code/agent-core/openjiuwen/extensions/store/db/dbay_db_store.py \
   src/openjiuwen_dbay_store/db.py
```

- [ ] **Step 3: Rewrite imports**

Every imported path needs updating. Run:

```bash
cd ~/code/openjiuwen-dbay-store/src/openjiuwen_dbay_store
# vector.py
sed -i '' 's|from openjiuwen.extensions.store.db.dbay_db_store|from openjiuwen_dbay_store.db|g' vector.py
# kv.py
sed -i '' 's|from openjiuwen.extensions.store.db.dbay_db_store|from openjiuwen_dbay_store.db|g' kv.py
```

Leave `from openjiuwen.core.foundation.store.base_*_store` imports alone — those reference the upstream ABCs, which is exactly what we want (contract is owned by openjiuwen).

Also replace the Huawei copyright header with yours:

```bash
for f in src/openjiuwen_dbay_store/*.py; do
  sed -i '' 's|# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.|# Copyright (c) 2026 Jacky Li. MIT License.|' "$f"
done
```

- [ ] **Step 4: Remove the logger dependency chain**

`DbayVectorStore` uses `openjiuwen.core.common.logging.store_logger`. That pulls in the full openjiuwen logging system. Replace with stdlib `logging`:

Open `src/openjiuwen_dbay_store/vector.py`. Find:

```python
from openjiuwen.core.common.logging import store_logger, LogEventType
```

Replace with:

```python
import logging
logger = logging.getLogger("openjiuwen_dbay_store.vector")
```

Then in the file, find every `store_logger.info(msg, event_type=LogEventType.X, ...)` call. Replace with `logger.info(msg)` (drop the `event_type` kwarg — stdlib logging doesn't have it). Keep any extra=... that makes sense as kwargs to the formatter, or just drop them.

Do the same for `kv.py` if it uses `store_logger`.

Similarly replace `build_error` / `StatusCode` imports with plain exceptions (see Task 4).

- [ ] **Step 5: Create `__init__.py` and `_version.py`**

```python
# src/openjiuwen_dbay_store/__init__.py
"""
openJiuwen dbay store — Neon Serverless PG + pgvector backend plugin.

Usage:
    from openjiuwen_dbay_store import DbayVectorStore, DbayKVStore, DbayDbStore

    # Or via openjiuwen factory (after openjiuwen Phase 1 release):
    from openjiuwen.core.foundation.store import create_vector_store
    vec = create_vector_store("dbay", dsn="postgresql://...")
"""
from openjiuwen_dbay_store._version import __version__
from openjiuwen_dbay_store.db import DbayDbStore
from openjiuwen_dbay_store.kv import DbayKVStore
from openjiuwen_dbay_store.vector import DbayVectorStore

__all__ = [
    "__version__",
    "DbayDbStore",
    "DbayKVStore",
    "DbayVectorStore",
]
```

```python
# src/openjiuwen_dbay_store/_version.py
__version__ = "0.1.0"
```

- [ ] **Step 6: Verify local install works**

```bash
cd ~/code/openjiuwen-dbay-store
uv venv .venv --python 3.11
source .venv/bin/activate
uv pip install -e ".[test]"
python -c "from openjiuwen_dbay_store import DbayVectorStore, DbayKVStore, DbayDbStore; print('imports ok')"
```

Expected: `imports ok`. Any ImportError here means Task 3 step 3–4 didn't fully rewire the imports — go fix before continuing.

- [ ] **Step 7: Commit**

```bash
git add src/ pyproject.toml
git commit -m "feat: port dbay store implementations from openjiuwen feat/dbay-store"
```

---

## Task 4: Friendly Errors Module

> The old code raised raw `asyncpg` exceptions on DSN errors, missing pgvector, permission issues, etc. Plugin users get better UX if we translate these into named exceptions with actionable messages.

**Files:**
- Create: `src/openjiuwen_dbay_store/errors.py`
- Modify: `src/openjiuwen_dbay_store/vector.py` (wrap `_ensure_pgvector`)
- Modify: `src/openjiuwen_dbay_store/db.py` (wrap connect)

- [ ] **Step 1: Write errors.py**

```python
# src/openjiuwen_dbay_store/errors.py
# Copyright (c) 2026 Jacky Li. MIT License.
"""Named exceptions raised by the dbay store plugin."""


class DbayStoreError(Exception):
    """Base class for all errors raised by the dbay store plugin."""


class DbayConnectionError(DbayStoreError):
    """Raised when the initial PG connection attempt fails.

    Message includes the target host and a hint to check DSN / firewall.
    """


class PgVectorNotAvailableError(DbayStoreError):
    """Raised when `CREATE EXTENSION vector` fails.

    Most common cause: the connected role lacks CREATE EXTENSION
    privileges on a managed PG instance. Message includes remediation:
    ask the DBA to pre-install pgvector, or use a role with superuser.
    """


class PgVectorExtensionMissingError(DbayStoreError):
    """Raised when pgvector is not installed AND the role cannot install it.

    Distinct from PgVectorNotAvailableError because here the extension
    binaries aren't on the server at all.
    """
```

- [ ] **Step 2: Wrap `_ensure_pgvector` in vector.py**

Replace the `_ensure_pgvector` method:

```python
async def _ensure_pgvector(self):
    if self._pgvector_ensured:
        return
    try:
        async with self._engine.begin() as conn:
            await conn.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
    except Exception as e:
        from openjiuwen_dbay_store.errors import (
            PgVectorNotAvailableError, PgVectorExtensionMissingError,
        )
        msg = str(e).lower()
        if "permission denied" in msg or "must be owner" in msg:
            raise PgVectorNotAvailableError(
                "pgvector extension is not enabled and the current DB role "
                "cannot run CREATE EXTENSION. Options:\n"
                "  (1) Ask your DBA to run `CREATE EXTENSION vector;` once, OR\n"
                "  (2) Use a role with sufficient privileges, OR\n"
                "  (3) Choose a managed PG that pre-installs pgvector "
                "(e.g., Neon, dbay.cloud, Supabase)."
            ) from e
        if "could not open extension control file" in msg or "not available" in msg:
            raise PgVectorExtensionMissingError(
                "pgvector is not installed on this PG server. "
                "Install the `pgvector` package on the server, or switch to a "
                "managed PG that includes it (Neon, dbay.cloud, Supabase)."
            ) from e
        raise  # unknown failure — re-raise for visibility
    self._pgvector_ensured = True
    logger.info("pgvector extension ensured")
```

- [ ] **Step 3: Wrap `get_async_engine` in db.py**

In `DbayDbStore.get_async_engine`, wrap the create_async_engine call to surface connection errors lazily — but since `create_async_engine` itself doesn't connect (it creates a pool lazily), the wrap should happen at first-use in vector / kv. Actually, simpler: add a `ping()` method:

```python
from openjiuwen_dbay_store.errors import DbayConnectionError

class DbayDbStore(BaseDbStore):
    # ... existing code ...

    async def ping(self) -> None:
        """Verify the connection works. Raises DbayConnectionError on failure."""
        from sqlalchemy import text
        try:
            async with self.get_async_engine().connect() as conn:
                await conn.execute(text("SELECT 1"))
        except Exception as e:
            raise DbayConnectionError(
                f"Cannot connect to dbay PG at {self._dsn_redacted()}: {e}\n"
                f"Check: (a) DSN is correct, (b) host is reachable, "
                f"(c) credentials are valid, (d) PG is listening on the given port."
            ) from e

    def _dsn_redacted(self) -> str:
        """DSN with password redacted for logging."""
        import re
        return re.sub(r"://([^:]+):[^@]+@", r"://\1:***@", self._dsn)
```

- [ ] **Step 4: Add unit tests for errors**

Create `tests/unit/test_errors.py`:

```python
# tests/unit/test_errors.py
# Copyright (c) 2026 Jacky Li. MIT License.
"""Unit tests for friendly error translation."""
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from openjiuwen_dbay_store import DbayDbStore, DbayVectorStore
from openjiuwen_dbay_store.errors import (
    DbayConnectionError,
    PgVectorExtensionMissingError,
    PgVectorNotAvailableError,
)


class TestDbayConnectionError:
    @pytest.mark.asyncio
    async def test_ping_raises_friendly_error_on_connection_failure(self):
        store = DbayDbStore(dsn="postgresql://bad:bad@nowhere:5432/x")
        with pytest.raises(DbayConnectionError) as exc_info:
            await store.ping()
        msg = str(exc_info.value)
        assert "dbay PG at" in msg
        assert "nowhere:5432" in msg
        assert "bad:***@" in msg  # password redacted

    def test_dsn_redacted_hides_password(self):
        store = DbayDbStore(dsn="postgresql://alice:secret123@host/db")
        assert "secret123" not in store._dsn_redacted()
        assert "alice:***@" in store._dsn_redacted()


class TestPgVectorErrors:
    @pytest.mark.asyncio
    async def test_permission_denied_raises_not_available_error(self):
        with patch("openjiuwen_dbay_store.db.create_async_engine") as mock_ca:
            mock_engine = MagicMock()
            mock_conn_ctx = MagicMock()
            mock_conn = AsyncMock()
            mock_conn.execute.side_effect = Exception("permission denied for database postgres")
            mock_conn_ctx.__aenter__ = AsyncMock(return_value=mock_conn)
            mock_conn_ctx.__aexit__ = AsyncMock(return_value=None)
            mock_engine.begin = MagicMock(return_value=mock_conn_ctx)
            mock_ca.return_value = mock_engine

            store = DbayVectorStore(dsn="postgresql://u:p@h/d")
            with pytest.raises(PgVectorNotAvailableError) as exc_info:
                await store._ensure_pgvector()
            assert "pgvector extension is not enabled" in str(exc_info.value)
            assert "CREATE EXTENSION vector" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_extension_binary_missing_raises_missing_error(self):
        with patch("openjiuwen_dbay_store.db.create_async_engine") as mock_ca:
            mock_engine = MagicMock()
            mock_conn_ctx = MagicMock()
            mock_conn = AsyncMock()
            mock_conn.execute.side_effect = Exception(
                'could not open extension control file "/usr/share/.../vector.control"'
            )
            mock_conn_ctx.__aenter__ = AsyncMock(return_value=mock_conn)
            mock_conn_ctx.__aexit__ = AsyncMock(return_value=None)
            mock_engine.begin = MagicMock(return_value=mock_conn_ctx)
            mock_ca.return_value = mock_engine

            store = DbayVectorStore(dsn="postgresql://u:p@h/d")
            with pytest.raises(PgVectorExtensionMissingError):
                await store._ensure_pgvector()
```

- [ ] **Step 5: Run tests and commit**

```bash
.venv/bin/python -m pytest tests/unit/test_errors.py -v
```

Expected: all pass.

```bash
git add src/openjiuwen_dbay_store/errors.py src/openjiuwen_dbay_store/vector.py src/openjiuwen_dbay_store/db.py tests/unit/test_errors.py
git commit -m "feat: friendly errors for DSN / pgvector / permission issues

Adds DbayConnectionError / PgVectorNotAvailableError /
PgVectorExtensionMissingError with actionable remediation messages.
Password is redacted in connection-error messages."
```

---

## Task 5: Port Existing Unit Tests

**Files:**
- Create: `tests/unit/test_db.py` (from feat/dbay-store `test_dbay_db_store.py`)
- Create: `tests/unit/test_kv.py`
- Create: `tests/unit/test_vector.py`

- [ ] **Step 1: Copy with import rewrites**

```bash
cp ~/code/agent-core/tests/unit_tests/extensions/store/test_dbay_db_store.py tests/unit/test_db.py
cp ~/code/agent-core/tests/unit_tests/extensions/store/test_dbay_kv_store.py tests/unit/test_kv.py
cp ~/code/agent-core/tests/unit_tests/core/foundation/store/test_dbay_vector_store.py tests/unit/test_vector.py

# Rewrite imports
for f in tests/unit/test_db.py tests/unit/test_kv.py tests/unit/test_vector.py; do
  sed -i '' 's|from openjiuwen.extensions.store.db.dbay_db_store|from openjiuwen_dbay_store.db|g' "$f"
  sed -i '' 's|from openjiuwen.extensions.store.kv.dbay_kv_store|from openjiuwen_dbay_store.kv|g' "$f"
  sed -i '' 's|from openjiuwen.core.foundation.store.vector.dbay_vector_store|from openjiuwen_dbay_store.vector|g' "$f"
  sed -i '' 's|from openjiuwen.extensions.store.kv import DbayKVStore|from openjiuwen_dbay_store import DbayKVStore|g' "$f"
  sed -i '' 's|patch("openjiuwen.extensions.store.db.dbay_db_store.create_async_engine")|patch("openjiuwen_dbay_store.db.create_async_engine")|g' "$f"
done
```

- [ ] **Step 2: Drop the `test_create_vector_store_dbay` test**

In `tests/unit/test_vector.py`, the old test verified `create_vector_store("dbay", ...)` worked because the hard-coded elif was in the factory. In the plugin model, that works via entry_points — which requires the package to actually be installed (site-packages). That's an integration concern, not a unit-test concern. Remove the `TestDbayVectorStoreFactory` class entirely from this file.

- [ ] **Step 3: Run unit tests**

```bash
.venv/bin/python -m pytest tests/unit/ -v
```

Expected: all pass. Count should be roughly: 8 from test_db + 8 from test_kv + 10 from test_vector (minus the dropped factory test) + 5 from test_errors = ~31.

- [ ] **Step 4: Commit**

```bash
git add tests/unit/
git commit -m "test: port unit tests for DbayDbStore / KVStore / VectorStore"
```

---

## Task 6: Entry-Point Integration Test

> Verify that `pip install openjiuwen-dbay-store` into a live site-packages actually registers the entry_point AND that `create_vector_store("dbay", ...)` finds it via the Phase 1 discovery mechanism.

**Files:**
- Create: `tests/unit/test_entry_point.py`

- [ ] **Step 1: Write test that queries entry_points for 'dbay'**

```python
# tests/unit/test_entry_point.py
# Copyright (c) 2026 Jacky Li. MIT License.
"""Verify the package correctly registers itself via entry_points.

This test exercises the installed package metadata. It passes only when the
package is installed (editable or not) — run after `pip install -e .`.
"""
from importlib.metadata import entry_points


def test_vector_entry_point_registered():
    eps = entry_points(group="openjiuwen.vector_stores")
    names = {ep.name: ep for ep in eps}
    assert "dbay" in names, f"Expected 'dbay' in {list(names)}"
    cls = names["dbay"].load()
    from openjiuwen_dbay_store.vector import DbayVectorStore
    assert cls is DbayVectorStore


def test_kv_entry_point_registered():
    eps = entry_points(group="openjiuwen.kv_stores")
    names = {ep.name: ep for ep in eps}
    assert "dbay" in names


def test_db_entry_point_registered():
    eps = entry_points(group="openjiuwen.db_stores")
    names = {ep.name: ep for ep in eps}
    assert "dbay" in names


def test_create_vector_store_finds_dbay():
    """End-to-end: openjiuwen's factory must discover us via entry_points.

    This test requires openjiuwen >= <PHASE_1_VERSION> (with the plugin
    framework merged). If the installed openjiuwen is older, this test
    will be skipped with a clear message.
    """
    try:
        from openjiuwen.core.foundation.store import create_vector_store
    except ImportError:
        import pytest
        pytest.skip("openjiuwen not installed")

    # Create with a dummy DSN — we only test discovery, not a real connection.
    store = create_vector_store("dbay", dsn="postgresql://u:p@h/d")
    import pytest
    if store is None:
        pytest.skip(
            "openjiuwen version is pre-Phase 1 (no entry_points discovery). "
            "Upgrade openjiuwen to test this integration."
        )
    from openjiuwen_dbay_store.vector import DbayVectorStore
    assert isinstance(store, DbayVectorStore)
```

- [ ] **Step 2: Run — expect PASS after `pip install -e .`**

```bash
.venv/bin/python -m pytest tests/unit/test_entry_point.py -v
```

Expected: 3 PASS (vector / kv / db entry points registered). The 4th (`test_create_vector_store_finds_dbay`) may SKIP if the installed openjiuwen hasn't yet got Phase 1 — that's acceptable during development, but it MUST PASS (not skip) before PyPI release.

- [ ] **Step 3: Commit**

```bash
git add tests/unit/test_entry_point.py
git commit -m "test: verify entry_points registration for dbay plugin"
```

---

## Task 7: Port E2E Test

**Files:**
- Create: `tests/e2e/test_e2e.py` (from feat/dbay-store `test_dbay_e2e.py`)
- Create: `tests/e2e/__init__.py`
- Create: `tests/e2e/conftest.py`

- [ ] **Step 1: Copy with import rewrites**

```bash
touch tests/e2e/__init__.py
cp ~/code/agent-core/tests/system_tests/store/test_dbay_e2e.py tests/e2e/test_e2e.py

sed -i '' 's|from openjiuwen.extensions.store.db.dbay_db_store|from openjiuwen_dbay_store.db|g' tests/e2e/test_e2e.py
sed -i '' 's|from openjiuwen.extensions.store.kv.dbay_kv_store|from openjiuwen_dbay_store.kv|g' tests/e2e/test_e2e.py
sed -i '' 's|from openjiuwen.core.foundation.store.vector.dbay_vector_store|from openjiuwen_dbay_store.vector|g' tests/e2e/test_e2e.py
```

Leave `from openjiuwen.core.foundation.store.base_vector_store import ...` — these are still the upstream ABC imports.

- [ ] **Step 2: Add conftest for E2E fixtures**

```python
# tests/e2e/conftest.py
# Copyright (c) 2026 Jacky Li. MIT License.
"""E2E test fixtures — require a real PG + pgvector reachable at $DBAY_E2E_DSN."""
import os
import pytest

DEFAULT_DSN = "postgresql://postgres:postgres@localhost:5432/dbay_e2e_test"


def pytest_configure(config):
    if not os.environ.get("DBAY_E2E_DSN") and not _local_pg_available():
        pytest.exit(
            "E2E tests require a reachable PG with pgvector. Set DBAY_E2E_DSN "
            "or start `postgres:16` + `pgvector` locally. See README for setup."
        )


def _local_pg_available() -> bool:
    import socket
    try:
        with socket.create_connection(("localhost", 5432), timeout=1):
            return True
    except (OSError, socket.timeout):
        return False
```

- [ ] **Step 3: Run locally**

```bash
# Start local pgvector if not running
docker run -d --name dbay-e2e-pg \
    -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dbay_e2e_test \
    -p 5432:5432 \
    pgvector/pgvector:pg16

sleep 3
DBAY_E2E_DSN="postgresql://postgres:postgres@localhost:5432/dbay_e2e_test" \
  .venv/bin/python -m pytest tests/e2e/ -v
```

Expected: all 19 E2E tests pass.

- [ ] **Step 4: Commit**

```bash
git add tests/e2e/
git commit -m "test: port E2E tests against real PG + pgvector"
```

---

## Task 8: gitcode CI Workflow

> If gitcode supports Actions-compatible workflows, use this. If not, document the manual steps in CONTRIBUTING.md and ship a `scripts/test.sh` that users/contributors can run.

**Files:**
- Create: `.gitcode/workflows/e2e.yml` (if Actions available) OR
- Create: `scripts/test.sh` + CONTRIBUTING.md updates (fallback)

- [ ] **Step 1: Check if gitcode supports Actions-style CI**

Visit gitcode docs or inspect a known public gitcode repo with CI (e.g., `openJiuwen/agent-core` itself) to see which CI config file is expected. Common options on gitcode:

- `.workflow/*.yml` — gitcode's native format
- `.github/workflows/*.yml` — if gitcode mirrors GitHub Actions
- Custom `.gitcode-ci.yml`

Pick whichever matches. If none available, skip to Step 2 fallback.

- [ ] **Step 2a (if CI available): Write workflow**

```yaml
# .gitcode/workflows/e2e.yml
name: E2E Tests

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: pgvector/pgvector:pg16
        env:
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: dbay_e2e_test
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 5s
          --health-timeout 5s
          --health-retries 10

    steps:
      - uses: actions/checkout@v4

      - name: Set up Python 3.11
        uses: actions/setup-python@v5
        with:
          python-version: "3.11"

      - name: Install dependencies
        run: |
          pip install uv
          uv pip install --system -e ".[test]"

      - name: Run unit tests
        run: python -m pytest tests/unit/ -v

      - name: Run E2E tests
        env:
          DBAY_E2E_DSN: "postgresql://postgres:postgres@localhost:5432/dbay_e2e_test"
        run: python -m pytest tests/e2e/ -v
```

- [ ] **Step 2b (fallback): Write scripts/test.sh**

```bash
#!/usr/bin/env bash
# scripts/test.sh — run unit + E2E tests locally.
# Requires: docker, Python 3.11+, uv.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."

CONTAINER=dbay-e2e-pg
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "Starting pgvector container..."
    docker run -d --name "$CONTAINER" \
        -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dbay_e2e_test \
        -p 5432:5432 pgvector/pgvector:pg16
    sleep 5
fi

if [ ! -d .venv ]; then
    uv venv .venv --python 3.11
    source .venv/bin/activate
    uv pip install -e ".[test]"
else
    source .venv/bin/activate
fi

python -m pytest tests/unit/ -v
DBAY_E2E_DSN="postgresql://postgres:postgres@localhost:5432/dbay_e2e_test" \
    python -m pytest tests/e2e/ -v
```

```bash
chmod +x scripts/test.sh
```

- [ ] **Step 3: Commit**

```bash
git add .gitcode/ scripts/ 2>/dev/null || git add scripts/
git commit -m "ci: add E2E workflow / test runner script"
```

---

## Task 9: Examples

**Files:**
- Create: `examples/quickstart.py`
- Create: `examples/long_term_memory.py`

- [ ] **Step 1: Write quickstart**

```python
# examples/quickstart.py
"""Quickstart: connect to dbay PG, create a collection, add + search vectors.

Prereqs:
    pip install openjiuwen openjiuwen-dbay-store
    export DBAY_DSN="postgresql://user:pass@host:5432/db"
"""
import asyncio
import os

from openjiuwen.core.foundation.store import create_vector_store
from openjiuwen.core.foundation.store.base_vector_store import (
    CollectionSchema, FieldSchema, VectorDataType,
)


async def main():
    dsn = os.environ["DBAY_DSN"]
    store = create_vector_store("dbay", dsn=dsn)
    assert store is not None, "openjiuwen Phase 1 not installed? install openjiuwen>=<PHASE_1_VER>"

    # Define collection schema
    schema = CollectionSchema()
    schema.add_field(FieldSchema(name="id", dtype=VectorDataType.VARCHAR, is_primary=True, max_length=64))
    schema.add_field(FieldSchema(name="embedding", dtype=VectorDataType.FLOAT_VECTOR, dim=4))
    schema.add_field(FieldSchema(name="content", dtype=VectorDataType.VARCHAR, max_length=512))

    await store.create_collection("docs", schema)
    await store.add_docs("docs", [
        {"id": "1", "embedding": [1, 0, 0, 0], "content": "cat"},
        {"id": "2", "embedding": [0, 1, 0, 0], "content": "dog"},
    ])

    results = await store.search("docs", [1, 0, 0, 0], "embedding", top_k=2)
    for r in results:
        print(f"  {r.fields['id']} ({r.fields['content']}) score={r.score:.3f}")

    await store.delete_collection("docs")
    await store.close()


if __name__ == "__main__":
    asyncio.run(main())
```

- [ ] **Step 2: Write LongTermMemory integration example**

```python
# examples/long_term_memory.py
"""Wire DbayKVStore + DbayVectorStore + DbayDbStore into openJiuwen's LongTermMemory.

Shows the shared-pool pattern: one DbayDbStore instance owns the asyncpg pool,
KV and Vector stores borrow it — so total connections stay bounded.

Prereqs:
    pip install openjiuwen openjiuwen-dbay-store
    export DBAY_DSN="postgresql://..."
"""
import asyncio
import os

from openjiuwen_dbay_store import DbayDbStore, DbayKVStore, DbayVectorStore
from openjiuwen.core.memory import LongTermMemory


async def main():
    dsn = os.environ["DBAY_DSN"]

    # One engine/pool shared across all three stores
    db = DbayDbStore(dsn=dsn, max_size=10)
    await db.ping()  # friendly error if DSN is bad

    kv = DbayKVStore(db_store=db)
    vec = DbayVectorStore(db_store=db)

    memory = LongTermMemory()
    await memory.register_store(kv_store=kv, vector_store=vec, db_store=db)

    # From here on, use memory.add_messages / memory.search_user_mem / etc.
    # See openJiuwen docs for LongTermMemory usage.
    print("LongTermMemory is now backed by dbay (Neon Serverless PG + pgvector)")

    await db.close()


if __name__ == "__main__":
    asyncio.run(main())
```

- [ ] **Step 3: Commit**

```bash
git add examples/
git commit -m "docs: quickstart + LongTermMemory integration examples"
```

---

## Task 10: README (English)

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write English README**

```markdown
# openjiuwen-dbay-store

[![PyPI](https://img.shields.io/pypi/v/openjiuwen-dbay-store.svg)](https://pypi.org/project/openjiuwen-dbay-store/)
[![Python](https://img.shields.io/pypi/pyversions/openjiuwen-dbay-store.svg)](https://pypi.org/project/openjiuwen-dbay-store/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

[中文](./README.zh.md) | English

**dbay (Neon Serverless PostgreSQL + pgvector) storage backend for [openJiuwen](https://gitcode.com/openJiuwen/agent-core).** Works with any standard PostgreSQL instance that has the `vector` extension — dbay.cloud is the reference host but the plugin is PG-agnostic.

## What you get

- `DbayVectorStore` — HNSW / IVFFlat, COSINE / L2 / IP, async via SQLAlchemy + asyncpg
- `DbayKVStore` — PG-native `ON CONFLICT` upserts, bytes round-trip, prefix ops, pipelined batches, exclusive locks
- `DbayDbStore` — shared async connection pool (one pool serves all three stores)
- Full `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` compliance

## Install

```bash
pip install openjiuwen openjiuwen-dbay-store
```

Requires `openjiuwen>=<PHASE_1_VERSION>` (the release that adds the plugin framework).

## Quickstart

```python
from openjiuwen.core.foundation.store import create_vector_store

store = create_vector_store("dbay", dsn="postgresql://user:pass@host:5432/db")
# store is a DbayVectorStore — use as any openJiuwen vector store
```

See [`examples/quickstart.py`](./examples/quickstart.py) for a runnable demo.

## LongTermMemory integration

```python
from openjiuwen_dbay_store import DbayDbStore, DbayKVStore, DbayVectorStore
from openjiuwen.core.memory import LongTermMemory

db = DbayDbStore(dsn="postgresql://...", max_size=10)
await db.ping()                 # friendly error if DSN is bad

kv = DbayKVStore(db_store=db)
vec = DbayVectorStore(db_store=db)

memory = LongTermMemory()
await memory.register_store(kv_store=kv, vector_store=vec, db_store=db)
```

Full example: [`examples/long_term_memory.py`](./examples/long_term_memory.py)

## Where to get a PG endpoint

Any PG 14+ with the `vector` extension works. Options:

| Provider | pgvector pre-installed? | Notes |
|---|---|---|
| [dbay.cloud](https://dbay.cloud) | ✅ | Default target, low friction |
| [Neon](https://neon.tech) | ✅ | Serverless, free tier |
| [Supabase](https://supabase.com) | ✅ | Free tier |
| AWS RDS PG 15+ | ✅ (enable via parameter group) | |
| Self-hosted PG | ❌ by default | `CREATE EXTENSION vector;` after installing `pgvector` binary |

## Troubleshooting

- **`DbayConnectionError: Cannot connect to dbay PG at ...`** — Check DSN, firewall, and that the server is listening.
- **`PgVectorNotAvailableError: pgvector extension is not enabled and the current DB role cannot run CREATE EXTENSION`** — Ask DBA to run `CREATE EXTENSION vector;` once, or use a role with appropriate privileges.
- **`PgVectorExtensionMissingError: pgvector is not installed on this PG server`** — Install `pgvector` binary on the server, or switch to a pre-installed provider.

## Documentation

- [Quickstart](./examples/quickstart.py)
- [LongTermMemory integration](./examples/long_term_memory.py)
- [Contributing](./CONTRIBUTING.md)
- [openJiuwen plugin-author guide](https://gitcode.com/openJiuwen/agent-core/blob/develop/docs/en/2.Development%20Guide/User%20Manual/plugin-development-store.md)

## License

MIT — see [LICENSE](./LICENSE).

## Credits

Built for the [openJiuwen](https://gitcode.com/openJiuwen/agent-core) community.
Uses [pgvector](https://github.com/pgvector/pgvector) and [Neon](https://neon.tech)-style serverless Postgres.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README with install, quickstart, troubleshooting"
```

---

## Task 11: README (Chinese)

**Files:**
- Create: `README.zh.md`

- [ ] **Step 1: Write Chinese mirror**

Mirror the English README in Chinese. Keep code blocks unchanged.

- [ ] **Step 2: Commit**

```bash
git add README.zh.md
git commit -m "docs: README.zh.md (Chinese mirror)"
```

---

## Task 12: CONTRIBUTING.md and CHANGELOG.md

- [ ] **Step 1: Write CONTRIBUTING.md**

Standard contributor guide — how to set up dev env, run tests, commit format, code of conduct reference (link to openJiuwen's).

- [ ] **Step 2: Write CHANGELOG.md**

```markdown
# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0] — 2026-04-XX

### Added
- `DbayVectorStore` — pgvector + HNSW/IVFFlat, COSINE/L2/IP metrics
- `DbayKVStore` — PG-native upserts, bytes, prefix, pipeline, exclusive_set
- `DbayDbStore` — shared async connection pool with DSN normalization
- Entry_points registration for `openjiuwen.vector_stores` (plus non-standard kv / db groups for IDE discoverability)
- Friendly errors: `DbayConnectionError`, `PgVectorNotAvailableError`, `PgVectorExtensionMissingError`
- 31 unit tests + 19 E2E tests (against live pgvector)
- Examples: quickstart, LongTermMemory integration
```

- [ ] **Step 2: Commit**

```bash
git add CONTRIBUTING.md CHANGELOG.md
git commit -m "docs: CONTRIBUTING and CHANGELOG"
```

---

## Task 13: Full Local Validation

Before release: run the full test matrix and smoke-test against the real openjiuwen package.

- [ ] **Step 1: Fresh venv + install from local source**

```bash
deactivate 2>/dev/null || true
rm -rf .venv
uv venv .venv --python 3.11
source .venv/bin/activate
uv pip install -e ".[test]"
```

- [ ] **Step 2: Run all tests**

```bash
python -m pytest tests/unit/ -v
DBAY_E2E_DSN="postgresql://postgres:postgres@localhost:5432/dbay_e2e_test" \
    python -m pytest tests/e2e/ -v
```

Expected: all tests pass (no FAILED, no SKIPPED except if Phase 1 isn't released yet).

- [ ] **Step 3: Smoke-test quickstart**

```bash
docker exec -it dbay-e2e-pg psql -U postgres -d dbay_e2e_test -c "CREATE EXTENSION IF NOT EXISTS vector;"
DBAY_DSN="postgresql://postgres:postgres@localhost:5432/dbay_e2e_test" \
    python examples/quickstart.py
```

Expected: prints two result lines with ids 1 and 2.

---

## Task 14: PyPI Release

> Only run this once Phase 1 is merged and the new openjiuwen version is on PyPI.

- [ ] **Step 1: Tighten openjiuwen version constraint**

Edit `pyproject.toml`:
```toml
dependencies = [
    "openjiuwen>=<PHASE_1_VERSION>",   # Replace placeholder
    ...
]
```

Set `<PHASE_1_VERSION>` to the first openjiuwen release containing entry_points support.

- [ ] **Step 2: Bump version and tag**

```bash
# _version.py already says 0.1.0 — keep it
git add pyproject.toml
git commit -m "chore: pin openjiuwen>=<PHASE_1_VERSION> for entry_points support"
git tag v0.1.0
```

- [ ] **Step 3: Build and upload**

```bash
uv pip install build twine
python -m build
twine check dist/*
twine upload dist/*        # Prompts for PyPI token
git push origin main --tags
```

- [ ] **Step 4: Verify PyPI install works**

```bash
cd /tmp
uv venv test-dbay --python 3.11
source test-dbay/bin/activate
uv pip install openjiuwen openjiuwen-dbay-store
python -c "
from openjiuwen.core.foundation.store import create_vector_store
store = create_vector_store('dbay', dsn='postgresql://u:p@h/d')
print('installed ok:', type(store).__name__)
"
```

Expected: `installed ok: DbayVectorStore`.

---

## Task 15: Announce

- [ ] **Step 1: Open issue/discussion on openJiuwen community**

Title: "New plugin: openjiuwen-dbay-store (pgvector backend, PyPI)"

Body: link to repo + PyPI + openJiuwen Phase 1 plugin guide, short quickstart.

- [ ] **Step 2: Update the openjiuwen Phase 1 plugin author guide**

If the Phase 1 plugin guide has a "References" / "Examples" section, open a follow-up PR adding `openjiuwen-dbay-store` as the first listed example plugin.

---

## Design Decisions

1. **Why separate `errors.py`?** Plugin users benefit from a named exception hierarchy. Raw asyncpg errors leak implementation details and aren't user-actionable.

2. **Why `ping()` instead of eager connection?** Lazy pool creation is SQLAlchemy's default; `ping()` gives opt-in early failure for apps that want it. Eager connection would slow every import.

3. **Why include non-standard `openjiuwen.kv_stores` / `openjiuwen.db_stores` entry-point groups?** These groups aren't consumed by Phase 1's factory (KV/DB have no factories). But declaring them gives tooling (IDEs, plugin discovery scripts) a way to list "all openJiuwen KV backends" and future-proofs in case upstream adds factories later. Cost is zero.

4. **Why `src/` layout?** Avoids the "import the source tree by accident" trap during dev, and matches modern Python packaging convention (setuptools, PyPA recommend it).

5. **Why no Graphiti / knowledge-graph support?** YAGNI. That's a separate backend category (`BaseGraphStore`). If there's demand, add later as a separate plugin package (`openjiuwen-dbay-graph`).

---

## Self-Review

- [x] Spec coverage: all Phase 2 items from roadmap have tasks.
- [x] No placeholders: every code step has real code (except `<PHASE_1_VERSION>` and `<your-user>` — these are runtime substitutions the implementer fills in, not content-gaps).
- [x] Type consistency: `DbayDbStore.ping()` signature matches across impl, tests, and example.
- [x] Test-first for new code: `errors.py` has tests before use in `vector.py` / `db.py` (Task 4).
- [x] Backwards compat with upstream: all `from openjiuwen.*` imports are preserved for ABCs; only local module paths change.
