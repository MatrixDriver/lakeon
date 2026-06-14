# dbay-sre-mcp Phase 1 Enhancement (0.1.0 → 0.2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `dbay-sre-mcp` 从 0.1.0 (4 个 log-only 工具) 升级到 0.2.0，新增 7 个工具覆盖**元数据/状态、数据一致性、任务健康、跨副本/跨服务** 4 类 SRE 场景。让 SRE agent 能直接回答 "为什么 tcph-bench 数据库唤醒失败" 这类需要 dbay 域知识的问题，不再瞎搜 log。Phase 1 后约 30-35% 已知 bug 类型可被 agent 自主发现 + 诊断。

**Architecture:**
- 0.1.0 已有 4 个工具 (`log_search` / `log_trace` / `log_errors` / `log_stats`) 签名零变化（向后兼容）
- 新工具**全部走 lakeon-api admin endpoints**（admin token 认证），不接 kubectl / 不持有 cluster RBAC，避免 Phase 0a 已明确的 ACL 工程
- 抽一层 `LakeonAdminClient`（httpx 薄封装）给所有新工具复用
- 每个工具的 fastmcp `description` 写 LLM-friendly 的"什么时候用我 / 什么时候不用我 / 参数说明 / 返回结构"——这是 agent 不瞎搜的关键
- TDD 全程：fastmcp 自带 test mode，新工具用 httpx mock + fixture 化的 fake admin server

**Tech Stack:** fastmcp >= 2.0、httpx（新增）、pytest、psycopg2 (已有)、lakeon-api admin REST。

**Related:**
- [`docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`](../specs/2026-04-23-agent-commit-log-phase0-design.md)
- 上一个 plan：[`2026-04-24-reading-companion-independent-service.md`](./2026-04-24-reading-companion-independent-service.md)
- **接续 plan**：Plan B (sre-agent watchers + 早晚报) 在 Plan A 落地后写

---

## Hard Constraints

1. **0.1.0 工具签名零变化** — `log_search` / `log_trace` / `log_errors` / `log_stats` 的参数和返回 JSON shape 完全不动；仍然走 `LOG_DB_DSN` 直连 PG。
2. **新工具不接 k8s** — Phase 0a 决策 D9 ("不暴露 k8s kubectl 给 SRE agent") 继续保持。所有 pod / event / status 信息走 `lakeon-api` 已有的 admin endpoints。
3. **Admin token 单一来源** — 新增 `LAKEON_ADMIN_TOKEN` env var；不在代码里硬编码，不在 log 里打印。容器/Railway env 配。
4. **每个工具 description 至少 200 字** — 三段：what / when to use / when NOT to use。LLM 不瞎用工具的前提是工具自我说明清晰。
5. **TDD** — 每个工具：先写 test (httpx mock admin response) → 跑失败 → impl → 跑成功。
6. **不破坏 SRE agent 现有 Dockerfile** — Plan A 最后一步只是 `pip install dbay-sre-mcp==0.2.0` 版本 bump，不动其他。

---

## File Structure (target)

```
lakeon/dbay-sre-mcp/
├── pyproject.toml                          # MODIFY: version 0.2.0, +httpx dep
├── README.md                               # MODIFY: 新工具列表
├── CHANGELOG.md                            # NEW: 0.2.0 release notes
├── src/dbay_sre_mcp/
│   ├── __init__.py
│   ├── __main__.py                         # UNCHANGED
│   ├── server.py                           # MODIFY: 注册 7 个新工具 + import 新模块
│   ├── log_db.py                           # NEW: 抽出 PG 连接 + 4 个 log tool 实现 (从 server.py 重构)
│   ├── admin_client.py                     # NEW: LakeonAdminClient (httpx 薄封装)
│   ├── tools/                              # NEW: 7 个新工具一文件一个
│   │   ├── __init__.py
│   │   ├── find_database.py
│   │   ├── find_tenant.py
│   │   ├── database_status.py
│   │   ├── data_consistency_check.py
│   │   ├── stuck_task_query.py
│   │   ├── pod_create_failures.py
│   │   └── multi_tenant_blast_radius.py
│   └── tool_descriptions.yaml              # MODIFY: 加 7 个新工具的 description (1 处集中,server.py 引用)
└── tests/
    ├── conftest.py                         # MODIFY: 加 fake_admin_server fixture
    ├── test_log_search.py                  # UNCHANGED (0.1.0 回归)
    ├── test_log_trace.py                   # UNCHANGED
    ├── test_log_errors.py                  # UNCHANGED
    ├── test_log_stats.py                   # UNCHANGED
    ├── test_admin_client.py                # NEW
    ├── test_find_database.py               # NEW
    ├── test_find_tenant.py                 # NEW
    ├── test_database_status.py             # NEW
    ├── test_data_consistency_check.py      # NEW
    ├── test_stuck_task_query.py            # NEW
    ├── test_pod_create_failures.py         # NEW
    └── test_multi_tenant_blast_radius.py   # NEW

# 部署侧（顺带改）
lakeon/sre-agent/
├── Dockerfile                              # MODIFY: ARG DBAY_SRE_MCP_VERSION=0.2.0
├── hermes_config/config.yaml               # MODIFY: mcp_servers.dbay_sre.env 加 LAKEON_ADMIN_TOKEN
├── .env.example                            # MODIFY: 加 LAKEON_ADMIN_TOKEN 一行
└── scripts/verify_env.py                   # MODIFY: REQUIRED 加 LAKEON_ADMIN_TOKEN
```

### Module responsibilities

| Module | 责任 | 依赖 |
|---|---|---|
| `log_db.py` | PG 连接池 + 4 个 log_* 工具实现（从 server.py 重构出来，不变行为） | psycopg2, stdlib |
| `admin_client.py` | `LakeonAdminClient(base_url, token)` 薄封装：`get_database(id_or_name)`, `list_databases(filter)`, `get_tenant(...)`, `get_operations(component, since)`, `get_compute_cold_start(since)` | httpx |
| `tools/find_database.py` | 工具实现：name → id + tenant + status + compute_host | admin_client |
| `tools/find_tenant.py` | tenant 信息 + db 列表 | admin_client |
| `tools/database_status.py` | 综合状态 + 最近 1h 时间序列 | admin_client + log_db |
| `tools/data_consistency_check.py` | 参数化规则引擎；内置 4 个规则查 PG 直连（不走 admin endpoint，因为是 raw 查询） | psycopg2 + log_db |
| `tools/stuck_task_query.py` | 跨任务表查 in_progress > threshold | admin_client (走 `/admin/operations` 或新 endpoint) |
| `tools/pod_create_failures.py` | 走 `/admin/operations` 拿 k8s event 数据 | admin_client |
| `tools/multi_tenant_blast_radius.py` | 跨 tenant 关联检测 | log_db (跨 tenant 聚合) |
| `tool_descriptions.yaml` | 所有工具 description 集中（LLM 易读） | — |

---

## Work Breakdown — 10 Tasks

| Group | Tasks | What it produces |
|---|---|---|
| A. Foundation | 1-2 | Version bump + LakeonAdminClient + 4 个旧工具重构到 log_db.py |
| B. Metadata 工具 | 3-4 | find_database, find_tenant, database_status |
| C. Consistency + tasks | 5-6 | data_consistency_check, stuck_task_query |
| D. Cluster signals | 7-8 | pod_create_failures, multi_tenant_blast_radius |
| E. Polish + ship | 9-10 | tool descriptions 集中 + 0.2.0 发版 + sre-agent 集成 |

---

## Group A: Foundation

### Task 1: Version bump + httpx dep + LakeonAdminClient scaffold

**Files:**
- Modify: `lakeon/dbay-sre-mcp/pyproject.toml`
- Create: `lakeon/dbay-sre-mcp/CHANGELOG.md`
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_admin_client.py`

- [ ] **Step 1.1: Bump version + add httpx dep**

Edit `lakeon/dbay-sre-mcp/pyproject.toml`:
```toml
[project]
name = "dbay-sre-mcp"
version = "0.2.0"
# ... (other fields unchanged)
dependencies = [
  "fastmcp>=2.0",
  "psycopg2-binary>=2.9",
  "pyyaml>=6.0",
  "httpx>=0.27",   # NEW for LakeonAdminClient
]
```

- [ ] **Step 1.2: Create CHANGELOG.md**

```markdown
# Changelog

## 0.2.0 (2026-04-24)

### Added (7 new tools)

- `find_database(name=, db_id=)` — resolve human-readable DB name to internal id + tenant + status + compute_host
- `find_tenant(name=, tenant_id=)` — tenant metadata + held databases
- `database_status(name_or_id)` — comprehensive status snapshot + last 1h key events
- `data_consistency_check(rule)` — parameterized invariant checks (KB↔db_id orphans, enqueued↔drained, etc.)
- `stuck_task_query(type=, threshold_min=10)` — async tasks stuck in_progress beyond threshold
- `pod_create_failures(since=)` — k8s pod creation failures (InvalidName, CrashLoopBackOff)
- `multi_tenant_blast_radius(window=)` — detect single fault domain affecting multiple tenants

### Changed

- New env var `LAKEON_ADMIN_TOKEN` required for the 7 new tools (signs admin REST calls). Original 4 log_* tools unaffected.

### Compatibility

- 100% backward compatible: `log_search` / `log_trace` / `log_errors` / `log_stats` signatures and return JSON shapes unchanged.

## 0.1.0 (2026-04-22)

- Initial release. 4 log-only tools backed by Postgres dbay-logs.
```

- [ ] **Step 1.3: Write failing tests for LakeonAdminClient**

Create `lakeon/dbay-sre-mcp/tests/test_admin_client.py`:

```python
"""LakeonAdminClient — thin httpx wrapper around lakeon-api admin endpoints."""
from __future__ import annotations

import httpx
import pytest

from dbay_sre_mcp.admin_client import LakeonAdminClient


class _FakeHttp:
    """Stand-in for httpx.Client. Records requests, returns canned JSON."""
    def __init__(self, responses: dict[tuple[str, str], dict]):
        self.responses = responses
        self.calls: list[tuple[str, str, dict]] = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        pass

    def get(self, url, headers=None, params=None, timeout=None):
        self.calls.append(("GET", url, params or {}))
        key = ("GET", url)
        if key not in self.responses:
            class R:
                status_code = 404
                def raise_for_status(self): raise httpx.HTTPStatusError("404", request=None, response=self)
                def json(self): return {}
            return R()
        body = self.responses[key]
        class R:
            status_code = 200
            def raise_for_status(self): pass
            def json(self_inner): return body
        return R()


def test_init_strips_trailing_slash():
    c = LakeonAdminClient(base_url="https://api.dbay.cloud:8443/api/v1/", token="t")
    assert c._base_url == "https://api.dbay.cloud:8443/api/v1"


def test_get_database_by_id(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://api.example/api/v1/admin/databases/db_xyz"): {
            "id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
            "status": "READY", "compute_host": "pod-1",
        },
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://api.example/api/v1", token="lakeon-sre-2026")
    out = c.get_database(db_id="db_xyz")
    assert out["name"] == "tcph-bench"
    assert fake.calls == [("GET", "https://api.example/api/v1/admin/databases/db_xyz", {})]


def test_list_databases_with_filter(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://api.example/api/v1/admin/databases"): {
            "items": [
                {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc"},
                {"id": "db_abc", "name": "perf-test", "tenant_id": "t_abc"},
            ],
            "total": 2,
        },
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://api.example/api/v1", token="t")
    out = c.list_databases(name_contains="tcph")
    assert any(d["name"] == "tcph-bench" for d in out)


def test_admin_token_in_header(monkeypatch):
    captured: dict = {}

    class TrackHttp(_FakeHttp):
        def get(self, url, headers=None, params=None, timeout=None):
            captured["headers"] = headers
            return super().get(url, headers, params, timeout)

    fake = TrackHttp({
        ("GET", "https://x/api/v1/admin/databases/d1"): {"id": "d1"},
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://x/api/v1", token="my-secret-token")
    c.get_database(db_id="d1")
    assert captured["headers"]["Admin-Token"] == "my-secret-token"


def test_404_returns_none():
    """Non-existent resource → None (not an exception)."""
    fake = _FakeHttp({})
    import httpx
    import unittest.mock as m
    with m.patch.object(httpx, "Client", lambda *a, **kw: fake):
        c = LakeonAdminClient(base_url="https://x/api/v1", token="t")
        out = c.get_database(db_id="nonexistent")
        assert out is None
```

- [ ] **Step 1.4: Run tests — fail**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv pip install -e .[dev] 2>&1 | tail -3
uv run pytest tests/test_admin_client.py -v
```
Expected: ModuleNotFoundError for `dbay_sre_mcp.admin_client`.

- [ ] **Step 1.5: Implement LakeonAdminClient**

Create `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py`:

```python
"""LakeonAdminClient — httpx wrapper around lakeon-api admin REST endpoints.

All new dbay-sre-mcp tools (find_database, database_status, etc.) go through this
client so token handling, base URL, timeouts are centralised.

Auth header convention: `Admin-Token: <token>` (matches lakeon-api AdminController).
"""
from __future__ import annotations

import os
from typing import Any

import httpx


class LakeonAdminClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        token: str | None = None,
        timeout: float = 30.0,
    ) -> None:
        self._base_url = (
            base_url or os.environ.get("LAKEON_API_BASE_URL", "https://api.dbay.cloud:8443/api/v1")
        ).rstrip("/")
        self._token = token or os.environ["LAKEON_ADMIN_TOKEN"]
        self._timeout = timeout

    def _get(self, path: str, params: dict | None = None) -> dict | None:
        url = f"{self._base_url}{path}"
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                url,
                headers={"Admin-Token": self._token},
                params=params,
                timeout=self._timeout,
            )
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            return resp.json()

    # ---- Databases ----

    def get_database(self, *, db_id: str) -> dict | None:
        """GET /admin/databases/{databaseId}"""
        return self._get(f"/admin/databases/{db_id}")

    def list_databases(self, *, name_contains: str | None = None, tenant_id: str | None = None) -> list[dict]:
        """GET /admin/databases — paginated list, supports search."""
        params: dict[str, Any] = {}
        if name_contains:
            params["search"] = name_contains
        if tenant_id:
            params["tenant_id"] = tenant_id
        body = self._get("/admin/databases", params=params)
        if not body:
            return []
        return body.get("items", [])

    # ---- Tenants ----

    def get_tenant(self, *, tenant_id: str) -> dict | None:
        """GET /admin/tenants/{tenantId}"""
        return self._get(f"/admin/tenants/{tenant_id}")

    def list_tenants(self, *, name_contains: str | None = None) -> list[dict]:
        """GET /admin/tenants"""
        params: dict[str, Any] = {}
        if name_contains:
            params["search"] = name_contains
        body = self._get("/admin/tenants", params=params)
        if not body:
            return []
        return body.get("items", [])

    # ---- Operations / Compute / Health ----

    def get_operations(self, *, component: str | None = None, since: str = "1h") -> list[dict]:
        """GET /admin/operations — k8s + lifecycle events."""
        params: dict[str, Any] = {"since": since}
        if component:
            params["component"] = component
        body = self._get("/admin/operations", params=params)
        if not body:
            return []
        return body.get("items", [])

    def get_compute_cold_start(self, *, since: str = "24h", db_id: str | None = None) -> dict:
        """GET /admin/compute/cold-start — aggregate cold-start metrics."""
        params: dict[str, Any] = {"since": since}
        if db_id:
            params["db_id"] = db_id
        return self._get("/admin/compute/cold-start", params=params) or {}

    def system_health(self, *, component: str | None = None) -> dict:
        """GET /admin/system/health[/{component}]"""
        if component:
            return self._get(f"/admin/system/health/{component}") or {}
        return self._get("/admin/system/health") or {}
```

- [ ] **Step 1.6: Run tests — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_admin_client.py -v
```
Expected: 5 passed.

- [ ] **Step 1.7: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/pyproject.toml dbay-sre-mcp/CHANGELOG.md \
        dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py \
        dbay-sre-mcp/tests/test_admin_client.py
git commit -m "feat(sre-mcp): bump 0.2.0 + LakeonAdminClient (admin REST wrapper)"
```

---

### Task 2: Refactor 4 log tools out of server.py to log_db.py (no behavior change)

**Why:** server.py 即将注册 11 个工具 (4 旧 + 7 新)。提前把 4 个旧 log 工具的实现抽到 `log_db.py`，server.py 只剩"注册 + 描述"，更干净。零行为变化，旧测试全绿。

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/log_db.py`
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/server.py` (delete 4 tool 实现，import from log_db)

- [ ] **Step 2.1: Read existing server.py 4 tool implementations**

```bash
sed -n '210,305p' /Users/jacky/code/lakeon/dbay-sre-mcp/src/dbay_sre_mcp/server.py
```
Identify the 4 functions and any helpers (PG connection pool, formatters).

- [ ] **Step 2.2: Create log_db.py with extracted code**

`lakeon/dbay-sre-mcp/src/dbay_sre_mcp/log_db.py`:

```python
"""Postgres-backed log query implementations.

Extracted from server.py in 0.2.0 refactor. Behavior unchanged from 0.1.0:
all 4 log_* tools work the same, same return JSON shapes.

Connection: reads LOG_DB_DSN env var (set by sre-agent main.py via DBAY_LOGS_DSN bridge).
"""
from __future__ import annotations

import json
import os
from typing import Any

import psycopg2
import psycopg2.extras


def _connect():
    return psycopg2.connect(os.environ["LOG_DB_DSN"])


def log_search_impl(
    *,
    component: str = "",
    keyword: str = "",
    since: str = "1h",
    limit: int = 100,
    tenant_id: str = "",
    db_id: str = "",
) -> str:
    """Search dbay-logs by component / keyword / time window. Returns JSON string."""
    # (PASTE the existing log_search body from server.py here, verbatim.
    #  Replace the @mcp.tool decorator + def signature with this function header.)
    ...


def log_trace_impl(request_id: str) -> str:
    # (extracted body)
    ...


def log_errors_impl(since: str = "1h", component: str = "") -> str:
    # (extracted body)
    ...


def log_stats_impl(since: str = "24h") -> str:
    # (extracted body)
    ...
```

**Concrete instruction**: copy the function bodies exactly from `server.py:213-305`. Strip the `@mcp.tool(...)` decorator. Rename the function from `log_search` to `log_search_impl` etc. (suffix `_impl`).

- [ ] **Step 2.3: Update server.py — delegate to log_db**

In `server.py`, replace the 4 inline implementations with thin wrappers:

```python
from dbay_sre_mcp.log_db import (
    log_search_impl,
    log_trace_impl,
    log_errors_impl,
    log_stats_impl,
)


@mcp.tool(description=_desc("log_search"))
def log_search(component: str = "", keyword: str = "", since: str = "1h",
               limit: int = 100, tenant_id: str = "", db_id: str = "") -> str:
    return log_search_impl(component=component, keyword=keyword, since=since,
                           limit=limit, tenant_id=tenant_id, db_id=db_id)


@mcp.tool(description=_desc("log_trace"))
def log_trace(request_id: str) -> str:
    return log_trace_impl(request_id=request_id)


@mcp.tool(description=_desc("log_errors"))
def log_errors(since: str = "1h", component: str = "") -> str:
    return log_errors_impl(since=since, component=component)


@mcp.tool(description=_desc("log_stats"))
def log_stats(since: str = "24h") -> str:
    return log_stats_impl(since=since)
```

The `@mcp.tool(description=_desc(...))` syntax is unchanged — `_desc` reads from `tool_descriptions.yaml`.

- [ ] **Step 2.4: Run all 0.1.0 tests — must still pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_log_search.py tests/test_log_trace.py tests/test_log_errors.py tests/test_log_stats.py -v
```
Expected: same pass count as before refactor (no test was modified).

If any 0.1.0 test was inline (mocking server module directly), it might break — fix in place by importing from `log_db` instead.

- [ ] **Step 2.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/log_db.py dbay-sre-mcp/src/dbay_sre_mcp/server.py
git commit -m "refactor(sre-mcp): extract log_* implementations to log_db.py"
```

---

## Group B: Metadata Tools

### Task 3: `find_database` + `find_tenant`

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/__init__.py` (empty)
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/find_database.py`
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/find_tenant.py`
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/server.py` (注册 2 个新 tool)
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml` (加 2 个 description)
- Create: `lakeon/dbay-sre-mcp/tests/test_find_database.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_find_tenant.py`

- [ ] **Step 3.1: Write failing tests for find_database**

```python
# lakeon/dbay-sre-mcp/tests/test_find_database.py
import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.find_database import find_database_impl


def _fake_admin(databases: list[dict]):
    """Build a fake LakeonAdminClient that returns the given databases."""
    c = MagicMock()
    by_id = {d["id"]: d for d in databases}
    c.get_database = lambda *, db_id: by_id.get(db_id)
    c.list_databases = lambda *, name_contains=None, tenant_id=None: [
        d for d in databases if (name_contains is None or name_contains in d["name"])
    ]
    return c


def test_find_by_id_returns_full_record():
    admin = _fake_admin([
        {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
         "status": "READY", "compute_host": "pod-1", "created_at": "2026-04-01T00:00:00Z"},
    ])
    out = json.loads(find_database_impl(name=None, db_id="db_xyz", _admin=admin))
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"
    assert out["database"]["name"] == "tcph-bench"
    assert out["database"]["tenant_id"] == "t_abc"


def test_find_by_name_exact_match():
    admin = _fake_admin([
        {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
         "status": "READY", "compute_host": "pod-1"},
        {"id": "db_other", "name": "tcph-other", "tenant_id": "t_abc",
         "status": "READY"},
    ])
    out = json.loads(find_database_impl(name="tcph-bench", db_id=None, _admin=admin))
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"


def test_find_by_name_multiple_matches_returns_disambiguation():
    admin = _fake_admin([
        {"id": "db_a", "name": "perf-test-a", "tenant_id": "t_1"},
        {"id": "db_b", "name": "perf-test-b", "tenant_id": "t_2"},
    ])
    out = json.loads(find_database_impl(name="perf-test", db_id=None, _admin=admin))
    assert out["found"] is True
    assert out["multiple"] is True
    assert len(out["matches"]) == 2
    assert {m["id"] for m in out["matches"]} == {"db_a", "db_b"}


def test_find_by_name_no_match():
    admin = _fake_admin([])
    out = json.loads(find_database_impl(name="nonexistent", db_id=None, _admin=admin))
    assert out["found"] is False
    assert "nonexistent" in out["message"]


def test_must_provide_name_or_id():
    admin = _fake_admin([])
    with pytest.raises(ValueError, match="provide.*name.*db_id"):
        find_database_impl(name=None, db_id=None, _admin=admin)
```

- [ ] **Step 3.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 3.3: Implement find_database.py**

```python
"""find_database tool — resolve human-readable name to internal id + full record."""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def find_database_impl(
    *,
    name: Optional[str] = None,
    db_id: Optional[str] = None,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    """Internal: returns JSON string. _admin is for test injection."""
    if not name and not db_id:
        raise ValueError("must provide either name or db_id")

    admin = _admin or LakeonAdminClient()

    if db_id:
        record = admin.get_database(db_id=db_id)
        if not record:
            return json.dumps({"found": False, "message": f"no database with id={db_id}"})
        return json.dumps({"found": True, "database": _normalize(record)})

    matches = admin.list_databases(name_contains=name)
    exact = [m for m in matches if m.get("name") == name]
    if exact:
        return json.dumps({"found": True, "database": _normalize(exact[0])})
    if not matches:
        return json.dumps({"found": False, "message": f"no database matching name={name!r}"})
    if len(matches) == 1:
        return json.dumps({"found": True, "database": _normalize(matches[0])})
    return json.dumps({
        "found": True,
        "multiple": True,
        "matches": [_normalize(m) for m in matches],
        "message": f"{len(matches)} databases matched name~={name!r}; refine with exact name or db_id",
    })


def _normalize(record: dict) -> dict:
    """Pick the fields the LLM cares about. Avoid leaking large/internal fields."""
    return {
        "id": record.get("id"),
        "name": record.get("name"),
        "tenant_id": record.get("tenant_id"),
        "status": record.get("status"),
        "compute_host": record.get("compute_host"),
        "created_at": record.get("created_at"),
    }
```

- [ ] **Step 3.4: Run find_database tests — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_find_database.py -v
```
Expected: 5 passed.

- [ ] **Step 3.5: Mirror for find_tenant**

`lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/find_tenant.py`:

```python
"""find_tenant tool — resolve tenant name to id + held databases."""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def find_tenant_impl(
    *,
    name: Optional[str] = None,
    tenant_id: Optional[str] = None,
    include_databases: bool = True,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    if not name and not tenant_id:
        raise ValueError("must provide either name or tenant_id")
    admin = _admin or LakeonAdminClient()

    if tenant_id:
        record = admin.get_tenant(tenant_id=tenant_id)
        if not record:
            return json.dumps({"found": False, "message": f"no tenant with id={tenant_id}"})
    else:
        matches = admin.list_tenants(name_contains=name)
        exact = [m for m in matches if m.get("name") == name]
        if exact:
            record = exact[0]
        elif matches and len(matches) == 1:
            record = matches[0]
        elif not matches:
            return json.dumps({"found": False, "message": f"no tenant matching name={name!r}"})
        else:
            return json.dumps({
                "found": True,
                "multiple": True,
                "matches": [_normalize_tenant(m) for m in matches],
            })

    out = {"found": True, "tenant": _normalize_tenant(record)}
    if include_databases:
        dbs = admin.list_databases(tenant_id=record["id"])
        out["databases"] = [
            {"id": d.get("id"), "name": d.get("name"), "status": d.get("status")}
            for d in dbs
        ]
    return json.dumps(out)


def _normalize_tenant(record: dict) -> dict:
    return {
        "id": record.get("id"),
        "name": record.get("name"),
        "status": record.get("status"),
        "quota": record.get("quota"),
        "created_at": record.get("created_at"),
    }
```

Tests:

```python
# lakeon/dbay-sre-mcp/tests/test_find_tenant.py
import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.find_tenant import find_tenant_impl


def _fake_admin(tenants, dbs_by_tenant):
    c = MagicMock()
    by_id = {t["id"]: t for t in tenants}
    c.get_tenant = lambda *, tenant_id: by_id.get(tenant_id)
    c.list_tenants = lambda *, name_contains=None: [
        t for t in tenants if (name_contains is None or name_contains in t["name"])
    ]
    c.list_databases = lambda *, name_contains=None, tenant_id=None: dbs_by_tenant.get(tenant_id, [])
    return c


def test_find_tenant_by_id_with_databases():
    admin = _fake_admin(
        tenants=[{"id": "t_abc", "name": "perf-team", "status": "ACTIVE", "quota": 10}],
        dbs_by_tenant={"t_abc": [{"id": "db_1", "name": "tcph-bench", "status": "READY"}]},
    )
    out = json.loads(find_tenant_impl(tenant_id="t_abc", _admin=admin))
    assert out["found"] is True
    assert out["tenant"]["name"] == "perf-team"
    assert len(out["databases"]) == 1
    assert out["databases"][0]["name"] == "tcph-bench"


def test_find_tenant_no_match():
    admin = _fake_admin(tenants=[], dbs_by_tenant={})
    out = json.loads(find_tenant_impl(name="ghost", _admin=admin))
    assert out["found"] is False


def test_must_provide_name_or_id():
    admin = _fake_admin([], {})
    with pytest.raises(ValueError, match="provide"):
        find_tenant_impl(_admin=admin)
```

- [ ] **Step 3.6: Run all new tests — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_find_database.py tests/test_find_tenant.py -v
```
Expected: 8 passed.

- [ ] **Step 3.7: Register in server.py + add descriptions to YAML**

In `tool_descriptions.yaml` (file already exists), append:

```yaml
find_database:
  description: |
    Resolve a human-readable database name to its internal id, tenant_id, status, and current compute host.

    USE WHEN:
    - User mentions a database by name (e.g. "tcph-bench", "perf-test")
    - You need db_id or tenant_id before calling other tools (log_search, database_status)
    - Disambiguating between databases with similar names

    DO NOT USE WHEN:
    - You already have the db_id (a UUID-like string) — call other tools directly
    - The user is asking about logs / errors / metrics — use log_search / log_errors instead

    PARAMETERS:
    - name: human-readable database name (string match, returns multiple if ambiguous)
    - db_id: internal id (preferred if known)
    Provide either name OR db_id; not both.

    RETURNS JSON:
    - found=true with database={id, name, tenant_id, status, compute_host, created_at}
    - found=false with message
    - multiple=true with matches=[...] when name was ambiguous

find_tenant:
  description: |
    Resolve a tenant name to id, status, quota, and (by default) list of held databases.

    USE WHEN:
    - User mentions a tenant by name and you need tenant_id for downstream queries
    - You want to enumerate which databases a tenant owns
    - Diagnosing "is this tenant healthy" — combine with database_status per db

    DO NOT USE WHEN:
    - You only need a single database — use find_database directly
    - Asking about cross-tenant patterns — use multi_tenant_blast_radius

    PARAMETERS:
    - name: tenant name
    - tenant_id: internal id (preferred if known)
    - include_databases: default True; set False if you only need tenant metadata

    RETURNS JSON:
    - tenant={id, name, status, quota, created_at}
    - databases=[{id, name, status}, ...] (if include_databases=True)
```

In `server.py`, register the tools:

```python
from dbay_sre_mcp.tools.find_database import find_database_impl
from dbay_sre_mcp.tools.find_tenant import find_tenant_impl


@mcp.tool(description=_desc("find_database"))
def find_database(name: str = "", db_id: str = "") -> str:
    return find_database_impl(name=name or None, db_id=db_id or None)


@mcp.tool(description=_desc("find_tenant"))
def find_tenant(name: str = "", tenant_id: str = "", include_databases: bool = True) -> str:
    return find_tenant_impl(name=name or None, tenant_id=tenant_id or None,
                            include_databases=include_databases)
```

- [ ] **Step 3.8: Smoke-import server.py**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run python -c "from dbay_sre_mcp import server; print('ok, tools:', [t for t in dir(server) if not t.startswith('_')][:20])"
```
Expected: prints without error.

- [ ] **Step 3.9: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/__init__.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tools/find_database.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tools/find_tenant.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_find_database.py \
        dbay-sre-mcp/tests/test_find_tenant.py
git commit -m "feat(sre-mcp): find_database + find_tenant tools"
```

---

### Task 4: `database_status` — comprehensive snapshot + recent events

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/database_status.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_database_status.py`
- Modify: `server.py` + `tool_descriptions.yaml`

- [ ] **Step 4.1: Write failing test**

```python
# lakeon/dbay-sre-mcp/tests/test_database_status.py
import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.database_status import database_status_impl


def _fake_admin(database, cold_start, operations):
    c = MagicMock()
    c.get_database = lambda *, db_id: database if database and database["id"] == db_id else None
    c.list_databases = lambda *, name_contains=None, tenant_id=None: (
        [database] if database and (not name_contains or name_contains in database["name"]) else []
    )
    c.get_compute_cold_start = lambda *, since, db_id=None: cold_start
    c.get_operations = lambda *, component=None, since="1h": operations
    return c


def test_status_for_existing_database():
    admin = _fake_admin(
        database={
            "id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
            "status": "READY", "compute_host": "pod-tcph-1",
        },
        cold_start={"p50_ms": 1200, "p95_ms": 3400, "count": 7, "max_ms": 8200},
        operations=[
            {"ts": "2026-04-24T18:00:00Z", "type": "WAKE", "outcome": "SUCCESS", "duration_ms": 1100},
            {"ts": "2026-04-24T17:00:00Z", "type": "COLD_START", "outcome": "SUCCESS", "duration_ms": 2200},
        ],
    )
    out = json.loads(database_status_impl(name_or_id="tcph-bench", _admin=admin))
    assert out["found"] is True
    assert out["database"]["status"] == "READY"
    assert out["cold_start_1h"]["p95_ms"] == 3400
    assert len(out["recent_events_1h"]) == 2


def test_status_disambiguation_for_multiple_matches():
    admin = MagicMock()
    admin.get_database = lambda *, db_id: None
    admin.list_databases = lambda *, name_contains=None, tenant_id=None: [
        {"id": "db_a", "name": "perf-a"},
        {"id": "db_b", "name": "perf-b"},
    ]
    out = json.loads(database_status_impl(name_or_id="perf", _admin=admin))
    assert out["found"] is False
    assert out.get("multiple") is True


def test_status_not_found():
    admin = _fake_admin(None, {}, [])
    out = json.loads(database_status_impl(name_or_id="ghost", _admin=admin))
    assert out["found"] is False
```

- [ ] **Step 4.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 4.3: Implement database_status.py**

```python
"""database_status tool — single call to get DB status + recent activity."""
from __future__ import annotations

import json
import re
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


_UUID_RE = re.compile(r"^[a-f0-9]{8}-?[a-f0-9-]+$|^db_[a-z0-9]+$", re.IGNORECASE)


def database_status_impl(
    *,
    name_or_id: str,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    """Return comprehensive snapshot. _admin for test injection."""
    admin = _admin or LakeonAdminClient()

    # Resolve to a single db record
    record = None
    if _looks_like_id(name_or_id):
        record = admin.get_database(db_id=name_or_id)
    if record is None:
        matches = admin.list_databases(name_contains=name_or_id)
        exact = [m for m in matches if m.get("name") == name_or_id]
        if exact:
            record = exact[0]
        elif len(matches) == 1:
            record = matches[0]
        elif len(matches) > 1:
            return json.dumps({
                "found": False, "multiple": True,
                "matches": [{"id": m.get("id"), "name": m.get("name")} for m in matches],
                "message": f"{len(matches)} databases match {name_or_id!r}; pass exact name or id",
            })

    if record is None:
        return json.dumps({"found": False, "message": f"no database matching {name_or_id!r}"})

    db_id = record["id"]
    cold_start = admin.get_compute_cold_start(since="1h", db_id=db_id)
    operations = admin.get_operations(component="compute", since="1h")
    db_ops = [op for op in operations if op.get("db_id") == db_id or op.get("database_id") == db_id]

    return json.dumps({
        "found": True,
        "database": {
            "id": db_id,
            "name": record.get("name"),
            "tenant_id": record.get("tenant_id"),
            "status": record.get("status"),
            "compute_host": record.get("compute_host"),
        },
        "cold_start_1h": {
            "p50_ms": cold_start.get("p50_ms"),
            "p95_ms": cold_start.get("p95_ms"),
            "count": cold_start.get("count"),
            "max_ms": cold_start.get("max_ms"),
        },
        "recent_events_1h": db_ops[:20],
    })


def _looks_like_id(s: str) -> bool:
    return bool(_UUID_RE.match(s))
```

- [ ] **Step 4.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_database_status.py -v
```
Expected: 3 passed.

- [ ] **Step 4.5: Register + description**

`tool_descriptions.yaml` append:

```yaml
database_status:
  description: |
    Comprehensive snapshot of a database — current status + last 1h cold-start metrics + recent lifecycle events.
    One call replaces "find_database + log_search + log_stats" sequence.

    USE WHEN:
    - User asks "what's the state of <db>", "is <db> healthy", "why is <db> slow"
    - First step in any database-specific incident triage

    DO NOT USE WHEN:
    - You need raw log lines — use log_search
    - Asking about a cross-database trend — use log_stats

    PARAMETERS:
    - name_or_id: database name OR id (auto-detected via heuristic)

    RETURNS JSON:
    - database={id, name, tenant_id, status, compute_host}
    - cold_start_1h={p50_ms, p95_ms, count, max_ms}
    - recent_events_1h=[{ts, type, outcome, duration_ms}, ...] (max 20)
```

`server.py`:
```python
from dbay_sre_mcp.tools.database_status import database_status_impl


@mcp.tool(description=_desc("database_status"))
def database_status(name_or_id: str) -> str:
    return database_status_impl(name_or_id=name_or_id)
```

- [ ] **Step 4.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/database_status.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_database_status.py
git commit -m "feat(sre-mcp): database_status tool (status + cold-start + events 一次拿)"
```

---

## Group C: Consistency + Tasks

### Task 5: `data_consistency_check` — parameterised invariant rules

**Why:** 5 个高价值 bug 中 4 个是事务时序/数据一致性类（KB-ready timing, kb-write drain, schema seeder, db ready vs pod running）。一个工具+多个参数化规则，比一个工具一个 bug 类型更可扩展。

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/data_consistency_check.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_data_consistency_check.py`
- Modify: `server.py` + `tool_descriptions.yaml`

**Architecture decision:** rules are **PG SQL queries** against the lakeon-api production DB (read-only). The tool needs a separate env var `LAKEON_DB_DSN` (NOT `LOG_DB_DSN` — that's for dbay-logs). Rules are hard-coded for Phase 1; not user-supplied (security).

- [ ] **Step 5.1: Add LAKEON_DB_DSN env handling**

In `admin_client.py` is the wrong place. Create a new helper `lakeon_db.py`:

```python
# lakeon/dbay-sre-mcp/src/dbay_sre_mcp/lakeon_db.py
"""Read-only PG connection to lakeon-api's production DB (NOT dbay-logs).

LOG_DB_DSN connects to dbay-logs (4 log_* tools).
LAKEON_DB_DSN connects to lakeon-api's tenants/databases/knowledge tables (data_consistency_check).
"""
from __future__ import annotations

import os

import psycopg2


def connect():
    return psycopg2.connect(os.environ["LAKEON_DB_DSN"])
```

- [ ] **Step 5.2: Write failing tests**

```python
# lakeon/dbay-sre-mcp/tests/test_data_consistency_check.py
import json
from unittest.mock import MagicMock, patch

import pytest

from dbay_sre_mcp.tools.data_consistency_check import (
    AVAILABLE_RULES,
    data_consistency_check_impl,
)


def _fake_pg(rows: list[tuple]):
    """Build a fake psycopg2 connection that returns `rows` for any cursor.fetchall()."""
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_lists_available_rules():
    out = json.loads(data_consistency_check_impl(rule="__list__"))
    assert "rules" in out
    assert {"kb_implies_db_id", "enqueued_implies_drained",
            "db_ready_implies_pod_running", "schema_seeded"} <= set(out["rules"])


def test_unknown_rule_returns_helpful_error():
    out = json.loads(data_consistency_check_impl(rule="bogus_rule"))
    assert out["ok"] is False
    assert "unknown" in out["message"].lower()
    assert "available" in out["message"].lower()


def test_kb_implies_db_id_no_violations():
    fake = _fake_pg([])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(rule="kb_implies_db_id"))
    assert out["ok"] is True
    assert out["violations"] == []


def test_kb_implies_db_id_with_violations():
    fake = _fake_pg([
        ("kb_abc", "demo-kb", "t_xyz", None),
        ("kb_def", "test-kb", "t_xyz", None),
    ])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(rule="kb_implies_db_id"))
    assert out["ok"] is False
    assert out["count"] == 2
    assert out["violations"][0]["kb_id"] == "kb_abc"


def test_enqueued_implies_drained_finds_orphans():
    fake = _fake_pg([
        ("write_42", "kb_abc", "2026-04-24T10:00:00Z", 600),  # 10 min ago, undrained
    ])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(
            rule="enqueued_implies_drained", threshold_minutes=5,
        ))
    assert out["ok"] is False
    assert out["count"] == 1
```

- [ ] **Step 5.3: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 5.4: Implement data_consistency_check.py**

```python
"""data_consistency_check tool — parameterized invariant checks against lakeon-api PG.

All rules are READ-ONLY SELECT queries. No writes possible.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.lakeon_db import connect


# Each rule is (description, SQL with optional %(param)s placeholders, result_columns)
_RULES = {
    "kb_implies_db_id": {
        "description": "Knowledge bases marked READY but with NULL db_id (event timing bug)",
        "sql": """
            SELECT id, name, tenant_id, db_id
            FROM knowledge_base
            WHERE status = 'READY' AND db_id IS NULL
            LIMIT 100
        """,
        "columns": ["kb_id", "name", "tenant_id", "db_id"],
        "params": [],
    },
    "enqueued_implies_drained": {
        "description": "Writes enqueued but not drained beyond threshold (tx commit ordering bug)",
        "sql": """
            SELECT id, kb_id, enqueued_at,
                   EXTRACT(EPOCH FROM (NOW() - enqueued_at))::int AS age_sec
            FROM kb_write_queue
            WHERE drained_at IS NULL
              AND enqueued_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY enqueued_at ASC
            LIMIT 100
        """,
        "columns": ["write_id", "kb_id", "enqueued_at", "age_sec"],
        "params": ["threshold_minutes"],
    },
    "db_ready_implies_pod_running": {
        "description": "Databases marked READY but compute_host is unknown / pod missing",
        "sql": """
            SELECT id, name, tenant_id, status, compute_host
            FROM database
            WHERE status = 'READY' AND (compute_host IS NULL OR compute_host = '')
            LIMIT 100
        """,
        "columns": ["db_id", "name", "tenant_id", "status", "compute_host"],
        "params": [],
    },
    "schema_seeded": {
        "description": "Wiki-enabled KBs missing their wiki_schema row (seeder listener bug)",
        "sql": """
            SELECT kb.id, kb.name, kb.tenant_id
            FROM knowledge_base kb
            LEFT JOIN wiki_schema ws ON ws.kb_id = kb.id
            WHERE kb.wiki_enabled = true AND ws.id IS NULL
            LIMIT 100
        """,
        "columns": ["kb_id", "name", "tenant_id"],
        "params": [],
    },
}


AVAILABLE_RULES = list(_RULES.keys())


def data_consistency_check_impl(
    *,
    rule: str,
    threshold_minutes: int = 10,
) -> str:
    if rule == "__list__":
        return json.dumps({
            "rules": AVAILABLE_RULES,
            "details": {k: v["description"] for k, v in _RULES.items()},
        })

    spec = _RULES.get(rule)
    if spec is None:
        return json.dumps({
            "ok": False,
            "message": f"unknown rule {rule!r}; available: {AVAILABLE_RULES}",
        })

    params: dict = {}
    if "threshold_minutes" in spec["params"]:
        params["threshold_minutes"] = threshold_minutes

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(spec["sql"], params if params else None)
            rows = cur.fetchall()

    violations = [dict(zip(spec["columns"], row)) for row in rows]
    return json.dumps({
        "ok": len(violations) == 0,
        "rule": rule,
        "description": spec["description"],
        "count": len(violations),
        "violations": violations,
    })
```

- [ ] **Step 5.5: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_data_consistency_check.py -v
```
Expected: 5 passed.

- [ ] **Step 5.6: Register + description + commit**

`tool_descriptions.yaml`:
```yaml
data_consistency_check:
  description: |
    Run a named invariant check against the lakeon-api production DB. Read-only.
    Use this to detect data anomalies caused by event-timing / tx-ordering / listener bugs.

    USE WHEN:
    - User reports "X created but Y can't find it" (cross-table consistency)
    - You suspect a tx-commit timing bug
    - Periodic cron sweep for orphans / undrained queues

    DO NOT USE WHEN:
    - Looking at runtime errors — use log_errors
    - Asking about cold-start performance — use database_status

    PARAMETERS:
    - rule: name of the invariant rule to check; pass "__list__" to see all available rules
    - threshold_minutes: for time-based rules (e.g. enqueued_implies_drained), default 10

    AVAILABLE RULES:
    - kb_implies_db_id: KB marked READY but db_id is NULL
    - enqueued_implies_drained: writes enqueued but not drained > threshold_minutes
    - db_ready_implies_pod_running: DB marked READY but no compute_host
    - schema_seeded: wiki-enabled KB missing its schema row

    RETURNS JSON:
    - ok=true (no violations) or ok=false with count + violations=[...]
```

`server.py`:
```python
from dbay_sre_mcp.tools.data_consistency_check import data_consistency_check_impl


@mcp.tool(description=_desc("data_consistency_check"))
def data_consistency_check(rule: str, threshold_minutes: int = 10) -> str:
    return data_consistency_check_impl(rule=rule, threshold_minutes=threshold_minutes)
```

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/lakeon_db.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tools/data_consistency_check.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_data_consistency_check.py
git commit -m "feat(sre-mcp): data_consistency_check + 4 built-in invariant rules"
```

---

### Task 6: `stuck_task_query` — async tasks stuck in_progress beyond threshold

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/stuck_task_query.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_stuck_task_query.py`
- Modify: `server.py` + `tool_descriptions.yaml`

**Architecture:** queries lakeon-api PG (LAKEON_DB_DSN) for in_progress async tasks. Three task tables expected: `wiki_run_logs`, `lbfs_jobs`, `kb_processing_tasks`. If a table doesn't exist in your DB, the query handles `relation does not exist` gracefully and returns 0 from that source.

- [ ] **Step 6.1: Write failing test**

```python
# lakeon/dbay-sre-mcp/tests/test_stuck_task_query.py
import json
from unittest.mock import MagicMock, patch

import psycopg2

from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


def _fake_pg_with_results(per_table: dict[str, list[tuple]]):
    cursor = MagicMock()

    def execute_side_effect(sql, params=None):
        # Detect which table this query targets
        for tbl in per_table:
            if tbl in sql:
                cursor._next = per_table[tbl]
                return
        cursor._next = []

    cursor.execute.side_effect = execute_side_effect
    cursor.fetchall.side_effect = lambda: cursor._next
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_no_stuck_tasks():
    fake = _fake_pg_with_results({"wiki_run_logs": [], "lbfs_jobs": [], "kb_processing_tasks": []})
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=10))
    assert out["count"] == 0
    assert out["tasks"] == []


def test_stuck_wiki_task():
    fake = _fake_pg_with_results({
        "wiki_run_logs": [("task_42", "kb_abc", "WIKI_UPDATE", "in_progress",
                           "2026-04-24T10:00:00Z", 700)],
        "lbfs_jobs": [],
        "kb_processing_tasks": [],
    })
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=5))
    assert out["count"] == 1
    assert out["tasks"][0]["source"] == "wiki_run_logs"
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_filter_by_type():
    fake = _fake_pg_with_results({
        "wiki_run_logs": [("t_a", "kb_a", "WIKI_UPDATE", "in_progress", "2026-04-24T10:00:00Z", 700)],
        "lbfs_jobs": [("t_b", None, "FUSE_BACKFILL", "in_progress", "2026-04-24T10:00:00Z", 700)],
        "kb_processing_tasks": [],
    })
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=5, type="WIKI_UPDATE"))
    assert out["count"] == 1
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_table_missing_handled_gracefully():
    """If a table doesn't exist (DB schema variation), query returns 0 from that source."""
    cursor = MagicMock()
    def execute_side_effect(sql, params=None):
        if "kb_processing_tasks" in sql:
            raise psycopg2.errors.UndefinedTable("relation \"kb_processing_tasks\" does not exist")
        cursor._next = []
    cursor.execute.side_effect = execute_side_effect
    cursor.fetchall.side_effect = lambda: cursor._next
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None

    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=conn):
        out = json.loads(stuck_task_query_impl(threshold_minutes=10))
    assert out["count"] == 0
    assert any("kb_processing_tasks" in w for w in out.get("warnings", []))
```

- [ ] **Step 6.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 6.3: Implement stuck_task_query.py**

```python
"""stuck_task_query — async tasks stuck in_progress beyond threshold across known tables."""
from __future__ import annotations

import json
from typing import Optional

import psycopg2

from dbay_sre_mcp.lakeon_db import connect


# (table_name, columns_select, columns_normalized)
_SOURCES = [
    {
        "table": "wiki_run_logs",
        "sql": """
            SELECT id, kb_id, task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM wiki_run_logs
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
    {
        "table": "lbfs_jobs",
        "sql": """
            SELECT id, NULL AS kb_id, job_type AS task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM lbfs_jobs
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
    {
        "table": "kb_processing_tasks",
        "sql": """
            SELECT id, kb_id, task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM kb_processing_tasks
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
]


def stuck_task_query_impl(
    *,
    threshold_minutes: int = 10,
    type: Optional[str] = None,
) -> str:
    tasks: list[dict] = []
    warnings: list[str] = []

    with connect() as conn:
        for src in _SOURCES:
            try:
                with conn.cursor() as cur:
                    cur.execute(src["sql"], {"threshold_minutes": threshold_minutes})
                    rows = cur.fetchall()
                for row in rows:
                    record = dict(zip(src["columns"], row))
                    record["source"] = src["table"]
                    if type and record.get("task_type") != type:
                        continue
                    tasks.append(record)
            except psycopg2.errors.UndefinedTable:
                warnings.append(f"table {src['table']} does not exist in this DB; skipped")
                conn.rollback()
            except psycopg2.Error as exc:
                warnings.append(f"query against {src['table']} failed: {exc}")
                conn.rollback()

    out = {"count": len(tasks), "threshold_minutes": threshold_minutes, "tasks": tasks}
    if warnings:
        out["warnings"] = warnings
    return json.dumps(out)
```

- [ ] **Step 6.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_stuck_task_query.py -v
```
Expected: 4 passed.

- [ ] **Step 6.5: Register + description**

```yaml
stuck_task_query:
  description: |
    Find async tasks stuck in_progress beyond a threshold across known task tables
    (wiki_run_logs, lbfs_jobs, kb_processing_tasks).

    USE WHEN:
    - Investigating "task X never completes"
    - Periodic sweep for stuck tasks (e.g. WIKI_UPDATE was using 30-min recovery instead of 5-min — bug b742634d)
    - Detecting agent loop hangs (DeepSeek skips done() call — bug 5f9e1fc9)

    DO NOT USE WHEN:
    - Asking about completed task error rates — use log_errors with the task component

    PARAMETERS:
    - threshold_minutes: how long is "too long" (default 10)
    - type: filter by task_type (e.g. "WIKI_UPDATE", "FUSE_BACKFILL"); empty = all types

    RETURNS JSON:
    - count, tasks=[{task_id, kb_id, task_type, status, started_at, age_sec, source}]
    - warnings=[...] if some task tables don't exist in this DB schema variant
```

`server.py`:
```python
from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


@mcp.tool(description=_desc("stuck_task_query"))
def stuck_task_query(threshold_minutes: int = 10, type: str = "") -> str:
    return stuck_task_query_impl(threshold_minutes=threshold_minutes, type=type or None)
```

- [ ] **Step 6.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/stuck_task_query.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_stuck_task_query.py
git commit -m "feat(sre-mcp): stuck_task_query across wiki/lbfs/kb task tables"
```

---

## Group D: Cluster Signals

### Task 7: `pod_create_failures` — k8s pod creation failures via admin/operations

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/pod_create_failures.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_pod_create_failures.py`
- Modify: `server.py` + `tool_descriptions.yaml`

- [ ] **Step 7.1: Write failing test**

```python
# lakeon/dbay-sre-mcp/tests/test_pod_create_failures.py
import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.pod_create_failures import pod_create_failures_impl


def _fake_admin(operations):
    c = MagicMock()
    c.get_operations = lambda *, component=None, since="1h": operations
    return c


def test_no_failures():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "SUCCESS"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["count"] == 0


def test_invalid_name_failures():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "InvalidName: must consist of lower case alphanumeric",
         "tenant_id": "t_abc", "db_id": "db_xyz"},
        {"ts": "2026-04-24T17:00:00Z", "type": "POD_CREATE", "outcome": "SUCCESS"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["count"] == 1
    assert "InvalidName" in out["failures"][0]["error"]
    assert out["failures"][0]["category"] == "InvalidName"


def test_crashloop_categorization():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "CrashLoopBackOff: container exited with code 1",
         "tenant_id": "t_abc"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["failures"][0]["category"] == "CrashLoopBackOff"


def test_unknown_failure_categorized_as_other():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "weird new error nobody has seen", "tenant_id": "t"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["failures"][0]["category"] == "Other"
```

- [ ] **Step 7.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 7.3: Implement pod_create_failures.py**

```python
"""pod_create_failures — k8s pod creation failures aggregated by category."""
from __future__ import annotations

import json
import re
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


_CATEGORIES = [
    ("InvalidName", re.compile(r"InvalidName|invalid.*name", re.IGNORECASE)),
    ("CrashLoopBackOff", re.compile(r"CrashLoopBackOff", re.IGNORECASE)),
    ("ImagePullBackOff", re.compile(r"ImagePull(BackOff|Error)", re.IGNORECASE)),
    ("FailedScheduling", re.compile(r"FailedScheduling|insufficient", re.IGNORECASE)),
    ("ContainerCreating", re.compile(r"ContainerCreating.*timeout", re.IGNORECASE)),
    ("DuplicateName", re.compile(r"already exists|AlreadyExists", re.IGNORECASE)),
]


def _categorize(error: str) -> str:
    for name, pat in _CATEGORIES:
        if pat.search(error or ""):
            return name
    return "Other"


def pod_create_failures_impl(
    *,
    since: str = "1h",
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    ops = admin.get_operations(component="compute", since=since)
    failures = []
    for op in ops:
        if op.get("type") != "POD_CREATE" or op.get("outcome") != "FAILURE":
            continue
        failures.append({
            "ts": op.get("ts"),
            "tenant_id": op.get("tenant_id"),
            "db_id": op.get("db_id"),
            "error": op.get("error", ""),
            "category": _categorize(op.get("error", "")),
        })

    by_cat: dict[str, int] = {}
    for f in failures:
        by_cat[f["category"]] = by_cat.get(f["category"], 0) + 1

    return json.dumps({
        "since": since,
        "count": len(failures),
        "by_category": by_cat,
        "failures": failures[:50],
    })
```

- [ ] **Step 7.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_pod_create_failures.py -v
```
Expected: 4 passed.

- [ ] **Step 7.5: Register + description**

```yaml
pod_create_failures:
  description: |
    Aggregate k8s pod creation failures by category over a time window. Sources from lakeon-api
    /admin/operations endpoint (no kubectl needed).

    USE WHEN:
    - User reports "X tenant can't deploy" / "compute won't start"
    - Periodic sweep for pod-spec issues (e.g. sanitize bug 00a65ec0 caused InvalidName)
    - Investigating multi-tenant deploy failures

    DO NOT USE WHEN:
    - You want raw events — use admin /admin/operations directly via your own caller
    - Asking about runtime crashes after pod started — use log_errors

    PARAMETERS:
    - since: time window (default 1h, examples: 30m, 6h, 1d)

    RETURNS JSON:
    - count, by_category={InvalidName:N, CrashLoopBackOff:N, ImagePullBackOff:N, FailedScheduling:N, ContainerCreating:N, DuplicateName:N, Other:N}
    - failures=[{ts, tenant_id, db_id, error, category}, ...] (max 50)
```

`server.py`:
```python
from dbay_sre_mcp.tools.pod_create_failures import pod_create_failures_impl


@mcp.tool(description=_desc("pod_create_failures"))
def pod_create_failures(since: str = "1h") -> str:
    return pod_create_failures_impl(since=since)
```

- [ ] **Step 7.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/pod_create_failures.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_pod_create_failures.py
git commit -m "feat(sre-mcp): pod_create_failures with category aggregation"
```

---

### Task 8: `multi_tenant_blast_radius` — single fault domain affecting multiple tenants

**Why:** Bug `lakebasefs A4` 是典型 "一个 tenant 失败拖死所有"。该工具检测：短窗口内多 tenant 同症状错误飙升 → 单一根因。

**Files:**
- Create: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/multi_tenant_blast_radius.py`
- Create: `lakeon/dbay-sre-mcp/tests/test_multi_tenant_blast_radius.py`
- Modify: `server.py` + `tool_descriptions.yaml`

**Architecture:** Reads `dbay-logs` PG (`LOG_DB_DSN`) directly with a SQL aggregation: errors grouped by `(error_signature, tenant_id)` over a window, returning groups where `distinct_tenant_count >= threshold`.

- [ ] **Step 8.1: Write failing test**

```python
# lakeon/dbay-sre-mcp/tests/test_multi_tenant_blast_radius.py
import json
from unittest.mock import MagicMock, patch

from dbay_sre_mcp.tools.multi_tenant_blast_radius import multi_tenant_blast_radius_impl


def _fake_pg(rows):
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_no_blast():
    fake = _fake_pg([])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 0


def test_detects_cross_tenant_pattern():
    fake = _fake_pg([
        ("lakebasefs", "MemorySvcClient connection refused", 5, 47),  # 5 tenants, 47 occurrences
        ("compute", "InvalidName must consist of lower case", 4, 8),
    ])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 2
    assert out["incidents"][0]["distinct_tenant_count"] == 5
    assert "MemorySvcClient" in out["incidents"][0]["error_signature"]


def test_threshold_filters_low_blast():
    fake = _fake_pg([
        ("lakebasefs", "common error", 2, 100),  # only 2 tenants
    ])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 0
```

- [ ] **Step 8.2: Run — fails**

Expected: ModuleNotFoundError.

- [ ] **Step 8.3: Implement**

```python
"""multi_tenant_blast_radius — detect single error pattern affecting multiple tenants."""
from __future__ import annotations

import json
import os

import psycopg2


def log_db_connect():
    """Connect to dbay-logs PG (separate from lakeon-api PG)."""
    return psycopg2.connect(os.environ["LOG_DB_DSN"])


# Group recent errors by (component, error_signature) and count distinct tenants
# Assumes dbay-logs has columns: ts, component, level, msg, tenant_id
_QUERY = """
    SELECT
        component,
        SUBSTRING(msg FROM 1 FOR 80) AS error_signature,
        COUNT(DISTINCT tenant_id) AS distinct_tenant_count,
        COUNT(*) AS total_occurrences
    FROM logs
    WHERE level IN ('ERROR', 'WARN')
      AND ts >= NOW() - %s::interval
      AND tenant_id IS NOT NULL
    GROUP BY component, SUBSTRING(msg FROM 1 FOR 80)
    HAVING COUNT(DISTINCT tenant_id) >= %s
    ORDER BY distinct_tenant_count DESC, total_occurrences DESC
    LIMIT 20
"""


def multi_tenant_blast_radius_impl(
    *,
    window: str = "15m",
    min_tenant_count: int = 3,
) -> str:
    # Convert "15m" / "1h" / "30s" to PG interval
    interval = window  # PG accepts strings like "15 minutes", "1 hour"
    # Map shorthand
    if interval.endswith("m") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} minutes"
    elif interval.endswith("h") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} hours"
    elif interval.endswith("s") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} seconds"
    elif interval.endswith("d") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} days"

    with log_db_connect() as conn:
        with conn.cursor() as cur:
            cur.execute(_QUERY, (interval, min_tenant_count))
            rows = cur.fetchall()

    incidents = [
        {
            "component": component,
            "error_signature": sig,
            "distinct_tenant_count": tenants,
            "total_occurrences": total,
        }
        for component, sig, tenants, total in rows
    ]
    return json.dumps({
        "window": window,
        "min_tenant_count": min_tenant_count,
        "count": len(incidents),
        "incidents": incidents,
    })
```

- [ ] **Step 8.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest tests/test_multi_tenant_blast_radius.py -v
```
Expected: 3 passed.

- [ ] **Step 8.5: Register + description**

```yaml
multi_tenant_blast_radius:
  description: |
    Detect error patterns that affect multiple tenants in a short window — signals a single
    fault domain (config/infra/dependency) failing for all of them.

    USE WHEN:
    - "All tenants suddenly broken" / "many tenants unhappy"
    - Periodic sweep (every 5-15 min) for cross-tenant blast events
    - Verifying suspected single-point-of-failure (e.g. lakebasefs A4 bug 98a29218)

    DO NOT USE WHEN:
    - Single tenant problem — use log_search filtered by tenant_id
    - Rate-limit / quota issues — those are per-tenant, not blast

    PARAMETERS:
    - window: time window (default 15m, examples: 5m, 1h)
    - min_tenant_count: minimum distinct tenants to be flagged (default 3)

    RETURNS JSON:
    - count, incidents=[{component, error_signature, distinct_tenant_count, total_occurrences}]
    - sorted by distinct_tenant_count desc
```

`server.py`:
```python
from dbay_sre_mcp.tools.multi_tenant_blast_radius import multi_tenant_blast_radius_impl


@mcp.tool(description=_desc("multi_tenant_blast_radius"))
def multi_tenant_blast_radius(window: str = "15m", min_tenant_count: int = 3) -> str:
    return multi_tenant_blast_radius_impl(window=window, min_tenant_count=min_tenant_count)
```

- [ ] **Step 8.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/multi_tenant_blast_radius.py \
        dbay-sre-mcp/src/dbay_sre_mcp/server.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml \
        dbay-sre-mcp/tests/test_multi_tenant_blast_radius.py
git commit -m "feat(sre-mcp): multi_tenant_blast_radius cross-tenant fault detection"
```

---

## Group E: Polish + Ship

### Task 9: README update + tool_descriptions.yaml audit

**Files:**
- Modify: `lakeon/dbay-sre-mcp/README.md`
- Audit: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml`

- [ ] **Step 9.1: Update README**

Replace the "Tools" section in `lakeon/dbay-sre-mcp/README.md` with:

```markdown
## Tools (0.2.0)

### Log queries (PG-backed dbay-logs)

| Tool | Purpose |
|---|---|
| `log_search` | Flexible keyword/component/time filter |
| `log_trace` | Follow a request_id chain across components |
| `log_errors` | Recent error-level lines with auto-aggregation |
| `log_stats` | Activity overview by component / level / time |

### Metadata (lakeon-api admin REST)

| Tool | Purpose |
|---|---|
| `find_database` | Resolve DB name → id + status + tenant + compute_host |
| `find_tenant` | Resolve tenant name → id + held databases |
| `database_status` | Comprehensive DB snapshot + last 1h cold-start + events |

### Consistency & queues (lakeon-api production PG, read-only)

| Tool | Purpose |
|---|---|
| `data_consistency_check` | Run named invariant rule (KB↔db_id, enqueued↔drained, etc.) |
| `stuck_task_query` | Async tasks in_progress beyond threshold across known tables |

### Cluster signals (admin REST + dbay-logs)

| Tool | Purpose |
|---|---|
| `pod_create_failures` | k8s pod-create failures aggregated by category |
| `multi_tenant_blast_radius` | Single error pattern affecting N tenants in a window |

## Required env vars

| Variable | Used by | Notes |
|---|---|---|
| `LOG_DB_DSN` | log_*, multi_tenant_blast_radius | dbay-logs Postgres connection string |
| `LAKEON_DB_DSN` | data_consistency_check, stuck_task_query | lakeon-api production Postgres (read-only role recommended) |
| `LAKEON_ADMIN_TOKEN` | find_*, database_status, pod_create_failures | Admin token for `/admin/*` endpoints |
| `LAKEON_API_BASE_URL` | (above) | default `https://api.dbay.cloud:8443/api/v1` |
```

- [ ] **Step 9.2: Audit each tool description for the 3-section format**

For each of the 11 tools (4 old + 7 new), confirm `tool_descriptions.yaml` entry has:
- WHAT (one-liner)
- USE WHEN (3+ bullets)
- DO NOT USE WHEN (2+ bullets — anti-pattern guidance)
- PARAMETERS (each with description)
- RETURNS (JSON shape sketch)

If the 4 old tools (log_search/trace/errors/stats) lack the "DO NOT USE WHEN" section, add it now. Example for `log_search`:

```yaml
log_search:
  description: |
    Flexible keyword search over dbay-logs. Returns up to `limit` matching log lines as JSON.

    USE WHEN:
    - You have an exact keyword / phrase that should appear in log message text
    - Filtering by component (lakeon-api, pageserver, etc.) and time window
    - Looking up by tenant_id or db_id when those fields are populated

    DO NOT USE WHEN:
    - You only know a database name (use find_database first to get db_id)
    - You only know a tenant name (use find_tenant first)
    - Asking about cold-start performance — use database_status or get_compute_cold_start
    - Cross-tenant pattern detection — use multi_tenant_blast_radius

    PARAMETERS: ...
    RETURNS: ...
```

(Match the same 3-section structure for the other 3 old tools.)

- [ ] **Step 9.3: Run all tests one more time**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv run pytest -v
```
Expected: all 0.1.0 + 0.2.0 tests pass; concrete count: ~ 25-30 passed.

- [ ] **Step 9.4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/README.md dbay-sre-mcp/src/dbay_sre_mcp/tool_descriptions.yaml
git commit -m "docs(sre-mcp): 0.2.0 README + tool_descriptions audit (3-section format)"
```

---

### Task 10: Publish 0.2.0 to PyPI + integrate into sre-agent

**Files:**
- Build: `lakeon/dbay-sre-mcp/dist/dbay_sre_mcp-0.2.0-*.whl`
- Modify: `lakeon/sre-agent/Dockerfile` (`ARG DBAY_SRE_MCP_VERSION=0.2.0`)
- Modify: `lakeon/sre-agent/hermes_config/config.yaml` (add `LAKEON_ADMIN_TOKEN` to `mcp_servers.dbay_sre.env`)
- Modify: `lakeon/sre-agent/.env.example` (add LAKEON_ADMIN_TOKEN, LAKEON_DB_DSN, LAKEON_API_BASE_URL)
- Modify: `lakeon/sre-agent/scripts/verify_env.py` (add 3 new env vars to REQUIRED)

- [ ] **Step 10.1: Local build + publish**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
uv build
ls dist/
# Expected: dbay_sre_mcp-0.2.0-py3-none-any.whl  dbay_sre_mcp-0.2.0.tar.gz
```

Publish (requires PyPI token in env or `~/.pypirc`):
```bash
uv publish
# Or:
# python -m twine upload dist/dbay_sre_mcp-0.2.0*
```

Verify installable:
```bash
uv pip install --force-reinstall dbay-sre-mcp==0.2.0 2>&1 | tail -3
uv run python -c "from dbay_sre_mcp.server import mcp; print('tools:', sorted([t.name for t in mcp._tool_manager._tools.values()]))"
```
Expected: prints 11 tools alphabetically.

- [ ] **Step 10.2: Bump sre-agent's pinned version**

```bash
sed -i.bak 's/DBAY_SRE_MCP_VERSION=0.1.0/DBAY_SRE_MCP_VERSION=0.2.0/' \
    /Users/jacky/code/lakeon/sre-agent/Dockerfile
rm /Users/jacky/code/lakeon/sre-agent/Dockerfile.bak
grep DBAY_SRE_MCP_VERSION /Users/jacky/code/lakeon/sre-agent/Dockerfile
# Expected: ARG DBAY_SRE_MCP_VERSION=0.2.0
```

- [ ] **Step 10.3: Add new env vars**

`lakeon/sre-agent/.env.example` — append:
```
# New in dbay-sre-mcp 0.2.0
LAKEON_ADMIN_TOKEN=lakeon-sre-2026
LAKEON_DB_DSN=postgresql://readonly_user:pass@host:5432/lakeon_api
LAKEON_API_BASE_URL=https://api.dbay.cloud:8443/api/v1
```

`lakeon/sre-agent/hermes_config/config.yaml` — under the existing `mcp_servers.dbay_sre.env` section, add:
```yaml
mcp_servers:
  dbay_sre:
    command: "dbay-sre-mcp"
    args: []
    env:
      LOG_DB_DSN: "${LOG_DB_DSN}"
      LAKEON_ADMIN_TOKEN: "${LAKEON_ADMIN_TOKEN}"
      LAKEON_DB_DSN: "${LAKEON_DB_DSN}"
      LAKEON_API_BASE_URL: "${LAKEON_API_BASE_URL}"
    timeout: 60
    connect_timeout: 30
```

`lakeon/sre-agent/scripts/verify_env.py` — find the `REQUIRED` list and add:
```python
REQUIRED = [
    # ... existing entries ...
    "LAKEON_ADMIN_TOKEN",
    "LAKEON_DB_DSN",
]
OPTIONAL = [
    # ... existing entries ...
    "LAKEON_API_BASE_URL",   # has default
]
```

- [ ] **Step 10.4: Local docker build smoke**

```bash
cd /Users/jacky/code/lakeon
docker build -f sre-agent/Dockerfile -t sre-agent:phase1 . 2>&1 | tail -10
# Expected: build succeeds; layer "Successfully installed dbay-sre-mcp-0.2.0" appears
```

- [ ] **Step 10.5: Smoke test inside container**

```bash
docker run --rm -e LOG_DB_DSN=postgresql://x:y@z/db \
                -e LAKEON_ADMIN_TOKEN=test \
                -e LAKEON_DB_DSN=postgresql://x:y@z/db \
                sre-agent:phase1 \
                python -c "from dbay_sre_mcp.server import mcp; print(sorted([t.name for t in mcp._tool_manager._tools.values()]))"
```
Expected: 11 tool names listed (the 4 old + 7 new).

- [ ] **Step 10.6: Commit + tag**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/Dockerfile sre-agent/.env.example \
        sre-agent/hermes_config/config.yaml sre-agent/scripts/verify_env.py
git commit -m "feat(sre-agent): integrate dbay-sre-mcp 0.2.0 (7 new tools)"

cd /Users/jacky/code/lakeon/dbay-sre-mcp
git tag dbay-sre-mcp-0.2.0
# Optional push: git push --tags
```

- [ ] **Step 10.7: Update Phase 1 transition note**

Append to `lakeon/sre-agent/reports/b2-refactor-report.md` (or create a new file `phase1-progress.md` under `reports/`):

```markdown
# Phase 1 Progress Notes

## dbay-sre-mcp 0.2.0 (DONE)

- 7 new tools (find_database / find_tenant / database_status / data_consistency_check / stuck_task_query / pod_create_failures / multi_tenant_blast_radius)
- 100% backward compatible with 0.1.0 — no SRE agent runtime change beyond env vars + version bump
- New env vars wired: LAKEON_ADMIN_TOKEN, LAKEON_DB_DSN, LAKEON_API_BASE_URL

## Next: SRE agent watchers + 早晚报 (Plan B, separate plan)

Watchers planned (one per major bug family):
- fuse_queue_health_watcher (every 5m)
- pod_create_failure_watcher (every 2m)
- data_consistency_watcher (every 15m, runs all 4 invariant rules)
- stuck_task_watcher (every 5m)
- multi_tenant_blast_radius_watcher (every 5m)

Briefings planned:
- daily_morning_briefing (cron 0 1 UTC = 9:00 Asia/Shanghai)
- daily_evening_briefing (cron 0 14 UTC = 22:00 Asia/Shanghai — coexists with reading-companion's reflection)
- weekly_pattern_clustering (cron 0 1 mon UTC, weekly insight)

Domain glossary:
- skills/sre/domain_glossary/SKILL.md — dbay 对象模型 + 症状映射 + 标准 5 步诊断 (prompt-only)
```

- [ ] **Step 10.8: Final commit**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/reports/  # whichever file you wrote
git commit -m "docs(phase1): dbay-sre-mcp 0.2.0 done; watchers + briefings next"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** All 7 new tools mapped to a task; backward compat to 0.1.0 verified by reusing existing tests; admin endpoint architecture sticks (no kubectl).
- [x] **Hard constraints respected:** original 4 tools' signatures untouched (Task 2 is pure refactor with behavior preservation); admin token in env not hardcoded; tool descriptions follow 3-section format (Task 9 audit step).
- [x] **No placeholders:** every step has full code OR exact bash. Step 2.2 says "PASTE the existing log_search body" — that IS the instruction (engineer reads the file and copies, no rewriting allowed).
- [x] **Identifier consistency:** `LakeonAdminClient`, `find_database_impl`, `database_status_impl`, etc. spelled identically in tests + impl + server.py.
- [x] **TDD discipline:** every tool task has explicit "fail → impl → pass" steps.
- [x] **File paths:** absolute paths or relative-from-repo-root, never ambiguous.
- [x] **Plan-A scoped to ~10 tasks:** Plan B (watchers + 早晚报) deliberately deferred to separate plan after observing Plan A in production.

## Open Risks During Execution

1. **`/admin/databases` query parameter shape unknown** — Task 1.5 / 3.x assumes `?search=name` works for fuzzy match. If lakeon-api uses a different param name (e.g. `?name_contains=`), update `LakeonAdminClient.list_databases` accordingly. Fix in-place; no plan revision needed.
2. **`/admin/operations` payload shape** — Task 7 assumes events have `type`, `outcome`, `error`, `tenant_id` fields. If the actual shape differs, Task 7 implementation needs to map. Run a real call against staging to confirm before commit.
3. **`kb_write_queue`, `wiki_run_logs`, `lbfs_jobs`, `kb_processing_tasks` table names** — assumed in Tasks 5/6. If actual table names differ, the consistency rules + stuck task queries will fail at runtime (not test time, because tests use mocks). Run them against a staging PG before considering production-ready. The `psycopg2.errors.UndefinedTable` graceful-skip in Task 6 will at least not crash.
4. **PyPI publish credentials** — Task 10.1 assumes PyPI token is set up. If not, fall back to `python -m twine upload` with explicit `--username __token__ --password $PYPI_TOKEN`.
5. **Admin token security** — `LAKEON_ADMIN_TOKEN` grants full admin rights. The plan does NOT enforce a read-only scope. Phase 1 acceptable; flag for Phase 2 to introduce a read-only token role on lakeon-api side.
6. **Existing `tool_descriptions.yaml` may use a different YAML key shape** — Task 9.2 assumes one description per tool key. If it uses nested `description` + `args` shape (some fastmcp versions do), adjust accordingly. Read existing file first before writing new entries.

---

## Execution Handoff

Plan A complete and saved to `docs/superpowers/plans/2026-04-24-dbay-sre-mcp-phase1-enhancement.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review, fast iteration in this session.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach? **And** — should I draft Plan B (sre-agent watchers + 早晚报 + glossary) immediately so you can see the full picture, or wait until Plan A lands and we can adjust based on actual experience?
