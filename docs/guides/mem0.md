# Mem0 + dbay.cloud：一个数据库搞定向量 + 图谱

[Mem0](https://github.com/mem0ai/mem0) 是最流行的 AI Agent 记忆框架（26k+ GitHub Stars）。但自托管 Mem0 需要同时部署和维护多个数据库。使用 [mem0-dbay](https://pypi.org/project/mem0-dbay/) 插件，一个 dbay.cloud 数据库替代全部。

## 为什么？

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

## 用 dbay.cloud 之后

```
Mem0 → dbay.cloud PostgreSQL
         ├── pgvector   （向量存储：embedding 相似度检索）  ✅
         ├── SQL JOIN    （图存储：实体关系图谱）           ✅
         └── pg_search   （全文检索：BM25 关键词搜索）      ✅
```

一个连接串。一个数据库。一种查询语言。零运维。

| 能力 | Mem0 默认方案 | mem0-dbay + dbay.cloud |
|------|-------------|----------------------|
| **向量存储** | PostgreSQL + pgvector | dbay.cloud pgvector |
| **图存储** | Neo4j（单独部署） | 同一个 dbay.cloud 实例 |
| **全文检索** | 无内置 | pg_search (ParadeDB BM25) |
| **要管几个数据库** | 3 个 | **1 个** |
| **空闲成本** | ~$50+/月（24/7 运行） | **Scale-to-zero，空闲零成本** |
| **算力弹性** | 固定规格 | **1cu–8cu 按需伸缩** |
| **数据库分支** | 不可能 | **Git 风格 copy-on-write 分支** |
| **搭建时间** | 几小时 | **30 秒** |

### Scale-to-zero：空闲不花钱

大多数 AI 记忆数据库 90% 的时间是空闲的。dbay.cloud 上空闲数据库自动休眠，零成本。Agent 查询时 ~500ms 自动唤醒。每个用户一个独立记忆数据库也不是问题——不活跃的不花钱。

### 弹性算力：批处理轻松扩容

日常 1cu 就够了。跑批量记忆反思任务？临时扩到 8cu，跑完自动缩回。不用改配置、不用重启。

### Git 风格分支：A/B 测试记忆策略

想测试新的记忆提取策略？分支是瞬间 copy-on-write，零存储开销。对比结果，留下赢家，删掉其余。

## 快速开始

### 1. 创建 dbay.cloud 数据库

在 [dbay.cloud](https://dbay.cloud) 注册并创建数据库，获取连接串：

```
postgres://user_xxx:password@pg.dbay.cloud:4432/my-mem0?sslmode=require&options=endpoint%3Dmy-mem0
```

### 2. 安装

```bash
pip install mem0ai mem0-dbay
```

### 3. 使用

```python
import mem0_dbay

DBAY_URL = "postgres://user_xxx:password@pg.dbay.cloud:4432/my-mem0?sslmode=require&options=endpoint%3Dmy-mem0"

m = mem0_dbay.create_memory({
    "graph_store": {
        "provider": "dbay",
        "config": {
            "connection_string": DBAY_URL,
            "embedding_dimension": 1536,   # 按你的 embedding 模型调整
        }
    },
    "vector_store": {
        "provider": "pgvector",
        "config": {
            "connection_string": DBAY_URL,
            "embedding_model_dims": 1536,
        }
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

# 添加记忆
m.add("Alice 在 Google 当工程师，喜欢 Python", user_id="alice")

# 搜索
results = m.search("Alice 在哪里工作？", user_id="alice")
```

表会在首次使用时自动创建，不需要手动建表。

### 使用国产 LLM（SiliconFlow / DeepSeek）

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

注意：使用 bge-m3 时 embedding_dimension 设为 1024。

## 参考

- [mem0-dbay PyPI](https://pypi.org/project/mem0-dbay/)
- [Mem0 文档](https://docs.mem0.ai/)
- [dbay.cloud](https://dbay.cloud)
