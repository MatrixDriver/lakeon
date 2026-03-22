# 数据集导出设计

> 2026-03-22 — 数据湖 Layer 0：从用户数据库导出 Parquet 数据集到 OBS

## 背景

用户需要将数据库中的业务数据导出为标准格式数据集（Parquet），用于数据分析、外部训练、或后续的数据湖作业处理。数据集是数据飞轮的基础节点——既是 DB 导出的产物，也是作业的输入/输出，形成链条：

```
DB → 导出 → 数据集A → Python作业(清洗) → 数据集B → Ray作业(训练) → 模型
```

## 核心决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 连接方式 | 直连 compute pod（绕过 proxy） | 内部 Job 不需要 SSL/认证/连接池；wakeCompute() 主动唤醒 |
| 导出引擎 | DuckDB `postgres_query` / `postgres_scan` → Parquet | 一条 SQL 完成，零中间文件，流式写入，依赖只需 `pip install duckdb` |
| 用户选择交互 | 选表/列为主 + 可切换自定义 SQL | 降低门槛，高级用户可写 SQL |
| 预览 | 导出前预览 10 行 + 总行数 | 反正要唤醒 compute，额外开销仅一次轻量 SELECT |
| 数据集双向流 | 作业可消费数据集（输入），也可产出数据集（输出） | 支撑数据飞轮链条 |

## 架构

```
Console                     API                          K8s
──────                     ────                         ────
选表/列 或 写SQL     →  POST /datasets             →  保存 DatasetEntity (DRAFT)
预览(10行+count)    →  POST /datasets/preview      →  wakeCompute → SELECT LIMIT 10
确认导出            →  POST /datasets/{id}/export  →  submitJob(EXPORT_PARQUET)
                                                        ↓
                                                   Job Pod (DuckDB)
                                                   ├─ 从 params.json 读取 connstr + SQL
                                                   ├─ postgres_query → Parquet → OBS
                                                   └─ callback(row_count, file_size, obs_path)
查看状态/下载        ←  GET /datasets/{id}          ←  DatasetEntity + OBS presigned URL
```

## 数据模型

### DatasetEntity

```sql
CREATE TABLE datasets (
    id            VARCHAR(64) PRIMARY KEY,   -- ds_ + 12 char
    tenant_id     VARCHAR(64) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    source_type   VARCHAR(16) NOT NULL,      -- DB_EXPORT / JOB_OUTPUT
    database_id   VARCHAR(64),               -- 源数据库 ID（DB_EXPORT 时必填）
    source_sql    TEXT,                       -- 导出的 SELECT 语句
    source_tables TEXT,                       -- JSON: 选表模式时记录选了哪些表和列
    obs_path      VARCHAR(512),              -- 导出完成后的 OBS 路径
    row_count     BIGINT,
    file_size     BIGINT,                    -- bytes
    status        VARCHAR(16) NOT NULL,      -- DRAFT / EXPORTING / READY / FAILED
    job_id        VARCHAR(64),               -- 关联的 export Job ID
    error         TEXT,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
```

**source_type 枚举**：

| 值 | 含义 |
|---|------|
| `DB_EXPORT` | 从用户数据库导出 |
| `JOB_OUTPUT` | 作业产出（作业完成后自动创建） |

## API 设计

### 创建数据集

```
POST   /api/v1/datasets              创建数据集（DRAFT 状态）
```

请求体：
```json
{
  "name": "订单数据-2026Q1",
  "description": "一季度全量订单",
  "database_id": "db_xxx",
  "query_mode": "TABLE_SELECT",
  "tables": [{"name": "orders", "columns": ["id", "user_id", "total", "created_at"]}],
  "sql": null
}
```

- `name`（必填）：数据集名称
- `database_id`（必填）：源数据库
- `query_mode`（必填）：`TABLE_SELECT` 或 `CUSTOM_SQL`
- `tables`（TABLE_SELECT 时必填）：选择的表和列
- `sql`（CUSTOM_SQL 时必填）：自定义 SELECT 语句
- `description`（可选）

API 根据 `query_mode` 生成 `source_sql` 存入实体。

### 其他 CRUD

```
GET    /api/v1/datasets              列表（支持 status 过滤）
GET    /api/v1/datasets/{id}         详情（含代码片段 + OBS 预签名下载 URL）
DELETE /api/v1/datasets/{id}         删除（含 OBS 文件清理）
```

### 预览

```
POST   /api/v1/datasets/preview
```

请求体（选表模式）：
```json
{
  "database_id": "db_xxx",
  "query_mode": "TABLE_SELECT",
  "tables": [{"name": "orders", "columns": ["id", "user_id", "total"]}],
  "sql": null
}
```

请求体（自定义 SQL 模式）：
```json
{
  "database_id": "db_xxx",
  "query_mode": "CUSTOM_SQL",
  "tables": null,
  "sql": "SELECT o.id, u.name, o.total FROM orders o JOIN users u ON o.user_id = u.id WHERE o.total > 100"
}
```

> **注意**：`query_mode`（TABLE_SELECT / CUSTOM_SQL）是请求参数，描述如何生成 SQL。
> `source_type`（DB_EXPORT / JOB_OUTPUT）是实体字段，描述数据集的来源方式。二者不同。

响应：
```json
{
  "columns": ["id", "name", "total"],
  "rows": [["ord_001", "Alice", 299], ...],
  "total_count": 12847,
  "preview_sql": "SELECT ... LIMIT 10"
}
```

实现：调用 `ComputeLifecycleService.wakeCompute()` 唤醒 compute pod，通过 `DatabaseQueryService` 执行 `SELECT ... LIMIT 10` 和 `SELECT COUNT(*)`。

### 触发导出

```
POST   /api/v1/datasets/{id}/export
```

无请求体。将 DRAFT 状态的数据集提交为 `EXPORT_PARQUET` Job：
1. 唤醒 compute pod：调用 `wakeCompute(dbId)` 返回 `host:port`
2. 构造 connstr：`postgresql://cloud_admin:cloud-admin-internal@{host}:{port}/{dbName}?sslmode=disable`（compute pod 的 `cloud_admin` 用户无需外部密码，`--dev` 模式下 SCRAM 认证用固定密码）
3. 刷新活跃时间：`entity.setLastActiveAt(Instant.now())` 防止导出期间 compute 被挂起
4. 构造 params（connstr、source_sql、obs_output_path）
5. 调用 `jobService.submitJob(tenant, JobType.EXPORT_PARQUET, params)`（走 `com.lakeon.job` 框架，不是 `com.lakeon.datalake`）
6. 更新 dataset status → EXPORTING，记录 job_id

> **Job 框架说明**：导出 Job 使用 `com.lakeon.job.JobService`（CCE 节点池，已有 `EXPORT_PARQUET` 枚举）。
> 数据集与作业的集成（input/output）则修改 `com.lakeon.datalake.DatalakeService`（CCI 作业系统）。
> 二者是独立的 Job 体系，仅通过 DatasetEntity 关联。

### 数据集详情响应

```json
{
  "id": "ds_xxxxxxxxxxxx",
  "name": "订单数据-2026Q1",
  "source_type": "DB_EXPORT",
  "database_id": "db_xxx",
  "database_name": "myapp",
  "status": "READY",
  "row_count": 12847,
  "file_size": 4521984,
  "obs_path": "datasets/tenant_xxx/ds_xxxxxxxxxxxx/data.parquet",
  "download_url": "https://obs...presigned...",
  "created_at": "2026-03-22T10:00:00Z",
  "code_snippets": {
    "pandas": "import pandas as pd\ndf = pd.read_parquet('s3://...')",
    "ray": "import ray.data\nds = ray.data.read_parquet('s3://...')",
    "duckdb": "import duckdb\nduckdb.sql(\"SELECT * FROM 's3://...'\")"
  }
}
```

## 作业 ↔ 数据集集成

### 作业消费数据集（输入）

作业提交时新增可选字段 `input_dataset_id`。API 解析后将数据集的 OBS 路径注入为环境变量：

```
DATASET_PATH=s3://bucket/datasets/tenant_xxx/ds_xxx/data.parquet
```

用户代码中：
```python
import os
import pandas as pd
df = pd.read_parquet(os.environ["DATASET_PATH"], storage_options={
    "key": os.environ["OBS_ACCESS_KEY"],
    "secret": os.environ["OBS_SECRET_KEY"],
    "endpoint_url": os.environ["OBS_ENDPOINT"],
})
```

### 作业产出数据集（输出）

作业提交时新增可选字段 `output_dataset_name`。API 分配输出路径并注入环境变量：

```
DATASET_OUTPUT_PATH=s3://bucket/datasets/tenant_xxx/ds_new/data.parquet
```

作业完成回调时，如果有 `output_dataset_name`，自动创建 DatasetEntity：
- source_type = JOB_OUTPUT
- obs_path = DATASET_OUTPUT_PATH
- status = READY
- 从 OBS 读取文件大小，行数从回调结果中获取

### DatalakeJobRequest 变更

新增两个可选字段：
```java
@JsonProperty("input_dataset_id")
private String inputDatasetId;

@JsonProperty("output_dataset_name")
private String outputDatasetName;
```

## Export Job Pod

### 镜像

复用 `knowledge/job/` 镜像，新增 `export_parquet.py` 脚本。在 `main.py` 根据 `JOB_TYPE` 环境变量分发：

```python
# main.py
job_type = os.environ.get("JOB_TYPE", "DOCUMENT_PARSE")
if job_type == "EXPORT_PARQUET":
    from export_parquet import main as export_main
    export_main()
else:
    # 现有 document parse 逻辑
    ...
```

### export_parquet.py

DuckDB 的 PG 扩展提供两个函数：
- `postgres_scan(dsn, schema, table)` — 单表扫描，不支持任意 SQL
- `postgres_query(dsn, query)` — 执行任意 SELECT 语句

API 层统一将用户选择转换为 SELECT 语句（选表模式 → `SELECT col1, col2 FROM tablename`），
Job Pod 统一使用 `postgres_query`。

```python
import duckdb
import json
import os
from callback import report_success, report_failure, report_progress

def main():
    with open("/etc/job/params.json") as f:
        params = json.load(f)

    connstr = params["database_connstr"]   # postgresql://cloud_admin:...@host:55433/dbname
    source_sql = params["source_sql"]       # SELECT 语句（API 层已生成）
    obs_path = params["obs_output_path"]

    obs_endpoint = os.environ["OBS_ENDPOINT"]
    obs_ak = os.environ["OBS_ACCESS_KEY"]
    obs_sk = os.environ["OBS_SECRET_KEY"]

    report_progress("Connecting to database", 0.1)

    conn = duckdb.connect()
    conn.execute("INSTALL postgres; LOAD postgres; INSTALL httpfs; LOAD httpfs;")
    conn.execute(f"""
        SET s3_endpoint = '{obs_endpoint.replace("https://", "")}';
        SET s3_access_key_id = '{obs_ak}';
        SET s3_secret_access_key = '{obs_sk}';
        SET s3_url_style = 'path';
        SET s3_use_ssl = true;
    """)

    report_progress("Exporting data", 0.3)

    # 统计行数（通过 postgres_query 执行用户 SQL 的 COUNT 包装）
    count_result = conn.execute(
        f"SELECT COUNT(*) FROM postgres_query('{connstr}', '{source_sql}')"
    ).fetchone()
    row_count = count_result[0]

    # 导出 Parquet（postgres_query 执行任意 SELECT，结果直写 OBS）
    conn.execute(f"""
        COPY (
            SELECT * FROM postgres_query('{connstr}', '{source_sql}')
        )
        TO '{obs_path}'
        (FORMAT PARQUET, ROW_GROUP_SIZE 100000, COMPRESSION ZSTD)
    """)

    report_progress("Finalizing", 0.9)

    # 获取文件大小（从 OBS HEAD 请求）
    file_size = get_obs_file_size(obs_path, obs_ak, obs_sk, obs_endpoint)

    report_success({
        "row_count": row_count,
        "file_size": file_size,
        "obs_path": obs_path
    })
```

> **注意**：`source_sql` 中的单引号需要在 API 层转义为双单引号，
> 因为它嵌入在 DuckDB 的字符串参数中。

### Job Pod 资源配置

```yaml
# application.yml
lakeon:
  job:
    types:
      export-parquet:
        image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge-job:0.2.0
        cpu: "1"
        memory: "2Gi"   # DuckDB 需要更多内存处理列式转换
```

### OBS 路径约定

```
s3://{bucket}/datasets/{tenant_id}/{dataset_id}/data.parquet
```

## Console UI

### 菜单变更

```
数据湖
├── 作业管理     ← 保留
└── 数据集       ← 做实（去掉 Notebook、模型仓库）
```

### 数据集列表页

表格：名称 | 来源 | 行数 | 大小 | 状态 | 创建时间 | 操作

- 来源列：DB_EXPORT 显示数据库名，JOB_OUTPUT 显示作业名
- 状态：DRAFT(灰) / EXPORTING(蓝/转圈) / READY(绿) / FAILED(红)
- 操作：下载 | 删除
- 右上角：「新建数据集」按钮

### 新建数据集页面

1. 名称输入框
2. 选择数据库（下拉列表）
3. 模式切换：「选择表」/「自定义 SQL」
4. 选择表模式：
   - 列出数据库中的表（复用 `GET /api/v1/databases/{id}/tables`）
   - 勾选表，展开可选列
5. 自定义 SQL 模式：
   - 代码编辑器（monaco / codemirror）
6. 「预览」按钮 → 显示 10 行样本 + 总行数
7. 「导出」按钮 → 创建 + 提交

### 数据集详情页

- 基本信息（名称、来源、状态、行数、大小）
- 导出进度（EXPORTING 时显示）
- 代码片段（pandas / ray / duckdb，带复制按钮）
- 下载按钮（OBS 预签名 URL）

### 数据库页面快捷入口

表列表中每个表增加「导出到数据集」操作按钮，点击跳转：
```
/datalake/datasets/new?database_id={id}&table={tableName}
```

自动预填数据库和表名。

## 安全

- **SQL 注入防护（纵深防御）**：
  - 主防线：DuckDB `postgres_query` 天然只读，不支持 INSERT/UPDATE/DELETE/DDL
  - 辅助防线：API 层校验拒绝分号和非 SELECT 语句（防御 `dblink` 等扩展函数的潜在副作用）
- **OBS 隔离**：路径包含 tenant_id，租户只能访问自己的数据集
- **compute pod 凭据**：connstr 中使用 `cloud_admin` 固定密码，传递在 ConfigMap（params.json）中。ConfigMap 不加密，但仅在 lakeon namespace 内可见，且 Job Pod 生命周期短暂（分钟级）
- **compute pod 生命周期**：导出前刷新 `lastActiveAt` + `hasActiveConnections()` 双重保护

## 风险缓解

| 风险 | 缓解措施 |
|------|---------|
| 大表导出 OOM | Job Pod 2Gi 内存；DuckDB `ROW_GROUP_SIZE=100000` 分批；超大表未来可拆分 Parquet 文件 |
| 导出期间 compute 被挂起 | 导出前刷新 `lastActiveAt`；`hasActiveConnections()` 检测 DuckDB 连接跳过挂起 |
| SQL 注入 | DuckDB postgres_query 天然只读（主防线）；API 层 SELECT-only 校验（辅助） |
| DuckDB postgres 扩展缺失 | 镜像构建时预装 `duckdb install postgres && duckdb install httpfs` |
| 空表导出 | DuckDB COPY 空结果集产生仅含 schema 的合法 Parquet 文件；status 正常置为 READY，row_count=0 |
| 同一数据库并发导出 | PG 支持并发只读，DuckDB 各开独立连接，互不干扰；允许并发 |

## 实现范围

### 本次实现（Layer 0）

- DatasetEntity + DatasetRepository
- DatasetService（preview / create / export / list / get / delete）
- DatasetController（6 个端点）
- export_parquet.py（DuckDB Job 脚本）
- Console：数据集列表页 + 新建页 + 详情页
- Console：去掉 Notebook、模型仓库菜单项
- Console：数据库页面「导出到数据集」快捷入口
- DatalakeJobRequest 新增 input_dataset_id / output_dataset_name
- DatalakeService 处理作业产出数据集的自动注册

### 不在本次范围

- 模型工坊（Layer 1+2）
- LLM 辅助 Transform
- 定时/增量导出
- 多文件分片导出
