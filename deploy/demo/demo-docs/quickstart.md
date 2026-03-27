# DBay 快速入门

## 什么是 DBay

DBay（数据港湾）是 Agent 时代的 Serverless 数据基础设施，提供数据库、知识库、记忆库、AI 数据湖四大产品模块。

## 核心产品

### Lakebase — Serverless PostgreSQL
- 秒级冷启动，空闲自动休眠
- 按需弹性伸缩，零运维
- 内置 pgvector 向量搜索
- 数据库分支与时间旅行

### 知识库
- 文档自动解析（PDF / Word / Markdown）
- 向量 + 全文混合检索
- 内置 Embedding 与 Reranker

### 记忆库
- AI Agent 长期记忆引擎
- 事实 / 事件 / 特征 / 文档四种记忆类型
- ingest · recall · digest 三个核心 API
- MCP 协议接入，5 分钟集成

### AI 数据湖
- Python / Ray 分布式任务调度
- 数据导出与模型微调
- DB ↔ 数据湖数据飞轮

## 5 分钟接入

### 步骤 1：安装 CLI

```bash
pip install dbay-cli
dbay login
```

### 步骤 2：创建数据库

```bash
curl -X POST https://api.dbay.cloud:8443/api/v1/databases \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-first-db"}'
```

### 步骤 3：连接数据库

```python
import psycopg2
conn = psycopg2.connect("postgres://user:pass@dbay.cloud:5432/mydb")
```

### 步骤 4：接入 MCP（Claude Code）

```bash
claude mcp add --scope user dbay -- uvx dbay-mcp
```

配置完成后，Claude Code 即可通过 MCP 访问你的知识库和记忆库。
