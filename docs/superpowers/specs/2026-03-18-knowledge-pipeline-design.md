# Knowledge Pipeline MVP 设计

> 2026-03-18 | 状态：已批准

## 目标

用户上传文档（PDF/DOCX/Markdown），自动解析、切分、向量化，存入用户自己的 PG 数据库。提供搜索 API，支持语义搜索 + 关键词搜索 + RRF 融合。

## 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 支持格式 | PDF + DOCX + Markdown | 开发者最常用的三种 |
| chunks 存储位置 | 用户自己的 PG | 天然租户隔离，pgvector 索引小而高效；避免 RDS 集中存储膨胀 |
| 搜索方案 | pgvector + pg_search + RRF | Neon compute 已自带两个扩展 |
| 文档上传 | OBS 预签名 URL 直传 | 大文件不经过 API |
| MCP endpoint | Phase 2 再做 | MVP 先跑通 pipeline + 搜索 API |
| Console UI | Phase 2 再做 | MVP 先提供 REST API |
| Chunking | Structure-Aware | 按文档结构切分，不暴力按 token |
| Embedding | BGE-M3 (568M, CPU) | 4C/8G 节点能跑，多语言 |
| 解析器 | Marker (PDF) + python-docx (DOCX) + 直读 (Markdown) | 开源免费，质量最高 |
| Job Pod | 普通 Python Pod，不用 Ray | 单文档串行处理 1-2 分钟够用 |

## 整体流程

```
1. GET  /api/v1/knowledge/upload-url
   → API 返回 OBS 预签名 PUT URL + 创建 document 记录 (PENDING)

2. 前端直传文件到 OBS

3. POST /api/v1/knowledge/documents/{id}/process
   → API 提交 DOCUMENT_PARSE Job

4. Job Pod 执行:
   → 从 OBS 下载文档
   → Marker/python-docx/直读 解析
   → Structure-Aware Chunking
   → BGE-M3 embedding (batch)
   → 连接用户 PG，建表（如不存在），写入 chunks
   → POST callback 更新状态

5. POST /api/v1/knowledge/search
   → API 通过 proxy 连接用户 PG
   → pgvector cosine + pg_search BM25 + RRF fusion
   → 返回 top-K chunks
```

## API 端点

```
GET    /api/v1/knowledge/upload-url            # 获取 OBS 预签名 URL + 创建 document
POST   /api/v1/knowledge/documents/{id}/process # 通知上传完成，提交解析 Job
GET    /api/v1/knowledge/documents             # 列出文档
GET    /api/v1/knowledge/documents/{id}        # 文档详情（含 Job 状态）
DELETE /api/v1/knowledge/documents/{id}        # 删除文档 + chunks
POST   /api/v1/knowledge/search               # 搜索
```

### GET /api/v1/knowledge/upload-url

请求：
```
GET /api/v1/knowledge/upload-url?filename=design.pdf&database_id=db_xxx
```

响应：
```json
{
  "document_id": "doc_a1b2c3d4e5f6",
  "upload_url": "https://obs.cn-north-4.myhuaweicloud.com/lakeon-storage/tenant/xxx/knowledge/doc_a1b2.pdf?X-Amz-Signature=...",
  "obs_key": "tenant/xxx/knowledge/doc_a1b2c3d4e5f6.pdf",
  "expires_in": 900
}
```

### POST /api/v1/knowledge/documents/{id}/process

前端上传完成后调用，触发解析 Job：
```json
{
  "document_id": "doc_a1b2c3d4e5f6"
}
```

### POST /api/v1/knowledge/search

请求：
```json
{
  "database_id": "db_xxx",
  "query": "怎么配置 OAuth?",
  "top_k": 5,
  "document_ids": ["doc_xxx"]  // 可选，限定搜索范围
}
```

响应：
```json
{
  "results": [
    {
      "content": "OAuth 配置需要在 application.yml 中设置...",
      "score": 0.847,
      "metadata": {
        "filename": "deployment-guide.pdf",
        "page": 12,
        "section": "第三章 认证配置",
        "document_id": "doc_xxx"
      }
    }
  ]
}
```

## 数据模型

### API 侧（metadata RDS）— documents 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) | `doc_` + 12 char |
| tenant_id | VARCHAR(32) | 租户 |
| database_id | VARCHAR(32) | 目标用户 PG |
| filename | VARCHAR(256) | 原始文件名 |
| obs_key | VARCHAR(512) | OBS 路径 |
| format | VARCHAR(16) | PDF / DOCX / MARKDOWN |
| size_bytes | BIGINT | 文件大小 |
| status | VARCHAR(16) | PENDING / PROCESSING / READY / FAILED |
| job_id | VARCHAR(32) | 关联的 Job ID |
| chunks_count | INT | 解析出的 chunk 数量 |
| error | TEXT | 失败原因 |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

索引：`tenant_id`, `database_id`, `status`

### 用户 PG 侧 — knowledge_chunks 表

Job Pod 连接用户 PG，自动建表并写入：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id SERIAL PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops);
```

## Structure-Aware Chunking

利用 Marker 输出的结构化 Markdown，按文档层级自然切分：

**规则**：
1. 按 `#`/`##`/`###` 标题边界切分 section
2. 每个 section 内，如果超过 ~400 tokens，按段落边界继续切分
3. 表格和代码块保持完整，不拆分（即使超长）
4. 每个 chunk 携带元数据：`{filename, page, section_title, chunk_index, format}`
5. 相邻 chunk 有 10-20% 文本重叠（保证语义连续性）

**示例**：
```
# 第一章 OAuth 配置           ← section boundary
## 1.1 获取 Client ID          ← chunk 1 开始
正文段落（200 tokens）...
正文段落（150 tokens）...       ← chunk 1 结束（350 tokens < 400）

## 1.2 配置回调 URL            ← chunk 2 开始
正文段落（300 tokens）...
```python                      ← 代码块，完整保留
def configure():
    ...
```                            ← chunk 2 结束

| 参数 | 说明 |              ← 表格，完整保留为独立 chunk 3
|------|------|
| ...  | ...  |
```

## 搜索 — RRF Fusion

API 通过 proxy 连接用户 PG（自动唤醒 suspended compute），执行：

```sql
WITH semantic AS (
    SELECT id, content, metadata,
           1 - (embedding <=> $query_vector) AS score,
           ROW_NUMBER() OVER (ORDER BY embedding <=> $query_vector) AS rank
    FROM knowledge_chunks
    WHERE ($doc_ids IS NULL OR document_id = ANY($doc_ids))
    ORDER BY embedding <=> $query_vector
    LIMIT 20
),
bm25 AS (
    SELECT id, content, metadata,
           paradedb.score(id) AS score,
           ROW_NUMBER() OVER (ORDER BY paradedb.score(id) DESC) AS rank
    FROM knowledge_chunks
    WHERE content @@@ $query_text
    AND ($doc_ids IS NULL OR document_id = ANY($doc_ids))
    LIMIT 20
)
SELECT COALESCE(s.id, b.id) AS id,
       COALESCE(s.content, b.content) AS content,
       COALESCE(s.metadata, b.metadata) AS metadata,
       COALESCE(1.0/(60+s.rank), 0) + COALESCE(1.0/(60+b.rank), 0) AS rrf_score
FROM semantic s FULL OUTER JOIN bm25 b ON s.id = b.id
ORDER BY rrf_score DESC
LIMIT $top_k;
```

搜索时 API 需要先将 query 文本通过 BGE-M3 生成 query_vector。两种方案：
- **A) API 侧调用 embedding** — API 内嵌 ONNX Runtime 跑 BGE-M3 推理（增加 API 包大小）
- **B) 调用独立 embedding 服务** — 单独部署一个 embedding Pod 提供 HTTP API

MVP 用 **B**：在 CCE 上部署一个常驻的 BGE-M3 serving Pod（sentence-transformers + FastAPI），搜索时 API 调它获取 query vector。这个 Pod 同时也供 Job Pod 使用（Job Pod 通过 HTTP 调 embedding 服务，而不是自己加载模型）。

好处：
- 模型只加载一次（常驻 Pod），不是每个 Job Pod 都加载 ~1.5GB 模型
- 搜索时的 query embedding 延迟低（模型已在内存）
- Job Pod 镜像更小（不含模型权重）

## Job Pod

### 镜像

基于 `python:3.12-slim`，预装：
- marker-pdf（PDF 解析）
- python-docx（DOCX 解析）
- psycopg2-binary（写用户 PG）
- boto3（读 OBS）
- requests（回调 API + 调 embedding 服务）

**不含 BGE-M3 模型**，通过 HTTP 调常驻 embedding 服务。

### 输入（/etc/job/params.json）

```json
{
  "document_id": "doc_a1b2c3d4e5f6",
  "obs_key": "tenant/xxx/knowledge/doc_a1b2.pdf",
  "format": "PDF",
  "database_connstr": "postgresql://user:pass@compute-host:55433/dbname",
  "embedding_service_url": "http://embedding-svc.lakeon.svc.cluster.local:8000/embed"
}
```

### 执行流程

```python
1. 读取 params.json
2. 从 OBS 下载文档到 /tmp/
3. 解析:
   - PDF → marker 解析出 Markdown
   - DOCX → python-docx 提取文本
   - Markdown → 直接读取
4. Structure-Aware Chunking → List[{content, metadata}]
5. 批量调用 embedding 服务 → 每个 chunk 得到 1024 维向量
6. 连接用户 PG:
   - CREATE TABLE IF NOT EXISTS knowledge_chunks ...
   - CREATE INDEX IF NOT EXISTS ...
   - 批量 INSERT chunks
   - 创建 BM25 索引（pg_search）
7. POST callback: {status: SUCCEEDED, result: {chunks_count: N}}
```

### 错误处理

- OBS 下载失败 → callback FAILED
- 解析失败 → callback FAILED + error message
- 用户 PG 连接失败 → callback FAILED（compute 可能未启动，Job 应在提交前确保 compute running）
- Embedding 服务不可达 → callback FAILED
- 部分写入后失败 → 清理已写入的 chunks（DELETE WHERE document_id = ?）再 callback FAILED

## Embedding 服务

常驻 Pod，部署在 CCE 弹性节点池：

```yaml
apiVersion: v1
kind: Pod  # 或 Deployment replicas: 1
metadata:
  name: embedding-svc
  namespace: lakeon
spec:
  nodeSelector:
    lakeon/role: compute
  containers:
    - name: embedding
      image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:latest
      ports:
        - containerPort: 8000
      resources:
        requests: { cpu: "2", memory: "4Gi" }
        limits: { cpu: "2", memory: "4Gi" }
---
apiVersion: v1
kind: Service
metadata:
  name: embedding-svc
  namespace: lakeon
spec:
  selector:
    app: lakeon-embedding
  ports:
    - port: 8000
```

**镜像内容**：python:3.12-slim + sentence-transformers + BGE-M3 模型权重 + FastAPI

**API**：
```
POST /embed
Body: {"texts": ["chunk1 content", "chunk2 content", ...]}
Response: {"embeddings": [[0.023, -0.15, ...], [...]]}
```

批量处理，batch_size=32。

## 组件边界

```
┌─────────────────────────────┐
│  Lakeon API (Java)          │
│  • KnowledgeController      │  ← REST API 端点
│  • KnowledgeService         │  ← 业务逻辑（document CRUD, 提交 Job, 搜索）
│  • DocumentEntity/Repo      │  ← metadata RDS
│  • OBS 预签名 URL 生成      │
│  • 搜索时连接用户 PG         │
└──────────┬──────────────────┘
           │ 提交 Job
           ▼
┌──────────────────────────────┐
│  Job Framework (已实现)       │
│  JobService → JobPodManager  │
└──────────┬───────────────────┘
           │ 创建 Pod
           ▼
┌──────────────────────────────┐
│  Knowledge Job Pod (Python)  │
│  • 下载文档 (OBS)            │
│  • 解析 (Marker/docx/md)     │
│  • Chunking (structure-aware)│
│  • 调 embedding 服务          │  ──→  Embedding Service Pod
│  • 写入用户 PG               │
│  • 回调 API                  │
└──────────────────────────────┘
```

## Phase 2 技术路线（需要 LLM，MVP 不做）

| 技术 | 作用 | 依赖 |
|------|------|------|
| RAPTOR 层次摘要 | L0/L1/L2 分层检索 | LLM 生成摘要 |
| v_inferred chunk 增强 | 补全代词/引用，提升检索质量 | LLM 处理每个 chunk |
| ColBERT 重排序 | 精排 top-K 结果 | 额外模型 |
| LightRAG 知识图谱 | 跨文档实体关联 | 实体抽取 + 图存储 |
| Query Expansion | 多子查询并行搜索 | LLM 改写查询 |
| 增量更新 (hash) | 避免重复处理未变内容 | 文档指纹比对 |
| Docling 解析 | 更好的表格提取 | 替换/补充 Marker |

## 不做的事

- 不做 MCP server（等搜索 API 稳定后再加）
- 不做 Console UI（后面单独设计）
- 不做增量更新（删了重传）
- 不做文档版本管理
- 不做 RAPTOR / v_inferred / ColBERT / GraphRAG（Phase 2）
- 不做 Query Expansion（Phase 2）
