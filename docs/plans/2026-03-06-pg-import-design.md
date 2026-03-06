# PG 数据导入功能设计

## 概述

支持用户从外部 PostgreSQL 数据库导入数据到 Lakeon 数据库，以任务方式管理，支持整库/按表导入、暂停/恢复/重试/取消。

## 用户流程

1. 在 Console 数据库详情页点击「导入数据」
2. 填写源库连接信息（host、port、dbname、user、password）
3. 点击「测试连接」验证连通性，通过后继续
4. API 拉取源库表列表，用户选择「整库」或勾选具体表
5. 选择冲突策略（追加/覆盖），确认发起导入
6. 跳转任务详情页，实时查看整体进度和每张表状态
7. 支持暂停/恢复/取消/重试操作

## 架构

```
Console UI -> lakeon-api -> 创建 Import Job Pod -> pg_dump/pg_restore -> 目标 compute pod
                 ^                    |
                 +--- 进度轮询 <-- curl 回调更新 RDS (import_tasks / import_table_tasks)
```

- Job Pod 运行在 lakeon-compute namespace，复用现有 RBAC
- 基于 compute-node-v17 镜像（自带 pg_dump/pg_restore）
- 密码通过 K8s Secret 注入，不写 ConfigMap

## 数据模型

### import_tasks（任务主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | VARCHAR(64) | `imp_xxxxxxxx` |
| tenant_id | VARCHAR(64) | 所属租户 |
| database_id | VARCHAR(64) | 目标 Lakeon 数据库 |
| source_host | VARCHAR(256) | 源库地址 |
| source_port | INTEGER | 源库端口 |
| source_dbname | VARCHAR(128) | 源库名 |
| source_user | VARCHAR(128) | 源库用户 |
| source_password | VARCHAR(256) | 加密存储（AES） |
| mode | VARCHAR(16) | `full` / `selective` |
| conflict_strategy | VARCHAR(16) | `append` / `replace` |
| status | VARCHAR(16) | 见状态枚举 |
| total_tables | INTEGER | 表总数 |
| completed_tables | INTEGER | 已完成数 |
| job_pod_name | VARCHAR(128) | K8s Job Pod 名 |
| error_message | TEXT | 错误信息 |
| created_at | TIMESTAMP | |
| started_at | TIMESTAMP | |
| finished_at | TIMESTAMP | |

### import_table_tasks（表级子任务）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | VARCHAR(64) | |
| import_task_id | VARCHAR(64) | 关联主任务 |
| schema_name | VARCHAR(128) | |
| table_name | VARCHAR(128) | |
| status | VARCHAR(16) | `pending` / `running` / `completed` / `failed` |
| row_count | BIGINT | 导入行数 |
| error_message | TEXT | |
| started_at | TIMESTAMP | |
| finished_at | TIMESTAMP | |

### 状态枚举

`pending` / `running` / `completed` / `failed` / `partial` / `cancelled` / `paused`

- `partial`: 部分表成功、部分失败
- `paused`: 用户手动暂停

## API 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/import/test-connection` | 测试源库连通性 |
| POST | `/api/v1/import/source-tables` | 连接源库，返回表列表 |
| POST | `/api/v1/databases/{dbId}/import` | 创建导入任务 |
| GET | `/api/v1/databases/{dbId}/import` | 列出该库的导入任务 |
| GET | `/api/v1/databases/{dbId}/import/{taskId}` | 查询任务详情（含表级状态） |
| POST | `/api/v1/databases/{dbId}/import/{taskId}/pause` | 暂停（删 Pod） |
| POST | `/api/v1/databases/{dbId}/import/{taskId}/resume` | 恢复（建新 Pod，跳过已完成表） |
| POST | `/api/v1/databases/{dbId}/import/{taskId}/cancel` | 取消任务 |
| POST | `/api/v1/databases/{dbId}/import/{taskId}/retry` | 重试失败的表 |
| PUT | `/api/v1/import/callback/{taskId}` | Job Pod 回调更新进度（内部接口） |

## Job Pod 设计

- 镜像: compute-node-v17（自带 pg_dump/pg_restore）
- 运行在 lakeon-compute namespace
- 配置通过 ConfigMap 注入（表列表、策略），密码通过 Secret 注入
- 执行逻辑（shell 脚本）:
  1. 读取配置，获取待导入表列表（跳过 status=completed 的表）
  2. 逐表执行:
     - 回调 API 标记当前表 running
     - 覆盖模式: `pg_dump --clean --if-exists -t <table>` | `pg_restore`
     - 追加模式: `pg_dump --data-only -t <table>` | `pg_restore`
     - 成功: 回调 API 标记 completed + row_count
     - 失败: 回调 API 标记 failed + error_message，继续下一张表
  3. 全部完成后回调 API 标记主任务终态
- Pod 完成后自动销毁（restartPolicy: Never）

## 暂停/恢复机制

- **暂停**: 删除 Job Pod，主任务标记 `paused`，已完成的表状态保留
- **恢复**: 创建新 Job Pod，配置中只包含 status != completed 的表
- **取消**: 删除 Job Pod，主任务标记 `cancelled`
- **重试**: 将 `failed` 表重置为 `pending`，创建新 Job Pod

## Console UI

### 入口
- 数据库详情页新增「导入数据」按钮

### 导入向导（分步）
1. 填写源库连接信息 + 测试连接
2. 选择导入模式（整库/按表勾选）
3. 选择冲突策略（追加/覆盖）+ 确认

### 任务列表
- 在数据库详情页展示导入历史
- 显示状态、进度、创建时间

### 任务详情
- 整体进度条（completed_tables / total_tables）
- 表级状态列表（状态标签、行数、耗时、错误信息）
- 操作按钮: running 显示「暂停」，paused 显示「恢复」，failed/partial 显示「重试」

## 安全

- 源库密码 AES 加密存储，任务完成后可选清除
- Job Pod 密码通过 K8s Secret 注入
- 回调接口仅集群内可访问（ClusterIP），带任务 token 验证

## 目标表处理

- 目标表不存在: pg_dump 带 schema，自动建表
- 目标表已存在 + 覆盖模式: `--clean --if-exists` 先删后建
- 目标表已存在 + 追加模式: `--data-only` 只导数据
