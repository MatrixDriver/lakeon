# Lakeon Serverless PostgreSQL - 架构文档

## 1. 项目概述

Lakeon 是一个基于 Neon 开源项目构建的 Serverless PostgreSQL 云服务平台，部署在华为云（CCE + OBS）上。核心能力：

- **存算分离**：计算节点按需启停，存储层持久化到对象存储
- **自动休眠/唤醒**：无活动连接时自动回收计算资源，连接到来时自动拉起
- **数据库分支**：类似 Git 的 copy-on-write 分支机制
- **多租户隔离**：API Key 认证，租户间数据完全隔离
- **弹性算力**：1cu ~ 8cu 按需配置

技术栈：

| 层次 | 技术选型 |
|------|----------|
| 管控面 API | Java 17 + Spring Boot 3.3.5 |
| CLI 客户端 | Python 3.11+ + Typer |
| 存储引擎 | Neon (Pageserver + Safekeeper + Storage Broker) |
| 计算节点 | Neon compute-node (PostgreSQL 17) |
| 连接代理 | Neon Proxy |
| 容器编排 | Kubernetes (华为云 CCE) + Fabric8 Client |
| 对象存储 | 华为云 OBS (S3 兼容) |
| 元数据库 | 华为云 RDS PostgreSQL (VPC 内网直连) |
| Web 控制台 | Vue 3 + TypeScript + TinyVue (华为云风格) |
| SRE 运维控制台 | Vue 3 + TypeScript (独立部署的 lakeon-admin) |
| 成本监控 | 华为云 CBC 账单 API + 资源单价预估 |
| 可观测性 | Micrometer + Prometheus + 自建告警 + K8s Metrics API |

---

## 2. 整体架构

![Lakeon 架构图](lakeon-arch.png)

```
                        ┌──────────────┐
                        │   用户/应用   │
                        └──────┬───────┘
                               │
               ┌───────────────┼───────────────┐
               │               │               │
         PostgreSQL 连接   REST API / CLI   Web 控制台
         (端口 4432)       (端口 8080)     (端口 80)
               │               │               │
               ▼               ▼               ▼
       ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
       │  Neon Proxy  │─│  Lakeon API  │◀│ Web 控制台   │ │ SRE Admin    │
       │  (ELB:4432)  │▶│ (Spring Boot)│ │ (Vue 3 SPA)  │ │ (Vue 3 SPA)  │
       └──────┬───────┘ └──────┬───────┘ │ Nginx 反代   │ │ Admin Token  │
              │                │         └──────────────┘ └──────┬───────┘
              │         ┌──────┼──────┐                          │
              │         │      │      │                          │
              ▼         ▼      ▼      ▼                          ▼
     ┌────────────┐ ┌──────┐┌─────┐┌──────────┐        ┌──────────────┐
     │ Compute Pod│ │ K8s  ││ RDS ││ CBC API  │        │  Lakeon API  │
     │ (按需创建)  │ │ API  ││(VPC)││(华为云)   │        │  /admin/*    │
     │ PG17:55433 │ └──────┘└─────┘└──────────┘        └──────────────┘
     └──────┬─────┘
            │
            ▼
     ┌────────────────┐
     │  Pageserver    │
     │  :9898 / :6400 │
     └────────┬───────┘
              │
     ┌────────┴────────┐
     │                 │
     ▼                 ▼
┌──────────────┐ ┌──────────────┐
│  Safekeeper  │ │  华为云 OBS  │
│  (WAL 持久化)│ │  (S3 兼容)   │
└──────────────┘ └──────────────┘
```

### 2.1 组件职责

| 组件 | 职责 | 副本数 | 端口 |
|------|------|--------|------|
| **Lakeon API** | 管控面，数据库/分支 CRUD，Compute 生命周期管理，Admin API | 1 | 8080 |
| **Neon Proxy** | PG 连接路由，SCRAM 认证，自动唤醒计算节点 | 1 | 4432 (PG), 7000 (HTTP) |
| **Compute Pod** | PostgreSQL 17 实例，按需创建/销毁 | 动态 (0~N) | 55433 (PG), 3080 (HTTP) |
| **Pageserver** | 页面服务，缓存热数据，从 OBS 读取冷数据 | 1 | 9898 (HTTP), 6400 (PG) |
| **Safekeeper** | WAL 持久化，quorum 保证零数据丢失 | 1 | 5454 (PG), 7676 (HTTP) |
| **Storage Broker** | 协调 Pageserver 与 Safekeeper 的远程存储 I/O | 1 | 50051 (gRPC) |
| **Web 控制台** | 用户自助管理界面（华为云风格），Nginx 反向代理 API 请求 | 1 | 80 (HTTP) |
| **SRE 运维控制台** | 管理员监控和运维界面，Admin Token 认证 | 1 | 80 (HTTP) |
| **RDS (元数据 DB)** | 存储租户、数据库实例、分支、操作日志等管理数据 | 华为云 RDS | 5432 |

### 2.2 存算分离原理

Neon 将 PostgreSQL 的存储层替换为自研的分布式存储：

```
传统 PostgreSQL:    Compute ←→ 本地磁盘
Neon 架构:          Compute ←→ Pageserver ←→ OBS
                                   ↕
                              Safekeeper (WAL)
```

- **Compute** 不持有任何数据，只是一个无状态的 PG 进程
- **Pageserver** 负责页面读取，优先从内存缓存返回，缓存未命中则从 OBS 拉取
- **Safekeeper** 接收 WAL 写入，3 副本 quorum 确认后返回，异步归档到 OBS
- **OBS** 作为最终持久化层，存储所有页面和 WAL 数据

这意味着 Compute 可以随时销毁和重建，数据不会丢失。

---

## 3. 数据模型

### 3.1 实体关系

```
Tenant (1) ──────< (N) Database (1) ──────< (N) Branch
  │                      │                        │
  │ api_key              │ neon_tenant_id          │ neon_timeline_id
  │                      │ neon_timeline_id        │ parent_branch_id
  │                      │ compute_pod_name        │ compute_pod_name
  │                      │ db_user / db_password   │
  │                      │ connection_uri          │ connection_uri
```

### 3.2 表结构

#### tenants

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 格式 `tn_xxxxxxxx` |
| name | VARCHAR(255) UNIQUE | 租户名称 |
| api_key | VARCHAR(128) UNIQUE | 格式 `lk_` + 64位十六进制，SecureRandom 生成 |
| max_databases | INT | 最大数据库数量配额，默认 5 |
| max_storage_gb | INT | 最大存储配额 (GB)，默认 50 |
| max_compute_cu | INT | 最大计算配额 (CU)，默认 8 |
| created_at | TIMESTAMP WITH TZ | 创建时间 |
| updated_at | TIMESTAMP WITH TZ | 更新时间 |

#### database_instances

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 格式 `db_xxxxxxxx` |
| tenant_id | VARCHAR(64) FK | 所属租户 |
| name | VARCHAR(255) | 租户内唯一 |
| status | VARCHAR(20) | CREATING / RUNNING / SUSPENDED / ERROR / DELETING |
| neon_tenant_id | VARCHAR(64) | 对应 Neon Pageserver 的 tenant |
| neon_timeline_id | VARCHAR(64) | 对应 Neon 的 timeline (主分支) |
| compute_size | VARCHAR(10) | 1cu / 2cu / 4cu / 8cu |
| suspend_timeout | VARCHAR(10) | 如 `5m`, `1h` |
| storage_limit_gb | INT | 存储上限，默认 10GB |
| db_user | VARCHAR(64) | 自动生成 `user_xxxxxxxx` |
| db_password | TEXT | SCRAM-SHA-256 哈希 |
| compute_pod_name | VARCHAR(128) | K8s Pod 名称，SUSPENDED 时为 null |
| compute_host | VARCHAR(128) | Pod IP |
| compute_port | INT | 默认 55433 |
| connection_uri | TEXT | `postgres://user@host:port/dbname`（不含密码） |
| last_active_at | TIMESTAMP WITH TZ | 最后活跃时间，用于自动休眠判断 |

UNIQUE 约束：`(tenant_id, name)`

#### branches

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(64) PK | 格式 `br_xxxxxxxx` |
| database_id | VARCHAR(64) FK | 所属数据库 |
| name | VARCHAR(255) | 数据库内唯一 |
| neon_timeline_id | VARCHAR(64) | 分支对应的独立 timeline |
| parent_branch_id | VARCHAR(64) | 父分支 ID |
| parent_branch_name | VARCHAR(255) | 父分支名称 |
| is_default | BOOLEAN | 是否为主分支 (main) |
| status | VARCHAR(20) | CREATING / ACTIVE / DELETING / ERROR |
| compute_pod_name | VARCHAR(128) | 分支独立的 Compute Pod |
| connection_uri | TEXT | 分支连接地址 |

UNIQUE 约束：`(database_id, name)`

#### operation_logs

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | 自增 ID |
| database_id | VARCHAR(64) | 关联数据库 |
| tenant_id | VARCHAR(64) | 关联租户 |
| database_name | VARCHAR(255) | 数据库名称（冗余，方便查询） |
| operation_type | VARCHAR(20) | CREATE / SUSPEND / RESUME / DELETE / UPDATE |
| status | VARCHAR(20) | IN_PROGRESS / SUCCESS / FAILED |
| duration_ms | BIGINT | 操作耗时（毫秒），等待 Pod Ready 后记录 |
| error_message | TEXT | 失败时的错误信息 |
| started_at | TIMESTAMP WITH TZ | 操作开始时间 |
| completed_at | TIMESTAMP WITH TZ | 操作完成时间 |

索引：`(tenant_id, started_at DESC)`, `(operation_type, started_at DESC)`

### 3.3 Compute 规格

| 规格 | CPU | 内存 |
|------|-----|------|
| 1cu | 1 core | 2 GiB |
| 2cu | 2 cores | 4 GiB |
| 4cu | 4 cores | 8 GiB |
| 8cu | 8 cores | 16 GiB |

---

## 4. 核心流程

### 4.1 创建数据库

```
用户 ──POST /api/v1/databases──▶ Lakeon API
                                    │
                        1. 校验名称唯一性
                        2. 生成 db_user + 密码 (SecureRandom)
                        3. SCRAM-SHA-256 哈希密码
                                    │
                        4. ──▶ Pageserver: PUT /v1/tenant/{id}/location_config
                              创建 Neon Tenant (AttachedSingle 模式)
                                    │
                        5. ──▶ Pageserver: POST /v1/tenant/{id}/timeline
                              创建 Neon Timeline (Bootstrap, PG 17)
                                    │
                        6. ──▶ K8s API: 创建 ConfigMap (config.json)
                              包含 tenant_id, timeline_id, 连接信息, 凭据
                                    │
                        7. ──▶ K8s API: 创建 Pod (compute-db_xxx)
                              挂载 ConfigMap, 启动 compute_ctl + PostgreSQL
                                    │
                        8. 创建默认分支 "main"
                        9. 保存元数据到 DB
                                    │
                    ◀── 返回 connection_uri + 密码 (仅此一次返回密码)
```

任何步骤失败时会回滚已创建的 Neon 资源。

### 4.2 客户端连接 (通过 Proxy 自动唤醒)

```
psql postgres://user@proxy:4432/mydb
        │
        ▼
   Neon Proxy
        │
        ├─▶ GET /proxy/get_endpoint_access_control?endpointish=mydb&role=user
        │   Lakeon API 返回 SCRAM 哈希 → Proxy 完成 SCRAM 认证
        │
        ├─▶ GET /proxy/wake_compute?endpointish=mydb
        │   如果数据库处于 SUSPENDED 状态:
        │     Lakeon API 创建 Compute Pod → 等待就绪 → 返回 Pod 地址
        │   如果已经 RUNNING:
        │     直接返回现有 Pod 地址
        │
        ▼
   Proxy 将连接路由到 Compute Pod :55433
        │
        ▼
   Compute Pod 处理 SQL ←→ Pageserver (读页面) ←→ Safekeeper (写 WAL)
```

### 4.3 自动休眠

```
ComputeLifecycleService (@Scheduled, 每 30 秒)
        │
        ├── 查询所有 status=RUNNING 的数据库
        │
        ├── 对每个实例检查:
        │     elapsed = 当前时间 - lastActivityTime
        │     timeout = parseDuration(suspend_timeout)  // 默认 5m
        │
        │     如果 elapsed > timeout:
        │       ├── 删除 Compute Pod
        │       ├── 清理 ConfigMap
        │       ├── 设置 status = SUSPENDED
        │       └── 清空 compute_host / compute_port / compute_pod_name
        │
        └── ReentrantLock 防止并发执行
```

### 4.4 数据库分支

分支利用 Neon 的 timeline 机制实现 copy-on-write 快照：

```
                    main (timeline-001)
                      │
              branch create "dev"
                      │
                      ├──▶ Pageserver: POST /v1/tenant/{id}/timeline
                      │    ancestor_timeline_id = timeline-001
                      │    → 创建新 timeline-002 (共享父 timeline 的页面)
                      │
                      ├──▶ 可选：创建独立 Compute Pod
                      │
                      ▼
                    dev (timeline-002)
                    共享 main 的历史数据，独立写入新数据
```

分支间完全隔离，写入不会互相影响，读取未修改的页面时从父 timeline 获取。

---

## 5. 部署架构

### 5.1 Kubernetes 资源清单

```
lakeon 命名空间
├── Deployment: lakeon-api (1 副本)
├── Deployment: lakeon-admin (1 副本, SRE 运维控制台)
├── Deployment: lakeon-console (1 副本, Web 控制台)
├── Deployment: proxy (1 副本)
├── Deployment: pageserver (1 副本)
├── Deployment: storage-broker (1 副本)
├── StatefulSet: safekeeper (1 副本, Headless Service)
├── ConfigMap: lakeon-api-config
├── Secret: api-credentials (DB 密码, Proxy Token, Admin Token)
├── Secret: obs-credentials (OBS AK/SK)
├── Service: lakeon-api (ClusterIP :8080)
├── Service: lakeon-admin (LoadBalancer, ELB:8081 → :80)
├── Service: lakeon-console (LoadBalancer, ELB:80 → :80)
├── Service: proxy (LoadBalancer, ELB:4432 → :4432)
├── Service: pageserver (ClusterIP :9898, :6400)
├── Service: safekeeper (Headless)
└── Service: storage-broker (ClusterIP :50051)

lakeon-compute 命名空间 (动态)
├── Pod: compute-db-xxx (按需创建)
├── ConfigMap: compute-db-xxx-config (按需创建)
└── ...
```

### 5.2 存储架构

```
华为云 OBS (S3 兼容)
└── lakeon-storage (Bucket)
    ├── pageserver/        Pageserver 页面数据
    │   └── tenants/
    │       └── {tenant_id}/
    │           └── timelines/
    │               └── {timeline_id}/
    └── safekeeper/        WAL 归档
        └── {tenant_id}/
            └── {timeline_id}/
```

Neon 仅使用 5 个 S3 API，与 OBS 完全兼容：
- PutObject, GetObject (Range), HeadObject, DeleteObjects, ListObjectsV2

### 5.3 网络拓扑 (华为云 CCE)

```
外部流量 (EIP: 114.116.210.49)
    │
    ▼
[华为云 ELB (共享型)]
    │
    ├── :4432 ──▶ Proxy Service ──▶ Proxy Pod
    │                                  │
    │                          ┌───────┴───────┐
    │                          ▼               ▼
    │                   Compute Pods     Lakeon API
    │                   (:55433)         (/proxy/*)
    │
    ├── :80   ──▶ Console Service ──▶ Web 控制台 Pod (Nginx → API)
    │
    ├── :8081 ──▶ Admin Service ──▶ SRE 运维控制台 Pod (Nginx → API)
    │
    └── :8080 (ClusterIP, 仅集群内部)
              ──▶ API Service ──▶ Lakeon API Pod
                                       │
                              ┌────────┼────────┐
                              ▼        ▼        ▼
                         RDS (VPC)  Pageserver  K8s API
                         :5432      :9898

集群内部通信 (VPC 内网)
    Compute Pod ──▶ Pageserver (:6400, FQDN 跨 namespace)
    Compute Pod ──▶ Safekeeper (:5454, FQDN 跨 namespace)
    Pageserver  ──▶ Storage Broker (:50051) ──▶ OBS (VPC 内网)
    Safekeeper  ──▶ Storage Broker (:50051) ──▶ OBS (VPC 内网)
    Lakeon API  ──▶ RDS (:5432, VPC 内网直连)
```

---

## 6. 安全设计

### 6.1 认证体系

```
┌─────────────────────────────────────────────────┐
│                  认证分层                        │
├─────────────────────────────────────────────────┤
│                                                 │
│  用户 ──Bearer API Key──▶ Lakeon API            │
│         (lk_xxxx...)      ApiKeyFilter 校验     │
│                                                 │
│  SRE ──Bearer Admin Token──▶ Lakeon API /admin/*│
│         (静态 Token)         AdminTokenFilter   │
│                                                 │
│  Proxy ──Bearer Token──▶ Lakeon API /proxy/*    │
│         (内部 Token)      ApiKeyFilter 校验     │
│                                                 │
│  客户端 ──SCRAM-SHA-256──▶ Neon Proxy            │
│         (PG 密码认证)      从 API 获取哈希校验  │
│                                                 │
│  无认证: POST /api/v1/tenants (租户注册)        │
│  无认证: /actuator/* (健康检查/指标)            │
│                                                 │
└─────────────────────────────────────────────────┘
```

### 6.2 关键安全措施

| 措施 | 说明 |
|------|------|
| API Key 生成 | SecureRandom 32 字节，十六进制编码 |
| 数据库密码 | SCRAM-SHA-256 哈希存储，明文仅在创建时返回一次 |
| 连接串 | connection_uri 不包含密码 |
| Compute 配置 | 通过 ConfigMap 挂载，避免 shell echo 命令注入 |
| Proxy 端点 | 独立内部 Token 认证，与用户 API Key 隔离 |
| URL 安全 | NeonApiClient 所有路径参数经 URLEncoder 编码 |

---

## 7. 监控与运维

### 7.1 SRE 运维控制台 (lakeon-admin)

独立部署的管理界面，通过 Admin Token 认证，提供以下功能：

| 功能 | 说明 |
|------|------|
| **总览仪表盘** | 租户/实例统计、24h 操作统计、成本预估、组件状态灯 |
| **租户管理** | 列表、搜索、配额调整、批量删除 |
| **数据库实例** | 全局列表（跨租户）、按状态/租户筛选、批量删除 |
| **操作审计** | 全局操作日志、按租户/类型/状态筛选、分页 |
| **组件健康** | Pageserver/Safekeeper/Proxy/RDS 连通性检查、唤醒延迟 P50/P90/P99 |
| **成本监控** | CBC 实际账单 + 预估成本双模式、每小时/天/月拆分、租户成本分摊 |
| **应用指标** | JVM / API 延迟 / Compute / 数据库 / 存储实时指标卡片 + Canvas 趋势图 |
| **日志查看** | 终端风格日志面板，支持 pageserver/safekeeper/proxy/api/compute 切换 |
| **告警管理** | 5 条内置巡检规则、Webhook 通知（企业微信/钉钉）、告警历史 |
| **基础设施** | K8s 节点 CPU/内存使用率 + Pod 资源排行 |

### 7.2 成本监控

双模式成本追踪：

**CBC 实际账单**：通过华为云 BSS API（AK/SK HMAC-SHA256 签名），获取当月实际消费按云服务拆分明细。需要 CCE Pod 可出公网（NAT 网关 + SNAT 规则）。

**预估成本**：基于 Helm values 中的资源单价配置（`cost.*`），结合实际资源数量计算：

| 资源 | 计算方式 |
|------|----------|
| CCE 节点 | `cceNodeHourly × nodeCount × 24 × 30` |
| ELB | 固定月费 |
| RDS | 固定月费 |
| EIP | 固定月费 |
| OBS | `数据库数 × 平均存储 × obsPerGbMonthly` |

租户成本分摊基于 compute CU·hours（从 operation_logs 计算实际运行时长）和存储用量。

### 7.3 可观测性（Stage 8a）

自建可观测性体系，内嵌到 SRE 控制台：

**自定义 Micrometer 指标**（同时供自建仪表盘和 AOM 使用）：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `lakeon_tenants_total` | Gauge | 租户总数 |
| `lakeon_databases_total` | Gauge（tag: status） | 数据库总数 |
| `lakeon_compute_pods_active` | Gauge | 活跃 compute pod |
| `lakeon_compute_wakeup_seconds` | Timer | 唤醒耗时 |
| `lakeon_compute_wakeup_failures_total` | Counter | 唤醒失败 |
| `lakeon_storage_used_bytes` | Gauge | OBS 已用存储 |
| `lakeon_api_operations_total` | Counter（tag: type, status） | 操作计数 |

**日志查看器**：通过 Fabric8 K8s Client 读取 Pod 日志（`tailingLines` API），支持 pageserver / safekeeper / proxy / api / compute-{dbId} 组件切换。

**告警服务**（`AlertService`）：每 60 秒巡检 5 条规则（组件宕机、唤醒失败、API 高延迟、存储超限、Pod 异常），通过 Webhook 发送通知（企业微信/钉钉/通用），支持冷却时间。

**基础设施监控**：通过 K8s `metrics.k8s.io/v1beta1` API 获取节点 CPU/内存使用率和 Pod 资源占用。

### 7.4 组件健康检查

| 组件 | 检查方式 |
|------|----------|
| Pageserver | HTTP GET /v1/status |
| Safekeeper | 配置地址可达性 |
| Proxy | 服务可达性 |
| RDS | JDBC 连接测试 |

### 7.4 唤醒延迟统计

从 operation_logs 中筛选 RESUME/CREATE 类型的成功操作，计算 P50/P90/P99 延迟。API 在 `createComputePod` 后等待 Pod Ready（最长 60s）才记录 `completeOperation`，确保 duration_ms 反映真实的 compute 启动耗时。

---

## 8. REST API 参考

Base URL: `http://<api-host>:8080/api/v1`

认证：`Authorization: Bearer <api_key>`（除特别说明外）

### 8.1 租户管理

#### 创建租户

```
POST /api/v1/tenants          (无需认证)

Request:
{
  "name": "my-team"
}

Response: 201 Created
{
  "id": "tn_a1b2c3d4",
  "name": "my-team",
  "api_key": "lk_3f8a1c2d...",     ← 仅此一次返回，妥善保存
  "created_at": "2026-03-03T12:00:00Z"
}
```

#### 查看租户

```
GET /api/v1/tenants/{tenantId}

Response: 200 OK
{
  "id": "tn_a1b2c3d4",
  "name": "my-team",
  "created_at": "2026-03-03T12:00:00Z"
}
```

注意：GET 不返回 api_key。

### 8.2 数据库管理

#### 创建数据库

```
POST /api/v1/databases

Request:
{
  "name": "my-app-db",
  "compute_size": "2cu",           // 可选，默认 1cu
  "suspend_timeout": "10m",        // 可选，默认 5m
  "storage_limit_gb": 20           // 可选，默认 10
}

Response: 201 Created
{
  "id": "db_x1y2z3w4",
  "name": "my-app-db",
  "status": "CREATING",
  "connection_uri": "postgres://user_abc12345@10.0.1.5:55433/my-app-db",
  "password": "Abc123XyzRandomPwd",    ← 仅此一次返回
  "compute_size": "2cu",
  "suspend_timeout": "10m",
  "storage_limit_gb": 20,
  "storage_used_gb": 0.0,
  "branches": [
    {
      "id": "br_m1n2o3p4",
      "name": "main",
      "is_default": true,
      "status": "active"
    }
  ],
  "created_at": "2026-03-03T12:00:00Z"
}
```

#### 列出数据库

```
GET /api/v1/databases

Response: 200 OK
[
  { "id": "db_x1y2z3w4", "name": "my-app-db", "status": "RUNNING", ... },
  { "id": "db_a5b6c7d8", "name": "staging-db", "status": "SUSPENDED", ... }
]
```

#### 查看数据库详情

```
GET /api/v1/databases/{dbId}

Response: 200 OK
{ ... }    // 同创建响应，但不含 password 字段
```

#### 更新配置

```
PATCH /api/v1/databases/{dbId}

Request:
{
  "compute_size": "4cu",
  "suspend_timeout": "15m",
  "storage_limit_gb": 50
}

Response: 200 OK
{ ... }    // 更新后的完整数据库信息
```

如果修改了 compute_size 且数据库正在运行，会自动重启 Compute Pod。

#### 删除数据库

```
DELETE /api/v1/databases/{dbId}

Response: 204 No Content
```

删除操作会清理：所有分支的 Compute Pod → Neon Timeline → Neon Tenant → 元数据记录。采用 best-effort 策略，单个资源清理失败不阻塞其余清理。

#### 休眠 / 唤醒

```
POST /api/v1/databases/{dbId}/suspend

Response: 200 OK
```

```
POST /api/v1/databases/{dbId}/resume

Response: 200 OK
```

### 8.3 分支管理

#### 创建分支

```
POST /api/v1/databases/{dbId}/branches

Request:
{
  "name": "dev-feature",
  "start_compute": true        // 可选，是否立即启动 Compute
}

Response: 201 Created
{
  "id": "br_e5f6g7h8",
  "name": "dev-feature",
  "parent_branch": "main",
  "is_default": false,
  "status": "creating",
  "connection_uri": "postgres://user_abc@10.0.1.5:55433/my-app-db?branch=dev-feature",
  "created_at": "2026-03-03T13:00:00Z"
}
```

#### 列出分支

```
GET /api/v1/databases/{dbId}/branches

Response: 200 OK
[
  { "id": "br_m1n2o3p4", "name": "main", "is_default": true, "status": "active" },
  { "id": "br_e5f6g7h8", "name": "dev-feature", "is_default": false, "status": "active" }
]
```

#### 删除分支

```
DELETE /api/v1/databases/{dbId}/branches/{branchId}

Response: 204 No Content
```

注意：不能删除默认分支 (main)。

### 8.4 错误响应格式

所有错误遵循统一格式：

```json
{
  "error": {
    "code": "UNAUTHORIZED | RESOURCE_NOT_FOUND | CONFLICT | VALIDATION_ERROR | SERVICE_EXCEPTION",
    "message": "Human readable error description"
  }
}
```

| HTTP 状态码 | 错误码 | 场景 |
|------------|--------|------|
| 401 | UNAUTHORIZED | 缺少或无效的 API Key |
| 403 | FORBIDDEN | Proxy 内部 Token 无效 |
| 404 | RESOURCE_NOT_FOUND | 数据库或分支不存在 |
| 409 | CONFLICT | 名称已存在 |
| 400 | VALIDATION_ERROR | 请求参数校验失败 |
| 500 | SERVICE_EXCEPTION | 内部服务错误 |

### 8.5 Admin API (SRE 运维)

Base URL: `/api/v1/admin`，认证：`Authorization: Bearer <admin_token>`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/dashboard` | GET | 总览仪表盘（租户/实例统计、操作统计、成本、健康） |
| `/tenants` | GET | 租户列表 |
| `/tenants/{id}/quota` | PUT | 更新租户配额（maxDatabases, maxStorageGb, maxComputeCu） |
| `/tenants/batch` | DELETE | 批量删除租户（含其下所有数据库） |
| `/databases` | GET | 全局数据库列表（?status=&tenant_id= 筛选） |
| `/databases/batch` | DELETE | 批量删除数据库 |
| `/operations` | GET | 全局操作日志（?tenant_id=&type=&status=&page=&size= 分页） |
| `/compute/stats` | GET | 唤醒延迟统计（P50/P90/P99） |
| `/system/health` | GET | 全部组件健康检查 |
| `/system/health/{component}` | GET | 单组件健康检查 |
| `/cost/summary` | GET | 预估月成本总览 |
| `/cost/tenants` | GET | 按租户成本分摊 |
| `/cost/cbc` | GET | 华为云 CBC 实际账单（?bill_cycle=YYYY-MM） |
| `/usage/tenants` | GET | 全租户用量（?from=&to= 时间范围） |
| `/usage/tenants/{id}` | GET | 单租户用量 |
| `/usage/databases/{id}` | GET | 单数据库用量 |
| `/logs/{component}` | GET | 组件日志（?tail=200，component: pageserver/safekeeper/proxy/api/compute-{dbId}） |
| `/metrics/summary` | GET | 应用指标汇总（JVM/API/Compute/DB/Storage） |
| `/alerts` | GET | 告警历史列表 |
| `/alerts/rules` | GET | 告警规则列表 |
| `/alerts/rules/{id}` | PUT | 更新告警规则（enabled/threshold/webhookUrl） |
| `/alerts/test-webhook` | POST | 测试 Webhook 通知 |
| `/infra/nodes` | GET | K8s 节点资源使用 + Pod 资源排行 |

### 8.6 内部 Proxy 适配接口

供 Neon Proxy 回调，不对外暴露，需 Bearer 内部 Token 认证：

```
GET /proxy/wake_compute?endpointish={db_name}&session_id=...
→ { "address": "host:port", "aux": { "endpoint_id", "cold_start_info": "warm|pool_miss" } }

GET /proxy/get_endpoint_access_control?endpointish={db_name}&role={user}&session_id=...
→ { "role_secret": "SCRAM hash", "project_id": "...", "allowed_ips": [] }
```

---

## 9. CLI 使用指南

### 9.1 安装

```bash
cd lakeon-cli
pip install -e .
```

安装后可使用 `lakeon` 命令。

### 9.2 配置

```bash
# 设置 API 地址和 API Key
lakeon config set --api-url http://lakeon-api:8080 --api-key lk_3f8a1c2d...

# 查看当前配置
lakeon config show
# → API URL : http://lakeon-api:8080
# → API Key : lk_3f8a1c...
```

配置保存在 `~/.lakeon/config.toml`。

### 9.3 租户管理

```bash
# 创建租户（不需要认证）
lakeon tenant create --name my-team
# → Tenant created: tn_a1b2c3d4
# → API Key: lk_3f8a1c2d...     ← 保存好这个 Key
```

### 9.4 数据库操作

```bash
# 创建数据库
lakeon db create --name my-app-db --compute-size 2cu --suspend-timeout 10m
# → Database created: db_x1y2z3w4
# → Name: my-app-db
# → Connection URI: postgres://user_abc@10.0.1.5:55433/my-app-db

# 列出所有数据库
lakeon db list
# → ┌────────────┬───────────┬──────────┬──────────────┬──────────────┐
# → │ ID         │ Name      │ Status   │ Compute Size │ Created At   │
# → ├────────────┼───────────┼──────────┼──────────────┼──────────────┤
# → │ db_x1y2z3w4│ my-app-db │ RUNNING  │ 2cu          │ 2026-03-03   │
# → └────────────┴───────────┴──────────┴──────────────┴──────────────┘

# 查看详情
lakeon db status --name my-app-db

# 修改配置
lakeon db update --name my-app-db --compute-size 4cu --suspend-timeout 15m

# 手动休眠（释放计算资源，数据不丢失）
lakeon db suspend --name my-app-db

# 手动唤醒
lakeon db resume --name my-app-db

# 删除数据库（会要求确认）
lakeon db delete --name my-app-db

# 跳过确认直接删除
lakeon db delete --name my-app-db --force
```

### 9.5 分支操作

```bash
# 创建分支（基于 main 的 copy-on-write 快照）
lakeon branch create --db my-app-db --name dev-feature
# → Branch created: br_e5f6g7h8
# → Name: dev-feature
# → Connection URI: postgres://user_abc@10.0.1.5:55433/my-app-db?branch=dev-feature

# 列出分支
lakeon branch list --db my-app-db
# → ┌────────────┬─────────────┬────────┬─────────┐
# → │ ID         │ Name        │ Status │ Default │
# → ├────────────┼─────────────┼────────┼─────────┤
# → │ br_m1n2o3p4│ main        │ active │ True    │
# → │ br_e5f6g7h8│ dev-feature │ active │ False   │
# → └────────────┴─────────────┴────────┴─────────┘

# 删除分支
lakeon branch delete --db my-app-db --name dev-feature
```

### 9.6 典型工作流

```bash
# === 初始化 ===
lakeon tenant create --name my-team
lakeon config set --api-url http://api.lakeon.example.com --api-key lk_xxx

# === 创建生产库 ===
lakeon db create --name prod-db --compute-size 4cu --suspend-timeout 30m
# → 记录返回的密码和 connection_uri

# === 连接数据库 ===
# 方式 1: 通过 Proxy（推荐，支持自动唤醒）
pgcli "postgres://user_abc:PASSWORD@proxy.lakeon.example.com:4432/prod-db"

# 方式 2: 直连 Compute Pod（仅集群内部）
pgcli "postgres://user_abc:PASSWORD@10.0.1.5:55433/prod-db"

# === 开发分支 ===
lakeon branch create --db prod-db --name staging
# 在 staging 分支上做实验，不影响生产数据
pgcli "postgres://user_abc:PASSWORD@proxy:4432/prod-db--staging"

# === 实验完毕，清理分支 ===
lakeon branch delete --db prod-db --name staging

# === 按需调整 ===
lakeon db update --name prod-db --compute-size 8cu     # 扩容
lakeon db suspend --name prod-db                        # 手动休眠节省资源
```

---

## 10. 配置参考

### 10.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LAKEON_API_PORT` | 8080 | API 服务端口 |
| `LAKEON_DB_DSN` | jdbc:postgresql://localhost:5432/lakeon | 元数据库连接串 |
| `LAKEON_DB_USER` | lakeon | 元数据库用户名 |
| `LAKEON_DB_PASSWORD` | lakeon | 元数据库密码 |
| `LAKEON_NEON_PAGESERVER_URL` | http://localhost:9898 | Pageserver HTTP API |
| `LAKEON_NEON_SAFEKEEPER_URLS` | safekeeper1:5454,... | Safekeeper 地址列表 |
| `LAKEON_NEON_STORAGE_BROKER_URL` | http://localhost:50051 | Storage Broker gRPC |
| `LAKEON_OBS_ENDPOINT` | - | OBS S3 端点 |
| `LAKEON_OBS_BUCKET` | neon | OBS 桶名 |
| `LAKEON_OBS_ACCESS_KEY` | - | OBS Access Key |
| `LAKEON_OBS_SECRET_KEY` | - | OBS Secret Key |
| `LAKEON_PROXY_INTERNAL_TOKEN` | - | Proxy 内部通信 Token |
| `LAKEON_K8S_NAMESPACE` | lakeon-compute | Compute Pod 命名空间 |
| `LAKEON_COMPUTE_IMAGE` | ghcr.io/neondatabase/compute-node-v17:latest | 计算节点镜像 |
| `LAKEON_DEFAULT_COMPUTE_SIZE` | 1cu | 默认算力规格 |
| `LAKEON_DEFAULT_SUSPEND_TIMEOUT` | 5m | 默认休眠超时 |
| `LAKEON_DEFAULT_STORAGE_LIMIT` | 10 | 默认存储上限 (GB) |

### 10.2 Helm Values 关键配置

```yaml
# deploy/cce/values-cce.yaml (华为云 CCE 部署配置)

api:
  replicas: 1
  adminToken: "lakeon-sre-2026"     # SRE Admin Token
  image:
    repository: swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-api

admin:
  enabled: true                      # SRE 运维控制台
  image:
    repository: swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-admin

console:
  enabled: true                      # Web 控制台

proxy:
  replicas: 1
  externalHost: "114.116.210.49"     # ELB EIP
  serviceType: LoadBalancer

obs:
  endpoint: "https://obs.cn-north-4.myhuaweicloud.com"
  bucket: "lakeon-storage"
  region: "cn-north-4"

metadataDb:
  enabled: false                     # 使用外部 RDS
  host: ""                           # --set metadataDb.host=$RDS_PRIVATE_IP

cost:                                # 成本预估参数
  cceNodeHourly: "1.0"
  cceNodeCount: "3"
  elbMonthly: "360"
  obsPerGbMonthly: "0.099"
  rdsMonthly: "500"
  eipMonthly: "300"
  computeCuHourly: "0.5"
```
