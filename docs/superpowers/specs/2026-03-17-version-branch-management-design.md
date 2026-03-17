# 版本与分支管理系统设计

## 概述

为 Lakeon 数据库平台增加多版本多分支管理能力，让用户（人类和 Agent）能够创建版本快照、对比差异、回滚状态、管理分支，提供类似 git 的数据库版本控制体验。

## 核心概念

### 架构：分支优先（Branch-centric）

每个数据库包含多个分支，每个分支有独立的版本历史。主干是 default 分支。

```
Database
 ├── Branch "main" (default)
 │    ├── v1 "初始化"
 │    ├── v2 "加了用户表"
 │    └── v3 "当前"
 └── Branch "experiment" (parent: main@v2)
      ├── v1 "改了索引"
      └── v2 "当前"
```

### 概念定义

| 概念 | 定义 | Neon 映射 |
|------|------|-----------|
| 分支（Branch） | 独立可写的数据库副本，有自己的 timeline 和 compute | Neon timeline |
| 版本（Version） | 分支上某个 LSN 点的命名书签 + 物化快照 timeline | Neon branch timeline（只读） |
| Promote | 将分支提升为主干，原主干降为普通分支 | 切换 active timeline |
| Restore | 硬回滚到历史版本，自动创建备份分支 | 创建新 timeline 替换 |
| Squash | 删除中间版本书签 + 删除对应快照 timeline，触发 GC | 删除 branch timeline |

### 连接模型

| 对象 | 有 compute | 可读写 | 连接方式 | 适用场景 |
|------|-----------|--------|---------|---------|
| 主干（当前版本） | 常驻 | 读写 | 主连接串 | 生产环境，日常业务读写 |
| 分支 | 按需启动 | 读写 | 分支连接串 | 隔离实验、Agent 投机执行、并行方案验证 |
| 历史版本 | 按需临时启动 | 只读 | 临时连接串 / Console 代理查询 | 数据审计、回溯查看、diff 对比的数据源 |

## 目标应用场景

### 人类用户
- 手动创建版本保存重要节点
- 查看版本差异（schema diff + data diff + AI 摘要）
- Squash 多次修改为一个版本
- 回滚到历史版本（Restore 或 Promote）

### Agent 场景
1. **投机执行** — Agent 在分支上试操作，成功后 promote 为主干，失败就丢弃
2. **并行探索** — 多 Agent 各自分支尝试不同方案，选最优 promote
3. **Tool-call 隔离** — 每次工具调用在分支上执行，确认后合入
4. **安全迁移** — 分支上跑 migration，验证后 promote

## 数据模型

### BranchEntity（已有，新增 branch_type）

现有字段 + 扩展：
- `id`, `name`, `database_id`, `neon_timeline_id`
- `parent_branch_id`, `parent_branch_name`, `is_default`
- `status`, `compute_status`, `compute_pod_name`, `compute_host`, `compute_port`
- `connection_uri`, `lastActiveAt`, `createdAt`, `updatedAt`
- `branch_type`（新增）— 枚举：`USER`（用户创建）/ `BACKUP`（Restore 自动备份）/ `SNAPSHOT`（版本快照 timeline 的逻辑归属）。UI 可据此过滤或弱化显示系统生成的分支。

### VersionEntity（新增）

```
VersionEntity
├── id (ver_xxx)           -- 自动生成，ver_ 前缀
├── branch_id              -- FK → BranchEntity
├── name                   -- 用户命名，如 "加了用户表"
├── description            -- 可选描述
├── lsn                    -- 快照点的 LSN
├── snapshot_timeline_id   -- 物化的快照 timeline ID（pageserver 上）
├── created_by             -- 'user' / 'agent'
├── created_at
└── updated_at
```

每个版本在 pageserver 上创建一个轻量 branch timeline 作为快照：
- 不挂 compute pod，存储开销为 CoW 差异数据
- 需要查看数据时临时启动只读 compute
- 删除版本时同时删除快照 timeline，pageserver GC 回收存储

**版本排序**：分支内版本按 LSN 排序（非创建时间）。如果用户用 `at_lsn` 创建指向过去 LSN 的版本，它按 LSN 值插入正确位置。Squash 范围校验也基于 LSN。

**获取当前 LSN**：`at: "current"` 通过 compute 执行 `SELECT pg_current_wal_flush_lsn()` 获取精确 LSN。若 compute 已 suspend，使用 pageserver `GET timeline` 返回的 `last_record_lsn`。

**按时间创建版本**（P1）：需要通过 compute 执行 SQL 将 timestamp 解析为 LSN。初期 scope 仅支持 `current` 和 `at_lsn`，`at_timestamp` 作为 P1 后续实现。

## API 设计

### 版本 API（新增）

```
POST   /api/v1/databases/{dbId}/branches/{branchId}/versions
       Body: { name, description, at: "current" | at_timestamp | at_lsn }
       → 创建版本（支持当前时刻/按时间/按 LSN）

GET    /api/v1/databases/{dbId}/branches/{branchId}/versions
       → 列出分支的版本历史

GET    /api/v1/databases/{dbId}/branches/{branchId}/versions/{versionId}
       → 获取版本详情

DELETE /api/v1/databases/{dbId}/branches/{branchId}/versions/{versionId}
       → 删除版本（删书签 + 删快照 timeline）

POST   /api/v1/databases/{dbId}/branches/{branchId}/versions/squash
       Body: { from_version_id, to_version_id }
       → 合并版本范围，删除中间版本的书签和快照 timeline
```

### Diff API（新增）

```
GET    /api/v1/databases/{dbId}/diff/schema
       Query: source_type, source_id, target_type, target_id
       → Schema diff（source/target 可以是 branch 或 version）

GET    /api/v1/databases/{dbId}/diff/data
       Query: source_type, source_id, target_type, target_id, table_name, limit
       → Data diff（指定表，限制行数）

POST   /api/v1/databases/{dbId}/diff/summarize
       Body: { schema_diff, data_diff }
       → AI 生成自然语言变更摘要
```

### 分支 API（扩展已有）

```
POST   /api/v1/databases/{dbId}/branches/{branchId}/promote
       → 提升为主干（原主干降为普通分支）

POST   /api/v1/databases/{dbId}/branches/{branchId}/restore
       Body: { target_version_id } 或 { target_lsn }
       → 硬回滚（自动创建备份分支保护回滚前状态）
```

## Diff 实现

### Schema Diff

连接两个 compute（或临时启动只读 compute），查询 `information_schema` 和 `pg_catalog` 对比：
- 表级别：新增/删除的表
- 列级别：新增/删除/修改的列（类型、默认值、NOT NULL）
- 索引：新增/删除/修改
- 约束：主键、外键、唯一约束、CHECK
- 函数/触发器

API 返回结构化 JSON（tables.added/removed/modified，嵌套 columns/indexes）。

### Data Diff

用户指定表名 + 限制行数：
1. 通过主键做 JOIN 或 EXCEPT 查询
2. 分类为 inserted / deleted / modified
3. Modified 行标出变更的列和新旧值
4. 无主键的表用全列对比，降级提示
5. 默认 limit=100，防止大表拖垮 compute

### AI 增强摘要

调用 AI 大模型对 schema diff 和 data diff 生成自然语言摘要：
- Schema: "新增了 orders 订单表，包含 5 个字段...为 user_id 创建了查询索引"
- Data: "users 表新增 12 行，修改 3 行（email 字段更新），删除 1 行"

UI 展示层次：
1. 顶部：AI 自然语言摘要卡片（一眼看懂）
2. 中间：变更统计标签（+N 新增、N 修改、-N 删除）
3. 底部：可折叠的原始 diff 详情（默认收起）

### 临时 Compute 生命周期

Diff 和版本数据查看需要临时启动只读 compute 连接快照 timeline：
- **启动**：首次 diff/查看请求时按需创建，suspend_timeout 设为 60s
- **共享**：同一 snapshot timeline 的并发请求共享同一 compute，不重复创建
- **超时回收**：60s 无活动后自动 suspend，后续再次请求时重新启动
- **UX**：前端显示"正在准备数据环境..."loading 状态（预计 8-17s）

### Data Diff 局限性

- 无主键的表使用全列 EXCEPT 对比，可能因重复行产生误报（显示为"删除+插入"）。UI 展示提示："该表无主键，对比结果可能不完全精确"
- 大表 diff 强制 limit（默认 100，最大 1000），超出部分仅显示统计计数

### Diff API 参数规范

`source_type` / `target_type` 允许值：
- `branch` — source_id/target_id 为 branch ID（br_xxx），对比分支的当前状态
- `version` — source_id/target_id 为 version ID（ver_xxx），对比版本快照

## Promote 流程

```
用户点击分支的 "Promote"
  → 确认对话框
  → 获取数据库级锁（防止并发 Promote/Restore）
  → API 执行：
      1. 当前主干 is_default = false，rename 为 "main-before-promote-{timestamp}"
         branch_type 设为 BACKUP
      2. 目标分支 is_default = true
      3. 切换数据库的 active timeline（DatabaseEntity.neonTimelineId）
      4. 重建 compute pod 连接新 timeline
  → 释放锁
  → 连接串不变（proxy 路由依据 DatabaseEntity 的 active timeline，非分支名）
```

**注意**：Promote 是纯元数据操作 + compute 重建。不修改分支树的 parent-child 关系，不移动版本。旧主干的版本历史保持不变，归属于 renamed 的备份分支。

## Restore 流程

```
用户点击版本的 "回滚到此版本"
  → 确认对话框
  → 获取数据库级锁（防止并发 Promote/Restore）
  → API 执行：
      1. 自动创建备份分支 "main-backup-{timestamp}"（branch_type=BACKUP）
         - 备份分支的 neon_timeline_id 指向当前旧 timeline（不创建新 timeline）
         - 旧 timeline 保留，因为它是版本快照 timeline 的 ancestor，不可删除
      2. 在 pageserver 创建新 timeline，从目标版本的 snapshot_timeline_id 的 LSN 分支
      3. 更新 BranchEntity.neonTimelineId 为新 timeline
      4. 重建 compute pod 连接新 timeline
      5. 目标版本之后的版本书签 branch_id 改为备份分支的 id
  → 释放锁
  → main 分支回到目标版本状态，后续版本随备份分支保留
```

**关键**：旧 timeline 不删除——它成为备份分支的 timeline，且是历史版本快照 timeline 的 ancestor。Neon pageserver 不允许删除有 children 的 timeline。

## Squash 流程

```
用户选择版本范围 (from_version, to_version)
  → 确认对话框：显示将被合并的中间版本列表
  → API 执行：
      1. 前置检查：验证中间版本的 snapshot timeline 没有 children
         （如果某个版本被用作分支的 parent，拒绝 squash 并提示用户先删除该分支）
      2. 保留 from_version 和 to_version
      3. 删除中间版本的快照 timeline（pageserver DELETE timeline API）
      4. 删除中间版本的 VersionEntity 记录
      5. Pageserver GC 自动回收不再被引用的旧 page 版本
  → 版本历史简化，存储空间随 GC 回收
```

## UI 设计

### 布局：Tab 切换

数据库详情页顶部 Tab：**概览 | 分支 | SQL 编辑器 | 设置**

「分支」Tab 内：
- 左侧：分支树面板（列出所有分支，缩进显示父子关系）
- 右侧：选中分支的版本时间线（竖向时间轴）

### 版本时间线

- 每个版本节点显示：名称、时间、LSN、创建者
- 最新版本高亮（蓝色圆点），历史版本灰色
- 点击版本展开操作按钮：查看数据、Diff 对比、从此创建分支、Promote、回滚、删除

### Squash 交互

- 用户选择版本范围
- 中间版本显示为删除线样式
- 底部确认栏显示合并后的版本名称和影响说明
- 确认后执行

### Diff 视图

- 顶部：左右下拉框选择对比源和目标（可跨分支/版本）
- Schema/Data 切换按钮
- AI 摘要卡片 + 变更统计 + 可折叠原始 diff

## 优先级

| 功能 | 优先级 |
|------|--------|
| 版本 CRUD（创建/列表/详情/删除） | P0 |
| 分支 Promote | P0 |
| 分支 Restore（硬回滚 + 自动备份） | P0 |
| Schema Diff | P0 |
| Data Diff | P1 |
| AI Diff 摘要 | P1 |
| Squash | P1 |
| Console UI（分支 Tab + 版本时间线） | P0 |
| Diff 视图 UI | P1 |
| 按时间/LSN 创建版本 | P1 |

## 并发控制

Promote 和 Restore 都涉及关键状态变更（active timeline 切换、compute 重建）。需要数据库级互斥锁：
- 使用 `DatabaseEntity` 上的乐观锁（`@Version`）或悲观锁（`SELECT FOR UPDATE`）
- 同一数据库的 Promote/Restore 操作串行执行
- 版本 CRUD 和 Diff 操作不需要锁（只读或仅操作 VersionEntity）

## 设计决策记录

1. **架构选择**：分支优先（Branch-centric），每个分支独立版本历史
2. **版本实现**：LSN 书签 + 物化快照 timeline（pageserver branch）
3. **合并方式**：Promote（分支提升为主干），不做数据级 diff merge
4. **回滚方式**：支持 Restore（硬回滚）和 Promote 两种，用户自选
5. **Squash**：删除中间版本书签 + 快照 timeline，依赖 pageserver GC 回收存储
6. **草稿模式**：不做。分支已覆盖草稿场景（创建临时分支 → 操作 → promote 或删除）
7. **自动 DDL 追踪**：不做。版本全部手动创建，避免性能风险
8. **Diff**：schema diff + data diff + AI 自然语言摘要，三层展示
9. **数据级 merge**：P2，暂不实现
