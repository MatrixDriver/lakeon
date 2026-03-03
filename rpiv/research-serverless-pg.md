---
description: "技术调研: serverless-pg"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

# 技术调研报告：LakeOn Serverless PostgreSQL

## 1. Neon Pageserver API

### 概述

Pageserver 暴露 HTTP Management API，默认监听端口 9898。所有 API 端点定义在 `pageserver/src/http/routes.rs` 中。以下列出与 LakeOn 管控面直接相关的核心端点。

### 1.1 Tenant（租户）管理 API

Neon 的新版架构中，tenant 的创建通过 `location_config` API 完成（而非独立的 create tenant 端点）。Pageserver 收到一个 tenant_shard_id 的 location_config 请求时，如果该 tenant 不存在则自动创建。

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/v1/tenant` | 列出所有 tenant |
| GET | `/v1/tenant/:tenant_shard_id` | 获取 tenant 详情（TenantDetails），包含 timelines 列表、物理大小 |
| DELETE | `/v1/tenant/:tenant_shard_id` | 删除 tenant 及其所有数据 |
| PUT | `/v1/tenant/:tenant_shard_id/location_config` | 创建/配置 tenant（upsert 语义），这是创建 tenant 的主要方式 |
| PUT | `/v1/tenant/config` | 更新 tenant 配置 |
| PATCH | `/v1/tenant/config` | 增量更新 tenant 配置 |
| GET | `/v1/tenant/:tenant_shard_id/config` | 获取 tenant 配置 |

**创建 Tenant 的请求格式** (`PUT /v1/tenant/:tenant_shard_id/location_config`)：

请求体为 `TenantLocationConfigRequest`，核心结构为 `LocationConfig`：

```json
{
  "mode": "AttachedSingle",
  "generation": 1,
  "tenant_conf": {
    // TenantConfig 各项配置，如 gc_period、compaction 等
  }
}
```

`LocationConfigMode` 枚举值：
- `AttachedSingle` - 单节点附着（我们 MVP 场景）
- `AttachedMulti` - 多节点附着
- `AttachedStale` - 过期附着
- `Secondary` - 二级副本
- `Detached` - 分离（用于删除本地数据）

**Tenant 状态响应** (`TenantDetails`)：包含 `TenantInfo`（id、state、current_physical_size、generation）和 `timelines` ID 列表。

**删除 Tenant** (`DELETE /v1/tenant/:tenant_shard_id`)：直接删除，返回 200 OK。

### 1.2 Timeline（时间线/分支）管理 API

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/v1/tenant/:tenant_shard_id/timeline` | 列出 tenant 下所有 timeline |
| POST | `/v1/tenant/:tenant_shard_id/timeline` | 创建 timeline |
| GET | `/v1/tenant/:tenant_shard_id/timeline/:timeline_id` | 获取 timeline 详情 |
| DELETE | `/v1/tenant/:tenant_shard_id/timeline/:timeline_id` | 删除 timeline |

**创建 Timeline 请求** (`TimelineCreateRequest`)：

```json
{
  "new_timeline_id": "<uuid>",
  // 以下三种模式选其一（serde untagged 枚举）

  // 模式1: Bootstrap（创建初始 timeline）
  "pg_version": 17,

  // 模式2: Branch（从现有 timeline 分支）
  "ancestor_timeline_id": "<uuid>",
  "ancestor_start_lsn": "0/16B9188"  // 可选
}
```

`TimelineCreateRequestMode` 是一个 untagged 枚举，有三种模式：
1. **Branch** - 从已有 timeline 创建分支：需要 `ancestor_timeline_id`，可选 `ancestor_start_lsn`
2. **ImportPgdata** - 导入外部数据
3. **Bootstrap** - 创建初始 timeline：可选 `pg_version`（默认使用 `DEFAULT_PG_VERSION`）

**Timeline 详情响应** (`TimelineInfo`)：包含 `tenant_id`、`timeline_id`、`ancestor_timeline_id`、`last_record_lsn`、`disk_consistent_lsn`、`current_logical_size`、`current_physical_size`、`state`、`pg_version` 等。

### 1.3 关键发现

- **tenant_id 和 timeline_id 都是 UUID 格式**，由调用方生成并传入
- **没有独立的 "create tenant" 端点**，tenant 通过 `PUT /v1/tenant/:id/location_config` 隐式创建
- **Timeline 创建的 Bootstrap 模式**会初始化一个全新的 PG 数据目录，这是创建新数据库实例时使用的方式
- **Timeline 创建的 Branch 模式**会从已有 timeline 创建分支，这是我们"数据库分支"功能使用的方式
- Pageserver 支持 JWT 认证（可选），通过 `auth_validation_public_key_path` 配置

## 2. Neon Proxy Control Plane 接口契约

### 2.1 Proxy 期望的 Control Plane API

Neon Proxy 通过 `ControlPlaneApi` trait 与 Control Plane 通信。在生产环境中使用 `NeonControlPlaneClient`（`cplane_proxy_v1`），通过 HTTP 调用 Control Plane。

Proxy 需要 Control Plane 实现以下 **3 个核心 HTTP 端点**：

#### 端点 1: `GET /get_endpoint_access_control`

**用途**：在用户连接时，获取认证信息和访问控制规则。

**查询参数**：
- `session_id` - 会话 ID
- `application_name` - 应用名称
- `endpointish` - endpoint ID（我们的"实例名"概念）
- `role` - 角色名

**请求头**：
- `Authorization: Bearer <jwt>`
- `X-Request-ID: <session_id>`

**期望响应** (`GetEndpointAccessControl`)：

```json
{
  "role_secret": "<scram-sha-256 secret>",
  "project_id": "project-id",
  "account_id": "account-id",
  "allowed_ips": ["0.0.0.0/0"],
  "allowed_vpc_endpoint_ids": [],
  "block_public_connections": false,
  "block_vpc_connections": false,
  "rate_limits": {
    "connection_attempts": {
      "tcp": null,
      "ws": null,
      "http": null
    }
  }
}
```

#### 端点 2: `GET /wake_compute`

**用途**：唤醒 compute 节点并返回连接信息。这是 Proxy 驱动唤醒的核心接口。

**查询参数**：
- `session_id` - 会话 ID
- `application_name` - 应用名称
- `endpointish` - endpoint ID
- 可选的 user options（deep object 格式）

**请求头**：
- `Authorization: Bearer <jwt>`
- `X-Request-ID: <session_id>`

**期望响应** (`WakeCompute`)：

```json
{
  "address": "<host>:<port>",
  "server_name": "optional-tls-server-name",
  "aux": {
    "endpoint_id": "ep-xxxx",
    "project_id": "proj-xxxx",
    "branch_id": "br-xxxx",
    "compute_id": "compute-xxxx",
    "cold_start_info": "warm"  // 枚举: unknown/warm/pool_hit/pool_miss
  }
}
```

**`address` 字段格式**：`host:port`，如 `compute-pod-xxx.lakeon-compute.svc.cluster.local:5432`。Proxy 会解析这个地址来连接 compute。

**`cold_start_info` 枚举值**：`unknown`、`warm`（已运行）、`pool_hit`（从池中唤醒）、`pool_miss`（冷启动）。

#### 端点 3: `GET /endpoints/:endpoint_id/jwks`

**用途**：获取 JWT 认证规则。

**MVP 中可以返回空列表**，因为我们使用密码认证而非 JWT。

```json
{
  "jwks": []
}
```

### 2.2 错误响应格式

Control Plane 错误响应需符合以下格式：

```json
{
  "error": "error message",
  "status": {
    "code": "ERROR_CODE",
    "message": "detailed message",
    "details": {
      "error_info": {
        "reason": "ENDPOINT_NOT_FOUND"
      },
      "retry_info": {
        "retry_delay_ms": 1000
      },
      "user_facing_message": {
        "message": "user visible message"
      }
    }
  }
}
```

**Reason 枚举值**（Proxy 会据此判断是否重试）：
- `ROLE_PROTECTED` - 不重试
- `RESOURCE_NOT_FOUND` / `PROJECT_NOT_FOUND` / `ENDPOINT_NOT_FOUND` / `BRANCH_NOT_FOUND` - 不重试
- `ENDPOINT_DISABLED` - 不重试
- `RUNNING_OPERATIONS` / `CONCURRENCY_LIMIT_REACHED` / `LOCK_ALREADY_TAKEN` - 可重试
- `RATE_LIMIT_EXCEEDED` - 不重试

### 2.3 我们的 Spring Boot API 需要实现的端点

| 端点 | 方法 | 说明 | 优先级 |
|------|------|------|--------|
| `/get_endpoint_access_control` | GET | 返回认证密钥和访问控制 | 必须 |
| `/wake_compute` | GET | 唤醒 compute，返回 host:port | 必须 |
| `/endpoints/:id/jwks` | GET | 返回 JWKS（可返回空） | 必须（可空实现） |

### 2.4 Proxy 配置要点

Proxy 启动时需配置 Control Plane 的 base URL（`--auth-endpoint` 参数）和 JWT token（`--auth-jwt` 参数）。Proxy 会在这些 URL 基础上拼接路径调用上述端点。

### 2.5 Mock Control Plane 模式

Neon Proxy 还支持 Mock 模式（`MockControlPlane`），直接连接一个 PostgreSQL 实例查询 `pg_authid` 获取密码。这在测试阶段可能有用，但生产环境我们需要实现完整的 HTTP Control Plane。

## 3. Neon Compute 管理

### 3.1 compute_ctl 启动参数

`compute_ctl` 是 compute 节点的管理进程，作为 Pod 的入口点运行。关键 CLI 参数：

```
compute_ctl \
  -D /var/db/postgres/compute \           # PGDATA 目录
  -C 'postgresql://cloud_admin@localhost/postgres' \  # 本地 PG 连接串
  -b /usr/local/bin/postgres \            # postgres 二进制路径
  -c /var/db/postgres/configs/config.json \  # 配置文件路径（ComputeSpec JSON）
  -i <compute_id> \                       # compute ID
  -p <control_plane_uri> \                # Control Plane API URL（与 -c 互斥）
  -r http://pg-ext-s3-gateway \           # 远程扩展存储 URL（可选）
  --external-http-port 3080 \             # 外部 HTTP API 端口
  --internal-http-port 3081 \             # 内部 HTTP API 端口
  --privileged-role-name neon_superuser   # 特权角色名
```

**两种配置获取方式**：
1. **本地配置文件** (`-c config.json`)：直接传入 `ComputeConfig` JSON
2. **从 Control Plane 拉取** (`-p <uri> -i <id>`)：compute_ctl 调用 `GET {uri}/compute/api/v2/computes/{compute_id}/spec` 获取配置

### 3.2 ComputeSpec JSON 格式

`ComputeSpec` 是 compute 节点的完整配置，关键字段：

```json
{
  "format_version": 2.0,
  "operation_uuid": "optional-uuid",
  "features": [],
  "cluster": {
    "cluster_id": "optional-id",
    "name": "optional-name",
    "state": null,
    "roles": [
      {
        "name": "cloud_admin",
        "encrypted_password": "SCRAM-SHA-256$...",
        "options": null
      }
    ],
    "databases": [
      {
        "name": "postgres",
        "owner": "cloud_admin"
      }
    ],
    "postgresql_conf": null,
    "settings": {
      "shared_buffers": "256MB",
      "max_connections": "100"
    }
  },
  "delta_operations": null,
  "skip_pg_catalog_updates": false,

  "tenant_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "timeline_id": "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff",

  "pageserver_connstring": "host=pageserver.lakeon.svc port=6400",
  "safekeeper_connstrings": [
    "safekeeper-0.lakeon.svc:5454",
    "safekeeper-1.lakeon.svc:5454",
    "safekeeper-2.lakeon.svc:5454"
  ],

  "mode": "Primary",
  "storage_auth_token": "optional-jwt-token",
  "suspend_timeout_seconds": 300
}
```

**关键字段说明**：
- `tenant_id` + `timeline_id`：连接到 Pageserver 的标识
- `pageserver_connstring`：Pageserver 的 PG 协议地址（端口 6400）
- `safekeeper_connstrings`：Safekeeper 地址列表（端口 5454）
- `cluster.roles`：要创建的数据库角色
- `cluster.databases`：要创建的数据库
- `cluster.settings`：PostgreSQL 配置参数
- `mode`：`Primary`（读写）、`Replica`（只读）、`Static`（静态只读）

### 3.3 ComputeConfig（完整配置）

从 Control Plane 获取的完整配置是 `ComputeConfig`，包含：
- `spec: Option<ComputeSpec>` - 计算规格
- `compute_ctl_config: ComputeCtlConfig` - compute_ctl 自身配置（JWKs、TLS）

`ControlPlaneConfigResponse`（Control Plane API 响应）：
- `spec: Option<ComputeSpec>`
- `status: ControlPlaneComputeStatus` - `Empty`（未附着）或 `Attached`（已附着，应有 spec）
- `compute_ctl_config: ComputeCtlConfig`

### 3.4 compute_ctl 启动流程

1. 解析 CLI 参数
2. 获取 ComputeSpec（从文件或 Control Plane）
3. 清空并初始化 PGDATA 目录
4. 同步 Safekeeper 获取 commit LSN
5. 从 Pageserver 获取 basebackup
6. 写入配置文件（postgresql.conf、pg_hba.conf 等）
7. 启动 PostgreSQL
8. 执行 catalog 更新（创建角色、数据库等）
9. 等待 PostgreSQL 进程退出

### 3.5 Compute Pod 配置建议

对于 K8s Pod，需要传入的核心配置：
- 环境变量或挂载 config.json 提供 ComputeSpec
- 挂载 postgres 二进制（或使用包含 postgres 的 compute 镜像）
- 网络可达 Pageserver（端口 6400）和 Safekeeper（端口 5454）
- 暴露 PostgreSQL 端口（5432）和 HTTP API 端口（3080）

## 4. S3 存储兼容性

### 4.1 Neon 使用的 S3 API

Neon 的远程存储层通过 AWS SDK for Rust（`aws-sdk-s3`）访问 S3，具体使用以下 S3 API 操作：

| S3 API | 用途 |
|--------|------|
| `PutObject` | 上传 layer 文件和 index |
| `GetObject` | 下载 layer 文件（支持 Range 请求） |
| `HeadObject` | 检查对象是否存在、获取 metadata |
| `DeleteObjects` | 批量删除对象（最多 1000 个/批） |
| `ListObjectsV2` | 列举对象（用于 layer 发现和清理） |

**注意**：当前代码中 **没有使用** MultipartUpload。上传使用的是简单的 `PutObject`。

### 4.2 S3Config 配置结构

```toml
bucket_name = "lakeon-storage"
bucket_region = "cn-north-4"
prefix_in_bucket = "pageserver/"
endpoint = "https://obs.cn-north-4.myhuaweicloud.com"
concurrency_limit = 100
max_keys_per_list_response = 1000
upload_storage_class = "STANDARD"
```

关键配置项：
- **bucket_name**：OBS Bucket 名称
- **bucket_region**：OBS Region
- **prefix_in_bucket**：Bucket 内路径前缀，用于多组件隔离
- **endpoint**：S3 兼容端点 URL。默认根据 region 推导 AWS 端点，通过此字段覆盖为 OBS 端点
- **concurrency_limit**：并发请求限制（默认 100）

认证方式：通过 AWS SDK 默认凭证链，最简单的方式是设置环境变量：
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`

### 4.3 华为云 OBS 兼容性评估

华为云 OBS 声明兼容 S3 协议，以下是对 Neon 实际使用的 API 的逐项评估：

| S3 API | OBS 支持 | 备注 |
|--------|----------|------|
| PutObject | 支持 | OBS 完整支持 |
| GetObject (含 Range) | 支持 | OBS 支持 Range 请求 |
| HeadObject | 支持 | OBS 完整支持 |
| DeleteObjects (批量) | 支持 | OBS 支持，需确认行为一致性 |
| ListObjectsV2 | 支持 | OBS 支持 V2 版本 |

**风险点**：
1. **签名算法**：OBS 同时支持 V2 和 V4 签名，AWS SDK 默认使用 V4，应兼容
2. **PathStyle vs VirtualHostedStyle**：OBS 默认支持两种寻址方式，可能需要通过 `force_path_style` 配置
3. **一致性模型**：S3 已提供强一致性，OBS 也声称提供读后写一致性，但实际行为需验证
4. **错误码**：OBS 的错误码可能与 S3 有细微差异，需要测试

**建议**：在 PoC 阶段优先进行以下测试：
- 基本的 put/get/delete/list 操作
- Range read（部分读取）
- 批量删除 (DeleteObjects)
- 并发操作下的一致性
- 大文件上传（>5MB 的 layer 文件）

## 5. 容器化方案

### 5.1 主 Dockerfile

位置：`/Users/jacky/code/neon/Dockerfile`

这是一个多阶段构建 Dockerfile，构建所有核心组件：

**构建阶段**：
1. **pg-build 阶段**：编译 PostgreSQL v14/v15/v16/v17（Neon 修改版）
2. **plan 阶段**：生成 cargo-chef recipe（Rust 依赖缓存）
3. **build 阶段**：编译所有 Rust 二进制文件

**构建的二进制文件**：
- `pageserver` - 页面服务器
- `safekeeper` - WAL 安全守护
- `storage_broker` - 存储协调
- `proxy` - PostgreSQL 协议代理
- `pg_sni_router` - SNI 路由
- `storage_controller` - 存储控制器
- `neon_local` - 本地开发工具
- `pagectl` - Pageserver 管理工具
- `storage_scrubber` - 存储清理工具

**最终镜像**基于 `debian:bookworm-slim`，包含：
- 所有 Rust 二进制文件在 `/usr/local/bin/`
- PostgreSQL v14-v17 在 `/usr/local/v14/` ~ `/usr/local/v17/`
- 默认 pageserver 配置在 `/data/.neon/`
- 默认暴露端口：6400（PG 协议）、9898（HTTP API）
- 默认入口点：`pageserver -D /data/.neon`

### 5.2 Compute 节点镜像

位置：`/Users/jacky/code/neon/docker-compose/compute_wrapper/Dockerfile`

基于 `compute-node-v14` 镜像（Neon 官方发布），添加了 curl/jq/netcat 工具。

### 5.3 LakeOn 容器化策略建议

由于主 Dockerfile 将所有组件打包在一个镜像中，我们有两种策略：

**策略 A：使用单一镜像，按组件启动不同进程**
- 优点：构建简单，镜像管理容易
- 缺点：镜像较大，不够精细

**策略 B：拆分为独立镜像（推荐）**
- `lakeon/pageserver` - 仅包含 pageserver 二进制 + PG 库
- `lakeon/safekeeper` - 仅包含 safekeeper 二进制
- `lakeon/storage-broker` - 仅包含 storage_broker 二进制
- `lakeon/proxy` - 仅包含 proxy 二进制
- `lakeon/compute` - 包含 compute_ctl + PostgreSQL 二进制

**建议**：MVP 阶段使用策略 A（单一镜像），通过 K8s command/args 指定不同的入口点启动不同组件。后续优化时再拆分为独立镜像。

### 5.4 各组件启动命令

```bash
# Pageserver
pageserver -D /data/.neon

# Safekeeper
safekeeper -D /data/safekeeper --listen-pg=0.0.0.0:5454 --listen-http=0.0.0.0:7676 --broker-endpoint=http://storage-broker:50051

# Storage Broker
storage_broker --listen-addr=0.0.0.0:50051

# Proxy
proxy --auth-endpoint=http://control-plane:8080 --listen-addr=0.0.0.0:4432 --mgmt-listen-addr=0.0.0.0:7000

# Compute
compute_ctl -D /var/db/postgres/compute -b /usr/local/v17/bin/postgres -c /config/spec.json -C 'postgresql://cloud_admin@localhost/postgres'
```

### 5.5 关键端口

| 组件 | PG 协议端口 | HTTP API 端口 | 其他端口 |
|------|------------|--------------|---------|
| Pageserver | 6400 | 9898 | - |
| Safekeeper | 5454 | 7676 | - |
| Storage Broker | - | - | 50051 (gRPC) |
| Proxy | 4432 | 7000 (mgmt) | - |
| Compute | 5432 | 3080 (external), 3081 (internal) | - |

## 6. Spring Boot K8s 集成

### 6.1 Fabric8 Kubernetes Client 概述

Fabric8 Kubernetes Client 是 Java 生态中最成熟的 K8s 客户端库。它提供了流畅的 API 来管理 K8s 资源。

### 6.2 基本使用模式

**Maven 依赖**：

```xml
<dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>kubernetes-client</artifactId>
    <version>6.13.0</version>
</dependency>
```

**初始化客户端**：

```java
// 自动检测集群内配置（ServiceAccount）或 ~/.kube/config
KubernetesClient client = new KubernetesClientBuilder().build();
```

**创建 Pod（Compute 节点）**：

```java
Pod computePod = new PodBuilder()
    .withNewMetadata()
        .withName("compute-" + tenantId + "-" + timelineId)
        .withNamespace("lakeon-compute")
        .addToLabels("app", "lakeon-compute")
        .addToLabels("tenant-id", tenantId)
        .addToLabels("timeline-id", timelineId)
    .endMetadata()
    .withNewSpec()
        .addNewContainer()
            .withName("compute")
            .withImage("lakeon/neon:latest")
            .withCommand("compute_ctl")
            .withArgs("-D", "/data/pgdata",
                      "-b", "/usr/local/v17/bin/postgres",
                      "-c", "/config/spec.json",
                      "-C", "postgresql://cloud_admin@localhost/postgres")
            .addNewPort()
                .withContainerPort(5432)
                .withName("postgres")
            .endPort()
            .withNewResources()
                .addToRequests("cpu", new Quantity("1"))
                .addToRequests("memory", new Quantity("2Gi"))
                .addToLimits("cpu", new Quantity("1"))
                .addToLimits("memory", new Quantity("2Gi"))
            .endResources()
            .addNewVolumeMount()
                .withName("config")
                .withMountPath("/config")
            .endVolumeMount()
        .endContainer()
        .addNewVolume()
            .withName("config")
            .withNewConfigMap()
                .withName("compute-spec-" + computeId)
            .endConfigMap()
        .endVolume()
    .endSpec()
    .build();

client.pods().inNamespace("lakeon-compute").resource(computePod).create();
```

**等待 Pod 就绪**：

```java
client.pods()
    .inNamespace("lakeon-compute")
    .withName(podName)
    .waitUntilReady(60, TimeUnit.SECONDS);
```

**删除 Pod（休眠/销毁 Compute）**：

```java
client.pods()
    .inNamespace("lakeon-compute")
    .withName(podName)
    .withGracePeriod(30)  // 优雅关闭时间
    .delete();
```

**监听 Pod 事件**：

```java
client.pods()
    .inNamespace("lakeon-compute")
    .withLabel("app", "lakeon-compute")
    .watch(new Watcher<Pod>() {
        @Override
        public void eventReceived(Action action, Pod pod) {
            // 处理 Pod 状态变化
        }
        @Override
        public void onClose(WatcherException e) {
            // 处理关闭
        }
    });
```

### 6.3 ConfigMap 管理（存储 ComputeSpec）

```java
ConfigMap specConfigMap = new ConfigMapBuilder()
    .withNewMetadata()
        .withName("compute-spec-" + computeId)
        .withNamespace("lakeon-compute")
    .endMetadata()
    .addToData("spec.json", objectMapper.writeValueAsString(computeSpec))
    .build();

client.configMaps().inNamespace("lakeon-compute").resource(specConfigMap).create();
```

### 6.4 Service 管理（暴露 Compute 端口）

```java
// 可选：为 Compute 创建 headless Service 或使用 Pod IP 直连
// 推荐直接使用 Pod IP + port，因为 Compute 是短暂的
String computeAddress = pod.getStatus().getPodIP() + ":5432";
```

### 6.5 Spring Boot 集成模式

```java
@Configuration
public class KubernetesConfig {
    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}

@Service
public class ComputeManager {
    @Autowired
    private KubernetesClient client;

    public String createCompute(String tenantId, String timelineId, ComputeSpec spec) {
        // 1. 创建 ConfigMap 存储 spec
        // 2. 创建 Pod
        // 3. 等待 Pod Ready
        // 4. 返回 Pod IP:5432
    }

    public void deleteCompute(String podName) {
        // 1. 删除 Pod
        // 2. 清理 ConfigMap
    }
}
```

## 调研结论与风险

### 关键发现摘要

1. **Pageserver API 可用性高**：Pageserver 提供完整的 HTTP API 用于 tenant 和 timeline 管理。创建 tenant 使用 `PUT /v1/tenant/:id/location_config`，创建 timeline 使用 `POST /v1/tenant/:id/timeline`，都是标准的 REST 接口，可直接通过 Spring Boot 的 HTTP 客户端调用。

2. **Proxy Control Plane 接口契约明确**：Proxy 期望 Control Plane 实现 3 个 HTTP GET 端点（`get_endpoint_access_control`、`wake_compute`、`endpoints/:id/jwks`）。其中 `wake_compute` 是核心，需要返回 compute 的 `host:port`。我们的 Spring Boot API 需要实现这 3 个端点，响应格式已从源码中确定。

3. **Compute 管理方案可行**：`compute_ctl` 支持通过 JSON 配置文件传入完整的 `ComputeSpec`，包括 tenant_id、timeline_id、pageserver/safekeeper 连接信息、PG 配置等。我们可以通过 ConfigMap 注入配置，通过 Fabric8 管理 Pod 生命周期。

4. **S3 兼容性需验证但风险可控**：Neon 只使用了 S3 的基础 API（PutObject、GetObject、HeadObject、DeleteObjects、ListObjectsV2），未使用 MultipartUpload。这些 API 华为云 OBS 都声明支持，但需要在 PoC 阶段实际验证。

5. **容器化方案完备**：Neon 提供了完整的 Dockerfile，可以构建包含所有组件的镜像。MVP 阶段建议使用单一镜像 + 不同入口点的方式部署。

### 潜在风险

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| OBS S3 兼容性差异（PathStyle、签名等） | 高 | PoC 阶段优先验证；准备 MinIO 作为备选 |
| Proxy Control Plane 接口变更 | 中 | 锁定 Neon 版本；实现接口适配层 |
| Compute 冷启动时间过长 | 中 | MVP 放宽要求；记录基准数据；后续优化 |
| Neon Rust 编译环境复杂 | 中 | 使用 Neon 官方 build-tools 镜像；CI 缓存 |
| UUID 生成与管理 | 低 | 由我们的 Control Plane 统一生成和管理 tenant_id/timeline_id |

### 建议

1. **PoC 阶段优先验证**：OBS S3 兼容性 > Proxy wake_compute 对接 > Compute Pod 生命周期管理
2. **锁定 Neon 版本**：选择一个稳定的 release tag，避免跟踪 main 分支带来的不稳定性
3. **Proxy 对接方案**：建议先使用 Mock Control Plane 模式进行测试，再切换到完整 HTTP Control Plane
4. **Compute Spec 简化**：MVP 阶段固化大部分 ComputeSpec 字段，仅暴露 tenant_id、timeline_id、compute 规格等必要参数给用户
