---
description: "产品需求文档: serverless-pg - 基于 Neon 的华为云 Serverless PostgreSQL 服务"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

# LakeOn Serverless PostgreSQL 产品需求文档

## 1. 执行摘要

LakeOn 是一个基于 Neon 开源项目构建的 Serverless PostgreSQL 云服务，部署在华为云上。它利用 Neon 的存算分离架构，将 PostgreSQL 的计算节点（Compute）与存储引擎（Pageserver + Safekeeper）分离，底层持久化到华为云 OBS 对象存储。

核心价值在于用户无需管理数据库基础设施，按需使用、自动弹性。当没有查询活动时 compute 自动休眠释放资源，当新连接到来时 proxy 层自动唤醒 compute，用户无感。数据始终安全持久化在 OBS 上，compute 的启停不影响数据完整性。

第一版（MVP）目标是在华为云上部署完整的 Neon 存储栈，构建管控面 API 和 CLI 工具，支持基础多租户、实例 CRUD、compute 自动休眠/唤醒、数据库分支等核心功能，并通过本地测试验证存算分离和 serverless 能力。

## 2. 使命

**使命声明**：让开发者像使用 Serverless 函数一样使用 PostgreSQL —— 零运维、按需启动、数据永不丢失。

**核心原则**：

1. **存算分离**：计算与存储完全解耦，compute 可以随时启停而数据始终安全
2. **Serverless 体验**：自动休眠、自动唤醒、用户无需关心基础设施
3. **PostgreSQL 兼容**：100% 标准 PostgreSQL 协议，用户现有工具和应用无需改造
4. **简单优先**：API 和 CLI 设计追求极简，合理默认值减少配置负担
5. **可观测性**：完善的监控和告警，运维团队能清晰掌握系统状态

## 3. 目标用户

### 主要用户：内部开发团队

- **角色**：后端开发工程师、DevOps 工程师
- **技术水平**：熟悉 PostgreSQL 和 SQL，有云服务使用经验
- **核心需求**：
  - 快速获取一个可用的 PostgreSQL 数据库，无需走复杂的申请/运维流程
  - 开发/测试环境的数据库能自动释放资源，节省成本
  - 利用分支功能快速创建数据库副本用于测试
- **痛点**：
  - 传统数据库申请流程长、运维成本高
  - 开发测试环境资源常年占用，浪费严重
  - 数据库环境隔离和克隆困难

### 次要用户（后续版本）：外部客户

- 中小型 SaaS 应用开发者
- 需要低成本、免运维的数据库服务

## 4. MVP 范围

### 范围内

**核心功能：**
- ✅ 创建/删除 PostgreSQL 数据库实例
- ✅ 标准 PostgreSQL 协议连接与读写
- ✅ Compute 自动休眠（无活动超时）
- ✅ Compute 自动唤醒（连接触发，proxy 层实现）
- ✅ 数据库分支管理（创建/删除/列出分支）
- ✅ 实例配置管理（compute 规格、休眠超时时间）
- ✅ 实例状态查询和列表
- ✅ 手动启停 compute
- ✅ 存储默认上限（10GB/实例，可调整）

**管控面：**
- ✅ RESTful 管控 API
- ✅ CLI 命令行工具
- ✅ API Key 认证
- ✅ 基础多租户（每个租户独立实例和 API Key）

**技术基础：**
- ✅ 基于 Neon 存算分离架构（Compute + Pageserver + Safekeeper）
- ✅ 华为云 OBS 作为持久化存储
- ✅ 华为云 CCE（K8s）部署所有组件
- ✅ 仅支持 PostgreSQL 17
- ✅ 单 AZ 部署，架构预留跨 AZ 扩展

**运维：**
- ✅ Prometheus + Grafana 监控仪表盘
- ✅ 关键指标告警（连接失败、存储异常、compute 唤醒失败等）
- ✅ 组件日志集中采集

### 范围外

- ❌ Web 管理控制台
- ❌ 计费系统和用量计量
- ❌ 冷启动优化（100ms 目标推迟到后续版本）
- ❌ 基于时间点的分支恢复（point-in-time branch）
- ❌ 跨 AZ 高可用部署
- ❌ 多 PostgreSQL 版本支持
- ❌ 连接池化（PgBouncer 集成）
- ❌ 自动备份与恢复
- ❌ SQL over HTTP / WebSocket 连接

## 5. 用户故事

### US-1: 创建数据库实例

> 作为一名后端开发者，我想要通过一条命令快速创建一个 PostgreSQL 数据库，以便立即开始开发工作而不需要走运维申请流程。

**示例**：
```bash
# 最简创建（全部使用默认值）
lakeon db create --name my-app-db

# 指定配置
lakeon db create --name my-app-db --compute-size 2cu --suspend-timeout 10m
```

返回连接串：`postgres://user:pass@proxy.lakeon.example.com/my-app-db`

### US-2: 连接使用数据库

> 作为一名后端开发者，我想要用 psql 或任何 PostgreSQL 客户端连接数据库并进行正常的 CRUD 操作，以便在我的应用中使用这个数据库。

**示例**：
```bash
psql "postgres://user:pass@proxy.lakeon.example.com/my-app-db"
# 正常执行 SQL
CREATE TABLE users (id SERIAL PRIMARY KEY, name TEXT);
INSERT INTO users (name) VALUES ('Alice');
SELECT * FROM users;
```

### US-3: 自动休眠节省资源

> 作为一名 DevOps 工程师，我希望开发环境的数据库在没人使用时自动释放计算资源，以便节省云上成本。

**示例**：开发者下班后数据库无查询活动，10 分钟后 compute 自动关闭。集群中的 Pod 被释放，仅保留存储层数据。

### US-4: 自动唤醒无感连接

> 作为一名后端开发者，我希望即使数据库已休眠，我连接时它也能自动启动，以便我不需要关心数据库的运行状态，直接使用即可。

**示例**：第二天上班，开发者直接运行 `psql` 连接数据库。proxy 检测到 compute 已休眠，自动唤醒。等待数秒后连接建立成功，之前的数据完好无损。

### US-5: 创建数据库分支用于测试

> 作为一名后端开发者，我想要从生产数据库创建一个分支副本用于测试新功能，以便在不影响生产数据的情况下使用真实数据进行开发测试。

**示例**：
```bash
# 从主分支创建测试分支
lakeon branch create --db my-app-db --name feature-test

# 连接到分支（独立的连接串）
psql "postgres://user:pass@proxy.lakeon.example.com/my-app-db?branch=feature-test"

# 测试完成后删除分支
lakeon branch delete --db my-app-db --name feature-test
```

### US-6: 管理实例生命周期

> 作为一名 DevOps 工程师，我想要通过 API 或 CLI 管理所有数据库实例的状态、配置和生命周期，以便高效运维数据库资源。

**示例**：
```bash
# 查看所有实例
lakeon db list

# 查看实例状态
lakeon db status --name my-app-db

# 手动停止/启动 compute
lakeon db suspend --name my-app-db
lakeon db resume --name my-app-db

# 调整配置
lakeon db update --name my-app-db --compute-size 4cu --suspend-timeout 30m

# 删除实例
lakeon db delete --name my-app-db --force
```

### US-7: 监控与告警

> 作为一名 DevOps 工程师，我想要实时监控所有数据库实例的健康状态和关键指标，并在异常时收到告警，以便及时发现和处理问题。

**示例**：通过 Grafana 仪表盘查看所有实例的连接数、查询延迟、存储用量、compute 状态。当 compute 唤醒失败或存储异常时收到告警通知。

## 6. 核心架构与模式

### 高层架构

```
                      ┌─────────────────────────────────────────┐
                      │           华为云 CCE (K8s)                │
                      │                                         │
  用户 ──psql──►      │  ┌─────────┐     ┌──────────────────┐   │
                      │  │  Proxy  │────►│  Compute (PG17)  │   │
  用户 ──API───►      │  └────┬────┘     └────────┬─────────┘   │
                      │       │                   │             │
                      │  ┌────▼────┐         ┌────▼─────────┐  │
                      │  │ Control │         │  Pageserver   │  │
                      │  │  Plane  │         └────┬──────────┘  │
                      │  └────┬────┘              │             │
                      │       │           ┌───────▼──────────┐  │
                      │  ┌────▼────┐      │   Safekeeper x3  │  │
                      │  │   DB    │      └───────┬──────────┘  │
                      │  │(元数据) │              │             │
                      │  └─────────┘      ┌───────▼──────────┐  │
                      │                   │  Storage Broker   │  │
                      │                   └──────────────────┘  │
                      └───────────────────────┬─────────────────┘
                                              │
                                    ┌─────────▼─────────┐
                                    │   华为云 OBS       │
                                    │  (S3 兼容存储)     │
                                    └───────────────────┘
```

### 组件职责

| 组件 | 来源 | 职责 |
|------|------|------|
| **Proxy** | Neon 开源 | PostgreSQL 协议代理，连接路由，compute 唤醒触发 |
| **Compute** | Neon（修改版 PG17） | 执行 SQL 查询，从 Pageserver 读取页面 |
| **Pageserver** | Neon 开源 | 存储引擎，管理页面版本，读写 OBS |
| **Safekeeper** | Neon 开源（3 节点） | WAL 持久化，共识协议保证数据不丢失 |
| **Storage Broker** | Neon 开源 | gRPC pub-sub，协调 Safekeeper 和 Pageserver |
| **Control Plane** | **自研** | 管控面 API，租户管理，compute 生命周期管理 |
| **CLI** | **自研** | 命令行工具，调用 Control Plane API |
| **元数据 DB** | PostgreSQL | 存储租户、实例、分支等管控元数据 |

### 关键设计模式

1. **Proxy-驱动唤醒**：Proxy 收到连接请求后，通过 Control Plane API 调用 `wake_compute()` 唤醒 compute。Neon proxy 原生支持此模式，需适配 Control Plane 接口。
2. **Kubernetes 原生调度**：Compute 节点作为 K8s Pod 运行，通过 K8s API 创建/销毁，利用 CCE 的调度和资源管理能力。
3. **S3 兼容存储**：Neon 的远程存储层使用标准 AWS S3 SDK，华为云 OBS 兼容 S3 API，通过配置 `endpoint` 参数即可对接。
4. **租户隔离**：每个租户对应 Neon 的一个 tenant，物理隔离在 Pageserver 层面；每个实例有独立的 compute Pod。

### 项目目录结构

```
lakeon/
├── lakeon-api/                 # Control Plane API（Spring Boot 3）
│   ├── src/main/java/com/lakeon/
│   │   ├── LakeonApplication.java
│   │   ├── controller/         # REST 控制器
│   │   ├── service/            # 业务逻辑层
│   │   ├── repository/         # 数据访问层（Spring Data JPA）
│   │   ├── model/              # 实体和 DTO
│   │   ├── neon/               # Neon 集成（Pageserver/Safekeeper API 调用）
│   │   ├── k8s/                # K8s 操作（compute Pod 管理）
│   │   └── config/             # 配置类
│   ├── src/main/resources/
│   │   └── application.yml     # Spring Boot 配置
│   └── pom.xml
├── lakeon-cli/                 # CLI 工具（Python + Click/Typer）
│   ├── lakeon_cli/
│   │   ├── __init__.py
│   │   ├── main.py             # CLI 入口
│   │   ├── commands/           # 命令模块（db, branch, tenant, config）
│   │   └── client.py           # API 客户端
│   ├── pyproject.toml
│   └── README.md
├── deploy/                     # 部署配置
│   ├── helm/                   # Helm Charts
│   ├── k8s/                    # K8s manifests
│   └── monitoring/             # Prometheus/Grafana 配置
├── rpiv/                       # RPIV 过程文件
└── docs/                       # 文档
```

## 7. 工具/功能

### 7.1 管控 API 功能

#### 租户管理
- 创建租户，自动生成 API Key
- 查看租户信息
- 列出租户下所有实例

#### 实例管理
- **创建实例**：指定名称（必填）、compute 规格（可选，默认 1CU）、休眠超时（可选，默认 5m），系统自动创建 Neon tenant + timeline + compute Pod，返回连接串
- **删除实例**：销毁 compute Pod，删除 Neon tenant 数据（支持 `--force` 跳过确认）
- **查看实例**：返回实例名称、状态（running/suspended/creating/error）、连接串、配置、存储用量
- **列出实例**：返回租户下所有实例列表
- **启动 compute**：手动唤醒已休眠的 compute
- **停止 compute**：手动休眠 compute，优雅关闭连接
- **更新配置**：调整 compute 规格、休眠超时（规格变更需重启 compute）

#### 分支管理
- **创建分支**：从实例的主分支 fork 创建新 timeline，可选是否自动启动 compute
- **删除分支**：销毁分支的 compute（如有），删除 timeline
- **列出分支**：返回实例下所有分支及其状态

### 7.2 CLI 工具功能

CLI 是管控 API 的命令行封装，命令结构：

```
lakeon <resource> <action> [flags]

资源类型：
  tenant    租户管理
  db        数据库实例管理
  branch    分支管理
  config    CLI 配置（API endpoint、API Key 等）
```

**核心命令**：

| 命令 | 说明 |
|------|------|
| `lakeon config set --api-url <url> --api-key <key>` | 配置 CLI |
| `lakeon db create --name <name> [--compute-size <size>] [--suspend-timeout <duration>]` | 创建实例 |
| `lakeon db list` | 列出所有实例 |
| `lakeon db status --name <name>` | 查看实例状态 |
| `lakeon db suspend --name <name>` | 休眠 compute |
| `lakeon db resume --name <name>` | 唤醒 compute |
| `lakeon db update --name <name> [flags]` | 更新配置 |
| `lakeon db delete --name <name> [--force]` | 删除实例 |
| `lakeon branch create --db <name> --name <branch>` | 创建分支 |
| `lakeon branch list --db <name>` | 列出分支 |
| `lakeon branch delete --db <name> --name <branch>` | 删除分支 |

### 7.3 Proxy 连接路由

基于 Neon proxy 组件，适配以下功能：

- **连接路由**：根据连接串中的数据库名/SNI 信息路由到对应 compute
- **自动唤醒**：检测 compute 状态，若已休眠则调用 Control Plane 唤醒，等待就绪后建立连接
- **错误处理**：唤醒失败时返回 PostgreSQL 标准错误消息，包含明确原因
- **分支路由**：支持通过连接参数指定分支

### 7.4 Compute 生命周期管理

- **创建**：通过 K8s API 创建 Pod，挂载 Neon compute_ctl，传入集群配置（Pageserver 地址、Safekeeper 地址、tenant/timeline ID）
- **启动流程**：compute_ctl 启动 → 同步 Safekeeper 获取 LSN → 从 Pageserver 获取 basebackup → 启动 PostgreSQL → 就绪
- **休眠**：优雅关闭 PostgreSQL → 销毁 Pod
- **唤醒**：重新创建 Pod → 执行启动流程 → 数据从 Pageserver 恢复，零丢失

## 8. 技术栈

### 管控面（Java）

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 (LTS) | 管控面 API 主要语言 |
| Spring Boot | 3.3+ | Web 框架 |
| Spring Data JPA | 3.3+ | ORM，管控元数据存储 |
| Spring Web | 3.3+ | RESTful API |
| Fabric8 Kubernetes Client | 6.x | K8s API 客户端 |
| PostgreSQL | 17 | 管控元数据存储 |
| Maven | 3.9+ | 构建工具 |

### CLI（Python）

| 技术 | 版本 | 用途 |
|------|------|------|
| Python | 3.11+ | CLI 工具语言 |
| Typer | 0.12+ | CLI 框架（基于 Click） |
| httpx | 0.27+ | HTTP 客户端，调用管控 API |
| rich | 13+ | 终端美化输出（表格、状态等） |

### Neon 组件

| 技术 | 版本 | 说明 |
|------|------|------|
| Neon | latest main | 存储引擎核心 |
| Rust | Neon 指定版本 | Neon 组件编译 |
| PostgreSQL | 17.5（Neon 修改版）| Compute 节点 |

### 基础设施

| 服务 | 说明 |
|------|------|
| 华为云 CCE | K8s 集群，运行所有组件 |
| 华为云 OBS | S3 兼容对象存储，Neon 持久化层 |
| Prometheus | 指标采集 |
| Grafana | 监控仪表盘和告警 |

### 依赖组件

| 组件 | 用途 |
|------|------|
| Neon Proxy | 连接路由和 compute 唤醒 |
| Neon Pageserver | 页面存储引擎 |
| Neon Safekeeper x3 | WAL 持久化和共识 |
| Neon Storage Broker | 存储节点协调 |
| Neon compute_ctl | Compute 节点管理 |

## 9. 安全与配置

### 认证

- **管控 API 认证**：API Key 认证，每个租户分配唯一的 API Key，通过 `Authorization: Bearer <api-key>` Header 传递
- **数据库连接认证**：标准 PostgreSQL 用户名/密码认证，创建实例时自动生成，通过连接串提供

### 配置管理

**管控面配置**（环境变量 + 配置文件）：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `LAKEON_API_PORT` | API 监听端口 | 8080 |
| `LAKEON_DB_DSN` | 元数据数据库连接串 | - |
| `LAKEON_NEON_PAGESERVER_URL` | Pageserver API 地址 | - |
| `LAKEON_NEON_SAFEKEEPER_URLS` | Safekeeper 地址列表 | - |
| `LAKEON_NEON_STORAGE_BROKER_URL` | Storage Broker 地址 | - |
| `LAKEON_OBS_ENDPOINT` | OBS S3 兼容端点 | - |
| `LAKEON_OBS_BUCKET` | OBS Bucket 名称 | - |
| `LAKEON_OBS_ACCESS_KEY` | OBS 访问密钥 | - |
| `LAKEON_OBS_SECRET_KEY` | OBS 密钥 | - |
| `LAKEON_K8S_NAMESPACE` | Compute Pod 部署命名空间 | `lakeon-compute` |
| `LAKEON_DEFAULT_COMPUTE_SIZE` | 默认 compute 规格 | `1cu` |
| `LAKEON_DEFAULT_SUSPEND_TIMEOUT` | 默认休眠超时 | `5m` |
| `LAKEON_DEFAULT_STORAGE_LIMIT` | 默认存储上限 | `10GB` |

### 安全范围

**范围内**：
- API Key 认证和鉴权
- PostgreSQL 密码认证
- K8s RBAC 限制管控面权限
- OBS 访问密钥安全存储（K8s Secret）

**范围外**：
- TLS/SSL 加密（第一版不强制）
- 网络策略隔离
- 审计日志
- 数据加密（at rest / in transit）

### 部署

- 所有组件部署在华为云 CCE K8s 集群
- 使用 Helm Charts 管理部署
- 单 AZ 部署，Safekeeper 3 节点在同 AZ 内不同节点
- 架构预留跨 AZ：Safekeeper 可通过配置分布到不同 AZ

## 10. API 规范

### 基础信息

- **Base URL**：`https://<api-host>/api/v1`
- **认证**：`Authorization: Bearer <api-key>`
- **响应格式**：JSON

### 端点定义

#### 租户

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tenants` | 创建租户 |
| GET | `/tenants/{tenant_id}` | 查看租户信息 |

#### 实例

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/databases` | 创建数据库实例 |
| GET | `/databases` | 列出所有实例 |
| GET | `/databases/{db_id}` | 查看实例详情 |
| PATCH | `/databases/{db_id}` | 更新实例配置 |
| DELETE | `/databases/{db_id}` | 删除实例 |
| POST | `/databases/{db_id}/suspend` | 休眠 compute |
| POST | `/databases/{db_id}/resume` | 唤醒 compute |

#### 分支

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/databases/{db_id}/branches` | 创建分支 |
| GET | `/databases/{db_id}/branches` | 列出分支 |
| GET | `/databases/{db_id}/branches/{branch_id}` | 查看分支详情 |
| DELETE | `/databases/{db_id}/branches/{branch_id}` | 删除分支 |

### 请求/响应示例

#### 创建实例

**请求**：
```json
POST /api/v1/databases
{
  "name": "my-app-db",
  "compute_size": "2cu",
  "suspend_timeout": "10m",
  "storage_limit_gb": 10
}
```

**响应**：
```json
{
  "id": "db_abc123",
  "name": "my-app-db",
  "status": "creating",
  "connection_uri": "postgres://user_xxx:pass_xxx@proxy.lakeon.example.com/my-app-db",
  "compute_size": "2cu",
  "suspend_timeout": "10m",
  "storage_limit_gb": 10,
  "storage_used_gb": 0,
  "branches": [
    {
      "id": "br_main",
      "name": "main",
      "is_default": true
    }
  ],
  "created_at": "2026-03-03T10:00:00Z"
}
```

#### 创建分支

**请求**：
```json
POST /api/v1/databases/db_abc123/branches
{
  "name": "feature-test",
  "start_compute": true
}
```

**响应**：
```json
{
  "id": "br_xyz789",
  "name": "feature-test",
  "parent_branch": "main",
  "status": "creating",
  "connection_uri": "postgres://user_xxx:pass_xxx@proxy.lakeon.example.com/my-app-db?branch=feature-test",
  "created_at": "2026-03-03T10:05:00Z"
}
```

#### 错误响应格式

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "Database 'my-app-db' not found",
    "details": {}
  }
}
```

## 11. 成功标准

### MVP 成功定义

在华为云上成功部署 LakeOn，能通过本地测试验证以下核心能力：

**功能要求**：
- ✅ 通过 API/CLI 创建数据库实例，获得连接串
- ✅ 使用 psql 通过连接串连接数据库，成功执行 DDL 和 DML
- ✅ Compute 在无活动超时后自动休眠（K8s Pod 被销毁）
- ✅ 用 psql 重新连接时 compute 自动唤醒，数据完好无损
- ✅ 创建数据库分支，分支有独立连接串，数据与主分支隔离
- ✅ 删除分支不影响主分支数据
- ✅ 多租户下不同租户的实例相互隔离
- ✅ Prometheus 能采集关键指标，Grafana Dashboard 可用

**质量指标**：
- Compute 唤醒成功率 > 99%（内部测试环境）
- 数据零丢失：compute 休眠/唤醒后数据完整性 100%
- API 响应时间 < 2s（管控操作）
- Pageserver 到 OBS 的数据持久化延迟 < 30s

**用户体验目标**：
- 创建实例到可用连接 < 60s
- 自动唤醒到连接建立 < 30s（第一版可接受较长时间）
- CLI 命令简洁直观，常用操作不超过 2 个参数

## 12. 实施阶段

### 阶段 1：基础设施与 Neon 部署（PoC 验证）

**目标**：在华为云 CCE 上部署完整 Neon 存储栈，验证 OBS 兼容性和基本功能。

**交付物**：
- ✅ CCE 集群创建和配置
- ✅ Neon 组件容器镜像构建（Pageserver、Safekeeper、Storage Broker、Proxy、Compute）
- ✅ K8s manifests / Helm Charts 编写
- ✅ OBS Bucket 配置和 S3 兼容性验证
- ✅ 单租户手动部署，本地 psql 连接成功读写
- ✅ 验证 compute 手动停止/启动后数据不丢失

**验证标准**：
- Neon 所有组件在 CCE 上正常运行
- OBS 作为远程存储正常工作（Pageserver 读写 OBS 无报错）
- psql 可连接并正常执行 SQL
- 手动销毁 compute Pod 后重建，数据完好

### 阶段 2：管控面 API 开发

**目标**：构建 Control Plane API，实现实例和分支的完整生命周期管理。

**交付物**：
- ✅ Control Plane API 服务（Go + Gin）
- ✅ 元数据数据库 Schema 和数据访问层
- ✅ 租户管理接口（创建租户、API Key 生成）
- ✅ 实例 CRUD 接口
- ✅ 分支管理接口
- ✅ Neon API 集成（调用 Pageserver/Safekeeper API 创建 tenant/timeline）
- ✅ K8s 集成（通过 client-go 管理 compute Pod）
- ✅ API Key 认证中间件

**验证标准**：
- 通过 API 创建/删除实例，底层 Neon 资源正确创建/清理
- 通过 API 创建/删除分支，对应 timeline 正确管理
- 多租户下实例互相隔离

### 阶段 3：Proxy 适配与自动休眠/唤醒

**目标**：适配 Neon Proxy，实现 compute 自动休眠和连接触发唤醒。

**交付物**：
- ✅ Neon Proxy 适配 Control Plane API（wake_compute 接口对接）
- ✅ 连接路由逻辑（根据数据库名/分支路由到正确 compute）
- ✅ Compute 自动休眠控制器（监控活动超时，调用 K8s API 销毁 Pod）
- ✅ 连接触发唤醒（Proxy → Control Plane → K8s 创建 Pod → 等待就绪）
- ✅ 错误处理（唤醒失败返回明确错误信息）

**验证标准**：
- 连接到已休眠实例时自动唤醒，连接成功建立
- 无活动超时后 compute Pod 被自动销毁
- 唤醒后数据完好无损
- 分支连接路由正确

### 阶段 4：CLI 工具与监控

**目标**：开发 CLI 工具，部署监控告警系统，完成端到端集成测试。

**交付物**：
- ✅ CLI 工具实现（所有管理命令）
- ✅ Prometheus 部署和指标采集配置
- ✅ Grafana Dashboard（实例状态、连接数、存储用量、唤醒延迟等）
- ✅ 告警规则配置（连接失败、存储异常、唤醒失败等）
- ✅ 端到端集成测试（创建 → 连接 → 写数据 → 休眠 → 唤醒 → 读数据 → 分支 → 删除）

**验证标准**：
- CLI 所有命令正常工作
- 监控仪表盘展示准确数据
- 告警能正确触发
- 端到端测试全部通过

## 13. 未来考虑

### MVP 后增强

- **Web 控制台**：提供可视化管理界面，降低使用门槛
- **计费系统**：基于 compute-hour + storage-GB 的按需计费
- **冷启动优化**：预热 compute 池、本地 SSD 缓存、优化启动流程，目标 100ms
- **连接池化**：集成 PgBouncer 提高连接效率
- **自动备份**：定期快照 + point-in-time recovery

### 集成机会

- **华为云 IAM**：集成华为云身份认证和权限管理
- **华为云 AOM**：对接华为云应用运维管理
- **华为云 SMN**：对接华为云消息通知服务用于告警

### 高级功能

- **跨 AZ 高可用**：Safekeeper 跨 AZ 部署，单 AZ 故障不丢数据
- **多 PG 版本**：支持 PG 15/16/17 用户自选
- **Point-in-time 分支**：基于任意历史时间点创建分支
- **SQL over HTTP**：支持 HTTP 方式执行 SQL，适用于 Edge/Serverless 函数
- **读副本**：只读 compute 节点，分担读负载
- **自动扩缩容**：根据负载自动调整 compute 规格

## 14. 风险与缓解措施

### 风险 1：华为云 OBS S3 兼容性

- **风险**：OBS 对 S3 API 的兼容可能存在细微差异，Neon 的存储层依赖特定 S3 行为（multipart upload、range read、list-objects-v2 等）
- **影响**：高 —— 存储层是核心依赖
- **缓解**：阶段 1 优先验证 OBS 兼容性；准备 MinIO 作为备选方案；记录所有不兼容点并评估是否可绕过

### 风险 2：Neon 上游更新维护成本

- **风险**：Neon 代码库更新频繁，长期维护 fork 或适配层的成本可能超出预期
- **影响**：中 —— 影响长期可维护性
- **缓解**：尽量通过配置和外部适配（而非修改 Neon 源码）实现定制；Control Plane 作为独立服务与 Neon 松耦合；跟踪 Neon 的 release 节奏，定期评估升级

### 风险 3：Compute 唤醒延迟

- **风险**：K8s Pod 创建 + Neon compute 启动流程（同步 Safekeeper + Pageserver basebackup）可能导致唤醒延迟超出用户预期
- **影响**：中 —— 影响用户体验
- **缓解**：第一版放宽延迟要求（接受数秒到十几秒）；记录各阶段耗时作为后续优化基准；后续通过 warm pool 和缓存优化

### 风险 4：Neon 组件容器化复杂度

- **风险**：Neon 组件（Rust 编译、PG 修改版）的容器镜像构建和 K8s 部署配置可能遇到兼容性和稳定性问题
- **影响**：中 —— 影响阶段 1 进度
- **缓解**：参考 Neon 官方 Docker 构建方案；从单节点部署开始逐步扩展；保持与 Neon 社区互动获取支持

### 风险 5：多租户资源隔离不足

- **风险**：基础多租户方案下，共享 Pageserver 和 Safekeeper 可能导致租户间性能相互影响
- **影响**：低（第一版内部使用） —— 后续商业化时影响提升
- **缓解**：第一版接受共享资源（内部使用场景租户少）；监控各租户资源使用；后续版本引入资源配额和 QoS 机制

## 15. 附录

### 关键依赖

| 依赖 | 链接/位置 | 说明 |
|------|-----------|------|
| Neon 源码 | `/Users/jacky/code/neon` | 存储引擎核心，Apache 2.0 协议 |
| Neon 文档 | https://neon.tech/docs | 官方文档 |
| 华为云 OBS | 华为云控制台 | S3 兼容对象存储 |
| 华为云 CCE | 华为云控制台 | Kubernetes 服务 |

### Neon 关键 API 端点（Pageserver）

| 端点 | 说明 |
|------|------|
| `POST /v1/tenant` | 创建 tenant |
| `DELETE /v1/tenant/{tenant_id}` | 删除 tenant |
| `POST /v1/tenant/{tenant_id}/timeline` | 创建 timeline（分支） |
| `DELETE /v1/tenant/{tenant_id}/timeline/{timeline_id}` | 删除 timeline |
| `GET /v1/tenant/{tenant_id}/timeline` | 列出 timelines |

### Neon S3 存储配置

Neon 通过以下配置对接 S3 兼容存储：
- `remote_storage.bucket_name` — OBS Bucket 名称
- `remote_storage.bucket_region` — OBS Region
- `remote_storage.endpoint` — OBS S3 兼容端点 URL
- `remote_storage.prefix_in_bucket` — Bucket 内路径前缀（用于多租户隔离）
- 凭证通过 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` 环境变量提供

### Compute 规格定义

| 规格 | CPU | 内存 | 说明 |
|------|-----|------|------|
| 1cu | 1 vCPU | 2 GB | 默认，适合开发测试 |
| 2cu | 2 vCPU | 4 GB | 小型应用 |
| 4cu | 4 vCPU | 8 GB | 中型应用 |
| 8cu | 8 vCPU | 16 GB | 较大负载 |
