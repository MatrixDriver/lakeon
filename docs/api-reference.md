# DBay API Reference

> Base URL: `https://api.dbay.cloud:8443/api/v1`
> 最后更新: 2026-03-22

---

## 目录

- [认证](#认证)
- [账户与租户](#账户与租户)
- [API Key 管理](#api-key-管理)
- [数据库管理](#数据库管理)
- [分支管理](#分支管理)
- [版本管理](#版本管理)
- [Schema 与表管理](#schema-与表管理)
- [SQL 查询](#sql-查询)
- [查询历史](#查询历史)
- [AI SQL 助手](#ai-sql-助手)
- [数据库用户管理](#数据库用户管理)
- [扩展与参数管理](#扩展与参数管理)
- [IP 白名单](#ip-白名单)
- [数据导入](#数据导入)
- [备份管理](#备份管理)
- [Schema Diff](#schema-diff)
- [审计日志](#审计日志)
- [操作日志](#操作日志)
- [知识库管理](#知识库管理)
- [文档管理](#文档管理)
- [知识库搜索](#知识库搜索)
- [切片管理](#切片管理)
- [重切片操作](#重切片操作)
- [写入任务](#写入任务)
- [数据湖任务](#数据湖任务)
- [内部任务系统](#内部任务系统)
- [用量查询](#用量查询)
- [试用](#试用)
- [Admin API](#admin-api)

---

## 认证

所有 API 请求需要在 `Authorization` 头中携带 API Key:

```
Authorization: Bearer <api_key>
```

API Key 格式为 `lk_` + 64 位十六进制字符。

Admin 端点使用独立的 admin token:

```
Authorization: Bearer <admin_token>
```

无需认证的端点:
- `POST /api/v1/auth/login`
- `GET /api/v1/auth/check-username`
- `POST /api/v1/tenants`
- `POST /api/v1/trial`

认证失败返回 `401 Unauthorized`。

---

## 账户与租户

### POST /auth/login -- 登录

用户名密码登录，返回租户信息和 API Key。

**请求体:**
```json
{
  "username": "myuser",
  "password": "mypassword"
}
```

**响应 200:**
```json
{
  "id": "t_abc123",
  "username": "myuser",
  "name": "My Name",
  "api_key": "lk_...",
  "max_databases": 5,
  "max_storage_gb": 10,
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** 无需认证

---

### GET /auth/check-username -- 检查用户名可用性

**查询参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | string | 是 | 要检查的用户名 |

**响应 200:**
```json
{
  "available": true
}
```

**认证:** 无需认证

---

### POST /tenants -- 注册新租户

**请求体:**
```json
{
  "username": "myuser",
  "password": "mypassword",
  "inviteCode": "ABC123"
}
```

**响应 201:**
```json
{
  "id": "t_abc123",
  "username": "myuser",
  "api_key": "lk_...",
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** 无需认证

---

### GET /tenants/me -- 获取当前租户信息

**响应 200:**
```json
{
  "id": "t_abc123",
  "username": "myuser",
  "name": "My Name",
  "max_databases": 5,
  "max_storage_gb": 10,
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /tenants/{tenantId} -- 获取指定租户信息

**路径参数:**
| 参数 | 说明 |
|------|------|
| `tenantId` | 租户 ID |

**认证:** Bearer Token

---

### POST /tenants/{tenantId}/regenerate-key -- 重新生成 API Key

只能为自己的租户重新生成 Key。

**响应 200:** 返回更新后的租户信息（含新 API Key）。

**认证:** Bearer Token

---

### POST /account/change-password -- 修改密码

**请求体:**
```json
{
  "current_password": "old_pass",
  "new_password": "new_pass"
}
```

**响应 200:**
```json
{
  "message": "密码修改成功"
}
```

**认证:** Bearer Token

---

### PATCH /account/profile -- 更新个人信息

**请求体:**
```json
{
  "name": "新名称"
}
```

**响应 200:** 返回更新后的租户信息。

**认证:** Bearer Token

---

## API Key 管理

### GET /api-keys -- 列出所有 API Key

**响应 200:**
```json
[
  {
    "id": "key_abc123",
    "name": "my-key",
    "prefix": "lk_a1b2...",
    "created_at": "2026-01-01T00:00:00Z"
  }
]
```

**认证:** Bearer Token

---

### POST /api-keys -- 创建新 API Key

**请求体:**
```json
{
  "name": "my-new-key"
}
```

**响应 201:**
```json
{
  "id": "key_abc123",
  "name": "my-new-key",
  "api_key": "lk_...",
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### DELETE /api-keys/{keyId} -- 删除 API Key

**响应:** `204 No Content`

**认证:** Bearer Token

---

## 数据库管理

### POST /databases -- 创建数据库

**请求体:**
```json
{
  "name": "my-database",
  "compute_size": "1cu",
  "suspend_timeout": "5m",
  "storage_limit_gb": 10
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 数据库名称 |
| `compute_size` | string | 否 | 计算规格: `1cu`, `2cu`, `4cu`, `8cu` |
| `suspend_timeout` | string | 否 | 自动挂起超时，如 `5m` |
| `storage_limit_gb` | integer | 否 | 存储上限 (GB) |

**响应 202:**
```json
{
  "id": "db_abc123",
  "name": "my-database",
  "status": "CREATING",
  "connection_uri": "postgresql://user:pass@proxy.dbay.cloud/my-database",
  "password": "generated_password",
  "compute_size": "1cu",
  "suspend_timeout": "5m",
  "storage_limit_gb": 10,
  "branches": [...],
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /databases -- 列出数据库

**响应 200:**
```json
[
  {
    "id": "db_abc123",
    "name": "my-database",
    "status": "RUNNING",
    "connection_uri": "postgresql://...",
    "compute_size": "1cu",
    "storage_used_gb": 0.05,
    "branches": [...],
    "created_at": "2026-01-01T00:00:00Z"
  }
]
```

**认证:** Bearer Token

---

### GET /databases/{dbId} -- 获取数据库详情

**响应 200:** 同上，返回单个数据库对象。

**认证:** Bearer Token

---

### PATCH /databases/{dbId} -- 更新数据库配置

**请求体:**
```json
{
  "compute_size": "2cu",
  "suspend_timeout": "10m",
  "storage_limit_gb": 20
}
```

所有字段均为可选。

**响应 200:** 返回更新后的数据库对象。

**认证:** Bearer Token

---

### DELETE /databases/{dbId} -- 删除数据库

**响应:** `204 No Content`

**认证:** Bearer Token

---

### POST /databases/{dbId}/suspend -- 挂起数据库

手动挂起数据库计算节点以节省资源。

**响应:** `200 OK`

**认证:** Bearer Token

---

### POST /databases/{dbId}/resume -- 恢复数据库

从挂起状态恢复数据库。

**响应:** `200 OK`

**认证:** Bearer Token

---

### POST /databases/{dbId}/reset-password -- 重置数据库密码

**响应 200:**
```json
{
  "password": "new_generated_password"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/metrics -- 获取数据库指标

**响应 200:**
```json
{
  "active_connections": 3,
  "storage_used_gb": 0.12,
  "compute_status": "RUNNING"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/logs -- 获取数据库日志

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `tail` | int | 200 | 返回的日志行数 |

**响应 200:**
```json
[
  { "timestamp": "...", "message": "..." }
]
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/connections -- 获取连接信息

返回数据库的连接详情。

**响应 200:**
```json
{
  "host": "proxy.dbay.cloud",
  "port": 5432,
  "database": "my-database",
  "user": "cloud_admin",
  "connection_uri": "postgresql://..."
}
```

**认证:** Bearer Token

---

## 分支管理

### POST /databases/{dbId}/branches -- 创建分支

从父分支的某个 LSN 点创建新分支。

**请求体:**
```json
{
  "name": "dev-branch",
  "parent_branch_id": "br_parent",
  "ancestor_lsn": "0/1234567",
  "start_compute": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 分支名称 |
| `parent_branch_id` | string | 否 | 父分支 ID，默认为默认分支 |
| `ancestor_lsn` | string | 否 | 分支起点 LSN |
| `start_compute` | boolean | 否 | 是否立即启动计算节点 |

**响应 201:**
```json
{
  "id": "br_abc123",
  "name": "dev-branch",
  "database_id": "db_xxx",
  "parent_branch_id": "br_parent",
  "is_default": false,
  "status": "ACTIVE",
  "compute_status": "STOPPED",
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/branches -- 列出分支

**响应 200:** 返回分支数组。

**认证:** Bearer Token

---

### GET /databases/{dbId}/branches/{branchId} -- 获取分支详情

**认证:** Bearer Token

---

### GET /databases/{dbId}/branches/tree -- 获取分支树

以树形结构返回所有分支的层级关系。

**响应 200:**
```json
{
  "root": {
    "id": "br_main",
    "name": "main",
    "children": [...]
  }
}
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/branches/{branchId}/promote -- 提升分支为默认分支

将指定分支提升为数据库的默认分支。

**响应 200:** 返回提升后的分支对象。

**认证:** Bearer Token

---

### POST /databases/{dbId}/branches/{branchId}/restore -- 恢复分支到指定版本

**请求体:**
```json
{
  "target_version_id": "ver_xxx",
  "target_lsn": "0/1234567"
}
```

二选一：`target_version_id` 或 `target_lsn`。

**响应 200:** 返回恢复后的分支对象。

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/branches/{branchId} -- 删除分支

不能删除默认分支。

**响应:** `204 No Content`

**认证:** Bearer Token

---

## 版本管理

基础路径: `/databases/{dbId}/branches/{branchId}/versions`

### POST /databases/{dbId}/branches/{branchId}/versions -- 创建版本快照

**请求体:**
```json
{
  "name": "v1.0",
  "description": "初始版本",
  "at": "2026-01-01T00:00:00Z",
  "at_lsn": "0/1234567"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 版本名称 |
| `description` | string | 否 | 版本描述 |
| `at` | string | 否 | 快照时间点 (ISO 8601) |
| `at_lsn` | string | 否 | 快照 LSN |

**响应 201:**
```json
{
  "id": "ver_abc123",
  "name": "v1.0",
  "description": "初始版本",
  "branch_id": "br_xxx",
  "lsn": "0/1234567",
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/branches/{branchId}/versions -- 列出版本

**响应 200:** 返回版本数组。

**认证:** Bearer Token

---

### GET /databases/{dbId}/branches/{branchId}/versions/{versionId} -- 获取版本详情

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/branches/{branchId}/versions/{versionId} -- 删除版本

**响应:** `204 No Content`

**认证:** Bearer Token

---

### POST /databases/{dbId}/branches/{branchId}/versions/squash -- 压缩版本

将多个连续版本压缩为一个。

**请求体:**
```json
{
  "from_version_id": "ver_001",
  "to_version_id": "ver_005"
}
```

**响应 200:** 返回压缩后的版本数组。

**认证:** Bearer Token

---

## Schema 与表管理

### GET /databases/{dbId}/schemas -- 列出 Schema

**响应 200:**
```json
[
  { "name": "public", "owner": "cloud_admin" }
]
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables -- 列出表

**响应 200:**
```json
[
  { "name": "users", "row_count": 1000 }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/schemas/{schema}/tables -- 创建表

**请求体:**
```json
{
  "name": "users",
  "columns": [
    { "name": "id", "type": "serial", "nullable": false },
    { "name": "name", "type": "text", "nullable": false },
    { "name": "email", "type": "text", "nullable": true, "default_value": null }
  ],
  "primary_key": ["id"]
}
```

**响应:** `201 Created`

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/schemas/{schema}/tables/{table} -- 删除表

**响应:** `204 No Content`

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables/{table}/columns -- 列出列

**响应 200:**
```json
[
  { "name": "id", "data_type": "integer", "nullable": false, "default_value": "nextval(...)" }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/schemas/{schema}/tables/{table}/columns -- 添加列

**请求体:**
```json
{
  "name": "age",
  "type": "integer",
  "nullable": true,
  "default_value": "0"
}
```

**响应:** `201 Created`

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/schemas/{schema}/tables/{table}/columns/{column} -- 删除列

**响应:** `204 No Content`

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables/{table}/indexes -- 列出索引

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables/{table}/constraints -- 列出约束

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables/{table}/stats -- 获取表统计信息

**认证:** Bearer Token

---

### GET /databases/{dbId}/schemas/{schema}/tables/{table}/data -- 查询表数据

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 50 | 每页行数 |
| `sort` | string | - | 排序字段 |
| `dir` | string | asc | 排序方向: `asc` / `desc` |

**响应 200:**
```json
{
  "columns": ["id", "name", "email"],
  "rows": [
    [1, "Alice", "alice@example.com"]
  ],
  "total": 100,
  "page": 0,
  "size": 50
}
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/schema-cache/refresh -- 刷新 Schema 缓存

**认证:** Bearer Token

---

### GET /databases/{dbId}/schema-cache/status -- 获取 Schema 缓存状态

**认证:** Bearer Token

---

## SQL 查询

### POST /databases/{dbId}/query -- 执行 SQL 查询

**请求体:**
```json
{
  "sql": "SELECT * FROM users LIMIT 10"
}
```

**响应 200:**
```json
{
  "columns": ["id", "name"],
  "rows": [[1, "Alice"], [2, "Bob"]],
  "row_count": 2,
  "duration_ms": 15
}
```

**认证:** Bearer Token

---

## 查询历史

### GET /databases/{dbId}/query-history -- 获取数据库查询历史

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 50 | 每页数量 (最大 200) |
| `q` | string | - | 搜索关键词 |

**响应 200:**
```json
{
  "items": [
    {
      "id": "qh_xxx",
      "sql": "SELECT ...",
      "success": true,
      "row_count": 10,
      "duration_ms": 15,
      "created_at": "2026-01-01T00:00:00Z"
    }
  ],
  "total": 100,
  "page": 0,
  "pages": 2
}
```

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/query-history -- 清空数据库查询历史

**响应:** `204 No Content`

**认证:** Bearer Token

---

### GET /query-history -- 列出租户所有查询历史 (跨数据库)

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 50 | 每页数量 (最大 200) |
| `q` | string | - | 搜索关键词 |

**响应 200:** 同上格式，额外包含 `database_id` 和 `database_name`。

**认证:** Bearer Token

---

### DELETE /query-history -- 清空租户所有查询历史

**响应:** `204 No Content`

**认证:** Bearer Token

---

## AI SQL 助手

### GET /databases/{dbId}/ai-sql/models -- 列出可用 AI 模型

**响应 200:**
```json
[
  { "id": "claude-3-haiku", "name": "Claude 3 Haiku", "provider": "anthropic" }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/ai-sql/generate -- AI 生成 SQL

根据自然语言描述和数据库 schema 自动生成 SQL。

**请求体:**
```json
{
  "prompt": "查找最近7天注册的用户",
  "model": "claude-3-haiku"
}
```

**响应 200:**
```json
{
  "sql": "SELECT * FROM users WHERE created_at > NOW() - INTERVAL '7 days'",
  "explanation": "..."
}
```

**认证:** Bearer Token

---

## 数据库用户管理

### POST /databases/{dbId}/users -- 创建数据库用户

**请求体:**
```json
{
  "username": "readonly_user",
  "role": "READER",
  "password": "optional_password"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | string | 是 | 用户名 |
| `role` | string | 是 | 角色: `OWNER`, `EDITOR`, `READER` |
| `password` | string | 否 | 密码，不指定则自动生成 |

**响应 201:**
```json
{
  "username": "readonly_user",
  "role": "READER",
  "password": "generated_or_provided_password"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/users -- 列出数据库用户

**认证:** Bearer Token

---

### PUT /databases/{dbId}/users/{userId}/role -- 更新用户角色

**请求体:**
```json
{
  "role": "EDITOR"
}
```

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/users/{userId} -- 删除数据库用户

**响应:** `204 No Content`

**认证:** Bearer Token

---

### POST /databases/{dbId}/users/{userId}/reset-password -- 重置用户密码

**响应 200:**
```json
{
  "password": "new_generated_password"
}
```

**认证:** Bearer Token

---

## 扩展与参数管理

### GET /databases/{dbId}/extensions -- 列出 PostgreSQL 扩展

**响应 200:**
```json
[
  { "name": "pgvector", "version": "0.7.0", "enabled": true, "description": "..." }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/extensions/{name}/enable -- 启用扩展

**响应 200:**
```json
{
  "status": "enabled",
  "extension": "pgvector"
}
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/extensions/{name}/disable -- 禁用扩展

**响应 200:**
```json
{
  "status": "disabled",
  "extension": "pgvector"
}
```

**认证:** Bearer Token

---

### GET /databases/{dbId}/parameters -- 列出数据库参数

**认证:** Bearer Token

---

### PUT /databases/{dbId}/parameters/{name} -- 更新数据库参数

**请求体:**
```json
{
  "value": "100"
}
```

**响应 200:**
```json
{
  "status": "updated",
  "parameter": "work_mem",
  "value": "100"
}
```

**认证:** Bearer Token

---

## IP 白名单

### GET /databases/{dbId}/allowed-ips -- 获取 IP 白名单

**响应 200:**
```json
{
  "ips": ["192.168.1.0/24", "10.0.0.1"],
  "mode": "allowlist"
}
```

**认证:** Bearer Token

---

### PUT /databases/{dbId}/allowed-ips -- 设置 IP 白名单

**请求体:**
```json
{
  "ips": ["192.168.1.0/24", "10.0.0.1"]
}
```

**响应 200:** 返回更新后的白名单。

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/allowed-ips -- 清空 IP 白名单 (允许所有 IP)

**响应:** `204 No Content`

**认证:** Bearer Token

---

## 数据导入

### POST /import/test-connection -- 测试源数据库连接

**请求体:**
```json
{
  "host": "source-db.example.com",
  "port": 5432,
  "dbname": "source_db",
  "user": "postgres",
  "password": "password"
}
```

**响应 200:**
```json
{
  "success": true,
  "version": "PostgreSQL 15.2",
  "message": "连接成功"
}
```

**认证:** Bearer Token

---

### POST /import/source-tables -- 列出源数据库的表

**请求体:** 同 `test-connection`。

**响应 200:**
```json
[
  { "schema": "public", "name": "users", "row_count": 10000 }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/import -- 创建导入任务

**请求体:**
```json
{
  "source_host": "source-db.example.com",
  "source_port": 5432,
  "source_dbname": "source_db",
  "source_user": "postgres",
  "source_password": "password",
  "mode": "FULL",
  "conflict_strategy": "REPLACE",
  "tables": ["public.users", "public.orders"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `mode` | string | `FULL` (全量) 或 `SYNC` (持续同步) |
| `conflict_strategy` | string | `REPLACE` / `SKIP` / `ERROR` |
| `tables` | string[] | 要导入的表列表，空则导入全部 |

**响应 201:** 返回导入任务对象。

**认证:** Bearer Token

---

### GET /databases/{dbId}/import -- 列出导入任务

**认证:** Bearer Token

---

### GET /databases/{dbId}/import/{taskId} -- 获取导入任务详情

**认证:** Bearer Token

---

### POST /databases/{dbId}/import/{taskId}/pause -- 暂停导入任务

**认证:** Bearer Token

---

### POST /databases/{dbId}/import/{taskId}/resume -- 恢复导入任务

**认证:** Bearer Token

---

### POST /databases/{dbId}/import/{taskId}/cancel -- 取消导入任务

**认证:** Bearer Token

---

### POST /databases/{dbId}/import/{taskId}/retry -- 重试导入任务

**认证:** Bearer Token

---

### GET /databases/{dbId}/import/{taskId}/sync-status -- 获取同步状态

获取持续同步模式下的实时同步状态。

**认证:** Bearer Token

---

### POST /databases/{dbId}/import/{taskId}/stop -- 停止同步

**请求体:**
```json
{
  "cleanup": false
}
```

| 字段 | 说明 |
|------|------|
| `cleanup` | 是否清理已导入的数据 |

**认证:** Bearer Token

---

## 备份管理

### GET /backups -- 列出所有备份 (跨数据库)

**响应 200:**
```json
[
  {
    "id": "bak_abc123",
    "database_id": "db_xxx",
    "name": "daily-backup",
    "status": "COMPLETED",
    "created_at": "2026-01-01T00:00:00Z"
  }
]
```

**认证:** Bearer Token

---

### POST /databases/{dbId}/backups -- 创建备份

**请求体:**
```json
{
  "name": "my-backup"
}
```

**响应 201:** 返回备份对象。

**认证:** Bearer Token

---

### GET /databases/{dbId}/backups -- 列出数据库的备份

**认证:** Bearer Token

---

### GET /databases/{dbId}/backups/{backupId} -- 获取备份详情

**认证:** Bearer Token

---

### POST /databases/{dbId}/backups/{backupId}/restore -- 从备份恢复

恢复到一个新数据库。

**请求体:**
```json
{
  "name": "restored-db"
}
```

**响应 201:**
```json
{
  "id": "db_new",
  "name": "restored-db",
  "status": "CREATING"
}
```

**认证:** Bearer Token

---

### DELETE /databases/{dbId}/backups/{backupId} -- 删除备份

**响应:** `204 No Content`

**认证:** Bearer Token

---

## Schema Diff

### GET /databases/{dbId}/diff/schema -- 比较 Schema 差异

比较两个分支或版本之间的 schema 差异。

**查询参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `source_type` | string | 是 | `branch` 或 `version` |
| `source_id` | string | 是 | 源分支/版本 ID |
| `target_type` | string | 是 | `branch` 或 `version` |
| `target_id` | string | 是 | 目标分支/版本 ID |

**响应 200:**
```json
{
  "source": { "type": "branch", "id": "br_xxx", "name": "main" },
  "target": { "type": "branch", "id": "br_yyy", "name": "dev" },
  "changes": [
    { "type": "ADD_TABLE", "schema": "public", "table": "new_table", "sql": "CREATE TABLE ..." }
  ]
}
```

**认证:** Bearer Token

---

## 审计日志

### GET /databases/{dbId}/audit/config -- 获取审计配置

**认证:** Bearer Token

---

### PUT /databases/{dbId}/audit/config -- 更新审计配置

**认证:** Bearer Token

---

### GET /databases/{dbId}/audit/logs -- 获取审计日志

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `type` | string | - | 事件类型过滤 |
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |

**认证:** Bearer Token

---

## 操作日志

### GET /databases/{dbId}/operations -- 获取数据库操作日志

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `type` | string | - | 操作类型过滤 |
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |

**认证:** Bearer Token

---

### GET /operations/recent -- 获取最近操作

返回当前租户最近的操作记录。

**认证:** Bearer Token

---

## 知识库管理

### POST /knowledge/bases -- 创建知识库

**请求体 (文档型):**
```json
{
  "name": "my-kb",
  "description": "项目文档库",
  "type": "DOCUMENT",
  "embedding_model": "bge-m3"
}
```

**请求体 (表型):**
```json
{
  "name": "sales-kb",
  "type": "TABLE",
  "source_database_id": "db_xxx",
  "table_names": ["public.orders", "public.customers"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 知识库名称 |
| `description` | string | 否 | 描述 |
| `type` | string | 否 | `DOCUMENT`(默认) 或 `TABLE` |
| `embedding_model` | string | 否 | 向量模型 |
| `source_database_id` | string | TABLE 时必填 | 源数据库 ID |
| `table_names` | string[] | TABLE 时必填 | 表名列表 |

**响应 201:**
```json
{
  "id": "kb_abc123",
  "tenant_id": "t_xxx",
  "name": "my-kb",
  "description": "项目文档库",
  "type": "DOCUMENT",
  "database_id": "db_internal",
  "status": "READY",
  "embedding_model": "bge-m3",
  "document_count": 0,
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /knowledge/bases -- 列出知识库

**响应 200:** 返回知识库数组。

**认证:** Bearer Token

---

### GET /knowledge/bases/{id} -- 获取知识库详情

**认证:** Bearer Token

---

### DELETE /knowledge/bases/{id} -- 删除知识库

**响应 200:** 返回被删除的知识库对象。

**认证:** Bearer Token

---

### GET /knowledge/bases/{id}/tables -- 获取表型知识库的表结构

仅适用于 TABLE 类型的知识库。

**响应 200:**
```json
[
  {
    "table_name": "orders",
    "columns": [
      { "name": "id", "type": "integer" },
      { "name": "amount", "type": "numeric" }
    ]
  }
]
```

**认证:** Bearer Token

---

## 文档管理

### GET /knowledge/upload-url -- 获取文档上传 URL

获取预签名上传 URL，客户端直接上传文件到 OBS。

**查询参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filename` | string | 是 | 文件名 |
| `kb_id` | string | 是 | 知识库 ID |
| `tags` | string[] | 否 | 标签列表 |

**响应 200:**
```json
{
  "upload_url": "https://obs.../presigned-put-url",
  "document_id": "doc_abc123",
  "obs_key": "tenants/t_xxx/kb_xxx/doc_xxx/file.pdf"
}
```

**认证:** Bearer Token

---

### POST /knowledge/documents/{id}/process -- 触发文档处理

上传文件后调用此接口启动解析和向量化。

**响应 200:**
```json
{
  "id": "doc_abc123",
  "status": "PROCESSING",
  "filename": "report.pdf"
}
```

**认证:** Bearer Token

---

### GET /knowledge/documents -- 列出文档

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `kb_id` | string | 按知识库过滤 |
| `database_id` | string | 按数据库过滤 |

**响应 200:**
```json
[
  {
    "id": "doc_abc123",
    "kb_id": "kb_xxx",
    "filename": "report.pdf",
    "format": "pdf",
    "status": "READY",
    "size_bytes": 102400,
    "chunks_count": 25,
    "tags": ["finance", "2026"],
    "created_at": "2026-01-01T00:00:00Z"
  }
]
```

**认证:** Bearer Token

---

### GET /knowledge/documents/{id} -- 获取文档详情

**认证:** Bearer Token

---

### DELETE /knowledge/documents/{id} -- 删除文档

**响应 200:** 返回被删除的文档对象。

**认证:** Bearer Token

---

### PUT /knowledge/documents/{id}/tags -- 设置文档标签

**请求体:**
```json
{
  "tags": ["finance", "quarterly"]
}
```

最多 20 个标签，每个标签最多 50 字符。

**响应 200:**
```json
{
  "tags": ["finance", "quarterly"]
}
```

**认证:** Bearer Token

---

## 知识库搜索

### POST /knowledge/search -- 搜索知识库

**请求体 (文档型 KB):**
```json
{
  "kb_id": "kb_abc123",
  "query": "如何配置数据库连接",
  "top_k": 5,
  "document_ids": ["doc_xxx"],
  "tags": ["config"],
  "rerank": true,
  "conversation_history": [
    { "role": "user", "content": "之前的问题" },
    { "role": "assistant", "content": "之前的回答" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `kb_id` | string | 是 | 知识库 ID |
| `query` | string | 是 | 搜索查询 |
| `top_k` | int | 否 | 返回结果数，默认 5 |
| `document_ids` | string[] | 否 | 限定文档范围 |
| `tags` | string[] | 否 | 按标签过滤 |
| `rerank` | boolean | 否 | 是否重排序，默认 false |
| `conversation_history` | array | 否 | 对话历史，用于查询改写 |

**响应 200:**
```json
{
  "results": [
    {
      "content": "数据库连接配置...",
      "document_id": "doc_xxx",
      "document_name": "config-guide.md",
      "chunk_index": 3,
      "score": 0.92
    }
  ],
  "count": 5,
  "rewritten_query": "数据库连接配置方法"
}
```

**请求体 (表型 KB):**
```json
{
  "kb_id": "kb_table_xxx",
  "query": "上个月销售额最高的产品",
  "model": "claude-3-haiku"
}
```

**响应 200 (表型):**
```json
{
  "sql": "SELECT product_name, SUM(amount) ...",
  "results": [...],
  "explanation": "..."
}
```

**认证:** Bearer Token

---

## 切片管理

基础路径: `/knowledge/bases/{kbId}`

### GET /knowledge/bases/{kbId}/documents/{docId}/chunks -- 列出文档切片

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `level` | int | 0 | 切片层级 |
| `offset` | int | 0 | 偏移量 |
| `limit` | int | 50 | 每页数量 (最大 200) |

**响应 200:**
```json
{
  "chunks": [
    {
      "index": 0,
      "content": "第一段文本...",
      "token_count": 150,
      "status": "ACTIVE"
    }
  ],
  "total": 25,
  "offset": 0,
  "limit": 50
}
```

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/chunks -- 列出知识库全部切片

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `doc_id` | string | - | 按文档过滤 |
| `status` | string | - | 按状态过滤 |
| `offset` | int | 0 | 偏移量 |
| `limit` | int | 50 | 每页数量 (最大 200) |

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex} -- 获取单个切片

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex}/context -- 获取切片上下文

返回切片及其前后相邻切片。

**认证:** Bearer Token

---

### PUT /knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex} -- 编辑切片

异步操作，返回写入任务 ID。

**请求体:**
```json
{
  "content": "修改后的切片内容"
}
```

**响应 202:**
```json
{
  "task_id": "wt_abc123",
  "status": "PENDING"
}
```

**认证:** Bearer Token

---

### DELETE /knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex} -- 删除切片

异步操作。

**响应 202:**
```json
{
  "task_id": "wt_abc123",
  "status": "PENDING"
}
```

**认证:** Bearer Token

---

### POST /knowledge/bases/{kbId}/documents/{docId}/chunks -- 创建切片

异步操作。

**请求体:**
```json
{
  "content": "新切片的内容",
  "insert_after_index": 3
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | string | 是 | 切片内容 |
| `insert_after_index` | int | 否 | 插入到该索引之后，-1 表示追加到末尾 |

**响应 202:**
```json
{
  "task_id": "wt_abc123",
  "status": "PENDING"
}
```

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/documents/{docId}/fulltext -- 获取文档全文

**响应 200:**
```json
{
  "fulltext": "完整文档内容..."
}
```

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/documents/{docId}/chunk-stats -- 获取切片统计

**认证:** Bearer Token

---

## 重切片操作

### POST /knowledge/bases/{kbId}/documents/{docId}/rechunk -- 重新切片

用新参数重新切分文档。

**请求体:**
```json
{
  "max_tokens": 400,
  "overlap_ratio": 0.15,
  "custom_separator": null
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max_tokens` | int | 400 | 每个切片的最大 token 数 |
| `overlap_ratio` | float | 0.15 | 切片间重叠比率 |
| `custom_separator` | string | - | 自定义分隔符 |

**认证:** Bearer Token

---

### POST /knowledge/bases/{kbId}/documents/{docId}/rechunk/rollback -- 回滚重切片

回滚到之前的切片分支。

**请求体:**
```json
{
  "branch_id": "rechunk_br_xxx"
}
```

**响应 202:**
```json
{
  "task_id": "wt_abc123",
  "status": "PENDING"
}
```

**认证:** Bearer Token

---

### GET /knowledge/bases/{kbId}/documents/{docId}/rechunk/branches -- 列出重切片分支

**响应 200:**
```json
{
  "branches": [
    { "id": "rechunk_br_xxx", "max_tokens": 400, "created_at": "..." }
  ]
}
```

**认证:** Bearer Token

---

## 写入任务

切片编辑/删除/创建等异步操作会返回写入任务 ID，通过此端点轮询状态。

### GET /knowledge/bases/{kbId}/write-tasks/{taskId} -- 获取写入任务状态

**响应 200:**
```json
{
  "task_id": "wt_abc123",
  "type": "EDIT_CHUNK",
  "status": "COMPLETED",
  "result": { "chunk_index": 3 },
  "error": null,
  "created_at": "2026-01-01T00:00:00Z",
  "completed_at": "2026-01-01T00:00:01Z"
}
```

状态值: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`

**认证:** Bearer Token

---

## 数据湖任务

### POST /datalake/jobs -- 提交数据湖任务

支持三种任务类型: `PYTHON`(纯 Python), `RAY`(分布式 Ray), `FINETUNE`(模型微调)。

**请求体 (PYTHON 任务):**
```json
{
  "name": "data-processing",
  "type": "PYTHON",
  "entrypoint": "python main.py",
  "requirements": "pandas==2.0\nnumpy",
  "env_vars": { "DATA_SOURCE": "obs://bucket/data" },
  "resources": { "cpu": "2", "memory": "4Gi" },
  "timeout_seconds": 3600
}
```

**请求体 (RAY 任务):**
```json
{
  "name": "distributed-training",
  "type": "RAY",
  "entrypoint": "python train.py",
  "requirements": "ray[default]==2.9\ntorch",
  "head": { "cpu": "2", "memory": "4Gi" },
  "workers": { "replicas": 3, "cpu": "4", "memory": "8Gi" },
  "timeout_seconds": 7200
}
```

**请求体 (FINETUNE 任务):**
```json
{
  "name": "llm-finetune",
  "type": "FINETUNE",
  "base_model": "Qwen/Qwen2.5-7B",
  "dataset_path": "obs://bucket/dataset.jsonl",
  "output_path": "obs://bucket/output/",
  "hyperparams": { "epochs": 3, "learning_rate": 2e-5, "batch_size": 8 },
  "gpu": { "type": "A100", "count": 1 }
}
```

**响应 201:**
```json
{
  "id": "dlj_abc123",
  "tenant_id": "t_xxx",
  "name": "data-processing",
  "type": "PYTHON",
  "status": "PENDING",
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /datalake/jobs -- 列出数据湖任务

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `status` | string | 按状态过滤: `PENDING`, `STARTING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |

**响应 200:** 返回任务数组。

**认证:** Bearer Token

---

### GET /datalake/jobs/{id} -- 获取任务详情

**响应 200:**
```json
{
  "id": "dlj_abc123",
  "name": "data-processing",
  "type": "PYTHON",
  "status": "RUNNING",
  "started_at": "2026-01-01T00:00:10Z",
  "core_hours": 0.5,
  "gpu_hours": 0.0,
  "created_at": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### DELETE /datalake/jobs/{id} -- 取消任务

**响应:** `204 No Content`

**认证:** Bearer Token

---

### GET /datalake/jobs/{id}/logs -- 获取任务日志流 (SSE)

通过 Server-Sent Events 实时推送任务日志。

**Content-Type:** `text/event-stream`

**响应示例:**
```
data: {"line": "Starting task...", "timestamp": "2026-01-01T00:00:10Z"}

data: {"line": "Processing data...", "timestamp": "2026-01-01T00:00:15Z"}
```

**认证:** Bearer Token

---

## 内部任务系统

通用任务系统，用于知识库文档处理等内部任务。

### POST /jobs -- 提交任务

**请求体:**
```json
{
  "type": "KB_WRITE",
  "params": { "kb_id": "kb_xxx", "doc_id": "doc_xxx" }
}
```

**响应 201:**
```json
{
  "id": "job_abc123",
  "type": "KB_WRITE",
  "status": "PENDING",
  "createdAt": "2026-01-01T00:00:00Z"
}
```

**认证:** Bearer Token

---

### GET /jobs -- 列出任务

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `type` | string | 任务类型过滤 |
| `status` | string | 状态过滤 |

**认证:** Bearer Token

---

### GET /jobs/{id} -- 获取任务详情

**认证:** Bearer Token

---

### POST /jobs/{id}/cancel -- 取消任务

**认证:** Bearer Token

---

### POST /jobs/{id}/callback -- 任务回调 (内部)

任务完成时由 Job Pod 回调。

**请求体:**
```json
{
  "token": "callback_token",
  "status": "COMPLETED",
  "result": {},
  "error": null
}
```

**认证:** 内部 Token 验证

---

## 用量查询

### GET /usage/me -- 获取当前租户用量

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `bill_cycle` | string | 账期，格式 `2026-03`，默认当月 |

**响应 200:**
```json
{
  "tenant_id": "t_xxx",
  "compute_cu_hours": 12.5,
  "storage_gb_hours": 50.0,
  "database_count": 3,
  "from": "2026-03-01T00:00:00Z",
  "to": "2026-03-22T12:00:00Z"
}
```

**认证:** Bearer Token

---

## 试用

### POST /trial -- 创建试用账户

创建临时试用账户和数据库，24 小时后过期。

**响应 201:**
```json
{
  "api_key": "lk_...",
  "database": {
    "id": "db_trial_xxx",
    "name": "trial-db",
    "connection_uri": "postgresql://...",
    "password": "..."
  },
  "expires_at": "2026-01-02T00:00:00Z"
}
```

**认证:** 无需认证

---

## Admin API

所有 Admin 端点需要 admin token 认证。基础路径: `/api/v1/admin`

### GET /admin/dashboard -- 获取管理面板概览

**响应 200:**
```json
{
  "total_tenants": 50,
  "total_databases": 120,
  "active_databases": 30,
  "total_storage_gb": 45.2
}
```

---

### GET /admin/tenants -- 列出所有租户

---

### GET /admin/tenants/{tenantId} -- 获取租户详情

---

### PUT /admin/tenants/{tenantId}/quota -- 更新租户配额

**请求体:**
```json
{
  "max_databases": 10,
  "max_storage_gb": 50,
  "max_compute_cu": 16
}
```

---

### POST /admin/tenants/{tenantId}/disable -- 禁用租户

---

### POST /admin/tenants/{tenantId}/enable -- 启用租户

---

### DELETE /admin/tenants/batch -- 批量删除租户

**请求体:**
```json
{
  "ids": ["t_xxx", "t_yyy"]
}
```

**响应 200:**
```json
{
  "deleted": 2,
  "errors": []
}
```

---

### GET /admin/databases -- 列出所有数据库 (全局)

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `status` | string | 按状态过滤 |
| `tenant_id` | string | 按租户过滤 |

---

### GET /admin/databases/{databaseId} -- 获取数据库详情 (全局)

---

### DELETE /admin/databases/batch -- 批量删除数据库

**请求体:**
```json
{
  "ids": ["db_xxx", "db_yyy"]
}
```

---

### GET /admin/cloud/resources -- 获取云资源信息

---

### GET /admin/compute/stats -- 获取计算统计

---

### GET /admin/system/health -- 获取系统健康状态 (全部组件)

**响应 200:**
```json
{
  "pageserver": { "status": "healthy" },
  "safekeeper": { "status": "healthy" },
  "proxy": { "status": "healthy" },
  "rds": { "status": "healthy" },
  "obs": { "status": "healthy" }
}
```

---

### GET /admin/system/health/obs -- 获取 OBS 健康状态

---

### GET /admin/system/health/{component} -- 获取指定组件健康状态

支持组件: `pageserver`, `safekeeper`, `proxy`, `rds`

---

### GET /admin/operations -- 列出操作日志 (全局)

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `tenant_id` | string | - | 按租户过滤 |
| `type` | string | - | 操作类型 |
| `status` | string | - | 操作状态 |
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 (最大 100) |

---

### GET /admin/cost/summary -- 获取成本概览

---

### GET /admin/cost/trend -- 获取成本趋势

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `days` | int | 30 | 天数 |

---

### GET /admin/cost/tenants -- 按租户查看成本

---

### GET /admin/cost/cbc -- 获取 CBC 账单

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `bill_cycle` | string | 账期，如 `2026-03`，默认当月 |

---

### GET /admin/usage/tenants -- 获取全部租户用量

**查询参数:**
| 参数 | 类型 | 说明 |
|------|------|------|
| `from` | string | 开始时间 (ISO 8601) |
| `to` | string | 结束时间 (ISO 8601) |

---

### GET /admin/usage/tenants/{tenantId} -- 获取指定租户用量

---

### GET /admin/usage/databases/{databaseId} -- 获取指定数据库用量

---

### GET /admin/logs/{component} -- 获取组件日志 (纯文本)

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `tail` | int | 200 | 日志行数 |

**Content-Type:** `text/plain`

---

### GET /admin/metrics/summary -- 获取指标概览

---

### GET /admin/alerts -- 获取告警列表

---

### GET /admin/alerts/rules -- 获取告警规则

---

### PUT /admin/alerts/rules/{id} -- 更新告警规则

---

### POST /admin/alerts/test-webhook -- 测试 Webhook

**请求体:**
```json
{
  "webhook_url": "https://hooks.example.com/alert"
}
```

---

### GET /admin/pageserver/metrics -- 获取 Pageserver 指标

---

### GET /admin/infra/nodes -- 获取集群节点和 Pod 信息

---

### GET /admin/infra/node-pool -- 获取节点池状态

---

### GET /admin/infra/autoscaling-events -- 获取弹性伸缩事件

---

### GET /admin/infra/events -- 获取 Pod 事件

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `namespace` | string | lakeon-compute | 命名空间 |

---

### GET /admin/infra/compute-summary -- 获取计算 Pod 概览

---

### POST /admin/infra/cleanup-idle-pods -- 清理空闲计算 Pod

---

### GET /admin/audit/logs -- 获取审计日志 (全局)

**查询参数:**
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `tenant_id` | string | - | 按租户过滤 |
| `db_id` | string | - | 按数据库过滤 |
| `type` | string | - | 事件类型 |
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |

---

### POST /admin/invite-codes -- 创建邀请码

**请求体 (可选):**
```json
{
  "max_uses": 10,
  "expires_at": "2026-06-01T00:00:00Z"
}
```

**响应 200:**
```json
{
  "code": "ABC12345",
  "max_uses": 10,
  "used_count": 0,
  "valid": true,
  "expires_at": "2026-06-01T00:00:00Z",
  "created_at": "2026-03-22T00:00:00Z"
}
```

---

### GET /admin/invite-codes -- 列出邀请码

---

### DELETE /admin/invite-codes/{code} -- 删除邀请码

---

## 错误响应

所有错误使用标准 HTTP 状态码，响应体格式:

```json
{
  "error": "错误描述信息"
}
```

| 状态码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证或认证失败 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 冲突 (如资源已存在) |
| 500 | 服务器内部错误 |
