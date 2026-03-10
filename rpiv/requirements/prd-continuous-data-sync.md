---
description: "产品需求文档: PostgreSQL 逻辑复制持续数据同步"
status: completed
created_at: 2026-03-09T15:00:00
updated_at: 2026-03-09T16:00:00
archived_at: null
---

# PRD: PostgreSQL 逻辑复制持续数据同步

## 1. 执行摘要

DBay 当前支持一次性全量数据导入，但用户在完成初始迁移后，往往需要持续将源数据库的变更同步到 DBay。手动重复导入效率低、容易遗漏数据，且无法支持准实时的数据一致性需求。

本功能基于 PostgreSQL 原生逻辑复制（Logical Replication）机制，为用户提供从外部 PostgreSQL 数据库到 DBay 的持续、增量数据同步能力。用户可在现有导入向导中选择"持续同步"模式，系统自动在源库创建 Publication、在目标库创建 Subscription，实现变更数据的准实时捕获和应用。

**MVP 目标：** 支持用户从外部 PostgreSQL 源库选定表，建立持续同步通道，提供同步状态监控、暂停/恢复/永久停止控制，确保 WAL 安全和 Serverless 休眠兼容。

## 2. 使命

**产品使命：** 让用户以最低成本、最小侵入的方式，将外部 PostgreSQL 数据持续同步到 DBay，消除手动迁移的重复劳动。

**核心原则：**
1. **低侵入** — 对源数据库的修改最小化（仅需 `wal_level=logical` 和复制权限），不安装插件
2. **安全优先** — WAL 膨胀保护、复制槽清理、权限隔离，防止对源库造成风险
3. **Serverless 兼容** — 同步机制与 DBay Serverless 休眠/唤醒生命周期无缝配合
4. **透明可控** — 用户可随时查看同步延迟、表级状态，并自由控制同步的暂停/恢复/停止

## 3. 目标用户

### 主要用户角色
- **数据库管理员 / 开发者**：需要将生产环境或开发环境的 PostgreSQL 数据迁移并持续同步到 DBay
- **技术水平**：熟悉 PostgreSQL 基本操作，了解连接字符串、权限配置
- **典型场景**：正在将业务从自建 PG 迁移到 DBay，迁移期间需要源库和 DBay 保持数据一致

### 关键需求和痛点
- 初始全量导入后，源库持续产生新数据，需要增量同步
- 不希望反复手动执行导入操作
- 担心同步机制对源库性能和存储产生影响
- 需要清晰了解同步进度和延迟

## 4. MVP 范围

### 范围内 ✅
**核心功能：**
- ✅ 在现有导入向导 Step 2 新增"持续同步"模式选项
- ✅ 连接测试时自动检测源库 `wal_level` 是否为 `logical`
- ✅ 支持用户选择需要同步的表（复用现有表选择 UI）
- ✅ 自动在源库创建 Publication（`CREATE PUBLICATION`）
- ✅ 自动在目标库创建 Subscription（`CREATE SUBSCRIPTION`）
- ✅ 初始数据同步（Subscription 的 `copy_data = true`）
- ✅ 持续增量同步（基于 WAL 逻辑解码）

**状态监控：**
- ✅ 同步延迟（replication lag）展示
- ✅ 每张表的同步状态（初始复制中 / 同步中 / 错误）
- ✅ 同步速率（rows/s 或 bytes/s）
- ✅ 错误信息展示

**生命周期控制：**
- ✅ 暂停同步（保留复制槽，Subscription `DISABLE`）
- ✅ 恢复同步（`ENABLE` Subscription，自动追赶）
- ✅ 永久停止（用户选择：清理复制槽 + 删除 Publication，或仅暂停保留槽）
- ✅ 用户主动选择停止类型（对话框确认）

**安全保护：**
- ✅ 源库 `max_slot_wal_keep_size` 检测和建议
- ✅ WAL 膨胀超阈值时告警提示
- ✅ Serverless 休眠期间复制槽保持，唤醒后自动追赶

**技术：**
- ✅ Import Job Pod 执行初始配置（创建 Publication、Subscription）
- ✅ 同步状态持久化到 metadata-db
- ✅ API 端点：创建/查询/暂停/恢复/停止同步任务

### 范围外 ❌
- ❌ 双向同步（仅支持 源库 → DBay 单向）
- ❌ DDL 自动同步（MVP 后考虑，逻辑复制不原生支持）
- ❌ 非 PostgreSQL 源数据库（MySQL、MongoDB 等）
- ❌ 跨版本复制兼容性处理（假设源库 PG 版本 ≥ 10）
- ❌ 自动冲突解决（目标库为只读接收端）
- ❌ 细粒度行级过滤（PG 15+ `WHERE` 子句，MVP 不支持）

## 5. 用户故事

### 主要用户故事

**US-1：建立持续同步**
> 作为数据库管理员，我想要在导入向导中选择"持续同步"模式并选定表，以便源库的数据变更能自动同步到 DBay。

示例：用户在 Step 2 选择"持续同步"，选定 `orders`、`customers`、`products` 三张表，点击开始后系统自动建立同步通道。

**US-2：查看同步状态**
> 作为用户，我想要在任务详情页看到每张表的同步状态和整体延迟，以便了解数据是否是最新的。

示例：任务详情页显示 `orders` 同步延迟 2s、`customers` 同步延迟 1s，整体状态为"同步中"。

**US-3：暂停和恢复同步**
> 作为用户，我想要暂停同步以便在源库执行维护操作，维护结束后恢复同步自动追赶数据。

示例：用户点击"暂停"，同步停止但复制槽保留；维护后点击"恢复"，系统从断点继续同步。

**US-4：永久停止同步**
> 作为用户，我想要永久停止同步并选择是否清理源库上的复制槽和 Publication，以便迁移完成后不再占用源库资源。

示例：用户点击"停止"，弹出对话框让用户选择"清理源库资源"或"仅停止不清理"，确认后执行。

**US-5：wal_level 检测**
> 作为用户，我想要在建立同步前系统自动检测源库是否支持逻辑复制，以便我知道需要做什么配置。

示例：连接测试时发现 `wal_level=replica`，页面提示"源库需要设置 wal_level=logical 才能使用持续同步，请联系您的数据库管理员修改配置并重启"。

**US-6：WAL 膨胀告警**
> 作为用户，我想要在 WAL 膨胀风险较高时收到告警，以便及时处理避免源库存储爆满。

示例：同步任务详情页显示黄色告警"源库复制槽 WAL 积压已超过 1GB，请确认网络连通性或考虑暂停同步"。

**US-7：Serverless 休眠兼容**
> 作为用户，我希望 DBay 数据库休眠后不会丢失同步进度，唤醒后自动追赶未同步的数据。

示例：DBay 数据库因无活动休眠 30 分钟，期间源库产生 1000 条新数据。DBay 被查询唤醒后，Subscription 自动从上次 LSN 继续，追赶期间任务状态显示"追赶中"。

### 技术用户故事

**TUS-1：Import Job Pod 生命周期**
> 作为系统，需要通过 Import Job Pod 连接源库和目标库，执行 `CREATE PUBLICATION` 和 `CREATE SUBSCRIPTION`，完成后 Pod 退出。后续同步由 PostgreSQL 内置机制驱动。

**TUS-2：同步状态采集**
> 作为系统，需要定期查询目标库 `pg_stat_subscription` 和 `pg_subscription_rel` 视图，采集同步状态和延迟信息，写入 metadata-db。

## 6. 核心架构与模式

### 高级架构

```
源 PostgreSQL                              DBay
┌─────────────┐                    ┌─────────────────┐
│  用户数据库   │    WAL Stream     │  Compute Pod     │
│             │ ◄──────────────── │  (PG Subscription)│
│ Publication │                    │                  │
│ Repl Slot   │                    │  Neon Pageserver │
└─────────────┘                    └─────────────────┘
                                          │
                                   ┌──────┴──────┐
                                   │ Lakeon API   │
                                   │ (状态监控)    │
                                   └──────┬──────┘
                                          │
                                   ┌──────┴──────┐
                                   │ Metadata DB  │
                                   │ (任务/状态)   │
                                   └─────────────┘
```

### 工作流程

1. **创建阶段**（Import Job Pod）：
   - 连接源库 → `CREATE PUBLICATION sync_xxx FOR TABLE t1, t2, t3`
   - 连接目标库 → `CREATE SUBSCRIPTION sync_xxx CONNECTION '...' PUBLICATION sync_xxx WITH (copy_data = true)`
   - 记录 Publication name、Subscription name、复制槽名到 metadata-db
   - Pod 退出

2. **同步阶段**（PostgreSQL 内置）：
   - Subscription worker 自动从源库拉取 WAL 变更并应用
   - 初始阶段：`copy_data` 执行全表拷贝
   - 稳定阶段：流式接收 INSERT/UPDATE/DELETE

3. **监控阶段**（Lakeon API 定时任务）：
   - 定期查询 `pg_stat_subscription`、`pg_subscription_rel`
   - 计算 `replay_lag`，更新 metadata-db 中的任务状态
   - WAL 积压超阈值时标记告警

4. **休眠/唤醒**（Serverless 生命周期）：
   - 休眠：Compute Pod 销毁，复制槽保留在源库，WAL 继续积累
   - 唤醒：新 Compute Pod 启动，Subscription 配置从 Neon 存储恢复，自动重连源库追赶

### 关键设计模式

- **Job Pod 模式**：复用现有 Import Job Pod 模式执行一次性配置，不引入常驻进程
- **PostgreSQL 原生驱动**：同步由 PG Subscription 机制驱动，Lakeon 仅负责编排和监控
- **状态采集与展示分离**：API 定时采集状态到 metadata-db，前端从 metadata-db 读取展示

## 7. 工具/功能

### 7.1 导入向导增强

**同步模式选择（Step 2）**
- 在现有"全量导入"和"指定表导入"选项旁，新增"持续同步"模式
- 选择持续同步后，表选择逻辑不变
- 连接测试增加 `wal_level` 检测：
  - `logical` → 通过，可继续
  - 其他 → 显示提示信息，阻止继续

**前置检查清单展示**
- 源库 `wal_level` 状态
- 源库 PostgreSQL 版本
- 用户是否有 `REPLICATION` 权限
- `max_slot_wal_keep_size` 当前值及建议

### 7.2 同步任务管理

**任务详情页扩展**
- 任务类型标识：`FULL`（一次性）/ `SYNC`（持续同步）
- 同步状态面板：
  - 整体状态：初始复制中 / 同步中 / 追赶中 / 已暂停 / 错误 / 已停止
  - 同步延迟（秒）
  - 同步速率
  - 最后同步时间
- 表级状态列表：
  - 每张表的同步状态（初始复制中 / 同步中 / 错误）
  - 已同步行数
  - 错误信息（如有）
- 操作按钮：暂停 / 恢复 / 停止

**停止确认对话框**
- 选项 A："永久停止并清理"— 删除源库 Publication + 复制槽，删除目标库 Subscription
- 选项 B："暂停（保留复制槽）"— Disable Subscription，复制槽保留
- 风险提示：选项 B 会持续占用源库 WAL 空间

### 7.3 WAL 安全监控

- 定期检查源库复制槽 WAL 积压大小
- 超过阈值（默认 1GB）时在任务详情页显示告警
- 建议用户设置 `max_slot_wal_keep_size`（告知具体 SQL 命令）

## 8. 技术栈

### 后端
- **Java 17 / Spring Boot 3.3.5** — 现有 Lakeon API
- **Fabric8 Kubernetes Client 6.13.4** — Import Job Pod 管理
- **PostgreSQL JDBC** — 连接源库和目标库执行 DDL
- **Spring Scheduler** — 定时同步状态采集

### 前端
- **Vue 3 + TypeScript** — 现有 Lakeon Console
- **Vue Router** — 复用现有路由
- **ImportWizard.vue / ImportTaskDetail.vue** — 扩展现有组件

### 数据库
- **metadata-db (RDS PostgreSQL)** — 存储同步任务配置和状态
- **PostgreSQL 逻辑复制** — 核心同步机制（Publication/Subscription）

### 依赖
- 源库 PostgreSQL ≥ 10（逻辑复制最低版本要求）
- 源库 `wal_level = logical`
- 源库用户需要 `REPLICATION` 权限和表的 `SELECT` 权限

## 9. 安全与配置

### 权限要求
- **源库**：用户需有 `REPLICATION` 权限（用于创建复制槽）和目标表的 `SELECT` 权限
- **目标库**：`cloud_admin` 超级用户（用于 `CREATE SUBSCRIPTION`）
- **Lakeon API**：需要源库连接凭据（已通过导入向导收集，存储在 metadata-db，密码加密）

### 配置项
| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| 状态采集间隔 | `LAKEON_SYNC_POLL_INTERVAL` | `30s` | 定时查询同步状态的间隔 |
| WAL 告警阈值 | `LAKEON_SYNC_WAL_WARN_BYTES` | `1073741824` (1GB) | WAL 积压超过此值告警 |
| 最大同步任务数 | `LAKEON_SYNC_MAX_TASKS` | `10` | 每个租户最大同步任务数 |

### 安全范围
- **范围内**：源库凭据加密存储、复制槽清理、WAL 膨胀保护
- **范围外**：传输层加密（依赖用户源库 SSL 配置）、源库防火墙规则

## 10. API 规范

### 创建持续同步任务
```
POST /api/v1/databases/{dbId}/imports
Content-Type: application/json
Authorization: Bearer {api_key}

{
  "mode": "SYNC",
  "source_host": "source.example.com",
  "source_port": 5432,
  "source_dbname": "mydb",
  "source_user": "replicator",
  "source_password": "***",
  "source_schema": "public",
  "tables": ["orders", "customers", "products"]
}
```

响应：
```json
{
  "id": "sync_abc123",
  "mode": "SYNC",
  "status": "INITIALIZING",
  "created_at": "2026-03-09T15:00:00Z"
}
```

### 查询同步状态
```
GET /api/v1/databases/{dbId}/imports/{taskId}/sync-status
```

响应：
```json
{
  "overall_status": "SYNCING",
  "replay_lag_seconds": 2.5,
  "sync_rate_rows_per_sec": 150,
  "last_sync_at": "2026-03-09T15:30:00Z",
  "wal_retained_bytes": 52428800,
  "wal_warning": false,
  "tables": [
    {
      "table_name": "orders",
      "status": "SYNCING",
      "synced_rows": 125000,
      "error": null
    },
    {
      "table_name": "customers",
      "status": "SYNCING",
      "synced_rows": 8500,
      "error": null
    }
  ]
}
```

### 暂停同步
```
POST /api/v1/databases/{dbId}/imports/{taskId}/pause
```

### 恢复同步
```
POST /api/v1/databases/{dbId}/imports/{taskId}/resume
```

### 停止同步
```
POST /api/v1/databases/{dbId}/imports/{taskId}/stop
Content-Type: application/json

{
  "cleanup": true
}
```

- `cleanup: true` — 删除源库 Publication + 复制槽 + 目标库 Subscription
- `cleanup: false` — 仅 Disable Subscription，保留复制槽

## 11. 成功标准

### MVP 成功定义
持续同步功能可供内部测试使用，核心流程通过 E2E 验证。

### 功能要求
- ✅ 用户可在导入向导中选择"持续同步"模式并成功建立同步
- ✅ 初始全量数据正确同步到目标库
- ✅ 源库 INSERT/UPDATE/DELETE 变更在 30 秒内同步到目标库
- ✅ 同步状态和延迟信息在控制台正确展示
- ✅ 暂停后恢复可正确追赶数据
- ✅ 永久停止可正确清理源库资源（Publication + 复制槽）
- ✅ Serverless 休眠后唤醒可自动恢复同步
- ✅ WAL 积压超阈值时显示告警

### 质量指标
- 同步延迟 P95 < 10 秒（稳定网络环境）
- 状态采集延迟 < 采集间隔 × 2
- 无复制槽泄漏（停止清理后源库无残留）

### 用户体验目标
- 建立同步全流程 < 5 步操作
- 同步状态一目了然（颜色编码 + 延迟数值）
- 停止操作有明确的风险提示

## 12. 实施阶段

### Phase 1：后端基础（目标：核心 API 和 Job Pod）
**目标：** 实现同步任务创建、Publication/Subscription 管理的后端逻辑

**交付物：**
- ✅ `ImportTaskEntity` 扩展：新增 `mode=SYNC` 类型和同步相关字段
- ✅ `SyncService`：创建/暂停/恢复/停止同步任务
- ✅ Import Job Pod 脚本扩展：支持创建 Publication 和 Subscription
- ✅ 连接测试增加 `wal_level` 检测
- ✅ API 端点：`POST create`、`POST pause`、`POST resume`、`POST stop`

**验证标准：** 通过 API 调用可成功建立同步，手动验证数据同步

### Phase 2：状态监控（目标：定时采集和持久化）
**目标：** 实现同步状态的自动采集和存储

**交付物：**
- ✅ `SyncStatusCollector`：定时任务，查询 `pg_stat_subscription` 采集状态
- ✅ `SyncStatusEntity`：同步状态持久化表
- ✅ WAL 积压检测和告警标记
- ✅ `GET sync-status` API 端点

**验证标准：** 同步状态可通过 API 查询，WAL 告警正确触发

### Phase 3：前端集成（目标：完整用户流程）
**目标：** 将同步功能集成到控制台 UI

**交付物：**
- ✅ ImportWizard Step 2 新增"持续同步"模式选项
- ✅ 连接测试 `wal_level` 检测结果展示
- ✅ ImportTaskDetail 同步状态面板（延迟、速率、表级状态）
- ✅ 暂停/恢复/停止按钮和确认对话框
- ✅ WAL 告警提示

**验证标准：** 全流程 E2E 测试通过（创建→监控→暂停→恢复→停止）

### Phase 4：Serverless 兼容和稳定性（目标：生产就绪）
**目标：** 确保同步与 Serverless 生命周期兼容，处理边缘情况

**交付物：**
- ✅ 休眠/唤醒后同步自动恢复验证
- ✅ 长时间休眠后 WAL 追赶性能测试
- ✅ 网络断开/重连场景处理
- ✅ 并发同步任务负载测试
- ✅ 集成测试补充

**验证标准：** 72 小时稳定性测试通过，休眠/唤醒周期数据无丢失

## 13. 未来考虑

### MVP 后增强
- **DDL 自动同步**：监控源库 `pg_ddl_command_end` 事件，自动在目标库重放 DDL（需要 `ddl_command_end` 事件触发器 + 逻辑解码插件扩展）
- **行级过滤**：利用 PG 15+ `WHERE` 子句支持，允许用户只同步满足条件的行
- **同步延迟告警通知**：对接邮件/Webhook，延迟超阈值主动通知
- **同步任务调度**：支持定时窗口同步（如仅在非高峰时段同步）

### 集成机会
- **监控面板集成**：在 Monitor 页面展示同步任务总览和延迟趋势图
- **操作日志集成**：同步的暂停/恢复/停止操作记录到 Audit 日志
- **计费集成**：按同步数据量或时长计费

### 高级功能
- **多源聚合同步**：支持从多个源库同步到同一目标库的不同 Schema
- **Schema 映射**：同步时重命名表或列
- **冲突检测**：如果目标表有写入，检测和报告冲突

## 14. 风险与缓解措施

### 风险 1：源库 WAL 膨胀
**描述：** DBay 数据库长时间休眠导致复制槽持续保留 WAL，源库存储可能被撑满。
**缓解：**
- 检测并建议用户设置 `max_slot_wal_keep_size`
- 超阈值告警提示
- 停止清理功能确保用户可随时释放

### 风险 2：Serverless 唤醒后追赶延迟
**描述：** 长时间休眠后积累大量 WAL，唤醒后追赶可能需要较长时间。
**缓解：**
- 任务状态显示"追赶中"，让用户了解进度
- 建议用户对高频变更表缩短休眠超时
- 未来考虑"自动唤醒"机制定期追赶

### 风险 3：源库网络不可达
**描述：** DBay Compute Pod 需要通过公网连接源库，网络中断导致同步失败。
**缓解：**
- Subscription 内置自动重连机制
- 状态面板显示连接状态和最后成功时间
- 持续不可达时标记告警

### 风险 4：权限不足
**描述：** 用户提供的源库账号可能缺少 `REPLICATION` 权限或表的 `SELECT` 权限。
**缓解：**
- 连接测试阶段主动检测权限
- 显示明确的权限要求和修复 SQL 命令

### 风险 5：大表初始同步性能
**描述：** 初始 `copy_data` 对大表可能耗时较长，占用源库资源。
**缓解：**
- 初始复制阶段显示进度
- 允许用户在初始复制阶段取消
- 未来考虑支持并行初始复制

## 15. 附录

### PostgreSQL 逻辑复制关键概念
- **Publication**：源库端定义需要复制的表集合
- **Subscription**：目标库端定义从哪个源库的哪个 Publication 接收数据
- **Replication Slot**：源库端保留 WAL 的机制，确保 Subscriber 断线后不丢失数据
- **LSN (Log Sequence Number)**：WAL 中的位置标识，Subscriber 从上次确认的 LSN 继续接收

### 关键系统视图
- `pg_stat_subscription` — Subscription worker 状态和延迟
- `pg_subscription_rel` — 每张表的同步状态（`i`=初始化, `d`=数据拷贝, `s`=已同步, `r`=就绪）
- `pg_replication_slots` — 复制槽信息和 WAL 积压

### 相关文件
- `lakeon-api/src/main/java/com/lakeon/service/ImportService.java`
- `lakeon-api/src/main/java/com/lakeon/k8s/ImportJobPodManager.java`
- `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java`
- `lakeon-console/src/views/database/ImportWizard.vue`
- `lakeon-console/src/views/database/ImportTaskDetail.vue`
- `lakeon-console/src/views/import/ImportEntry.vue`
- `deploy/helm/lakeon/scripts/import.sh`
