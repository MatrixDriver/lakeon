# mem0-dbay

**Mem0 的一站式存储后端。一个数据库，搞定向量、图谱、全文检索。**

[Mem0](https://github.com/mem0ai/mem0) 是目前最流行的 AI Agent 记忆框架（26k+ GitHub Stars）。但自托管 Mem0 需要同时部署和维护多个数据库。`mem0-dbay` 用一个 [dbay.cloud](https://dbay.cloud) Serverless PostgreSQL 实例满足 Mem0 的全部存储需求。

## 问题：Mem0 自托管太重了

自托管 Mem0 需要 **3 个独立的存储组件**：

```
Mem0 → PostgreSQL + pgvector  （向量存储：embedding 相似度检索）
     → Neo4j + APOC           （图存储：实体关系图谱）
     → SQLite                 （历史记录：记忆变更审计）
```

这意味着：
- 3 套数据库要部署、监控、备份、付费
- 3 套连接配置和凭据管理
- Docker Compose 起 3 个容器，Neo4j 冷启动 90 秒
- 向量用 SQL，图谱用 Cypher，两套查询语言

**大多数开发者只想给 Agent 加记忆，不想当 DBA。**

## 解决方案：一个 dbay.cloud 数据库全搞定

```
Mem0 → dbay.cloud PostgreSQL
         ├── pgvector   （向量存储：embedding 相似度检索）  ✅
         ├── SQL JOIN    （图存储：实体关系图谱）           ✅
         └── pg_search   （全文检索：BM25 关键词搜索）      ✅
```

一个连接串。一个数据库。一种查询语言。零运维。

| 能力 | Mem0 默认方案 | mem0-dbay |
|------|-------------|-----------|
| **向量存储** | PostgreSQL + pgvector | dbay.cloud pgvector |
| **图存储** | Neo4j（单独部署） | 同一个 dbay.cloud 实例 |
| **全文检索** | 无内置 | dbay.cloud pg_search (ParadeDB BM25) |
| **要管几个数据库** | 3 个 | **1 个** |
| **空闲成本** | ~$50+/月（24/7 运行） | **Scale-to-zero，空闲零成本** |
| **搭建时间** | 几小时 | **30 秒** |

## 快速开始

### 1. 在 [dbay.cloud](https://dbay.cloud) 创建数据库

注册 [dbay.cloud](https://dbay.cloud)，30 秒创建一个数据库。pgvector、pg_search 已预装，不需要手动配置任何东西。

### 2. 安装

```bash
pip install mem0-dbay
```

### 3. 使用

```python
import mem0_dbay

# dbay.cloud 连接串（创建数据库时获得）
DBAY_URL = "postgres://user_xxx:password@pg.dbay.cloud:4432/my-mem0-db?sslmode=require&options=endpoint%3Dmy-mem0-db"

m = mem0_dbay.create_memory({
    "graph_store": {
        "provider": "dbay",
        "config": {"connection_string": DBAY_URL, "embedding_dimension": 1536}
    },
    "vector_store": {
        "provider": "pgvector",
        "config": {"connection_string": DBAY_URL, "embedding_model_dims": 1536}
    },
    "llm": {
        "provider": "openai",
        "config": {"api_key": "sk-..."}
    },
    "embedder": {
        "provider": "openai",
        "config": {"api_key": "sk-..."}
    },
})

# 添加记忆 —— 自动提取实体和关系，向量化存储
m.add("Alice 在 Google 当工程师", user_id="alice")
m.add("Bob 是 Alice 在 Google 的上司", user_id="alice")

# 搜索 —— 向量相似度 + 图谱关系扩展 + BM25 重排序
results = m.search("Alice 在哪里工作？", user_id="alice")
```

不需要装 Neo4j，不需要 Docker Compose 起 3 个容器。`pip install` + 一个连接串就行。

## 支持任何 OpenAI 兼容的 LLM

不只是 OpenAI —— 可以用 DeepSeek、硅基流动 SiliconFlow 或任何 OpenAI 兼容的服务：

```python
m = mem0_dbay.create_memory({
    "graph_store": {
        "provider": "dbay",
        "config": {"connection_string": DBAY_URL, "embedding_dimension": 1024}
    },
    "vector_store": {
        "provider": "pgvector",
        "config": {"connection_string": DBAY_URL, "embedding_model_dims": 1024}
    },
    "llm": {
        "provider": "openai",
        "config": {
            "api_key": "sk-...",
            "openai_base_url": "https://api.siliconflow.cn/v1",
            "model": "deepseek-ai/DeepSeek-V3",
        }
    },
    "embedder": {
        "provider": "openai",
        "config": {
            "api_key": "sk-...",
            "openai_base_url": "https://api.siliconflow.cn/v1",
            "model": "BAAI/bge-m3",
        }
    },
})
```

## 为什么选 dbay.cloud？

[dbay.cloud](https://dbay.cloud) 是专为 AI 场景打造的 **Serverless PostgreSQL 平台**。一个实例同时提供向量检索、图谱存储、全文检索 —— Mem0 需要的全部存储能力。

### Scale-to-zero：空闲不花钱

大多数 AI 记忆数据库 90% 的时间都是空闲的。在 dbay.cloud 上，空闲数据库自动休眠，零成本。Agent 查询时 ~500ms 自动唤醒。每个用户一个独立记忆数据库也不是问题 —— 不活跃的不花钱。

### 弹性算力：1cu–8cu 按需伸缩

日常 1cu（1 vCPU / 2 GB）就够了。跑批量记忆反思任务？临时扩到 8cu（8 vCPU / 16 GB），跑完自动缩回。不用改配置、不用重启。

### Git 风格分支：A/B 测试记忆策略

想测试新的记忆反思策略，又不想影响生产数据？

```
main（生产记忆）
  ├── branch: experiment-a  ← 测试新的实体提取 prompt
  └── branch: experiment-b  ← 测试不同的相似度阈值
```

分支是瞬间 copy-on-write —— 零存储开销。对比结果，留下赢家，删掉其余。

### 预装 AI 扩展

- **pgvector** —— 向量相似度检索，HNSW 索引开箱即用
- **pg_search (ParadeDB)** —— BM25 全文检索，比 PostgreSQL 原生 FTS 更强

不需要自己编译安装扩展，创建数据库就能用。

## 技术原理

Mem0 的图谱记忆实际只做很简单的操作 —— 1-hop 邻居查询，不需要 PageRank、最短路径等图算法。这些操作用两张 PostgreSQL 表 + pgvector 就能高效实现：

| Mem0 需要的能力 | Neo4j 方案 | mem0-dbay 方案 |
|---|---|---|
| 存实体 + embedding | Neo4j 节点 + 向量索引 | `graph_nodes` 表 + pgvector |
| 存实体关系 | Neo4j 关系 (Cypher) | `graph_edges` 表 (SQL JOIN) |
| 向量相似搜索 | `vector.similarity.cosine()` | pgvector `<=>` 算子 |
| 查邻居关系 | `MATCH (n)-[r]->(m)` | `JOIN graph_edges ON ...` |
| 全文检索 | 需额外组件 | pg_search BM25 内置 |

向量搜索和图谱遍历在同一个数据库里完成，比跨库方案少一次网络往返，延迟更低。

## 也支持本地 PostgreSQL

如果你更喜欢自托管，`mem0-dbay` 也支持任何装了 pgvector 的 PostgreSQL：

```python
"connection_string": "postgresql://user:pass@localhost:5432/mydb"
```

## 许可

MIT
