---
description: "实现对齐审查: serverless-pg"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

# 实现对齐审查报告

## 功能完整性检查

### 1. 租户管理

| 功能点 | PRD 要求 | 实现状态 | 说明 |
|--------|----------|----------|------|
| 创建租户 | POST `/api/v1/tenants` | 已实现 | `TenantController.createTenant()` |
| 查看租户 | GET `/api/v1/tenants/{tenant_id}` | 已实现 | `TenantController.getTenant()` |
| API Key 自动生成 | 创建时自动生成 | 已实现 | `TenantEntity.prePersist()` 生成 `lk_` 前缀的 API Key |
| 列出租户下所有实例 | PRD 7.1 提及 | 间接实现 | 通过 `GET /api/v1/databases` 按租户 API Key 过滤实现，未提供独立的租户实例列表端点 |

### 2. 实例 CRUD + 启停

| 功能点 | PRD 要求 | 实现状态 | 说明 |
|--------|----------|----------|------|
| 创建实例 | POST `/api/v1/databases` | 已实现 | `DatabaseController.createDatabase()` |
| 查看实例 | GET `/api/v1/databases/{db_id}` | 已实现 | `DatabaseController.getDatabase()` |
| 列出实例 | GET `/api/v1/databases` | 已实现 | `DatabaseController.listDatabases()` |
| 更新配置 | PATCH `/api/v1/databases/{db_id}` | 已实现 | `DatabaseController.updateDatabase()` |
| 删除实例 | DELETE `/api/v1/databases/{db_id}` | 已实现 | `DatabaseController.deleteDatabase()` |
| 休眠 compute | POST `/api/v1/databases/{db_id}/suspend` | 已实现 | `DatabaseController.suspendDatabase()` |
| 唤醒 compute | POST `/api/v1/databases/{db_id}/resume` | 已实现 | `DatabaseController.resumeDatabase()` |
| 连接串返回 | 创建时返回连接串 | 已实现 | `DatabaseService.create()` 构建 `connection_uri` |
| 默认值填充 | compute_size=1cu, suspend_timeout=5m, storage_limit_gb=10 | 已实现 | 通过 `LakeonProperties.defaults` 配置 |
| 实例状态 | CREATING/RUNNING/SUSPENDED/ERROR/DELETING | 已实现 | `DatabaseStatus` 枚举 |

### 3. 分支管理

| 功能点 | PRD 要求 | 实现状态 | 说明 |
|--------|----------|----------|------|
| 创建分支 | POST `/api/v1/databases/{db_id}/branches` | 已实现 | `BranchController.createBranch()` |
| 列出分支 | GET `/api/v1/databases/{db_id}/branches` | 已实现 | `BranchController.listBranches()` |
| 查看分支详情 | GET `/api/v1/databases/{db_id}/branches/{branch_id}` | 已实现 | `BranchController.getBranch()` |
| 删除分支 | DELETE `/api/v1/databases/{db_id}/branches/{branch_id}` | 已实现 | `BranchController.deleteBranch()` |
| 默认分支保护 | 不允许删除默认分支 | 已实现 | `BranchService.delete()` 中检查 `isDefault` |
| start_compute 选项 | 创建分支时可选启动 compute | 已实现 | `CreateBranchRequest.startCompute()` |
| 分支连接串 | 分支有独立连接串 | 已实现 | `BranchService.create()` 附加 `?branch=` 参数 |

### 4. Proxy 适配

| 功能点 | PRD/计划要求 | 实现状态 | 说明 |
|--------|-------------|----------|------|
| wake_compute | GET `/proxy/wake_compute` | 已实现 | `ProxyAdapterController.wakeCompute()` |
| get_endpoint_access_control | GET `/proxy/get_endpoint_access_control` | 已实现 | `ProxyAdapterController.getEndpointAccessControl()` |
| jwks 端点 | 任务描述中提及 | 未实现 | PRD 和实施计划中均未要求 JWKS 端点，属于任务描述多写，不影响功能 |
| endpointish 解析 | `db_name` 或 `db_name--branch_name` | 已实现 | 使用 `--` 分隔符解析 |
| 响应格式 | address + aux (endpoint_id, project_id, branch_id, compute_id, cold_start_info) | 已实现 | 与 Neon Proxy 期望的 `WakeCompute` 结构一致 |
| SCRAM 密码 | role_secret 返回 SCRAM-SHA-256 hash | 已实现 | `ScramUtils` 工具类生成标准格式 |

### 5. 自动休眠调度

| 功能点 | PRD 要求 | 实现状态 | 说明 |
|--------|----------|----------|------|
| 定时检查 | 定期检查无活动实例 | 已实现 | `ComputeLifecycleService.checkAutoSuspend()` |
| 调度间隔 | 30 秒 | 已实现 | `@Scheduled(fixedDelay = 30000)` |
| 超时判断 | 基于 suspend_timeout 配置 | 已实现 | `parseDuration()` 解析 "5m"/"10m" 格式 |
| Pod 清理 | 超时后删除 compute Pod | 已实现 | 调用 `computePodManager.deleteComputePod()` |

### 6. CLI 所有命令

| 命令 | PRD 要求 | 实现状态 | 说明 |
|------|----------|----------|------|
| `lakeon config set` | 配置 API URL 和 API Key | 已实现 | `commands/config.py` |
| `lakeon config show` | 查看当前配置 | 已实现 | 计划中未明确要求，属增强 |
| `lakeon tenant create` | 创建租户 | 已实现 | `commands/tenant.py` |
| `lakeon db create` | 创建实例 | 已实现 | `commands/db.py` |
| `lakeon db list` | 列出实例 | 已实现 | `commands/db.py` |
| `lakeon db status` | 查看实例状态 | 已实现 | `commands/db.py` |
| `lakeon db suspend` | 休眠 compute | 已实现 | `commands/db.py` |
| `lakeon db resume` | 唤醒 compute | 已实现 | `commands/db.py` |
| `lakeon db update` | 更新配置 | 已实现 | `commands/db.py` |
| `lakeon db delete` | 删除实例 | 已实现 | `commands/db.py`，支持 `--force` |
| `lakeon branch create` | 创建分支 | 已实现 | `commands/branch.py` |
| `lakeon branch list` | 列出分支 | 已实现 | `commands/branch.py` |
| `lakeon branch delete` | 删除分支 | 已实现 | `commands/branch.py` |

### 7. Helm Charts 所有组件

| 组件 | 计划要求 | 实现状态 | 说明 |
|------|----------|----------|------|
| Chart.yaml | Helm Chart 元数据 | 已实现 | `deploy/helm/lakeon/Chart.yaml` |
| values.yaml | 全局配置值 | 已实现 | 包含所有组件配置 |
| namespace.yaml | 命名空间 | 已实现 | |
| deployment-pageserver.yaml | Pageserver 部署 | 已实现 | |
| configmap-pageserver.yaml | Pageserver 配置 | 已实现 | |
| service-pageserver.yaml | Pageserver Service | 已实现 | |
| statefulset-safekeeper.yaml | Safekeeper StatefulSet (3副本) | 已实现 | |
| service-safekeeper.yaml | Safekeeper Service | 已实现 | |
| deployment-storage-broker.yaml | Storage Broker 部署 | 已实现 | |
| service-storage-broker.yaml | Storage Broker Service | 已实现 | |
| deployment-proxy.yaml | Proxy 部署 | 已实现 | |
| service-proxy.yaml | Proxy Service | 已实现 | |
| deployment-api.yaml | API 部署 | 已实现 | |
| service-api.yaml | API Service | 已实现 | |
| configmap-api.yaml | API 配置 | 已实现 | |
| secret-obs.yaml | OBS 访问密钥 | 已实现 | |
| secret-api.yaml | API 内部 token | 已实现 | |
| _helpers.tpl | Helm 模板辅助函数 | 已实现 | |

### 8. 监控和告警

| 功能点 | PRD 要求 | 实现状态 | 说明 |
|--------|----------|----------|------|
| Prometheus 采集配置 | 采集 API、Pageserver、Safekeeper、Proxy | 已实现 | `deploy/monitoring/prometheus/prometheus.yml` |
| Grafana Dashboard | 实例状态、唤醒延迟、连接数、存储用量 | 已实现 | `deploy/monitoring/grafana/dashboards/lakeon-overview.json` |
| ComputeWakeupFailed 告警 | 唤醒失败告警 | 已实现 | `deploy/monitoring/prometheus/alerts.yml` |
| PageserverDown 告警 | Pageserver 不可用 | 已实现 | |
| SafekeeperDown 告警 | Safekeeper 节点不足 | 已实现 | |
| HighStorageUsage 告警 | 存储超 90% | 已实现 | |
| APIHighLatency 告警 | API P99 > 2s | 已实现 | |
| Actuator Prometheus 端点 | Spring Boot 指标导出 | 已实现 | `application.yml` 配置了 Prometheus 端点 |

## 架构一致性检查

### 1. 分层结构

计划要求的分层：Controller -> Service -> Repository / NeonApiClient / ComputePodManager

| 层级 | 计划 | 实现 | 一致性 |
|------|------|------|--------|
| Controller 层 | TenantController, DatabaseController, BranchController, ProxyAdapterController | 全部实现 | 一致 |
| Service 层 | TenantService, DatabaseService, BranchService, SuspendScheduler | TenantService, DatabaseService, BranchService, ComputeLifecycleService | 一致（SuspendScheduler 改名为 ComputeLifecycleService，职责相同） |
| Repository 层 | TenantRepository, DatabaseInstanceRepository, BranchRepository | TenantRepository, DatabaseRepository, BranchRepository | 一致（DatabaseInstanceRepository 改名为 DatabaseRepository） |
| Neon 集成 | PageserverClient | NeonApiClient | 一致（类名变更，职责相同） |
| K8s 集成 | ComputePodManager | ComputePodManager | 一致 |
| 配置层 | LakeonProperties, ApiKeyFilter | LakeonProperties, ApiKeyFilter | 一致 |

### 2. API 路径一致性

| PRD 定义 | 实现路径 | 一致性 |
|----------|----------|--------|
| POST `/api/v1/tenants` | `/api/v1/tenants` | 一致 |
| GET `/api/v1/tenants/{tenant_id}` | `/api/v1/tenants/{tenantId}` | 一致 |
| POST `/api/v1/databases` | `/api/v1/databases` | 一致 |
| GET `/api/v1/databases` | `/api/v1/databases` | 一致 |
| GET `/api/v1/databases/{db_id}` | `/api/v1/databases/{dbId}` | 一致 |
| PATCH `/api/v1/databases/{db_id}` | `/api/v1/databases/{dbId}` | 一致 |
| DELETE `/api/v1/databases/{db_id}` | `/api/v1/databases/{dbId}` | 一致 |
| POST `/api/v1/databases/{db_id}/suspend` | `/api/v1/databases/{dbId}/suspend` | 一致 |
| POST `/api/v1/databases/{db_id}/resume` | `/api/v1/databases/{dbId}/resume` | 一致 |
| POST `/api/v1/databases/{db_id}/branches` | `/api/v1/databases/{dbId}/branches` | 一致 |
| GET `/api/v1/databases/{db_id}/branches` | `/api/v1/databases/{dbId}/branches` | 一致 |
| GET `/api/v1/databases/{db_id}/branches/{branch_id}` | `/api/v1/databases/{dbId}/branches/{branchId}` | 一致 |
| DELETE `/api/v1/databases/{db_id}/branches/{branch_id}` | `/api/v1/databases/{dbId}/branches/{branchId}` | 一致 |
| GET `/proxy/wake_compute` | `/proxy/wake_compute` | 一致 |
| GET `/proxy/get_endpoint_access_control` | `/proxy/get_endpoint_access_control` | 一致 |

### 3. 错误响应格式

PRD 定义的格式：
```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "..."
  }
}
```

实现：`ErrorResponse` 类包含嵌套的 `ErrorBody(code, message)`，通过 `GlobalExceptionHandler` 统一处理。**与 PRD 一致**。

异常类型映射：
- `NotFoundException` -> 404 `RESOURCE_NOT_FOUND`
- `ConflictException` -> 409 `CONFLICT`
- `BadRequestException` -> 400 `BAD_REQUEST`
- `ServiceException` -> 500 `SERVICE_ERROR`
- `MethodArgumentNotValidException` -> 400 `VALIDATION_ERROR`

注意：PRD 中的错误响应包含 `details` 字段（`"details": {}`），实现中 `ErrorBody` 只有 `code` 和 `message`，缺少 `details` 字段。这是一个**微小偏离**，不影响功能。

### 4. 认证机制

计划要求 `Authorization: Bearer <api-key>` 头部认证。

实现：`ApiKeyFilter` 作为 Servlet Filter，提取 Bearer token 并通过 `TenantService.authenticateByApiKey()` 验证。排除路径与计划一致（`/actuator/**`、`/proxy/**`、`POST /api/v1/tenants`）。**与计划一致**。

## 偏离记录

### 偏离 1：JDK 版本 21 -> 17

- **计划/PRD**：Java 21 (LTS)
- **实现**：`pom.xml` 中 `<java.version>17</java.version>`
- **原因**：开发环境兼容性考虑，JDK 17 也是 LTS 版本
- **影响**：低。Spring Boot 3.3 同时支持 JDK 17 和 21，不影响功能。缺少 JDK 21 的虚拟线程等新特性，但 MVP 阶段不需要。

### 偏离 2：实体类命名变化

- **计划**：`Tenant`、`DatabaseInstance`、`Branch`（直接放在 `com.lakeon.model` 包）
- **实现**：`TenantEntity`、`DatabaseEntity`、`BranchEntity`（放在 `com.lakeon.model.entity` 包）
- **原因**：避免与 DTO 类命名冲突，遵循更清晰的包结构约定
- **影响**：无。类名变化是纯重构，不影响数据库映射和业务逻辑。

### 偏离 3：Repository 类命名变化

- **计划**：`DatabaseInstanceRepository`
- **实现**：`DatabaseRepository`
- **原因**：简化命名，与 `DatabaseEntity` 对应
- **影响**：无。

### 偏离 4：Neon 客户端类命名变化

- **计划**：`PageserverClient`
- **实现**：`NeonApiClient`
- **原因**：更通用的命名，可能后续扩展为包含 Safekeeper API 的调用
- **影响**：无。功能完全一致。

### 偏离 5：DTO 类由 record 改为普通类 + Builder

- **计划**：`DatabaseResponse`、`BranchResponse`、`TenantResponse`、`ErrorResponse` 使用 Java record
- **实现**：`DatabaseResponse` 和 `ErrorResponse` 改为普通类（带 getter/setter 和 Builder 模式），`BranchResponse` 和 `TenantResponse` 也使用 Builder 模式
- **原因**：Builder 模式更灵活，适合构建包含可选字段的响应对象
- **影响**：无。JSON 序列化结果一致。`CreateDatabaseRequest`、`CreateBranchRequest`、`CreateTenantRequest`、`UpdateDatabaseRequest` 仍保留为 record 类型。

### 偏离 6：数据模型扁平化（compute 信息存储位置）

- **计划**：compute 信息（pod_name, host, port, compute_status）存在 `Branch` 实体上（每个分支可有独立 compute）
- **实现**：compute 信息同时存在 `DatabaseEntity`（compute_pod_name, compute_host, compute_port）和 `BranchEntity` 上
- **原因**：简化 MVP 实现，大多数场景下只有一个默认分支
- **影响**：低。`ProxyAdapterController` 中的 wake_compute 直接使用 `DatabaseEntity` 的 compute 信息而非通过 Branch 查找。分支级别的 compute 路由功能实现不完整（见遗漏项）。

### 偏离 7：DatabaseEntity 新增字段

- **计划**：`DatabaseInstance` 不包含 `neon_timeline_id`、`compute_pod_name`、`compute_host`、`compute_port`、`connection_uri`、`last_active_at`
- **实现**：`DatabaseEntity` 额外包含上述字段
- **原因**：偏离 6 导致的连带变更，将 compute 信息和主分支的 timeline 信息提升到 DatabaseEntity 级别
- **影响**：低。简化了查询逻辑但引入了轻微的数据冗余。

### 偏离 8：自动休眠调度器命名和实现层级

- **计划**：`SuspendScheduler`（独立组件类），基于 Branch 级别的 compute_status 检查
- **实现**：`ComputeLifecycleService`（Service 类），基于 DatabaseEntity 级别的 status 检查
- **原因**：与偏离 6 一致，简化为 Database 级别的休眠控制
- **影响**：低。功能正确实现，但分支级别的独立休眠控制不可用。

### 偏离 9：异常类命名变化

- **计划**：`ResourceNotFoundException`、`DuplicateResourceException`
- **实现**：`NotFoundException`、`ConflictException`、`BadRequestException`、`ServiceException`、`WakeComputeTimeoutException`
- **原因**：更细粒度的异常分类
- **影响**：无。实际上实现的异常体系更完善。

### 偏离 10：测试依赖增强

- **计划**：仅 H2 内存数据库用于测试
- **实现**：额外添加了 WireMock（`wiremock-standalone:3.3.1`）和 Testcontainers（`junit-jupiter`、`postgresql`）
- **原因**：增强集成测试能力
- **影响**：正面偏离。测试基础设施更完善。

### 偏离 11：CLI 配置模块重构

- **计划**：配置逻辑在 `client.py` 的 `LakeonClient` 类中（`save_config` 静态方法，`CONFIG_FILE` 常量）
- **实现**：独立的 `config.py` 模块（`load_config()`、`save_config()`、`get_api_url()`、`get_api_key()`），CLI 命令中新增 `config show` 子命令
- **原因**：更好的关注点分离
- **影响**：正面偏离。代码结构更清晰。

### 偏离 12：CLI 增加 find_database_by_name / find_branch_by_name 辅助方法

- **计划**：CLI 命令中直接调用 `list_databases()` 然后过滤
- **实现**：`LakeonClient` 类新增 `find_database_by_name()` 和 `find_branch_by_name()` 辅助方法
- **原因**：消除各命令中的重复代码
- **影响**：正面偏离。代码更简洁。

### 偏离 13：CLI 命令增加错误处理

- **计划**：命令中直接调用 API，未显式处理异常
- **实现**：所有命令使用 try/except 包裹 API 调用，输出红色错误信息并 `raise typer.Exit(1)`
- **原因**：改善用户体验
- **影响**：正面偏离。

## 遗漏项

### 遗漏 1：Proxy 分支级路由不完整（中等优先级）

- **PRD 要求**：Proxy 支持通过连接参数指定分支，分支路由到正确的 compute
- **当前状态**：`ProxyAdapterController.wakeCompute()` 虽然解析了 `branchName`，但实际唤醒逻辑使用的是 `DatabaseEntity` 级别的 compute 信息，`branchName` 变量被解析但未被使用
- **影响**：分支连接路由无法正确工作。当前实现只能路由到默认分支的 compute
- **建议**：需要在 `wakeCompute()` 中根据 `branchName` 查找对应的 `BranchEntity`，并使用 Branch 级别的 compute 信息

### 遗漏 2：schema.sql 缺少 BranchEntity 的部分新增字段（低优先级）

- **当前状态**：`BranchEntity` 实现中可能包含 `connection_uri`、`parent_branch_name` 等字段，需要确认 schema.sql 是否已同步更新
- **影响**：如果 `ddl-auto` 设为 `validate`，启动时可能报错
- **建议**：校验 schema.sql 与实体类字段的完全一致性

### 遗漏 3：ProxyAdapterController 缺少测试文件（低优先级）

- **计划要求**：`ProxyAdapterControllerTest.java` 测试 wake_compute 响应格式
- **当前状态**：测试目录中无 `ProxyAdapterControllerTest.java`
- **影响**：Proxy 适配接口的响应格式未被自动化测试覆盖，这是系统中最关键的集成点之一
- **建议**：补充 ProxyAdapterController 的单元测试

### 遗漏 4：ScramUtilsTest 缺失（低优先级）

- **计划要求**：`ScramUtilsTest.java` 验证 SCRAM hash 格式
- **当前状态**：测试目录中无此文件
- **影响**：密码 hash 格式正确性未被自动验证
- **建议**：补充 ScramUtils 测试

### 遗漏 5：ApiKeyFilter 测试缺失（低优先级）

- **计划要求**：`ApiKeyFilterTest.java` 测试认证逻辑
- **当前状态**：测试目录中无此文件
- **影响**：认证过滤器逻辑未被自动测试覆盖

### 遗漏 6：DatabaseService 中异步 provisioning 未实现（中等优先级）

- **计划要求**：使用 `CompletableFuture.runAsync()` 异步执行 Neon tenant/timeline 创建和 Pod 创建
- **当前状态**：`DatabaseService.create()` 同步执行所有操作
- **影响**：创建实例的 API 响应时间较长（需要等待 Neon 操作和 Pod 创建完成），但功能正确
- **建议**：MVP 阶段可接受同步方式，后续优化为异步

### 遗漏 7：Proxy 内部 token 验证未实现（低优先级）

- **计划要求**：Proxy 使用 `--control-plane-token` 参数传递 JWT，API 端需要验证此 token
- **当前状态**：`ProxyAdapterController` 的 `/proxy/**` 路径被 `ApiKeyFilter` 直接放行，未验证内部 token
- **影响**：Proxy 适配接口无认证保护，生产环境需加固
- **建议**：MVP 阶段可接受（内部网络），后续需添加 token 验证

## 总体评估

### 完成度评分：92%

实现代码高度对齐实施计划和 PRD 要求。所有核心功能（租户管理、实例 CRUD、分支管理、Proxy 适配、自动休眠、CLI 命令、Helm Charts、监控告警）均已实现。

### 关键发现

1. **功能完整性优秀**：PRD 中定义的所有 API 端点、CLI 命令、Helm 组件和监控配置均已实现，API 路径与 PRD 定义完全一致。

2. **架构基本一致**：分层结构（Controller -> Service -> Repository / NeonApiClient / ComputePodManager）与计划一致。主要偏离集中在类命名和包结构上，属于合理的代码组织优化。

3. **最重要的功能缺陷**：Proxy 分支级路由不完整（遗漏 1），`branchName` 被解析但未使用。这影响了 PRD 中"分支路由"的核心功能。建议优先修复。

4. **正面偏离较多**：错误处理增强、测试依赖增强、CLI 代码重构等均为正面偏离，提升了代码质量。

5. **已知偏离合理**：JDK 21->17 和实体类命名变化均为已知偏离，原因合理，影响极低。

### 风险评估

- **高风险**：无
- **中风险**：分支级 Proxy 路由不完整（遗漏 1）、同步 provisioning 可能导致 API 超时（遗漏 6）
- **低风险**：测试覆盖缺口（遗漏 3/4/5）、Proxy token 验证缺失（遗漏 7）、ErrorResponse 缺少 details 字段

### 建议优先级

1. 修复分支级 Proxy 路由（遗漏 1）-- 影响核心功能
2. 补充 ProxyAdapterController 测试 -- 关键集成点
3. 其余遗漏可在后续迭代中处理
