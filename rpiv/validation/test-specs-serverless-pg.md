---
description: "测试规格与验收标准: serverless-pg"
status: completed
created_at: 2026-03-03T00:00:00
updated_at: 2026-03-03T00:00:00
archived_at: null
---

# LakeOn Serverless PostgreSQL 测试规格与验收标准

本文档基于 PRD 定义详细的测试规格、输入/输出约束和精确的验收标准，供测试代码编写时直接引用。

---

## 1. API 请求/响应验证规格

### 1.1 创建实例 — `POST /api/v1/databases`

#### 请求验证

| 字段 | 类型 | 必填 | 约束 | 无效输入示例 |
|------|------|------|------|-------------|
| name | string | 是 | 1-63 字符，小写字母+数字+连字符，以字母开头 | `""`, `"123db"`, `"MY-DB"`, `"a".repeat(64)` |
| compute_size | string | 否 | 枚举: `1cu`, `2cu`, `4cu`, `8cu`；默认 `1cu` | `"16cu"`, `"abc"` |
| suspend_timeout | string | 否 | 格式: `{number}m`，范围 1m-60m；默认 `5m` | `"0m"`, `"120m"`, `"5s"` |
| storage_limit_gb | integer | 否 | 范围 1-100；默认 10 | `0`, `200`, `-1` |

#### 响应验证（201 Created）

| 字段 | 类型 | 断言 |
|------|------|------|
| id | string | 非空，以 `db_` 开头 |
| name | string | 等于请求中的 name |
| status | string | `creating` 或 `running` |
| connection_uri | string | 匹配 `postgres://\w+:\w+@[\w.]+/\S+` |
| compute_size | string | 等于请求值或默认值 `1cu` |
| suspend_timeout | string | 等于请求值或默认值 `5m` |
| storage_limit_gb | integer | 等于请求值或默认值 10 |
| storage_used_gb | number | >= 0 |
| branches | array | 长度 1，包含 name=`main`, is_default=`true` 的元素 |
| created_at | string | ISO 8601 格式，与当前时间差 < 5s |

#### 错误响应

| 条件 | 状态码 | error.code |
|------|--------|------------|
| 缺少 name | 400 | `VALIDATION_ERROR` |
| name 格式无效 | 400 | `VALIDATION_ERROR` |
| compute_size 非法值 | 400 | `VALIDATION_ERROR` |
| 同租户同名实例已存在 | 409 | `CONFLICT` |
| 无 Authorization header | 401 | `UNAUTHORIZED` |
| 无效 API Key | 401 | `UNAUTHORIZED` |
| Neon 后端不可用 | 503 | `SERVICE_UNAVAILABLE` |

### 1.2 查看实例 — `GET /api/v1/databases/{db_id}`

#### 响应验证（200 OK）

与创建实例的响应字段一致，额外验证：
- `status` 取值范围：`creating`, `running`, `suspended`, `error`
- `storage_used_gb` 准确反映实际用量（允许 ±10% 误差）

#### 错误响应

| 条件 | 状态码 | error.code |
|------|--------|------------|
| db_id 不存在 | 404 | `RESOURCE_NOT_FOUND` |
| db_id 属于其他租户 | 404 | `RESOURCE_NOT_FOUND`（不泄露存在性） |

### 1.3 列出实例 — `GET /api/v1/databases`

#### 响应验证（200 OK）

- 返回 JSON 数组
- 每个元素结构与查看实例一致
- 仅包含当前租户的实例
- 默认按 created_at 降序

### 1.4 更新配置 — `PATCH /api/v1/databases/{db_id}`

#### 请求验证

| 字段 | 类型 | 约束 |
|------|------|------|
| compute_size | string | 可选，枚举值同创建 |
| suspend_timeout | string | 可选，范围同创建 |
| storage_limit_gb | integer | 可选，范围同创建，且不小于当前 storage_used_gb |

#### 副作用验证

- 修改 `compute_size`：compute Pod 需重建（旧 Pod 销毁，新 Pod 以新规格创建）
- 修改 `suspend_timeout`：仅更新元数据，不触发 Pod 重启
- 修改 `storage_limit_gb`：不小于当前用量，否则返回 400

### 1.5 删除实例 — `DELETE /api/v1/databases/{db_id}`

#### 响应验证

- 成功：204 No Content
- 资源清理验证（通过 K8s API 和 Neon API 检查）：
  - K8s compute Pod 已删除
  - Neon tenant 已删除
  - 所有分支的 timeline 已删除
  - 元数据库中记录已删除

### 1.6 休眠/唤醒 — `POST /api/v1/databases/{db_id}/suspend|resume`

#### suspend 验证

- 成功返回 200，实例 status 变为 `suspended`
- K8s compute Pod 已销毁
- 对已 suspended 的实例再次 suspend：幂等返回 200

#### resume 验证

- 成功返回 200，实例 status 变为 `running`
- K8s 新 compute Pod 已创建且 Ready
- 对已 running 的实例再次 resume：幂等返回 200

### 1.7 创建分支 — `POST /api/v1/databases/{db_id}/branches`

#### 请求验证

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| name | string | 是 | 1-63 字符，同实例名规则 |
| start_compute | boolean | 否 | 默认 false |

#### 响应验证（201 Created）

| 字段 | 类型 | 断言 |
|------|------|------|
| id | string | 非空，以 `br_` 开头 |
| name | string | 等于请求中的 name |
| parent_branch | string | `main` |
| status | string | `creating` |
| connection_uri | string | 包含 `branch=` 参数 |
| created_at | string | ISO 8601 格式 |

### 1.8 删除分支 — `DELETE /api/v1/databases/{db_id}/branches/{branch_id}`

#### 验证

- 成功返回 204
- 禁止删除默认分支（name=main）：返回 400
- 分支 compute Pod 已销毁（如有）
- Neon timeline 已删除

### 1.9 租户 API

#### 创建租户 — `POST /api/v1/tenants`

- 响应包含 `id`（`tn_` 前缀）和 `api_key`
- api_key 为随机生成的安全令牌（长度 >= 32 字符）

---

## 2. 认证与鉴权规格

### 2.1 API Key 认证

| 测试用例 | 请求 | 预期 |
|---------|------|------|
| AUTH-001 | 无 Authorization header | 401, code=UNAUTHORIZED |
| AUTH-002 | `Authorization: Bearer invalid-key` | 401, code=UNAUTHORIZED |
| AUTH-003 | `Authorization: Bearer <valid-key>` | 正常处理请求 |
| AUTH-004 | `Authorization: Basic user:pass` | 401, code=UNAUTHORIZED |
| AUTH-005 | `Authorization: Bearer <expired-key>` | 401, code=UNAUTHORIZED |
| AUTH-006 | `Authorization: Bearer <other-tenant-key>` | 请求成功但只能看到自己的资源 |

---

## 3. 数据完整性验证规格

### 3.1 休眠/唤醒数据完整性

**精确验证步骤**：

```
1. 创建实例，等待 running
2. 写入测试数据集：
   - 创建 3 张表（不同数据类型）
   - 每张表写入 100 行数据
   - 数据包含：INTEGER, TEXT, TIMESTAMP, JSONB, BYTEA 类型
   - 记录每张表的行数和 checksum（MD5 聚合）
3. 手动 suspend
4. 等待 compute Pod 完全销毁
5. 手动 resume 或通过连接触发唤醒
6. 重新连接，验证：
   - 3 张表均存在
   - 每张表行数一致
   - checksum 一致
   - 所有数据类型读取正确
```

**checksum 计算方法**：

```sql
SELECT md5(string_agg(t::text, ',' ORDER BY id)) FROM test_table t;
```

### 3.2 分支数据隔离

**精确验证步骤**：

```
1. 主分支写入 dataset_A（100 行）
2. 创建分支 B
3. 分支 B 验证包含 dataset_A
4. 主分支写入 dataset_B（额外 50 行）
5. 分支 B 写入 dataset_C（额外 50 行）
6. 验证主分支：含 dataset_A + dataset_B = 150 行，不含 dataset_C
7. 验证分支 B：含 dataset_A + dataset_C = 150 行，不含 dataset_B
```

---

## 4. 性能验收标准

| 指标 | 验收标准 | 测量方法 |
|------|---------|---------|
| 创建实例到可用 | < 60s | 从 API 调用到 status=running 的时间 |
| API 响应时间（管控操作） | < 2s（p95） | 测量 API 调用的响应时间 |
| 自动唤醒到连接建立 | < 30s | 从 psql 发起连接到连接成功的时间 |
| Compute 唤醒成功率 | > 99% | 100 次唤醒中失败次数 < 1 |
| 数据持久化延迟 | < 30s | 写入到 OBS 可见的延迟 |

---

## 5. CLI 验收标准

### 5.1 输出格式验证

| 命令 | 输出格式要求 |
|------|------------|
| `lakeon db list` | 表格格式，包含列：Name, Status, Compute Size, Created At |
| `lakeon db status` | 详情面板，包含所有实例字段 |
| `lakeon db create` | 输出连接串，高亮显示 |
| `lakeon branch list` | 表格格式，包含列：Name, Parent, Status, Default |
| 所有错误 | 红色文字，包含错误码和消息 |

### 5.2 退出码验证

| 场景 | 退出码 |
|------|--------|
| 成功 | 0 |
| 参数错误 | 2 |
| API 错误（4xx） | 1 |
| 网络错误 | 1 |

### 5.3 配置文件验证

- `lakeon config set` 写入配置文件路径：`~/.lakeon/config.toml` 或 `~/.config/lakeon/config.toml`
- 配置文件包含 `api_url` 和 `api_key`
- api_key 在配置文件中不以明文存储（或有访问权限限制）

---

## 6. 安全验证规格

| 测试用例 | 场景 | 验证 |
|---------|------|------|
| SEC-001 | SQL 注入 — 实例名包含 SQL | name 验证拒绝特殊字符 |
| SEC-002 | API Key 不在日志中出现 | 检查应用日志，API Key 被脱敏 |
| SEC-003 | 连接串密码不在 API 响应列表中暴露 | `GET /databases` 列表接口中 connection_uri 密码部分脱敏 |
| SEC-004 | 跨租户资源访问 | 使用 tenant-A 的 key 访问 tenant-B 的资源返回 404 |
| SEC-005 | API Key 暴力破解防护 | 连续多次无效 key 请求后触发限流 |
| SEC-006 | K8s RBAC 最小权限 | 管控面 ServiceAccount 仅能操作 lakeon-compute namespace |

---

## 7. 错误处理验证规格

### 7.1 统一错误响应格式

所有错误响应必须遵循：

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "人类可读的错误描述",
    "details": {}
  }
}
```

### 7.2 错误码清单

| 错误码 | HTTP 状态码 | 触发条件 |
|--------|-----------|---------|
| UNAUTHORIZED | 401 | API Key 无效或缺失 |
| VALIDATION_ERROR | 400 | 请求参数校验失败 |
| RESOURCE_NOT_FOUND | 404 | 资源不存在或不属于当前租户 |
| CONFLICT | 409 | 资源名称冲突 |
| BAD_REQUEST | 400 | 不允许的操作（如删除默认分支） |
| SERVICE_UNAVAILABLE | 503 | 后端服务不可用（Neon/K8s） |
| INTERNAL_ERROR | 500 | 未预期的内部错误 |
| STORAGE_LIMIT_EXCEEDED | 400 | 存储上限不能小于当前用量 |

---

## 8. 边界条件测试规格

| 测试用例 | 边界条件 | 预期行为 |
|---------|---------|---------|
| EDGE-001 | 实例名 63 个字符 | 创建成功 |
| EDGE-002 | 实例名 64 个字符 | 400 错误 |
| EDGE-003 | 实例名 1 个字符 | 创建成功 |
| EDGE-004 | 实例名空字符串 | 400 错误 |
| EDGE-005 | suspend_timeout = 1m | 创建成功，1 分钟后休眠 |
| EDGE-006 | suspend_timeout = 60m | 创建成功 |
| EDGE-007 | storage_limit_gb = 1 | 创建成功 |
| EDGE-008 | storage_limit_gb = 100 | 创建成功 |
| EDGE-009 | 租户下同时创建多个实例 | 全部创建成功，无冲突 |
| EDGE-010 | 快速连续 suspend/resume | 操作串行化，状态一致 |
| EDGE-011 | 删除正在创建中的实例 | 允许删除，清理资源 |
| EDGE-012 | 对 error 状态实例 resume | 尝试恢复或返回明确错误 |

---

## 9. 测试环境要求

### 单元测试环境

- JDK 21
- Maven 3.9+
- Python 3.11+
- 无需 Docker（Mockito mock 所有外部依赖）

### 集成测试环境

- 上述 + Docker（Testcontainers 需要）
- 端口 9090 可用（WireMock）
- 至少 4GB 可用内存

### 验收测试环境

- 完整 LakeOn 部署（包括 Neon 组件）
- K8s 集群可访问
- psql 客户端可用
- 网络可达 Proxy 端口
