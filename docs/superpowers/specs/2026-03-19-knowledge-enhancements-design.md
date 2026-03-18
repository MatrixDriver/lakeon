# Knowledge Enhancements Design — 标签、查询改写、ReRank、数据表知识库

## Overview

为知识库补齐与竞品（火山 Viking、阿里百炼）的功能差距：文档标签过滤、查询改写、ReRank 重排序、数据表知识库。4 个功能互相独立。

---

## 1. 文档标签/元数据过滤

### 数据模型

- `DocumentEntity` 加 `tags` 字段：`List<String>`，RDS 中存为 JSON 字符串
- Flyway 迁移：`ALTER TABLE documents ADD COLUMN IF NOT EXISTS tags TEXT DEFAULT '[]'`

### API 变更

- `POST /api/v1/knowledge/documents/{id}/tags` — 设置标签 `{tags: ["内部", "API"]}`
- `GET /api/v1/knowledge/upload-url` — 新增可选 `tags` 参数，上传时直接打标签
- `POST /api/v1/knowledge/search` — 新增可选 `tags` 参数（`List<String>`）
  - 搜索前先按标签过滤 `document_ids`：`SELECT id FROM documents WHERE kb_id = ? AND tags 包含任一指定标签`
  - 过滤后的 `document_ids` 传入现有向量+BM25 搜索逻辑

### Console 变更

- Documents tab：每个文档行显示标签 badges，支持点击编辑
- 搜索 tab：加标签筛选下拉（多选），搜索时自动带上选中标签
- 文档上传对话框：加可选标签输入

---

## 2. 查询改写

### API 变更

- `POST /api/v1/knowledge/search` — 新增可选 `conversation_history` 参数
  ```json
  {
    "kb_id": "kb_xxx",
    "query": "那超时时间呢？",
    "conversation_history": [
      {"role": "user", "content": "OAuth 怎么配置？"},
      {"role": "assistant", "content": "OAuth 配置需要在..."}
    ]
  }
  ```
- 当 `conversation_history` 非空时，搜索前调 LLM 改写 query

### 实现

- 新增 `QueryRewriteService`（或在 `KnowledgeService.search()` 中内联）
- 复用 `AiSqlService` 的 LLM HTTP 调用基础设施（同一个 OpenAI 兼容 API）
- 模型固定用 `Qwen/Qwen3.5-4B`（免费）
- Prompt：
  ```
  你是查询改写助手。根据对话历史，将用户的最新问题改写为一个独立的、上下文完整的搜索查询。
  只输出改写后的查询，不要解释。

  对话历史：
  {conversation_history}

  用户问题：{query}

  改写后的查询：
  ```
- 改写后的 query 替代原始 query 做 embedding + BM25 搜索
- 返回结果中增加 `rewritten_query` 字段，让调用方知道实际搜索了什么

### Console 变更

- 搜索 tab 从单次搜索改为聊天式交互：
  - 消息列表展示历次 QA（用户问题 + 搜索结果）
  - 输入框在底部，发送后自动带上前几轮 QA 作为 `conversation_history`
  - 搜索结果卡片保持不变（content, score, metadata）
  - 显示 `rewritten_query`（如果与原始 query 不同）

---

## 3. ReRank 重排序

### embedding-service 变更

- 加载 BGE-Reranker-v2-m3 模型（与 BGE-M3 embedding 模型共存）
- 新增 `/rerank` 端点：
  ```
  POST /rerank
  {
    "query": "OAuth 配置",
    "passages": ["文本1", "文本2", ...],
    "top_k": 5
  }
  → {"scores": [0.95, 0.87, ...], "rankings": [0, 3, 1, ...]}
  ```
- 模型大小：BGE-Reranker-v2-m3 约 568MB，embedding-service 内存需求从 ~2GB 增加到 ~4GB

### 搜索流程变更

```
原始 query
  → embedding（现有）
  → 向量召回 top-20 + BM25 召回 top-20（现有）
  → RRF 合并得到候选集（现有）
  → 【新增】ReRank：将候选集的 content + query 发到 /rerank，重排序
  → 返回 top-K
```

### API 变更

- `POST /api/v1/knowledge/search` — 新增可选 `rerank` 布尔参数（默认 `true`）
- 返回结果的 `score` 改为 rerank score（如果 rerank 开启）

### 配置

- `application.yml` 新增 `lakeon.knowledge.rerank` 配置：
  ```yaml
  lakeon:
    knowledge:
      rerank:
        enabled: true
        url: ${LAKEON_RERANK_URL:http://embedding-service:8000/rerank}
  ```
- 支持配置外部 ReRank API URL（替代本地 embedding-service）

---

## 4. 数据表知识库

### 数据模型

- `KnowledgeBaseEntity` 新增字段：
  - `type`：`VARCHAR(16) DEFAULT 'DOCUMENT'` — 枚举 `DOCUMENT`, `TABLE`
  - `tableNames`：`TEXT` — JSON 数组，TABLE 类型关联的表名列表 `["products", "orders"]`
- `KnowledgeBaseType` 枚举：`DOCUMENT`, `TABLE`
- Flyway 迁移加这两个字段

### API 变更

- `POST /api/v1/knowledge/bases` — 新增 `type` 参数（默认 `DOCUMENT`）和 `table_names` 参数
  - TABLE 类型创建时不需要等数据库 provisioning（复用已有数据库的表）
  - TABLE 类型需要指定 `database_id`（关联哪个用户数据库）
- `POST /api/v1/knowledge/search` — TABLE 类型走不同逻辑：
  1. 获取关联表的 schema（`SELECT column_name, data_type FROM information_schema.columns WHERE table_name IN (...)`)
  2. 调 `AiSqlService.generateSql(query, schemaInfo, modelId)`
  3. 在用户 compute 上执行 SQL
  4. 返回 `{type: "sql", sql: "SELECT ...", results: [...], model: "...", tokens: {...}}`
- `GET /api/v1/knowledge/bases/{id}/tables` — 列出关联表的 schema 信息

### Console 变更

- 创建知识库对话框：加类型选择（文档知识库 / 数据表知识库）
  - 选"数据表"时：选择关联数据库 → 勾选要关联的表
- KnowledgeBaseDetail：TABLE 类型不显示"文档"和"切片" tab，替换为：
  - **数据表 tab**：展示关联表名、列信息、行数
  - **查询 tab**：自然语言输入 → 展示生成的 SQL + 查询结果表格
  - 查询 tab 复用搜索 tab 的聊天式交互（带对话历史），但结果展示为表格而非搜索卡片

### 复用

- `AiSqlService.generateSql()` — 已有，直接调用
- `DatabaseQueryService` — 已有，执行 SQL 并返回结果
- `KnowledgeDbHelper.getComputeConnection()` — 已有，获取用户 compute 连接

---

## 实现顺序

4 个功能互相独立，建议按复杂度递增：

1. **标签**（最小改动：1 个字段 + 过滤逻辑 + UI badges）
2. **ReRank**（embedding-service 加端点 + 搜索流程插入一步）
3. **查询改写**（LLM 调用 + Console 搜索 tab 改为聊天式）
4. **数据表知识库**（新类型 + 新 UI + 串联已有 AI SQL）
