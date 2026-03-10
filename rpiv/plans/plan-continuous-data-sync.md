---
description: "功能实施计划: continuous-data-sync"
status: completed
created_at: 2026-03-09T16:00:00
updated_at: 2026-03-09T18:30:00
archived_at: null
related_files:
  - rpiv/requirements/prd-continuous-data-sync.md
---

# 功能：PostgreSQL 逻辑复制持续数据同步

以下计划应该是完整的，但在开始实施之前，验证文档和代码库模式以及任务合理性非常重要。

特别注意现有工具、类型和模型的命名。从正确的文件导入等。

## 功能描述

基于 PostgreSQL 原生逻辑复制（Publication/Subscription）机制，为用户提供从外部 PostgreSQL 数据库到 DBay 的持续增量数据同步能力。用户在现有导入向导中选择"持续同步"模式，系统自动在源库创建 Publication、在目标库创建 Subscription，实现变更数据的准实时捕获和应用。包含同步状态监控、暂停/恢复/永久停止控制、WAL 安全保护和 Serverless 休眠兼容。

## 用户故事

作为数据库管理员
我想要在导入向导中建立从源 PostgreSQL 到 DBay 的持续同步
以便源库的数据变更能自动同步到 DBay，无需重复手动导入

## 问题陈述

DBay 当前仅支持一次性全量导入。用户完成初始迁移后，源库持续产生新数据，需要反复手动执行导入，效率低且容易遗漏。

## 解决方案陈述

复用现有导入向导，新增"持续同步（SYNC）"模式。通过 Import Job Pod 在源库创建 Publication 和在目标库创建 Subscription。同步由 PostgreSQL 内置逻辑复制驱动，Lakeon API 定时采集状态到 metadata-db，前端展示同步延迟和表级状态。

## 功能元数据

**功能类型**：新功能
**估计复杂度**：高
**主要受影响的系统**：ImportService, ImportJobPodManager, ImportWizard, ImportTaskDetail, import.sh
**依赖项**：PostgreSQL 逻辑复制（源库 wal_level=logical）

---

## 上下文参考

### 相关代码库文件 重要：在实施之前必须阅读这些文件！

**后端：**
- `lakeon-api/src/main/java/com/lakeon/service/ImportService.java` (全文) - 现有导入服务，需扩展支持 SYNC 模式
  - 第 68-86 行: `testConnection()` — 需增加 wal_level 检测
  - 第 130-175 行: `createImport()` — 需处理 mode=SYNC 的创建逻辑
  - 第 177-247 行: `prepareAndLaunchImport()` — 需为 SYNC 模式构建不同的 task.json
  - 第 272-331 行: `handleCallback()` — 需处理 SYNC 模式的回调（初始化完成 vs 持续同步）
  - 第 334-418 行: `pauseImport()/resumeImport()` — 需扩展支持 Subscription DISABLE/ENABLE
  - 第 514-541 行: `checkOrphanedImportTasks()` — SYNC 模式不应标记 orphaned（pod 已退出是正常的）
- `lakeon-api/src/main/java/com/lakeon/k8s/ImportJobPodManager.java` (全文) - Job Pod 创建
  - 第 35-148 行: `launchJobPod()` — 需支持 SYNC 模式的不同配置
  - 第 197-234 行: `buildTaskJson()` — 需添加 sync 相关字段（publication_name, subscription_name）
- `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java` (全文) - 任务实体
  - 需新增字段: publication_name, subscription_name, sync_status, replay_lag_seconds 等
- `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTableTaskEntity.java` (全文) - 表级任务实体
  - 需新增字段: sync_state (对应 pg_subscription_rel 的 srsubstate)
- `lakeon-api/src/main/java/com/lakeon/controller/ImportController.java` (全文) - 需新增 sync-status、stop 端点
- `lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java` (全文) - 需支持 mode=SYNC
- `lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java` (全文) - 需包含 sync 字段
- `lakeon-api/src/main/java/com/lakeon/model/enums/ImportMode.java` (全文) - 需新增 SYNC 枚举值
- `lakeon-api/src/main/java/com/lakeon/model/enums/ImportTaskStatus.java` (全文) - 需新增 SYNCING, CATCHING_UP 状态
- `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` (第 88-108 行) - 需新增 sync 配置
- `lakeon-api/src/main/resources/application.yml` (全文) - 需新增 LAKEON_SYNC_* 环境变量
- `deploy/helm/lakeon/scripts/import.sh` (全文) - 需扩展支持 sync 模式（创建 Publication/Subscription）
- `deploy/helm/lakeon/templates/configmap-api.yaml` (全文) - 需新增 sync 配置项

**前端：**
- `lakeon-console/src/views/database/ImportWizard.vue` (全文) - 需在 Step 1 添加 SYNC 模式选项
  - 第 54-86 行: Step 1 模式选择 — 需新增"持续同步"选项
  - 第 18-52 行: Step 0 连接测试 — 需展示 wal_level 检测结果
- `lakeon-console/src/views/database/ImportTaskDetail.vue` (全文) - 需展示 sync 状态面板
  - 第 3-48 行: 头部概要 — 需区分导入/同步任务
  - 第 41-47 行: 操作按钮 — 需新增 stop 按钮和确认对话框
  - 第 58-86 行: 表级状态 — 需展示同步状态（不只是导入状态）
- `lakeon-console/src/api/import.ts` (全文) - 需新增 syncStatus、stop API 方法
- `lakeon-console/src/views/import/ImportEntry.vue` (全文) - 需区分导入/同步任务的展示

### 要创建的新文件

**后端：**
- `lakeon-api/src/main/java/com/lakeon/service/SyncStatusCollector.java` - 定时任务，采集同步状态
- `lakeon-api/src/main/java/com/lakeon/model/dto/SyncStatusResponse.java` - 同步状态响应 DTO
- `lakeon-api/src/main/java/com/lakeon/model/dto/StopSyncRequest.java` - 停止同步请求 DTO
- `deploy/helm/lakeon/scripts/sync-setup.sh` - 同步初始化脚本（创建 Publication + Subscription）

**前端：**
- 无需新建文件，全部在现有文件中扩展

### 相关文档

- [PostgreSQL Logical Replication](https://www.postgresql.org/docs/17/logical-replication.html)
  - Publication/Subscription 创建语法和选项
- [pg_stat_subscription](https://www.postgresql.org/docs/17/monitoring-stats.html#MONITORING-PG-STAT-SUBSCRIPTION)
  - 同步状态视图字段说明
- [pg_subscription_rel](https://www.postgresql.org/docs/17/catalog-pg-subscription-rel.html)
  - 表级同步状态字段: srsubstate (i/d/f/s/r)
- [CREATE PUBLICATION](https://www.postgresql.org/docs/17/sql-createpublication.html)
  - `FOR TABLE` 语法, `publish` 参数
- [CREATE SUBSCRIPTION](https://www.postgresql.org/docs/17/sql-createsubscription.html)
  - `copy_data`, `enabled`, `slot_name` 参数
- [pg_replication_slots](https://www.postgresql.org/docs/17/view-pg-replication-slots.html)
  - `pg_wal_lsn_diff()` 计算 WAL 积压

### 要遵循的模式

**命名约定：**
- Java: camelCase 方法/变量, PascalCase 类, snake_case 数据库列名
- Vue: camelCase 变量/函数, PascalCase 组件, kebab-case CSS
- API: snake_case JSON 字段名
- K8s: kebab-case 资源名

**错误处理：**
- Java: try/catch 在 async 线程中，异常写入 task.error_message
- Vue: try/catch + 错误 ref，`e.response?.data?.error?.message || e.response?.data?.message || e.message`

**状态机模式：**
- ImportTaskStatus 枚举定义所有可能状态
- Service 层方法校验状态转换合法性（如只有 RUNNING 才能 pause）
- 前端按 status 显示不同操作按钮

**异步模式：**
- Java: `importExecutor.submit(() -> ...)` 异步执行，立即返回 response
- Vue: `async/await` + loading ref 禁用 UI

**K8s Pod 模式：**
- Pod name: `import-{taskId}` (underscores → hyphens)
- 3 个 Volume: scripts (ConfigMap), config (per-task ConfigMap), secrets (per-task Secret)
- Command: `bash /scripts/{script-name}.sh`
- Callback URL: `http://lakeon-api.lakeon.svc.cluster.local:8080/api/v1/import/callback/{taskId}`

---

## 实施计划

### 阶段 1：后端数据模型和配置扩展

扩展现有实体、枚举和配置，为 SYNC 模式添加数据基础。

**任务：**
- ImportMode 枚举新增 SYNC
- ImportTaskStatus 枚举新增 SYNCING, CATCHING_UP
- ImportTaskEntity 新增 sync 相关字段
- ImportTableTaskEntity 新增 sync_state 字段
- LakeonProperties 新增 SyncConfig
- application.yml 新增 LAKEON_SYNC_* 配置
- configmap-api.yaml 新增 sync 配置映射

### 阶段 2：后端同步创建和生命周期管理

实现同步任务的创建、暂停/恢复/停止 API，以及 sync-setup.sh 脚本。

**任务：**
- sync-setup.sh 脚本（创建 Publication + Subscription）
- ImportService 扩展：SYNC 模式创建逻辑
- ImportService 扩展：stop 操作（cleanup 选项）
- ImportService 扩展：pause/resume 对 SYNC 模式的处理
- ImportController 新增端点
- testConnection 增加 wal_level 检测
- checkOrphanedImportTasks 排除 SYNC 任务

### 阶段 3：后端同步状态采集

实现定时任务采集同步状态，包括 replay_lag、表级状态、WAL 积压检测。

**任务：**
- SyncStatusCollector 定时任务
- SyncStatusResponse DTO
- ImportController 新增 sync-status 端点

### 阶段 4：前端集成

扩展 ImportWizard、ImportTaskDetail、ImportEntry 和 import API。

**任务：**
- import.ts API 新增方法
- ImportWizard 新增 SYNC 模式
- ImportTaskDetail 同步状态面板
- ImportEntry 区分导入/同步任务

---

## 逐步任务

### 任务 1: UPDATE `ImportMode.java` — 新增 SYNC 枚举值

- **IMPLEMENT**: 在枚举中添加 `SYNC` 值
- **PATTERN**: 参考现有 `FULL, SELECTIVE` 模式
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 2: UPDATE `ImportTaskStatus.java` — 新增同步状态

- **IMPLEMENT**: 添加 `SYNCING`（持续同步中）和 `CATCHING_UP`（追赶中）枚举值
- **PATTERN**: 参考现有 `PENDING, RUNNING, COMPLETED, FAILED, PARTIAL, CANCELLED, PAUSED`
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 3: UPDATE `ImportTaskEntity.java` — 新增同步字段

- **IMPLEMENT**: 添加以下字段:
  ```java
  @Column(name = "publication_name", length = 128)
  private String publicationName;

  @Column(name = "subscription_name", length = 128)
  private String subscriptionName;

  @Column(name = "slot_name", length = 128)
  private String slotName;

  @Column(name = "sync_status", length = 32)
  private String syncStatus; // INITIALIZING, SYNCING, CATCHING_UP, PAUSED, STOPPED, ERROR

  @Column(name = "replay_lag_seconds")
  private Double replayLagSeconds;

  @Column(name = "sync_rate_rows_per_sec")
  private Long syncRateRowsPerSec;

  @Column(name = "last_sync_at")
  private Instant lastSyncAt;

  @Column(name = "wal_retained_bytes")
  private Long walRetainedBytes;

  @Column(name = "wal_warning")
  private Boolean walWarning;
  ```
- **PATTERN**: 参考现有字段命名和注解风格 (snake_case 列名, camelCase 变量名)
- **GOTCHA**: JPA ddl-auto=update 会自动添加列，无需手动 DDL
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 4: UPDATE `ImportTableTaskEntity.java` — 新增同步状态字段

- **IMPLEMENT**: 添加:
  ```java
  @Column(name = "sync_state", length = 16)
  private String syncState; // i=initializing, d=data-copy, f=finished-copy, s=synchronized, r=ready

  @Column(name = "synced_rows")
  private Long syncedRows;
  ```
- **PATTERN**: 参考现有字段
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 5: UPDATE `LakeonProperties.java` — 新增 SyncConfig

- **IMPLEMENT**: 在 LakeonProperties 中添加内部类:
  ```java
  @Data
  public static class SyncConfig {
      private Duration pollInterval = Duration.ofSeconds(30);
      private long walWarnBytes = 1073741824L; // 1GB
      private int maxTasks = 10;
  }
  ```
  并在 LakeonProperties 中添加字段: `private SyncConfig sync = new SyncConfig();`
- **PATTERN**: 参考现有 `K8sConfig`, `DefaultsConfig` 内部类风格
- **IMPORTS**: `java.time.Duration`
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 6: UPDATE `application.yml` — 新增同步配置

- **IMPLEMENT**: 在 `lakeon:` 下添加:
  ```yaml
  sync:
    poll-interval: ${LAKEON_SYNC_POLL_INTERVAL:30s}
    wal-warn-bytes: ${LAKEON_SYNC_WAL_WARN_BYTES:1073741824}
    max-tasks: ${LAKEON_SYNC_MAX_TASKS:10}
  ```
- **PATTERN**: 参考现有 `defaults:`, `k8s:` 配置块
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 7: UPDATE `configmap-api.yaml` — 新增 sync 环境变量

- **IMPLEMENT**: 在 ConfigMap data 中添加:
  ```yaml
  LAKEON_SYNC_POLL_INTERVAL: "{{ .Values.sync.pollInterval | default "30s" }}"
  LAKEON_SYNC_WAL_WARN_BYTES: "{{ .Values.sync.walWarnBytes | default "1073741824" }}"
  LAKEON_SYNC_MAX_TASKS: "{{ .Values.sync.maxTasks | default "10" }}"
  ```
- **PATTERN**: 参考现有环境变量映射风格
- **VALIDATE**: `helm template lakeon deploy/helm/lakeon -f deploy/cce/values-cce.yaml --show-only templates/configmap-api.yaml 2>&1 | head -5`

### 任务 8: CREATE `deploy/helm/lakeon/scripts/sync-setup.sh` — 同步初始化脚本

- **IMPLEMENT**: Shell 脚本，由 Import Job Pod 执行，流程:
  1. 解析 task.json（复用 import.sh 的解析模式）
  2. 连接源库:
     - 检测 wal_level: `SHOW wal_level` → 必须为 logical
     - 创建 Publication: `CREATE PUBLICATION {pub_name} FOR TABLE {table_list}`
  3. 连接目标库:
     - 创建 Subscription: `CREATE SUBSCRIPTION {sub_name} CONNECTION '{source_connstr}' PUBLICATION {pub_name} WITH (copy_data = true, slot_name = '{slot_name}')`
  4. 等待初始同步完成: 轮询 `pg_subscription_rel` 直到所有表 `srsubstate = 'r'`
  5. 调用 callback 报告初始化完成（status=SYNCING）
  6. 退出 Pod
- **PATTERN**: 参考 `import.sh` 的结构（JSON 解析、callback 函数、错误处理）
- **GOTCHA**:
  - 源库连接串需要包含 `?sslmode=prefer` 处理各种网络环境
  - Publication 和 Subscription 名称使用 task ID 避免冲突
  - `slot_name` 必须显式指定（Subscription 默认自动生成但无法预知名称）
  - 需要 keepalive（同 import.sh）防止目标 compute 在初始复制时休眠
- **VALIDATE**: `bash -n deploy/helm/lakeon/scripts/sync-setup.sh` (语法检查)

### 任务 9: UPDATE `ImportJobPodManager.java` — 支持 SYNC 模式

- **IMPLEMENT**:
  - `launchJobPod()` 方法: 当 task.mode == SYNC 时:
    - 脚本路径改为 `/scripts/sync-setup.sh`
    - Pod name 前缀改为 `sync-{taskId}`
  - `buildTaskJson()` 方法: 当 mode == SYNC 时添加字段:
    ```json
    {
      "mode": "SYNC",
      "publication_name": "lakeon_pub_{taskId}",
      "subscription_name": "lakeon_sub_{taskId}",
      "slot_name": "lakeon_slot_{taskId}"
    }
    ```
  - 确保 ConfigMap 挂载包含 sync-setup.sh 脚本
- **PATTERN**: 参考现有 `launchJobPod()` 的 Pod 构建模式
- **GOTCHA**:
  - ConfigMap for scripts 是全局的（来自 Helm），不是 per-task 的
  - 需要确认 Helm ConfigMap `lakeon-import-scripts` 包含 sync-setup.sh
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 10: UPDATE Helm scripts ConfigMap — 挂载 sync-setup.sh

- **IMPLEMENT**: 找到 Helm 中创建 `lakeon-import-scripts` ConfigMap 的模板，添加 sync-setup.sh 的内容
- **PATTERN**: 参考 import.sh 的挂载方式
- **VALIDATE**: `helm template lakeon deploy/helm/lakeon -f deploy/cce/values-cce.yaml --show-only templates/configmap-scripts.yaml 2>&1 | head -5`

### 任务 11: UPDATE `CreateImportRequest.java` — 支持 SYNC 模式

- **IMPLEMENT**: mode 字段已支持 ImportMode 枚举，确认可以接收 "SYNC" 值。如果 mode 校验逻辑中有 FULL/SELECTIVE 硬编码，需要放开。
- **PATTERN**: 参考现有 DTO 模式
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 12: CREATE `StopSyncRequest.java` — 停止同步请求 DTO

- **IMPLEMENT**:
  ```java
  @Data
  public class StopSyncRequest {
      private boolean cleanup = true; // true=清理源库资源, false=仅暂停
  }
  ```
- **PATTERN**: 参考 `CreateImportRequest.java` 风格
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 13: CREATE `SyncStatusResponse.java` — 同步状态响应 DTO

- **IMPLEMENT**:
  ```java
  @Data
  public class SyncStatusResponse {
      private String overallStatus;
      private Double replayLagSeconds;
      private Long syncRateRowsPerSec;
      private Instant lastSyncAt;
      private Long walRetainedBytes;
      private Boolean walWarning;
      private List<TableSyncStatus> tables;

      @Data
      public static class TableSyncStatus {
          private String tableName;
          private String schemaName;
          private String status; // INITIALIZING, COPYING, SYNCING, ERROR
          private Long syncedRows;
          private String error;
      }
  }
  ```
- **PATTERN**: 参考 `ImportTaskResponse.java` 嵌套类风格
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 14: UPDATE `ImportTaskResponse.java` — 包含同步字段

- **IMPLEMENT**: 添加 sync 相关字段到 response DTO:
  ```java
  private String publicationName;
  private String subscriptionName;
  private String syncStatus;
  private Double replayLagSeconds;
  private Long syncRateRowsPerSec;
  private Instant lastSyncAt;
  private Long walRetainedBytes;
  private Boolean walWarning;
  ```
  并在构建方法（fromEntity 或构造器）中映射这些字段。
- **PATTERN**: 参考现有 fromEntity 映射模式
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 15: UPDATE `ImportService.java` — testConnection 增加 wal_level 检测

- **IMPLEMENT**: 在 `testConnection()` 方法中，连接成功后额外执行:
  ```java
  String walLevel = stmt.executeQuery("SHOW wal_level").getString(1);
  boolean hasReplicationPriv = stmt.executeQuery(
      "SELECT rolreplication FROM pg_roles WHERE rolname = current_user"
  ).getBoolean(1);
  ```
  返回 wal_level 和 replication 权限信息到前端。
- **PATTERN**: 参考现有 testConnection 的 JDBC 执行模式
- **GOTCHA**: SHOW 语句可能在某些托管 PG 上受限，需要 catch 并返回 "unknown"
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 16: UPDATE `ImportService.java` — createImport 支持 SYNC 模式

- **IMPLEMENT**: 在 `createImport()` 中:
  - 检查 SYNC 模式下的租户同步任务数限制 (`maxTasks`)
  - 生成 publication/subscription/slot 名称: `lakeon_pub_{shortId}`, `lakeon_sub_{shortId}`, `lakeon_slot_{shortId}`
  - 设置 task 的 publicationName, subscriptionName, slotName
  - 设置初始 syncStatus = "INITIALIZING"
  - `prepareAndLaunchImport()` 中: SYNC 模式使用 sync-setup.sh 而非 import.sh
- **PATTERN**: 参考现有 createImport 异步模式
- **GOTCHA**: shortId 用 taskId 的后 12 位即可（PG 标识符长度限制 63 字符）
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 17: UPDATE `ImportService.java` — handleCallback 支持 SYNC 回调

- **IMPLEMENT**: sync-setup.sh 完成后回调:
  - 收到 status=SYNCING 的回调时，将任务状态更新为 SYNCING
  - 不同于导入的 COMPLETED，SYNC 任务不会在 callback 后标记完成
  - 清理 Job Pod（sync-setup Pod 已完成使命）
  - 恢复原始 suspend timeout（但保持较长值，如 60m，避免频繁休眠）
- **PATTERN**: 参考现有 handleCallback 状态机
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 18: UPDATE `ImportService.java` — stop 操作

- **IMPLEMENT**: 新增 `stopSync(tenantId, dbId, taskId, cleanup)` 方法:
  - 验证任务是 SYNC 模式且状态为 SYNCING/PAUSED/CATCHING_UP/ERROR
  - 如果 `cleanup == true`:
    - 连接目标库: `DROP SUBSCRIPTION IF EXISTS {sub_name}`
    - 连接源库: `DROP PUBLICATION IF EXISTS {pub_name}`
    - 源库上的 replication slot 会随 DROP SUBSCRIPTION 自动删除
  - 如果 `cleanup == false`:
    - 连接目标库: `ALTER SUBSCRIPTION {sub_name} DISABLE`
  - 更新任务状态为 COMPLETED (cleanup) 或 PAUSED (!cleanup)
- **PATTERN**: 参考现有 pauseImport/cancelImport 模式
- **GOTCHA**:
  - DROP SUBSCRIPTION 需要先 DISABLE 再 DROP（active subscription 无法直接 DROP）
  - 如果目标库已休眠，需要先唤醒
  - 如果源库不可达，cleanup 应跳过源库操作并记录警告
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 19: UPDATE `ImportService.java` — pause/resume 扩展

- **IMPLEMENT**:
  - `pauseImport()`: 当 mode==SYNC 时:
    - 连接目标库执行 `ALTER SUBSCRIPTION {sub_name} DISABLE`
    - 更新 syncStatus = "PAUSED"
  - `resumeImport()`: 当 mode==SYNC 时:
    - 唤醒 compute（如已休眠）
    - 连接目标库执行 `ALTER SUBSCRIPTION {sub_name} ENABLE`
    - 更新 syncStatus = "CATCHING_UP"（状态采集器会更新为 SYNCING）
- **PATTERN**: 参考现有 pause/resume 模式
- **GOTCHA**: 需要获取目标库连接串（通过 database entity 的 compute host + port 55433）
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 20: UPDATE `ImportService.java` — checkOrphanedImportTasks 排除 SYNC

- **IMPLEMENT**: 在 orphan 检测逻辑中，排除 mode==SYNC 且 status==SYNCING 的任务（因为 SYNC 模式的 Job Pod 正常退出后任务继续运行）
- **PATTERN**: 添加条件判断
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 21: UPDATE `ImportController.java` — 新增端点

- **IMPLEMENT**:
  ```java
  @GetMapping("/databases/{dbId}/import/{taskId}/sync-status")
  public ResponseEntity<SyncStatusResponse> getSyncStatus(...)

  @PostMapping("/databases/{dbId}/import/{taskId}/stop")
  public ResponseEntity<ImportTaskResponse> stopSync(@RequestBody StopSyncRequest request, ...)
  ```
- **PATTERN**: 参考现有 pause/resume/cancel 端点模式
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 22: CREATE `SyncStatusCollector.java` — 定时状态采集

- **IMPLEMENT**: Spring `@Scheduled` 定时任务:
  ```java
  @Component
  public class SyncStatusCollector {
      @Scheduled(fixedDelayString = "${lakeon.sync.poll-interval:30000}")
      public void collectSyncStatus() {
          // 1. 查询所有 status=SYNCING/CATCHING_UP 的 SYNC 任务
          // 2. 对每个任务:
          //    a. 获取目标库连接信息
          //    b. 连接目标库查询 pg_stat_subscription:
          //       SELECT latest_end_lsn, latest_end_time FROM pg_stat_subscription WHERE subname = ?
          //    c. 计算 replay_lag = now() - latest_end_time
          //    d. 查询 pg_subscription_rel:
          //       SELECT srrelid::regclass, srsubstate FROM pg_subscription_rel WHERE srsubid = (SELECT oid FROM pg_subscription WHERE subname = ?)
          //    e. 连接源库查询 WAL 积压:
          //       SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) FROM pg_replication_slots WHERE slot_name = ?
          //    f. 更新 ImportTaskEntity 和 ImportTableTaskEntity 的 sync 字段
          //    g. WAL 积压超阈值时设置 walWarning = true
      }
  }
  ```
- **PATTERN**: 参考现有 `checkOrphanedImportTasks` 的 @Scheduled 模式
- **GOTCHA**:
  - 目标库可能已休眠（compute pod 不存在），此时跳过采集
  - 源库可能不可达，catch 异常并标记状态
  - 需要 JDBC 连接池管理，避免每次采集都新建连接
  - `pg_subscription_rel.srrelid::regclass` 返回表名，需匹配到 ImportTableTaskEntity
- **IMPORTS**: `org.springframework.scheduling.annotation.Scheduled`
- **VALIDATE**: `cd lakeon-api && ./mvnw compile -q`

### 任务 23: UPDATE `import.ts` — 新增 API 方法

- **IMPLEMENT**:
  ```typescript
  // 同步状态查询
  syncStatus(dbId: string, taskId: string) {
    return client.get<SyncStatusResponse>(`/databases/${dbId}/import/${taskId}/sync-status`)
  }

  // 停止同步
  stop(dbId: string, taskId: string, cleanup: boolean) {
    return client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/stop`, { cleanup })
  }
  ```
  新增类型:
  ```typescript
  interface SyncStatusResponse {
    overall_status: string
    replay_lag_seconds: number | null
    sync_rate_rows_per_sec: number | null
    last_sync_at: string | null
    wal_retained_bytes: number | null
    wal_warning: boolean
    tables: Array<{
      table_name: string
      schema_name: string
      status: string
      synced_rows: number | null
      error: string | null
    }>
  }
  ```
  扩展 ImportTask 类型:
  ```typescript
  // 添加到现有 ImportTask interface
  publication_name?: string
  subscription_name?: string
  sync_status?: string
  replay_lag_seconds?: number | null
  wal_retained_bytes?: number | null
  wal_warning?: boolean
  ```
- **PATTERN**: 参考现有 `pause()`, `resume()` 方法风格
- **VALIDATE**: `cd lakeon-console && npx vue-tsc --noEmit 2>&1 | tail -5`

### 任务 24: UPDATE `ImportWizard.vue` — Step 1 新增 SYNC 模式

- **IMPLEMENT**:
  - 在 Step 0 连接测试成功后，展示 wal_level 检测结果:
    - 如果 wal_level == 'logical': 显示绿色提示 "✓ 支持逻辑复制"
    - 如果 wal_level != 'logical': 显示黄色警告 "源库 wal_level 为 {value}，持续同步需要设置为 logical"
  - 在 Step 1 模式选择中新增第三个选项:
    ```html
    <label class="mode-option">
      <input type="radio" v-model="form.mode" value="SYNC" :disabled="walLevel !== 'logical'" />
      <div>
        <strong>持续同步</strong>
        <p>基于逻辑复制，持续同步源库数据变更</p>
      </div>
    </label>
    ```
  - SYNC 模式下:
    - 隐藏冲突策略选择（Step 2），因为同步不存在冲突策略
    - 表选择逻辑不变（复用）
    - 确认页展示: "将建立从 {source} 到 {target} 的持续同步"
  - form 新增 `walLevel` ref（从 testConnection 响应中获取）
- **PATTERN**: 参考现有模式选择 radio 的样式和交互
- **GOTCHA**: SYNC 模式 disabled 时需要 tooltip 说明原因
- **VALIDATE**: `cd lakeon-console && npx vue-tsc --noEmit 2>&1 | tail -5`

### 任务 25: UPDATE `ImportTaskDetail.vue` — 同步状态面板

- **IMPLEMENT**:
  - 当 `task.mode === 'SYNC'` 时，显示同步状态面板替代进度条:
    ```html
    <!-- Sync Status Panel -->
    <div v-if="task.mode === 'SYNC'" class="sync-status-panel">
      <div class="sync-metrics">
        <div class="sync-metric">
          <div class="sync-metric-value">{{ syncStatus?.replay_lag_seconds?.toFixed(1) ?? '-' }}s</div>
          <div class="sync-metric-label">同步延迟</div>
        </div>
        <div class="sync-metric">
          <div class="sync-metric-value">{{ syncStatus?.sync_rate_rows_per_sec ?? '-' }}</div>
          <div class="sync-metric-label">行/秒</div>
        </div>
        <div class="sync-metric">
          <div class="sync-metric-value">{{ formatBytes(syncStatus?.wal_retained_bytes) }}</div>
          <div class="sync-metric-label">WAL 积压</div>
        </div>
        <div class="sync-metric">
          <div class="sync-metric-value">{{ syncStatus?.last_sync_at ? formatDate(syncStatus.last_sync_at) : '-' }}</div>
          <div class="sync-metric-label">最后同步</div>
        </div>
      </div>
      <!-- WAL Warning -->
      <div v-if="syncStatus?.wal_warning" class="wal-warning">
        ⚠ 源库复制槽 WAL 积压较大，请确认网络连通性或考虑暂停同步
      </div>
    </div>
    ```
  - 操作按钮: SYNC 模式显示"暂停" / "恢复" / "停止"（不显示 cancel/retry）
  - 点击"停止"弹出确认对话框:
    ```html
    <div v-if="showStopDialog" class="dialog-overlay">
      <div class="dialog-box">
        <h4>停止同步</h4>
        <label><input type="radio" v-model="stopCleanup" :value="true" /> 永久停止并清理源库资源</label>
        <p class="stop-hint">删除源库 Publication 和复制槽</p>
        <label><input type="radio" v-model="stopCleanup" :value="false" /> 暂停（保留复制槽）</label>
        <p class="stop-hint warn">复制槽会持续占用源库 WAL 空间</p>
        <div class="dialog-actions">
          <button @click="showStopDialog = false">取消</button>
          <button class="btn btn-danger" @click="handleStop">确认停止</button>
        </div>
      </div>
    </div>
    ```
  - 轮询: SYNC 模式使用 `syncStatus` API 轮询（间隔改为 10s，同步状态变化慢于导入）
  - 表级状态: SYNC 模式显示 sync_state 而非 import status
- **PATTERN**: 参考现有 metric-cards 样式（MonitorView.vue）
- **VALIDATE**: `cd lakeon-console && npx vue-tsc --noEmit 2>&1 | tail -5`

### 任务 26: UPDATE `ImportEntry.vue` — 区分同步任务

- **IMPLEMENT**:
  - 任务列表表格新增"类型"列:
    ```html
    <td>{{ task.mode === 'SYNC' ? '持续同步' : (task.mode === 'FULL' ? '全库' : '指定表') }}</td>
    ```
  - SYNC 任务的状态显示使用 syncStatus 映射: SYNCING→"同步中", CATCHING_UP→"追赶中"
  - SYNC 任务不显示进度条（改为显示同步延迟）
- **PATTERN**: 参考现有 taskStatusText 映射
- **VALIDATE**: `cd lakeon-console && npx vue-tsc --noEmit 2>&1 | tail -5`

### 任务 27: 编译验证

- **VALIDATE**:
  ```bash
  cd lakeon-api && ./mvnw compile -q
  cd lakeon-console && npx vue-tsc --noEmit
  ```

---

## 测试策略

### 单元测试

- `ImportService.testConnection()`: 验证 wal_level 检测返回正确值
- `ImportService.createImport()`: 验证 SYNC 模式正确生成 publication/subscription 名称
- `ImportService.stopSync()`: 验证 cleanup=true/false 的不同行为
- `SyncStatusCollector`: Mock JDBC 连接验证状态采集逻辑

### 集成测试

使用本地 Docker PostgreSQL (wal_level=logical) 进行端到端测试:
1. 创建 SYNC 任务 → 验证 Publication + Subscription 创建成功
2. 在源库 INSERT 数据 → 验证数据同步到目标库
3. 暂停同步 → 验证 Subscription DISABLED
4. 恢复同步 → 验证追赶
5. 停止并清理 → 验证源库 Publication + 复制槽已删除

### 边缘情况

- 源库 wal_level 不是 logical → 前端阻止 SYNC 模式
- 源库不可达时的 stop 操作 → 跳过源库清理，仅清理目标库
- 目标库休眠时的状态采集 → 跳过，不报错
- 同一源表已有 Publication → 复用或报错
- 达到最大同步任务数限制 → 返回错误

---

## 验证命令

### 级别 1：编译检查

```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw compile -q
cd /Users/jacky/code/lakeon/lakeon-console && npx vue-tsc --noEmit
```

### 级别 2：单元测试

```bash
cd /Users/jacky/code/lakeon/lakeon-api && ./mvnw test -q
```

### 级别 3：脚本语法检查

```bash
bash -n /Users/jacky/code/lakeon/deploy/helm/lakeon/scripts/sync-setup.sh
```

### 级别 4：Helm 模板验证

```bash
helm template lakeon /Users/jacky/code/lakeon/deploy/helm/lakeon -f /Users/jacky/code/lakeon/deploy/cce/values-cce.yaml 2>&1 | head -20
```

### 级别 5：手动验证

1. 启动本地 PostgreSQL with wal_level=logical
2. 通过控制台创建 SYNC 导入任务
3. 在源库执行 INSERT/UPDATE/DELETE
4. 验证目标库数据同步
5. 测试暂停/恢复/停止操作

---

## 验收标准

- [ ] ImportMode 枚举包含 SYNC
- [ ] ImportTaskEntity 包含 publication_name, subscription_name, slot_name, sync 状态字段
- [ ] testConnection 返回 wal_level 信息
- [ ] SYNC 模式可通过 API 创建同步任务
- [ ] sync-setup.sh 正确创建 Publication 和 Subscription
- [ ] SyncStatusCollector 定时采集同步状态到 metadata-db
- [ ] sync-status API 返回延迟、表级状态、WAL 积压
- [ ] pause/resume 正确 DISABLE/ENABLE Subscription
- [ ] stop with cleanup=true 清理源库 Publication + 复制槽
- [ ] ImportWizard 显示 SYNC 模式选项和 wal_level 检测
- [ ] ImportTaskDetail 显示同步状态面板（延迟、速率、WAL 积压）
- [ ] ImportTaskDetail 停止对话框提供 cleanup 选项
- [ ] 后端编译无错误
- [ ] 前端类型检查无错误
- [ ] orphan 检测不误标 SYNC 任务

---

## 完成检查清单

- [ ] 所有任务按顺序完成
- [ ] 每个任务验证立即通过
- [ ] 所有验证命令成功执行
- [ ] 后端编译 + 前端类型检查通过
- [ ] sync-setup.sh 语法检查通过
- [ ] Helm 模板渲染正常
- [ ] 所有验收标准均满足

---

## 备注

**设计决策：**
1. **复用 ImportTaskEntity 而非新建 SyncTaskEntity**: 同步任务和导入任务共享大量字段（source_*, database_id, tenant_id, tables），通过 mode=SYNC 区分，避免代码重复。
2. **sync-setup.sh 独立脚本而非扩展 import.sh**: 两者逻辑差异较大（pg_dump/pg_restore vs CREATE PUBLICATION/SUBSCRIPTION），独立脚本更清晰。
3. **状态采集用定时任务而非回调**: SYNC 的 Job Pod 退出后同步由 PG 内置驱动，无法回调，只能轮询。
4. **暂停/恢复通过 ALTER SUBSCRIPTION**: 这是 PG 原生支持的操作，不需要额外机制。
5. **停止时 DROP SUBSCRIPTION 自动删除复制槽**: PG 行为，无需手动删除 slot。

**已知风险：**
- Neon compute 的 `cloud_admin` 用户是否有 `CREATE SUBSCRIPTION` 权限需要验证
- 源库 `REPLICATION` 权限检测可能在某些托管 PG（如 RDS）上表现不同
- `pg_subscription_rel` 视图在 PG 10/11 中字段可能略有不同

**信心分数：7/10**
- 核心逻辑清晰，PG 逻辑复制机制成熟
- 主要不确定性在于 Neon compute 对 Subscription 的支持程度
- sync-setup.sh 的 WAL 初始同步等待可能需要调试
