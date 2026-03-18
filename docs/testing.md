# 测试指南

Lakeon 项目有三层测试：单元测试、集成测试、E2E 测试。

## 1. 单元测试 (lakeon-api)

Java 单元测试，不依赖外部服务。

```bash
cd lakeon-api
mvn test
```

覆盖：Service 层逻辑、Controller 层接口、工具类。

## 2. 本地集成测试 (Docker Desktop K8s)

在本地 K8s 环境跑的 31 个 E2E 测试，通过 curl + kubectl exec 验证完整链路。

**前置条件：** Docker Desktop Kubernetes 已部署 Lakeon。

```bash
./deploy/local/integration-test.sh
```

覆盖：租户 CRUD、数据库生命周期、SQL 操作、数据持久化、多租户隔离、分支/版本操作。

## 3. CCE 冒烟测试

部署到华为云 CCE 后的快速健康检查（7 项）。

```bash
./deploy/cce/deploy.sh              # 部署 + 自动冒烟测试
./deploy/cce/deploy.sh --skip-test  # 仅部署
```

单独跑冒烟测试：
```bash
source deploy/cce/site.sh && source deploy/cce/smoke-test.sh
```

检查项：API Pod 运行、HTTPS 可达、SRE Dashboard、Pageserver 健康、Proxy Pod、PG 端口、PG TLS 握手。

## 4. E2E 测试 (pytest + psql)

针对 CCE 生产环境的 47 个端到端测试，通过 DBay CLI client + psql 验证 API 和数据链路。

**前置条件：**
- CCE 环境已部署并可访问 `api.dbay.cloud:8443`
- 本地安装了 `psql`
- Python 3.11+

**安装：**
```bash
pip install -e dbay-cli/
```

**运行：**
```bash
# 全量（约 10-20 分钟，创建多个数据库和分支）
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/ -v

# 按模块
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_auth.py -v
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_database.py -v
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_branch.py -v
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_version.py -v
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_multi_tenant.py -v
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_connection.py -v

# 单个用例
no_proxy="api.dbay.cloud,pg.dbay.cloud" pytest tests/e2e/test_branch.py -k test_promote_data_visible -v
```

**环境变量（可选）：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DBAY_ENDPOINT` | `https://api.dbay.cloud:8443` | API 地址 |
| `DBAY_ADMIN_TOKEN` | `lakeon-sre-2026` | Admin token，用于创建测试租户 |

**测试用例清单：**

| 文件 | 用例数 | 覆盖内容 |
|------|--------|----------|
| test_auth.py | 3 | 无效 key 401、缺失 auth 401、禁用租户 403 |
| test_database.py | 8 | 创建/获取/列表/SQL/挂起/恢复/数据持久化/删除 |
| test_branch.py | 15 | CRUD + 数据隔离 + Promote + Restore + 边界 |
| test_version.py | 12 | CRUD + LSN + Squash + 边界 |
| test_multi_tenant.py | 5 | 跨租户隔离（GET/DELETE/List/psql/分支） |
| test_connection.py | 4 | psql 默认分支/分支连接/SSL/连接串格式 |
| **合计** | **47** | |

**测试隔离：** 每次运行自动创建独立测试租户 `e2e-{timestamp}`，测试结束后清理所有资源。不影响生产数据。

## 5. DBay CLI 手动验证

```bash
# 配置
dbay config set endpoint https://api.dbay.cloud:8443
dbay config set api_key lk_xxx

# 数据库操作
dbay db list
dbay db create mydb
dbay db info mydb
dbay db connstr mydb                       # 输出连接串
dbay db connstr mydb --branch dev          # 分支连接串
psql "$(dbay db connstr mydb)"             # 直接连 psql

# 分支操作
dbay branch list --db mydb
dbay branch create --db mydb --name dev
dbay branch promote --db mydb --branch dev

# 版本操作
dbay version list --db mydb --branch main
dbay version create --db mydb --branch main --name v1.0
```
