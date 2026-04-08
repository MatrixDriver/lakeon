# OBS 存储管理 — 设计文档

## 概述

在 SRE 控制台基础设施页面新增「存储」Tab，展示各租户的 OBS 存储占用情况（数据库、知识库、记忆库），并支持检测和清理孤儿对象。

## 架构

### 前端

- **位置**：`InfraMonitor.vue` 新增第 5 个 Tab `storage`
- **组件**：独立的 `StoragePanel.vue`，通过 InfraMonitor 懒加载
- **数据流**：StoragePanel 内部调用 admin API，不影响其他 Tab 的数据加载

### 后端

- **API 端点**：`AdminController` 新增存储相关端点
- **服务层**：`AdminService` 新增存储统计方法，调用 Neon pageserver API + 数据库查询 + OBS S3 SDK

## API 设计

### GET /api/v1/admin/storage/summary

返回存储概览和租户明细。数据来源：pageserver logical_size + DocumentEntity.obsSize 汇总。

```json
{
  "totalObsBytes": 13312000000,
  "totalDbBytes": 8800000000,
  "totalKbDocBytes": 4080000000,
  "orphanBytes": 440000000,
  "tenants": [
    {
      "tenantId": "demo",
      "tenantName": "demo",
      "status": "ACTIVE",
      "dbBytes": 4400000000,
      "kbDocBytes": 2460000000,
      "memoryBytes": 190000000,
      "totalBytes": 7050000000,
      "items": [
        {
          "type": "database",
          "id": "db_xxx",
          "name": "mydb",
          "status": "ACTIVE",
          "dbBytes": 3400000000,
          "kbDocBytes": 0,
          "memoryBytes": 0
        },
        {
          "type": "knowledge_base",
          "id": "kb_xxx",
          "name": "产品文档",
          "status": "READY",
          "dbBytes": 660000000,
          "kbDocBytes": 1900000000,
          "memoryBytes": 0
        },
        {
          "type": "memory_base",
          "id": "mb_xxx",
          "name": "工作助手",
          "status": "ACTIVE",
          "dbBytes": 300000000,
          "kbDocBytes": 0,
          "memoryBytes": 190000000
        }
      ]
    }
  ],
  "orphans": [
    {
      "tenantId": "tn_deleted_abc",
      "bytes": 440000000,
      "prefixes": ["datasets/tn_deleted_abc/", "knowledge/tn_deleted_abc/"]
    }
  ],
  "lastScanTime": null
}
```

**数据来源逻辑：**
1. 遍历所有租户
2. 每个租户查其所有 DatabaseEntity → 调用 pageserver timeline API 获取 `current_logical_size`
3. 每个租户查其所有 KnowledgeBaseEntity → 汇总关联 DocumentEntity.obsSize
4. 每个租户查其所有 MemoryBaseEntity → 通过关联的 databaseId 获取 logical_size
5. 孤儿检测：OBS `ListObjectsV2` 列出顶层前缀，与已知租户 ID 比对

### POST /api/v1/admin/storage/scan

触发 OBS 精确扫描（异步）。遍历 OBS bucket 按前缀统计实际占用。返回精确的 summary（同上格式），同时更新 `lastScanTime`。

### POST /api/v1/admin/storage/cleanup

清理孤儿对象。参数：

```json
{
  "tenantId": "tn_deleted_abc",
  "prefixes": ["datasets/tn_deleted_abc/", "knowledge/tn_deleted_abc/"],
  "dryRun": true
}
```

- `dryRun: true` 仅返回将要删除的对象数和大小，不实际删除
- `dryRun: false` 执行删除

响应：

```json
{
  "objectsDeleted": 142,
  "bytesFreed": 440000000,
  "dryRun": true
}
```

## 前端组件设计

### StoragePanel.vue

**汇总卡片区**（4 个）：
- OBS 总用量（暖色主色调）
- 数据库存储（蓝色）
- 知识库文档（绿色）
- 孤儿对象（红色，仅有孤儿时显示）

**租户表格**：
- 列：展开箭头 | 租户 | 数据库 | 知识库 | 记忆库 | 总量 | 状态
- 点击行展开子行：显示该租户下每个数据库/知识库/记忆库的明细
- 孤儿租户标红背景，状态列显示「清理」按钮

**工具栏**：
- 「刷新 OBS 扫描」按钮 → 调用 POST /storage/scan
- 「清理孤儿对象」按钮 → 先 dryRun 确认，再执行清理

**底部**：数据来源说明 + 最后扫描时间

### 交互流程

1. 切换到存储 Tab → 调用 GET /storage/summary（快速，基于数据库估算）
2. 点击「刷新 OBS 扫描」→ POST /storage/scan（较慢，显示 loading）→ 刷新数据
3. 点击孤儿行「清理」→ POST /storage/cleanup (dryRun=true) → 弹窗确认 → POST /storage/cleanup (dryRun=false)

## 数据来源映射

| 存储类型 | 快速估算 | 精确扫描 |
|---|---|---|
| 数据库（Neon） | pageserver `current_logical_size` | 同左 |
| 知识库文档 | `SUM(DocumentEntity.obsSize)` | OBS `ListObjectsV2` 扫描 `knowledge/{tenant}/{kbId}/` |
| 记忆库 | 关联数据库的 logical_size | 同左 |
| 孤儿检测 | — | OBS 前缀枚举 vs 已知租户 ID |

## 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `lakeon-admin/src/views/system/InfraMonitor.vue` | 修改 | 新增 `storage` Tab 入口 |
| `lakeon-admin/src/views/system/StoragePanel.vue` | 新建 | 存储面板组件 |
| `lakeon-admin/src/api/admin.ts` | 修改 | 新增 3 个 API 调用 |
| `lakeon-api/.../controller/AdminController.java` | 修改 | 新增 3 个端点 |
| `lakeon-api/.../service/AdminService.java` | 修改 | 新增存储统计、扫描、清理方法 |

## 不做的事

- 不做存储告警阈值设置（后续需要再加）
- 不做自动定时扫描（手动触发即可）
- 不做单个知识库文档级别的删除（用知识库管理页面操作）
- 不做存储趋势图（第一版只看当前快照）
