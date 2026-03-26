# OBS 数据源同步设计

> 2026-03-26 | 状态：待批准

## 目标

用户可以创建 OBS 数据源，将文件批量上传到平台 OBS bucket 的指定目录，然后手动触发同步，系统增量扫描目录并处理新增/修改/删除的文件。

类似 AWS Bedrock Knowledge Base 的 S3 Data Source + StartIngestionJob 模式。

## 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 同步触发方式 | 手动（用户点"同步"） | 简单可控，Phase 2 再考虑定时 |
| OBS 范围 | 平台自有 bucket，按 prefix 隔离 | Phase 1 不需要用户提供 AK/SK |
| 增量策略 | List + ETag 对比 | 和 Bedrock 一致，简单可靠 |
| 文件上传方式 | STS 临时凭据 + hcloud/obsutil/网页 | 灵活，支持批量 |
| 文件类型 | 自动扫描 prefix 下所有支持格式 | PDF/EPUB/DOCX/MD/TXT |

## 数据模型

### 新增 `datasources` 表

```sql
CREATE TABLE datasources (
    id VARCHAR(32) PRIMARY KEY,        -- ds_ + random
    tenant_id VARCHAR(64) NOT NULL,
    kb_id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    obs_prefix VARCHAR(256) NOT NULL,  -- datasources/{tenant_id}/{ds_id}/
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SYNCING / ERROR
    last_synced_at TIMESTAMPTZ,
    last_sync_stats JSONB,             -- {"added": 5, "modified": 2, "deleted": 1, "skipped": 42, "errors": 0}
    file_count INT DEFAULT 0,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX idx_datasources_tenant_kb ON datasources(tenant_id, kb_id);
```

### `documents` 表新增字段

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS datasource_id VARCHAR(32);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS obs_etag VARCHAR(64);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS obs_size BIGINT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS obs_last_modified TIMESTAMPTZ;

CREATE INDEX idx_documents_datasource_id ON documents(datasource_id);
```

手动上传的文档 `datasource_id` 为 null。数据源同步的文档 `datasource_id` 指向对应数据源。

## 同步逻辑

```
用户点"同步"
  ↓
1. 设 datasource.status = SYNCING
2. OBS ListObjects(prefix=datasource.obs_prefix)
   → 过滤支持的文件扩展名 (.pdf/.epub/.docx/.md/.txt)
   → 得到 obs_files = [{key, etag, size, lastModified}, ...]
3. 查 DB: existing = documents WHERE datasource_id = ds_id
4. 对比:
   - obs 有 + db 无            → 新增: 创建 document(PENDING) + 提交处理
   - obs 有 + db 有 + etag 变了 → 修改: 标记 document(PENDING) + 删旧 chunks + 重新处理
   - obs 无 + db 有            → 删除: 删 document + 删 chunks
   - obs 有 + db 有 + etag 同  → 跳过
5. 用批量上传接口提交新增+修改的文档（复用 BATCH_DOCUMENT_PARSE job type）
6. 更新 datasource: last_synced_at, file_count, last_sync_stats
7. 设 datasource.status = ACTIVE（或 ERROR）
```

## OBS 目录结构

```
lakeon-storage/
  datasources/
    {tenant_id}/
      {ds_id}/
        report.pdf
        manual.epub
        notes/           ← 支持子目录，递归扫描
          chapter1.md
          chapter2.md
```

文件的 `obs_key` = 完整路径，如 `datasources/tn_xxx/ds_xxx/notes/chapter1.md`。
`filename` = 相对于 prefix 的路径，如 `notes/chapter1.md`。

## 上传凭据

`GET /api/v1/knowledge/{kb_id}/datasources/{ds_id}/credentials` 返回：

```json
{
  "endpoint": "obs.cn-north-4.myhuaweicloud.com",
  "bucket": "lakeon-storage",
  "prefix": "datasources/tn_xxx/ds_xxx/",
  "access_key": "<临时 AK>",
  "secret_key": "<临时 SK>",
  "security_token": "<STS Token>",
  "expires_at": "2026-03-26T15:00:00Z",
  "upload_commands": {
    "hcloud": "hcloud obs cp ./my-docs/ obs://lakeon-storage/datasources/tn_xxx/ds_xxx/ -r -f -e obs.cn-north-4.myhuaweicloud.com -i <AK> -k <SK> -t <Token>",
    "obsutil": "obsutil cp ./my-docs/ obs://lakeon-storage/datasources/tn_xxx/ds_xxx/ -r -f -e obs.cn-north-4.myhuaweicloud.com -i <AK> -k <SK> -t <Token>"
  }
}
```

STS 凭据 scope 限定到 `datasources/{tenant_id}/{ds_id}/*`，防止跨租户/跨数据源访问。有效期 15 分钟。

复用已有的 `ObsStsService`（已用于文档上传的预签名 URL）。

## API 接口

```
POST   /knowledge/{kb_id}/datasources                   创建数据源（传 name）
GET    /knowledge/{kb_id}/datasources                    列出数据源
GET    /knowledge/{kb_id}/datasources/{ds_id}            数据源详情
DELETE /knowledge/{kb_id}/datasources/{ds_id}            删除数据源（含关联文档+chunks）
POST   /knowledge/{kb_id}/datasources/{ds_id}/sync       触发同步
GET    /knowledge/{kb_id}/datasources/{ds_id}/credentials 获取上传凭据
```

所有接口在 `/api/v1` 前缀下，需要租户 API Key 认证。

## Console UI

### 数据源 Tab（在知识库详情页）

已有 tab：概览 / 文档 / 搜索 / 切片 → 新增 **数据源** tab

#### 数据源列表

```
+-------+------------------------------------------+------+----------+--------+----------+
| 名称  | OBS 目录                                   | 文件数 | 上次同步   | 状态   | 操作     |
+-------+------------------------------------------+------+----------+--------+----------+
| 产品文档 | datasources/tn_xxx/ds_xxx/              | 42   | 3/26 14:00 | 已同步 | 同步 删除 |
+-------+------------------------------------------+------+----------+--------+----------+
```

点击"同步"按钮 → 状态变"同步中" → 完成后显示同步结果（新增 X / 修改 Y / 删除 Z / 跳过 W）。

#### 创建数据源

点击"添加数据源"按钮 → 弹窗输入名称 → 创建成功后显示上传指引。

#### 上传指引面板

创建后或点击数据源名称展开详情时显示：

```
📁 OBS 上传目录
   obs://lakeon-storage/datasources/tn_xxx/ds_xxx/

   支持格式：PDF、EPUB、DOCX、Markdown、TXT
   支持子目录，系统会递归扫描所有文件。

📋 上传方式

   [获取上传凭据]  ← 按钮，点击后显示临时 AK/SK/Token

   方式一：使用 hcloud CLI
   hcloud obs cp ./my-docs/ obs://lakeon-storage/.../  -r -f \
     -e obs.cn-north-4.myhuaweicloud.com -i <AK> -k <SK> -t <Token>

   方式二：使用 obsutil
   obsutil cp ./my-docs/ obs://lakeon-storage/.../  -r -f \
     -e obs.cn-north-4.myhuaweicloud.com -i <AK> -k <SK> -t <Token>

   方式三：在华为云 OBS Console 网页端
   登录华为云 Console → 对象存储服务 → lakeon-storage 桶 → 进入上述目录 → 上传

   上传完成后，点击 [同步] 按钮将文件导入知识库。
```

凭据区域：点击"获取上传凭据"后展开，显示 AK/SK/Token（带复制按钮），15 分钟后过期可重新获取。

### 文档 Tab 变更

数据源同步的文档在"文档"tab 里显示时，增加"来源"列：
- 手动上传的显示"手动上传"
- 数据源的显示数据源名称

## 实现要点

### Java 侧

- `DataSourceEntity` + `DataSourceRepository` — 新增 JPA entity
- `DataSourceController` — REST 接口
- `DataSourceSyncService` — 同步逻辑：list OBS → diff → 提交处理
- `DocumentEntity` 新增 `datasourceId`, `obsEtag`, `obsSize`, `obsLastModified` 字段
- 复用 `ObsStsService` 生成 STS 临时凭据
- 复用 `KbWriteQueue` 批量提交 `BATCH_DOCUMENT_PARSE` 任务

### Console 侧

- 知识库详情页新增"数据源"tab
- 数据源 CRUD + 同步按钮 + 凭据展示 + 上传指引
- 文档列表增加"来源"列

## 边界与限制

- Phase 1 只支持平台自有 OBS bucket（用户不能指定外部 bucket）
- Phase 1 只支持手动同步（不支持定时/自动）
- 单次同步最多处理 1000 个文件（OBS ListObjects 分页，超过时提示）
- 同步期间不允许重复触发
- 删除数据源时同步删除关联的所有文档和 chunks
