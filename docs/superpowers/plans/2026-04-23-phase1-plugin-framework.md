# Phase 1: openJiuwen Vector Store Plugin Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add plugin discovery mechanism to `openjiuwen.core.foundation.store.create_vector_store()` so third-party packages can register vector-store backends via Python entry_points or explicit API, without modifying openJiuwen core code and without breaking any existing built-in backend.

**Architecture:** Three-level resolution in the factory — (1) check built-in registry (chroma/milvus/gaussvector — unchanged), (2) check in-process explicit registrations, (3) scan `openjiuwen.vector_stores` entry_points group. Broken plugins log a warning and continue (never break the factory for a 3rd-party bug). A new `register_vector_store(name, factory)` public API supports programmatic registration in app init code. `BaseVectorStore` gets a "stable public plugin API" docstring block to lock the contract.

**Tech Stack:** Python 3.11+, `importlib.metadata.entry_points` (stdlib), pytest, unittest.mock.

---

## Pre-flight

- [ ] **Pre-flight 1: Verify baseline on develop**

```bash
cd ~/code/agent-core
git checkout develop
git pull origin develop
source .venv/bin/activate  # or create if absent
PYTHONPATH=. python -c "from openjiuwen.core.foundation.store import create_vector_store; print(create_vector_store('unknown'))"
```

Expected: prints `None` (current behavior for unknown type).

- [ ] **Pre-flight 2: Create feature branch**

```bash
git checkout -b feat/plugin-registry
git log --oneline -1
```

Expected: branch created from `develop` HEAD, no commits yet on this branch.

- [ ] **Pre-flight 3: Confirm no existing factory tests**

```bash
grep -rn "test.*create_vector_store" tests/ --include='*.py' | grep -v "__pycache__"
```

Expected: only matches in `test_knowledge_retrieval_comp.py` (they patch it but don't test the factory itself). We're adding the first real factory tests.

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `openjiuwen/core/foundation/store/__init__.py` | Add 3-level resolution in `create_vector_store`; add `register_vector_store` API |
| Modify | `openjiuwen/core/foundation/store/base_vector_store.py` | Add "stable plugin API" docstring block to `BaseVectorStore` ABC |
| Create | `tests/unit_tests/core/foundation/store/test_vector_store_plugin.py` | 6 unit tests for plugin framework |
| Create | `docs/zh/2.开发指南/使用手册/插件开发-存储后端.md` | Plugin author guide (中文) |
| Create | `docs/en/2.Development Guide/User Manual/plugin-development-store.md` | Plugin author guide (English) |

---

## Task 1: Baseline Regression Tests (Built-ins Unchanged)

> Lock in current behavior first. Any plugin-framework change must keep these green.

**Files:**
- Create: `tests/unit_tests/core/foundation/store/test_vector_store_plugin.py`

- [ ] **Step 1: Create the test file with baseline regression tests**

Full file content:

```python
# coding: utf-8
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""
Unit tests for the vector-store factory plugin framework.

These tests have two concerns:
  1. Regression — built-in backends (chroma/milvus/gaussvector) still resolve the
     same way they did before the plugin framework landed.
  2. Plugin framework — explicit registration and entry_points discovery both
     work, name collisions are resolved deterministically, and a broken plugin
     never crashes the factory.
"""
import sys
from unittest.mock import MagicMock, patch

import pytest

from openjiuwen.core.foundation.store import create_vector_store
from openjiuwen.core.foundation.store.base_vector_store import BaseVectorStore


class _FakeVectorStore(BaseVectorStore):
    """Minimal BaseVectorStore impl used as a test plugin."""

    def __init__(self, **kwargs):
        self.init_kwargs = kwargs

    async def create_collection(self, collection_name, schema, **kwargs): pass
    async def delete_collection(self, collection_name, **kwargs): pass
    async def collection_exists(self, collection_name, **kwargs): return False
    async def get_schema(self, collection_name, **kwargs): raise NotImplementedError
    async def add_docs(self, collection_name, docs, **kwargs): pass
    async def search(self, collection_name, query_vector, vector_field, top_k=5, filters=None, **kwargs): return []
    async def delete_docs_by_ids(self, collection_name, ids, **kwargs): pass
    async def delete_docs_by_filters(self, collection_name, filters, **kwargs): pass
    async def list_collection_names(self): return []
    async def get_collection_metadata(self, collection_name): return {}
    async def update_collection_metadata(self, collection_name, metadata): pass
    async def update_schema(self, collection_name, operations): pass


class TestBuiltinRegression:
    """Built-in backends must resolve identically to pre-plugin behavior."""

    def test_unknown_returns_none(self):
        assert create_vector_store("this_backend_does_not_exist") is None

    def test_chroma_dispatches_to_chroma_class(self):
        with patch(
            "openjiuwen.core.foundation.store.vector.chroma_vector_store.ChromaVectorStore"
        ) as MockChroma:
            create_vector_store("chroma", persist_directory="/tmp/x")
            MockChroma.assert_called_once_with(persist_directory="/tmp/x")

    def test_milvus_dispatches_to_milvus_class(self):
        with patch(
            "openjiuwen.core.foundation.store.vector.milvus_vector_store.MilvusVectorStore"
        ) as MockMilvus:
            create_vector_store("milvus", uri="http://localhost:19530")
            MockMilvus.assert_called_once_with(uri="http://localhost:19530")

    def test_gaussvector_dispatches_to_gauss_class(self):
        with patch(
            "openjiuwen.core.foundation.store.vector.gauss_vector_store.GaussVectorStore"
        ) as MockGauss:
            create_vector_store("gaussvector", host="h", port=5432)
            MockGauss.assert_called_once_with(host="h", port=5432)
```

- [ ] **Step 2: Run baseline tests (should PASS on develop before any refactor)**

```bash
cd ~/code/agent-core
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/test_vector_store_plugin.py::TestBuiltinRegression -v
```

Expected: 4 passed. These prove current factory behavior. If any fail, STOP — the develop branch state is not what we expect, diagnose before proceeding.

- [ ] **Step 3: Commit baseline regression tests**

```bash
git add tests/unit_tests/core/foundation/store/test_vector_store_plugin.py
git commit -m "test(store): add regression tests for built-in vector-store factory dispatch"
```

---

## Task 2: Failing Tests for Plugin Framework

> Write tests that describe the new feature — they should FAIL now, PASS after Task 3.

**Files:**
- Modify: `tests/unit_tests/core/foundation/store/test_vector_store_plugin.py` (append 3 new test classes)

- [ ] **Step 1: Append failing tests for plugin framework**

Append to end of the test file:

```python
class TestExplicitRegistration:
    """`register_vector_store(name, factory)` adds a new backend at runtime."""

    def setup_method(self):
        # Import lazily; module may not yet expose these symbols before Task 3
        from openjiuwen.core.foundation import store as store_mod
        self._mod = store_mod
        # Snapshot the registry so each test is isolated
        self._snapshot = dict(getattr(store_mod, "_CUSTOM_VECTOR_STORES", {}))

    def teardown_method(self):
        if hasattr(self._mod, "_CUSTOM_VECTOR_STORES"):
            self._mod._CUSTOM_VECTOR_STORES.clear()
            self._mod._CUSTOM_VECTOR_STORES.update(self._snapshot)

    def test_register_then_create(self):
        from openjiuwen.core.foundation.store import register_vector_store
        register_vector_store("test_fake", _FakeVectorStore)

        store = create_vector_store("test_fake", dsn="x")
        assert isinstance(store, _FakeVectorStore)
        assert store.init_kwargs == {"dsn": "x"}

    def test_register_does_not_shadow_builtin(self):
        """A plugin MUST NOT be able to override a built-in by re-registering its name."""
        from openjiuwen.core.foundation.store import register_vector_store
        register_vector_store("chroma", _FakeVectorStore)

        with patch(
            "openjiuwen.core.foundation.store.vector.chroma_vector_store.ChromaVectorStore"
        ) as MockChroma:
            create_vector_store("chroma")
            # Built-in still wins
            MockChroma.assert_called_once()


class TestEntryPointsDiscovery:
    """Third-party packages can register via the `openjiuwen.vector_stores` entry_points group."""

    def test_entry_point_is_discovered(self):
        # Build a fake EntryPoint whose .load() returns _FakeVectorStore
        fake_ep = MagicMock()
        fake_ep.name = "test_ep_fake"
        fake_ep.load.return_value = _FakeVectorStore

        with patch(
            "openjiuwen.core.foundation.store.entry_points"
        ) as mock_eps:
            mock_eps.return_value = [fake_ep]
            store = create_vector_store("test_ep_fake", foo="bar")

        assert isinstance(store, _FakeVectorStore)
        assert store.init_kwargs == {"foo": "bar"}

    def test_entry_point_load_error_is_swallowed(self):
        """A plugin that fails to import must log a warning and NOT crash the factory."""
        broken_ep = MagicMock()
        broken_ep.name = "broken"
        broken_ep.load.side_effect = ImportError("fake import failure")

        with patch(
            "openjiuwen.core.foundation.store.entry_points"
        ) as mock_eps:
            mock_eps.return_value = [broken_ep]
            # Factory must return None for the broken plugin, not raise
            result = create_vector_store("broken")

        assert result is None

    def test_builtin_wins_over_entry_point(self):
        """If a 3rd-party plugin claims a built-in name, the built-in wins."""
        fake_ep = MagicMock()
        fake_ep.name = "chroma"
        fake_ep.load.return_value = _FakeVectorStore

        with patch(
            "openjiuwen.core.foundation.store.entry_points"
        ) as mock_eps, patch(
            "openjiuwen.core.foundation.store.vector.chroma_vector_store.ChromaVectorStore"
        ) as MockChroma:
            mock_eps.return_value = [fake_ep]
            create_vector_store("chroma")
            MockChroma.assert_called_once()


class TestEntryPointsGroupName:
    """Lock in the entry_points group name — once published, it cannot change."""

    def test_group_name_is_documented_constant(self):
        from openjiuwen.core.foundation.store import VECTOR_STORE_ENTRY_POINT_GROUP
        assert VECTOR_STORE_ENTRY_POINT_GROUP == "openjiuwen.vector_stores"
```

- [ ] **Step 2: Run new tests, verify they FAIL**

```bash
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/test_vector_store_plugin.py -v
```

Expected:
- 4 PASSED (baseline)
- 6 FAILED / ERRORED with variants of "ImportError: cannot import name 'register_vector_store'" and "ImportError: cannot import name 'VECTOR_STORE_ENTRY_POINT_GROUP'"

This is expected — the symbols don't exist yet.

- [ ] **Step 3: Commit failing tests**

```bash
git add tests/unit_tests/core/foundation/store/test_vector_store_plugin.py
git commit -m "test(store): add failing tests for vector-store plugin framework

Tests describe:
  - register_vector_store() public API (explicit in-process registration)
  - entry_points discovery from 'openjiuwen.vector_stores' group
  - Built-in names win over plugin-registered names
  - Broken plugin load is swallowed (returns None, doesn't raise)
  - Group-name constant is exported

Implementation in next commit."
```

---

## Task 3: Implement Plugin Framework in Factory

**Files:**
- Modify: `openjiuwen/core/foundation/store/__init__.py`

- [ ] **Step 1: Replace `create_vector_store` with 3-level resolution**

Open `openjiuwen/core/foundation/store/__init__.py`. Replace the entire `create_vector_store` function (currently lines 26-41) and add supporting registry code. Final file should have this structure — paste the complete new factory + registry block right after the existing imports (before `__all__`):

```python
import logging
from importlib.metadata import entry_points
from typing import Callable

_logger = logging.getLogger(__name__)

# Entry-points group name for 3rd-party vector-store plugins.
# STABLE PUBLIC API: published plugins declare this group in their
# pyproject.toml. Changing this string breaks every external plugin.
VECTOR_STORE_ENTRY_POINT_GROUP = "openjiuwen.vector_stores"

# Built-in backends. Closed to extension by design — use register_vector_store
# or the entry_points mechanism for 3rd-party backends.
_BUILTIN_VECTOR_STORE_NAMES = frozenset({"chroma", "milvus", "gaussvector"})

# Explicit in-process registrations (register_vector_store).
# Maps backend name -> factory callable (typically a class).
_CUSTOM_VECTOR_STORES: dict[str, Callable[..., "BaseVectorStore"]] = {}


def register_vector_store(
    name: str, factory: Callable[..., "BaseVectorStore"]
) -> None:
    """
    Register a vector-store backend at runtime for programmatic use.

    Use this in application init code when shipping a plugin via
    entry_points is not practical (e.g., private in-repo backend).

    Built-in names (chroma, milvus, gaussvector) cannot be overridden — a
    register call with a built-in name is kept in the registry but the
    built-in still wins in `create_vector_store()` resolution.

    Args:
        name: Backend identifier used in `create_vector_store(name, ...)`.
        factory: Callable that accepts **kwargs and returns a BaseVectorStore.
                 Typically a class, but any callable is allowed.

    Thread-safety: not thread-safe. Call during app init, before any worker
    threads start.
    """
    _CUSTOM_VECTOR_STORES[name] = factory


def _resolve_builtin(store_type: str, kwargs: dict) -> "BaseVectorStore | None":
    """Built-in backends are hard-coded to keep import costs tight and
    behavior completely stable across releases."""
    if store_type == "chroma":
        from openjiuwen.core.foundation.store.vector.chroma_vector_store import ChromaVectorStore
        return ChromaVectorStore(**kwargs)
    if store_type == "milvus":
        from openjiuwen.core.foundation.store.vector.milvus_vector_store import MilvusVectorStore
        return MilvusVectorStore(**kwargs)
    if store_type == "gaussvector":
        from openjiuwen.core.foundation.store.vector.gauss_vector_store import GaussVectorStore
        return GaussVectorStore(**kwargs)
    return None


def _resolve_entry_point(store_type: str, kwargs: dict) -> "BaseVectorStore | None":
    """Scan `openjiuwen.vector_stores` entry_points for a matching plugin.

    A plugin load failure (ImportError / any exception from `.load()`) is
    logged and turns into a None result, so a broken third-party wheel
    cannot break the factory for everyone.
    """
    try:
        eps = entry_points(group=VECTOR_STORE_ENTRY_POINT_GROUP)
    except Exception as e:  # noqa: BLE001 — stdlib may raise on broken metadata
        _logger.warning("Failed to enumerate entry_points for %s: %s",
                        VECTOR_STORE_ENTRY_POINT_GROUP, e)
        return None

    for ep in eps:
        if ep.name != store_type:
            continue
        try:
            cls = ep.load()
        except Exception as e:  # noqa: BLE001 — any plugin import failure
            _logger.warning(
                "Failed to load vector-store plugin '%s' (entry point %r): %s. "
                "Install/update the plugin package or uninstall it to silence this warning.",
                store_type, ep, e,
            )
            return None
        try:
            return cls(**kwargs)
        except Exception as e:  # noqa: BLE001 — plugin constructor failed
            _logger.warning(
                "Vector-store plugin '%s' loaded but failed to instantiate: %s",
                store_type, e,
            )
            return None
    return None


def create_vector_store(store_type: str, **kwargs) -> "BaseVectorStore | None":
    """Factory for vector-store backends.

    Resolution order:
      1. Built-in (chroma, milvus, gaussvector) — always wins, closed set.
      2. Explicit registrations via `register_vector_store()`.
      3. Entry_points in group `openjiuwen.vector_stores`.

    Returns None if none match. A plugin that fails to load or instantiate
    is logged as a warning and treated as "no match".
    """
    if store_type in _BUILTIN_VECTOR_STORE_NAMES:
        return _resolve_builtin(store_type, kwargs)

    if store_type in _CUSTOM_VECTOR_STORES:
        return _CUSTOM_VECTOR_STORES[store_type](**kwargs)

    return _resolve_entry_point(store_type, kwargs)
```

Then update `__all__` to export the new public symbols — change:

```python
__all__ = [
    "BaseKVStore",
    ...
    "create_vector_store",
    ...
]
```

to:

```python
__all__ = [
    "BaseKVStore",
    ...
    "create_vector_store",
    "register_vector_store",
    "VECTOR_STORE_ENTRY_POINT_GROUP",
    ...
]
```

- [ ] **Step 2: Run all plugin tests — expect PASS**

```bash
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/test_vector_store_plugin.py -v
```

Expected: 10 passed (4 baseline + 6 new). If any fail, debug; do not proceed.

- [ ] **Step 3: Run the entire store test subtree — no regressions**

```bash
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/ -v
```

Expected: all previously-passing tests still pass. Any newly failing test is a regression — fix before moving on.

- [ ] **Step 4: Commit implementation**

```bash
git add openjiuwen/core/foundation/store/__init__.py
git commit -m "feat(store): pluggable vector-store backends via entry_points + register API

Adds three-level resolution in create_vector_store():
  1. Built-in (chroma/milvus/gaussvector) — unchanged, always wins
  2. Explicit register_vector_store(name, factory) for in-process plugins
  3. entry_points(group='openjiuwen.vector_stores') for 3rd-party wheels

Broken plugins (import error, instantiation error, or malformed metadata)
are logged at WARNING level and resolved to None — the factory never
raises for a plugin bug."
```

---

## Task 4: Stable Public Plugin ABC Docstrings

> Lock in that `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` are treated as stable public APIs for plugin authors. No code change — docstring only.

**Files:**
- Modify: `openjiuwen/core/foundation/store/base_vector_store.py`
- Modify: `openjiuwen/core/foundation/store/base_kv_store.py`
- Modify: `openjiuwen/core/foundation/store/base_db_store.py`

- [ ] **Step 1: Add plugin-API docstring to BaseVectorStore**

Open `openjiuwen/core/foundation/store/base_vector_store.py`. Find `class BaseVectorStore(ABC):` (around line 257). Replace its docstring (or add one if absent) with:

```python
class BaseVectorStore(ABC):
    """
    Abstract base class for all vector-store backends.

    **Plugin authoring**: This class is a stable public API. Third-party
    packages MAY subclass this and register via the ``openjiuwen.vector_stores``
    entry_points group or ``openjiuwen.core.foundation.store.register_vector_store``.

    Plugin contract:
      - Implement every abstract method declared below.
      - Every method MUST be async-compatible (``async def``).
      - Constructor kwargs are plugin-defined and documented in the plugin's
        own docs; ``create_vector_store(name, **kwargs)`` forwards them verbatim.
      - The plugin owns its own connection / resource lifecycle; the
        factory does not call ``close()`` for you. Provide ``close()``
        yourself and document when callers must invoke it.

    Compatibility: breaking changes to this ABC are announced at least one
    minor release ahead of the change. Plugin authors should pin the
    openjiuwen minor version they were built against.
    """
```

(Preserve any existing implementation methods below the docstring.)

- [ ] **Step 2: Apply the same pattern to BaseKVStore and BaseDbStore**

Open `openjiuwen/core/foundation/store/base_kv_store.py`. Find `class BaseKVStore(ABC):` and set its docstring to:

```python
class BaseKVStore(ABC):
    """
    Abstract base class for all KV-store backends.

    **Plugin authoring**: Stable public API. Third-party packages may
    subclass this and export the class directly from their package;
    callers import and instantiate the class directly (there is no
    ``create_kv_store`` factory — KV stores are used via direct import,
    not name-based lookup).

    See BaseVectorStore for the plugin contract and compatibility policy;
    the same rules apply here.
    """
```

Open `openjiuwen/core/foundation/store/base_db_store.py`. Find `class BaseDbStore(ABC):` and set its docstring to:

```python
class BaseDbStore(ABC):
    """
    Abstract base class for raw DB access (returns a SQLAlchemy AsyncEngine).

    **Plugin authoring**: Stable public API. Same rules as BaseKVStore —
    used via direct import, no factory.

    See BaseVectorStore for the plugin contract and compatibility policy.
    """
```

- [ ] **Step 3: Verify nothing broke**

```bash
PYTHONPATH=. .venv/bin/python -c "
from openjiuwen.core.foundation.store.base_vector_store import BaseVectorStore
from openjiuwen.core.foundation.store.base_kv_store import BaseKVStore
from openjiuwen.core.foundation.store import BaseDbStore
print(BaseVectorStore.__doc__[:60])
print(BaseKVStore.__doc__[:60])
print(BaseDbStore.__doc__[:60])
"
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/ -v
```

Expected: docstrings print with "Abstract base class..." prefix; all tests still pass.

- [ ] **Step 4: Commit**

```bash
git add openjiuwen/core/foundation/store/base_vector_store.py \
        openjiuwen/core/foundation/store/base_kv_store.py \
        openjiuwen/core/foundation/store/base_db_store.py
git commit -m "docs(store): declare Base*Store ABCs as stable public plugin APIs

Adds 'Plugin authoring' docstring sections to BaseVectorStore, BaseKVStore,
and BaseDbStore explaining the stability contract and how to register a
plugin. No code change."
```

---

## Task 5: Plugin Author Guide — Chinese

**Files:**
- Create: `docs/zh/2.开发指南/使用手册/插件开发-存储后端.md`

- [ ] **Step 1: Create the guide**

Write the complete file with this content:

```markdown
# 开发存储后端插件

openJiuwen 的存储层支持通过 Python 标准 entry_points 机制接入第三方 backend，无需修改核心代码。本文说明如何发布一个 vector store 插件，并指出 KV / DB store 的接入方式。

## 概念

openJiuwen 的存储层分三类：

| 类型 | 抽象类 | 工厂 | 接入方式 |
|------|--------|------|----------|
| Vector | `BaseVectorStore` | `create_vector_store(name, **kwargs)` | entry_points 或显式 `register_vector_store()` |
| KV | `BaseKVStore` | 无 | 用户代码直接 `from X import Y` 实例化 |
| DB | `BaseDbStore` | 无 | 用户代码直接 `from X import Y` 实例化 |

Vector store 之所以有工厂，是因为上层组件（如 KnowledgeRetrieval）需要按名字动态创建。KV / DB 通常是应用代码直接持有的组件，无需名字解析。

## 写一个 vector store 插件

### 1. 继承 BaseVectorStore

```python
# my_package/my_vector_store.py
from openjiuwen.core.foundation.store.base_vector_store import (
    BaseVectorStore, CollectionSchema, VectorSearchResult,
)

class MyVectorStore(BaseVectorStore):
    def __init__(self, connection_uri: str, **kwargs):
        self._uri = connection_uri

    async def create_collection(self, collection_name, schema, **kwargs):
        ...  # 实现所有抽象方法
```

完整接口见 `openjiuwen/core/foundation/store/base_vector_store.py`。插件必须实现所有 `@abstractmethod`。

### 2. 在 pyproject.toml 声明 entry_point

```toml
[project]
name = "my-openjiuwen-vector"
dependencies = ["openjiuwen>=0.1.9"]

[project.entry-points."openjiuwen.vector_stores"]
my_backend = "my_package.my_vector_store:MyVectorStore"
```

入口点格式：`name = "module.path:ClassName"`。`name` 是用户在 `create_vector_store(name, ...)` 里传的字符串。

### 3. 发 PyPI

```bash
python -m build
twine upload dist/*
```

### 4. 用户端

```bash
pip install openjiuwen my-openjiuwen-vector
```

```python
from openjiuwen.core.foundation.store import create_vector_store
store = create_vector_store("my_backend", connection_uri="...")
```

## 显式注册（适合私有后端）

如果你的 backend 不准备发到 PyPI，应用启动时手动注册：

```python
from openjiuwen.core.foundation.store import register_vector_store
from my_private_pkg.backend import PrivateBackend

register_vector_store("private", PrivateBackend)
# 之后 create_vector_store("private", ...) 就能用
```

## 名字冲突规则

解析顺序是 **built-in → 显式注册 → entry_points**。built-in 名（chroma / milvus / gaussvector）不可覆盖——插件用这些名字会被忽略。

## 错误处理

- 插件 `load()` 失败：记录 WARNING，`create_vector_store` 返回 `None`，不抛异常
- 插件构造器抛异常：记录 WARNING，返回 `None`
- 所以一个坏插件不会把整个工厂搞崩

## KV / DB 插件

KV / DB 没有工厂。写法：

```python
# my_package/my_kv_store.py
from openjiuwen.core.foundation.store.base_kv_store import BaseKVStore

class MyKVStore(BaseKVStore):
    async def set(self, key, value): ...
    async def get(self, key): ...
    # ... 其他抽象方法
```

用户端直接导入：

```python
from my_package.my_kv_store import MyKVStore
kv = MyKVStore(...)
long_term_memory.register_store(kv_store=kv, ...)
```

## 版本兼容

`Base*Store` ABC 被视为稳定公共 API。破坏性变更至少提前一个小版本宣告。建议插件在 `pyproject.toml` 里对 openjiuwen 小版本加 pin：

```toml
dependencies = ["openjiuwen>=0.2.0,<0.3"]
```

## 参考示例

- `openjiuwen-dbay-store` （Neon Serverless PG + pgvector）：https://gitcode.com/jacky-li/openjiuwen-dbay-store
```

- [ ] **Step 2: Verify doc builds (or at least renders in a markdown viewer)**

```bash
grep -c '^## ' docs/zh/2.开发指南/使用手册/插件开发-存储后端.md
```

Expected: 6 (六个二级标题)。

- [ ] **Step 3: Commit**

```bash
git add docs/zh/2.开发指南/使用手册/插件开发-存储后端.md
git commit -m "docs(zh): plugin author guide for vector-store backends"
```

---

## Task 6: Plugin Author Guide — English

**Files:**
- Create: `docs/en/2.Development Guide/User Manual/plugin-development-store.md`

- [ ] **Step 1: Create the English guide**

Write the English mirror. Full content:

```markdown
# Developing Store-Backend Plugins

openJiuwen's store layer supports third-party backends via Python's standard entry_points mechanism — no core-code modification needed. This document covers how to publish a vector-store plugin and how KV / DB stores are integrated.

## Concepts

| Type | ABC | Factory | Integration |
|------|-----|---------|-------------|
| Vector | `BaseVectorStore` | `create_vector_store(name, **kwargs)` | entry_points or explicit `register_vector_store()` |
| KV | `BaseKVStore` | none | Direct `from X import Y` + instantiate |
| DB | `BaseDbStore` | none | Direct `from X import Y` + instantiate |

Vector stores have a factory because higher-level components (e.g. KnowledgeRetrieval) create them by name. KV / DB stores are typically application-owned components; no name-based lookup is needed.

## Writing a Vector-Store Plugin

### 1. Subclass BaseVectorStore

```python
# my_package/my_vector_store.py
from openjiuwen.core.foundation.store.base_vector_store import (
    BaseVectorStore, CollectionSchema, VectorSearchResult,
)

class MyVectorStore(BaseVectorStore):
    def __init__(self, connection_uri: str, **kwargs):
        self._uri = connection_uri

    async def create_collection(self, collection_name, schema, **kwargs):
        ...  # Implement all abstract methods
```

Full interface: `openjiuwen/core/foundation/store/base_vector_store.py`. Implement every `@abstractmethod`.

### 2. Declare entry_point in pyproject.toml

```toml
[project]
name = "my-openjiuwen-vector"
dependencies = ["openjiuwen>=0.1.9"]

[project.entry-points."openjiuwen.vector_stores"]
my_backend = "my_package.my_vector_store:MyVectorStore"
```

Entry-point format: `name = "module.path:ClassName"`. The `name` is the string users pass to `create_vector_store(name, ...)`.

### 3. Publish to PyPI

```bash
python -m build
twine upload dist/*
```

### 4. User Side

```bash
pip install openjiuwen my-openjiuwen-vector
```

```python
from openjiuwen.core.foundation.store import create_vector_store
store = create_vector_store("my_backend", connection_uri="...")
```

## Explicit Registration (private backends)

If you don't plan to publish to PyPI, register at app startup:

```python
from openjiuwen.core.foundation.store import register_vector_store
from my_private_pkg.backend import PrivateBackend

register_vector_store("private", PrivateBackend)
# Now create_vector_store("private", ...) works
```

## Name Collision

Resolution order is **built-in → explicit registrations → entry_points**. Built-in names (chroma / milvus / gaussvector) cannot be overridden — plugins that claim those names are silently ignored in favor of the built-in.

## Error Handling

- Plugin `load()` fails: logged at WARNING, `create_vector_store` returns `None`, no exception.
- Plugin constructor raises: logged at WARNING, returns `None`.
- A broken plugin never crashes the factory for the whole application.

## KV / DB Plugins

KV / DB have no factory. Pattern:

```python
# my_package/my_kv_store.py
from openjiuwen.core.foundation.store.base_kv_store import BaseKVStore

class MyKVStore(BaseKVStore):
    async def set(self, key, value): ...
    async def get(self, key): ...
    # ... other abstract methods
```

User side imports directly:

```python
from my_package.my_kv_store import MyKVStore
kv = MyKVStore(...)
long_term_memory.register_store(kv_store=kv, ...)
```

## Compatibility

`Base*Store` ABCs are treated as stable public APIs. Breaking changes are announced at least one minor release in advance. Pin your plugin to an openjiuwen minor range:

```toml
dependencies = ["openjiuwen>=0.2.0,<0.3"]
```

## Reference

- `openjiuwen-dbay-store` (Neon Serverless PG + pgvector): https://gitcode.com/jacky-li/openjiuwen-dbay-store
```

- [ ] **Step 2: Commit**

```bash
git add "docs/en/2.Development Guide/User Manual/plugin-development-store.md"
git commit -m "docs(en): plugin author guide for vector-store backends"
```

---

## Task 7: Full Regression Run

- [ ] **Step 1: Run full unit-test suite for the store module**

```bash
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/core/foundation/store/ tests/unit_tests/core/component/test_knowledge_retrieval_comp.py -v
```

Expected: everything PASS. The `test_knowledge_retrieval_comp.py` tests use `create_vector_store` via patching — they should be unaffected.

- [ ] **Step 2: Run broader unit-test suite**

```bash
PYTHONPATH=. .venv/bin/python -m pytest tests/unit_tests/ -v --ignore=tests/unit_tests/core/foundation/store/test_dbay_vector_store.py --ignore=tests/unit_tests/extensions/store/test_dbay_db_store.py --ignore=tests/unit_tests/extensions/store/test_dbay_kv_store.py 2>&1 | tail -30
```

(Dbay tests are excluded because Phase 1 branch is based on `develop` — dbay files aren't here yet. The Phase 2 repo will carry those tests.)

Expected: no new failures compared to a clean `develop` run. If anything fails, investigate — it's a regression from the plugin framework changes.

- [ ] **Step 3: Verify importability with zero plugins installed**

```bash
PYTHONPATH=. .venv/bin/python -c "
from openjiuwen.core.foundation.store import (
    create_vector_store, register_vector_store, VECTOR_STORE_ENTRY_POINT_GROUP,
)
print('group:', VECTOR_STORE_ENTRY_POINT_GROUP)
print('unknown:', create_vector_store('this_is_nobody'))
print('chroma (import-only, no kwargs):', create_vector_store.__doc__ is not None)
"
```

Expected:
```
group: openjiuwen.vector_stores
unknown: None
chroma (import-only, no kwargs): True
```

---

## Task 8: Prepare PR

- [ ] **Step 1: Rebase on latest develop**

```bash
git fetch origin
git rebase origin/develop
```

Expected: clean rebase (no conflicts). If conflicts, resolve them and re-run Task 7 before continuing.

- [ ] **Step 2: Review commit history**

```bash
git log --oneline origin/develop..HEAD
```

Expected output (6 commits, chronological):

```
<sha> docs(en): plugin author guide for vector-store backends
<sha> docs(zh): plugin author guide for vector-store backends
<sha> docs(store): declare Base*Store ABCs as stable public plugin APIs
<sha> feat(store): pluggable vector-store backends via entry_points + register API
<sha> test(store): add failing tests for vector-store plugin framework
<sha> test(store): add regression tests for built-in vector-store factory dispatch
```

Each commit is small and reviewable on its own. Do NOT squash — this history helps maintainers review the TDD flow.

- [ ] **Step 3: Push to your gitcode fork**

Requires you to have forked `openJiuwen/agent-core` to your own gitcode account first. Replace `<your-user>` below.

```bash
git remote add fork https://gitcode.com/<your-user>/agent-core.git
git push fork feat/plugin-registry
```

- [ ] **Step 4: Sign the CLA if not already signed**

Follow instructions at `openJiuwen-ai/community` repo. Do this once, before opening any PR.

- [ ] **Step 5: Open the PR on gitcode**

PR target: `openJiuwen/agent-core` base `develop`, head `<your-user>:feat/plugin-registry`.

PR title: `feat(store): pluggable vector-store backends via entry_points`

PR description template:

```markdown
## Motivation

Current `create_vector_store()` requires in-tree code for every new backend.
This blocks third-party ecosystem growth — a Qdrant / Weaviate / Dbay author
cannot ship their backend as an independent package.

## What this PR does

1. Adds 3-level resolution in the factory: built-in → explicit registration
   → entry_points(`openjiuwen.vector_stores`).
2. New public API `register_vector_store(name, factory)` for programmatic
   in-process registration (useful for private backends).
3. Exports `VECTOR_STORE_ENTRY_POINT_GROUP` constant so plugin authors can
   reference the group name.
4. Declares `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` as stable
   public plugin APIs (docstring only, no code change).
5. Adds plugin author guide in zh + en.
6. 10 unit tests — 4 regression + 6 framework.

## What this PR does NOT do

- Does not introduce any new backend (no dbay, no qdrant).
- Does not change any existing backend behavior — all built-in tests pass unchanged.
- Does not add factories for KV / DB stores (YAGNI; users instantiate directly).

## Plugin ecosystem

The first external plugin using this mechanism is
[openjiuwen-dbay-store](https://gitcode.com/jacky-li/openjiuwen-dbay-store)
(Neon Serverless PG + pgvector). Its `pyproject.toml` uses:

    [project.entry-points."openjiuwen.vector_stores"]
    dbay = "openjiuwen_dbay_store.vector:DbayVectorStore"

## Compatibility

- No public API removed.
- Resolution order preserves built-in precedence — existing apps behave identically.
- Broken plugins log WARNING and are silently skipped, never propagate.

## Testing

- 10 new unit tests in `tests/unit_tests/core/foundation/store/test_vector_store_plugin.py`
- Full `tests/unit_tests/core/foundation/store/` suite passes
- `test_knowledge_retrieval_comp.py` (which patches `create_vector_store`) passes unchanged
```

- [ ] **Step 6: Wait for CI and maintainer review**

Respond to review comments with follow-up commits (don't force-push — each iteration is its own commit). Keep the TDD sequence visible in history.

---

## Design Decisions

1. **Why entry_points + explicit register, not just one?** Entry_points cover published-package flow (standard for ecosystem growth). Explicit register covers monorepo / private-backend flow where packaging a PyPI wheel is overkill. Both are ~10 lines; the cost of supporting both is negligible.

2. **Why built-ins win over plugins?** A plugin re-using a built-in name is almost certainly a mistake by the plugin author. Silently shadowing a built-in would cause hard-to-debug "my chroma behaves weirdly" reports. Making built-ins win is a safety rail.

3. **Why swallow plugin load errors?** One bad wheel in site-packages should not break `create_vector_store("milvus")`. Symmetric to how pytest / setuptools / pip handle broken plugins.

4. **Why no KV / DB factories?** The callers that build KV / DB stores are application-init code, which already knows which concrete class to instantiate. Adding a factory would be abstraction for its own sake — YAGNI.

5. **Why declare ABCs as "stable public"?** Plugin ecosystems need a contract. Without this declaration, plugin authors don't know if the next minor release will break them. "Stable with one-minor advance notice" is the industry-standard SLA (Flask, FastAPI, SQLAlchemy all do this).

6. **Why `openjiuwen.vector_stores` as group name?** Convention: `<package>.<plural>` — matches `setuptools.entry_points_conventions`, `flask.extensions`, `pytest.plugins`. Using a plural means we can later add `openjiuwen.kv_stores` / `openjiuwen.db_stores` when the need arises, without renaming.

---

## Self-Review

- [x] Spec coverage: all items in master-roadmap Phase 1 have tasks.
- [x] No placeholders: every code step has real code.
- [x] Type consistency: `register_vector_store(name, factory)` signature matches in tests, impl, and docs.
- [x] Test-first: Task 2 tests fail, Task 3 impl makes them pass.
- [x] Commit cadence: 6 commits, each reviewable independently.
- [x] Backwards compat: Task 1 locks in pre-change behavior as regression tests.
