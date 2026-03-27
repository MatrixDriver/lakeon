# DBay REST API 参考

## 认证

所有 API 请求需要在 Header 中携带 API Key：

```
Authorization: Bearer lk_your_api_key
```

## 记忆库 API

### POST /api/v1/memory/bases/{id}/ingest

将对话和事实存入长期记忆。

**请求体：**
```json
{
  "messages": [
    {"role": "user", "content": "我喜欢用 Python 写后端"},
    {"role": "assistant", "content": "好的，我记住了您偏好使用 Python"}
  ]
}
```

**响应：**
```json
{
  "status": "ok",
  "memories_created": 2
}
```

### POST /api/v1/memory/bases/{id}/recall

混合检索记忆，结合向量搜索、BM25 和知识图谱。

**请求体：**
```json
{
  "query": "用户的编程语言偏好",
  "top_k": 5
}
```

**响应：**
```json
{
  "memories": [
    {
      "content": "用户偏好使用 Python 写后端",
      "memory_type": "fact",
      "relevance": 0.92,
      "created_at": "2026-03-15T10:30:00Z"
    }
  ]
}
```

### POST /api/v1/memory/bases/{id}/digest

分析累积的记忆，生成用户画像和行为模式。

## 知识库 API

### POST /api/v1/knowledge/bases

创建知识库。

**请求体：**
```json
{
  "name": "产品文档",
  "description": "公司产品的技术文档",
  "type": "DOCUMENT"
}
```

### POST /api/v1/knowledge/bases/{id}/search

搜索知识库。

**请求体：**
```json
{
  "query": "如何配置 MCP",
  "top_k": 3
}
```

## 数据库 API

### POST /api/v1/databases

创建 Serverless PostgreSQL 数据库。

### GET /api/v1/databases/{id}

获取数据库详情和连接信息。

### POST /api/v1/databases/{id}/query

执行 SQL 查询。

**请求体：**
```json
{
  "sql": "SELECT * FROM users LIMIT 10"
}
```

## 数据湖 API

### POST /api/v1/datalake/jobs

提交数据处理作业。

**请求体：**
```json
{
  "name": "data-analysis",
  "type": "PYTHON",
  "entrypoint": "python main.py",
  "resources": {
    "cpu": "1",
    "memory": "2Gi"
  }
}
```
