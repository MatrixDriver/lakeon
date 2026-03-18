# DBay CLI & E2E 测试设计

## 概述

开发 DBay CLI 工具和 E2E 测试框架。CLI 先作为 Claude 的 E2E 测试工具，后续打磨为用户产品。E2E 测试跑 CCE 生产环境（hwstaff 北京四），用独立测试租户隔离。

## 决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| CLI 使用者 | 先给 Claude 用，后续打磨给用户 | 快速出活，优先保障测试能力 |
| 测试环境 | CCE 生产环境 | 测真实链路 |
| 数据隔离 | 独立测试租户 `e2e-test-{timestamp}` | 不影响生产数据 |
| CLI 与现有代码关系 | 新建 `dbay-cli/`，不改 `lakeon-cli/` | 用户品牌是 DBay |
| CLI 与测试分离 | CLI + pytest 分离部署 | CLI 保持干净，测试 import client |
| 测试框架 | pytest | 可单独跑某个模块/用例，fixtures 管理生命周期 |
| SQL 验证 | E2E 测试 subprocess 调 psql | CLI 只管 API，不内置 PG 驱动 |

## CLI 架构

### 目录结构

```
dbay-cli/
  pyproject.toml               # 入口: dbay, 依赖: typer/httpx/rich
  dbay_cli/
    __init__.py
    main.py                    # typer app, 注册子命令组
    client.py                  # DbayClient — httpx 封装, 所有 API 调用
    config.py                  # ~/.dbay/config.json 管理 (endpoint, api_key)
    output.py                  # 输出格式化 (table/json)
    commands/
      auth.py                  # dbay login / dbay config set/get/show
      db.py                    # dbay db list/create/delete/info/suspend/resume/connstr/reset-password
      branch.py                # dbay branch list/create/delete/promote/restore
      version.py               # dbay version list/create/delete/squash
      user.py                  # dbay user list/create/delete/reset-password
```

### DbayClient (client.py)

纯 HTTP 客户端，无 CLI 依赖，E2E 测试直接 import。

```python
class DbayClient:
    def __init__(self, endpoint: str, api_key: str | None = None):
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.http = httpx.Client(verify=False, timeout=30)

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        return h

    def _url(self, path: str) -> str:
        return f"{self.endpoint}/api/v1{path}"

    def _request(self, method, path, **kwargs):
        resp = self.http.request(method, self._url(path), headers=self._headers(), **kwargs)
        if resp.status_code >= 400:
            raise DbayApiError(resp.status_code, resp.json())
        return resp.json() if resp.content else None

    # ── Tenant ──
    def create_tenant(self, username, password, name=None): ...
    def login(self, username, password): ...
    def get_me(self): ...

    # ── Database ──
    def create_database(self, name, compute_size="0.25cu"): ...
    def list_databases(self): ...
    def get_database(self, db_id): ...
    def delete_database(self, db_id): ...
    def suspend_database(self, db_id): ...
    def resume_database(self, db_id): ...
    def get_connstr(self, db_id): ...

    # ── Branch ──
    def list_branches(self, db_id): ...
    def create_branch(self, db_id, name, parent_branch_id=None): ...
    def delete_branch(self, db_id, branch_id): ...
    def promote_branch(self, db_id, branch_id): ...
    def restore_branch(self, db_id, branch_id, target_version_id=None, target_lsn=None): ...

    # ── Version ──
    def list_versions(self, db_id, branch_id): ...
    def create_version(self, db_id, branch_id, name, description=None): ...
    def delete_version(self, db_id, branch_id, version_id): ...
    def squash_versions(self, db_id, branch_id, from_id, to_id): ...

    # ── Database User ──
    def list_users(self, db_id): ...
    def create_user(self, db_id, username, role="READER"): ...
    def delete_user(self, db_id, user_id): ...
```

### CLI 命令

```bash
# 配置
dbay config set endpoint https://api.dbay.cloud:8443
dbay config set api_key lk_xxx
dbay config show

# 登录（设置 api_key）
dbay login --username jacky --password xxx

# 数据库
dbay db list
dbay db create mydb
dbay db info mydb
dbay db connstr mydb                          # 输出: postgres://user@host/db?options=...&sslmode=require
dbay db connstr mydb --branch dev             # 输出分支连接串
dbay db suspend mydb
dbay db resume mydb
dbay db delete mydb

# 分支
dbay branch list --db mydb
dbay branch create --db mydb --name dev
dbay branch promote --db mydb --branch dev
dbay branch delete --db mydb --branch dev

# 版本
dbay version list --db mydb --branch main
dbay version create --db mydb --branch main --name v1.0 --desc "initial"
dbay version delete --db mydb --branch main --version v1.0

# 用户
dbay user list --db mydb
dbay user create --db mydb --username reader1 --role READER
```

### 配置文件

`~/.dbay/config.json`:
```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_8b2d97d67f5892adc2f3aff03c0479be7c837a34965218a94788a833abc66ebb"
}
```

## E2E 测试框架

### 目录结构

```
tests/e2e/
  conftest.py                  # fixtures + helpers
  test_auth.py                 # 认证测试
  test_database.py             # 数据库生命周期
  test_branch.py               # 分支操作 (15 用例)
  test_version.py              # 版本操作 (12 用例)
  test_multi_tenant.py         # 租户隔离
  test_connection.py           # psql 连接验证
```

### conftest.py

```python
import os, time, subprocess, pytest
from dbay_cli.client import DbayClient

ENDPOINT = os.environ.get("DBAY_ENDPOINT", "https://api.dbay.cloud:8443")
ADMIN_TOKEN = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")

@pytest.fixture(scope="session")
def admin_client():
    """Admin client for tenant management."""
    return DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN, admin=True)

@pytest.fixture(scope="session")
def e2e_tenant(admin_client):
    """创建测试租户，session 结束后清理。"""
    ts = int(time.time())
    client = DbayClient(endpoint=ENDPOINT)
    tenant = client.create_tenant(
        username=f"e2e-{ts}",
        password=f"E2eTest@{ts}",
        name=f"E2E Test {ts}"
    )
    client.api_key = tenant["api_key"]
    yield {"client": client, **tenant}
    # cleanup: 删除所有数据库
    for db in client.list_databases():
        try:
            client.delete_database(db["id"])
        except Exception:
            pass

@pytest.fixture(scope="session")
def e2e_client(e2e_tenant):
    """已认证的 DbayClient。"""
    return e2e_tenant["client"]

@pytest.fixture
def test_db(e2e_client):
    """创建临时数据库，测试后删除。"""
    db = e2e_client.create_database(name=f"e2e-db-{int(time.time())}")
    db = poll_until(
        lambda: e2e_client.get_database(db["id"]),
        condition=lambda d: d["status"] in ("running", "error"),
        timeout=120, interval=3,
    )
    assert db["status"] == "running", f"数据库创建失败: {db}"
    yield db
    try:
        e2e_client.delete_database(db["id"])
    except Exception:
        pass

def poll_until(fetch_fn, condition, timeout=120, interval=3):
    """轮询直到条件满足或超时。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = fetch_fn()
        if condition(result):
            return result
        time.sleep(interval)
    raise TimeoutError(f"Condition not met within {timeout}s, last result: {result}")

def run_psql(connstr: str, sql: str, password: str = None) -> str:
    """执行 psql 命令，返回输出。"""
    env = {**os.environ, "no_proxy": "pg.dbay.cloud"}
    if password:
        env["PGPASSWORD"] = password
    # 确保 connstr 有 sslmode=require
    if "sslmode=" not in connstr:
        sep = "&" if "?" in connstr else "?"
        connstr += f"{sep}sslmode=require"
    result = subprocess.run(
        ["psql", connstr, "-c", sql, "-t", "-A"],
        capture_output=True, text=True, timeout=60, env=env,
    )
    if result.returncode != 0:
        raise RuntimeError(f"psql failed: {result.stderr}")
    return result.stdout.strip()
```

### 测试用例清单

#### test_auth.py (3 用例)

1. **test_invalid_api_key** — 无效 key 返回 401
2. **test_missing_auth** — 无 Authorization header 返回 401
3. **test_disabled_tenant** — 禁用租户返回 403 (需 admin API)

#### test_database.py (8 用例)

1. **test_create_database** — 创建，轮询 CREATING → RUNNING，返回 connection_uri 和 password
2. **test_get_database** — 获取数据库详情，password 不返回
3. **test_list_databases** — 列表包含已创建的数据库
4. **test_sql_operations** — psql 创建表 + 插入 + 查询
5. **test_suspend_database** — 挂起后状态 SUSPENDED
6. **test_resume_database** — 恢复后状态 RUNNING
7. **test_data_persistence** — 挂起 → 恢复 → psql 查询数据仍在
8. **test_delete_database** — 删除后 GET 返回 404

#### test_branch.py (15 用例)

**基础 CRUD：**
1. **test_create_branch** — 创建分支，状态 active，parent 正确
2. **test_list_branches** — 列表包含 main + 新分支
3. **test_delete_branch** — 删除后列表不再包含
4. **test_delete_default_branch_rejected** — 删除默认分支返回 400

**数据隔离：**
5. **test_branch_inherits_data** — 主分支写数据 → 创建分支 → 分支看到继承数据
6. **test_branch_write_isolation** — 分支写数据 → 主分支看不到
7. **test_main_write_after_branch** — 创建分支后主分支写数据 → 分支看不到

**Promote：**
8. **test_promote_data_visible** — 分支写数据 → promote → 默认连接看到数据
9. **test_promote_old_default_demoted** — promote 后旧默认降级为 backup
10. **test_promote_new_branch_from_promoted** — promote 后新建分支，parent 是新默认

**Restore：**
11. **test_restore_to_version** — 创建 v1 → 写数据 → restore → 新数据消失
12. **test_restore_creates_backup** — restore 后有 backup 分支，含 restore 前的数据

**边界场景：**
13. **test_duplicate_branch_name_rejected** — 同名分支返回 409
14. **test_nested_branch** — 在分支上创建子分支，验证数据继承链
15. **test_delete_branch_cleans_compute** — 删除有 compute 的分支，pod 被清理

#### test_version.py (12 用例)

**基础 CRUD：**
1. **test_create_version** — 创建版本，验证 name/LSN/created_by
2. **test_list_versions** — 列表按 LSN 排序
3. **test_delete_version** — 删除后列表不含
4. **test_get_version** — 获取单个版本详情

**版本与数据：**
5. **test_version_lsn_increases** — v1 → 写数据 → v2，v2.LSN > v1.LSN
6. **test_restore_to_version_data** — restore 到 v1，psql 只有 v1 时的数据

**Squash：**
7. **test_squash_versions** — v1/v2/v3 → squash → v2 被删，v1 v3 保留
8. **test_squash_insufficient_versions** — 不足 3 个版本无法 squash

**边界场景：**
9. **test_duplicate_version_name** — 同名版本的行为
10. **test_version_on_non_default_branch** — 非默认分支创建版本，属于该分支
11. **test_versions_after_branch_delete** — 删除分支后版本不可访问
12. **test_version_on_empty_database** — 空数据库创建版本，LSN 有效

#### test_multi_tenant.py (5 用例)

1. **test_cross_tenant_get_404** — 租户 A 的数据库，租户 B GET 返回 404
2. **test_cross_tenant_delete_404** — 租户 B DELETE 返回 404
3. **test_list_isolation** — 各租户只看到自己的数据库
4. **test_psql_data_isolation** — 两个租户各自数据库的 psql 数据完全隔离
5. **test_cross_tenant_branch_404** — 租户 B 不能操作租户 A 的分支

#### test_connection.py (4 用例)

1. **test_default_branch_psql** — 默认连接串 psql 能连通
2. **test_branch_psql** — 分支连接串 psql 能连通
3. **test_ssl_required** — 不带 sslmode=require 被拒
4. **test_connstr_format** — connstr 包含 `options=endpoint%3D`，格式正确

### 运行方式

```bash
# 环境变量（可选，有默认值）
export DBAY_ENDPOINT=https://api.dbay.cloud:8443
export DBAY_ADMIN_TOKEN=lakeon-sre-2026

# 安装 CLI（开发模式）
pip install -e dbay-cli/

# 全量测试
pytest tests/e2e/ -v

# 单模块
pytest tests/e2e/test_branch.py -v

# 单用例
pytest tests/e2e/test_branch.py -k test_promote_data_visible -v
```

### 新功能开发测试流程

1. 开发功能代码
2. 补充 CLI client 方法（如有新 API）
3. 补充对应 E2E 测试用例
4. `pytest tests/e2e/test_xxx.py` 通过
5. 全量 `pytest tests/e2e/` 通过
6. 部署到 CCE
7. 功能标记完成
