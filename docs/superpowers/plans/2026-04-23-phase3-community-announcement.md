# Phase 3 · 社区公告草稿

> 待发布位置：openJiuwen-ai/community（或社区指定的公告入口，例如 discussions / issue）
> 发布时机：Phase 1 MR #1155 合入 + 新版 openjiuwen 上 PyPI 后
> 发布前要改：
>   - 把 "PR #1155 已合入" 的措辞改成最终合入 commit / release note 链接
>   - 替换版本号占位 `<PHASE_1_VERSION>` 为真实版本
>   - benchmark 数字已填真实数据，无需改

---

## 标题

**🎉 社区第一个外部存储后端插件：openjiuwen-dbay-store**

## 正文

### 背景

在 [PR #1155](https://gitcode.com/openJiuwen/agent-core/merge_requests/1155) 里，openJiuwen 加入了基于 Python 标准 `entry_points` 的**插件发现机制**，并提供了公共 API `register_vector_store(name, factory)`。这意味着：

**社区开发者可以把新的存储后端作为独立 PyPI 包发布，不用把代码合进 openJiuwen 主仓。**

`openjiuwen-dbay-store` 是第一个按这个模式发布的外部插件，提供 **Neon Serverless PostgreSQL + pgvector** 作为存储后端——兼容任何装了 `vector` 扩展的 PG 实例（Neon / dbay.cloud / Supabase / 自建 PG 都可以）。

### 快速试用

```bash
pip install openjiuwen>=<PHASE_1_VERSION> openjiuwen-dbay-store
```

```python
from openjiuwen.core.foundation.store import create_vector_store

store = create_vector_store("dbay", dsn="postgresql://user:pass@host:5432/db")
# store 就是 DbayVectorStore 实例，可以直接 create_collection / add_docs / search
```

接入 `LongTermMemory`（一个 DbayDbStore 共享连接池）：

```python
from openjiuwen_dbay_store import DbayDbStore, DbayKVStore, DbayVectorStore
from openjiuwen.core.memory import LongTermMemory

db = DbayDbStore(dsn="postgresql://...", max_size=10)
await db.ping()  # DSN 错会立刻抛带修复提示的错误

memory = LongTermMemory()
await memory.register_store(
    kv_store=DbayKVStore(db_store=db),
    vector_store=DbayVectorStore(db_store=db),
    db_store=db,
)
```

### 亮点

- ✅ **零侵入** —— 不需要改 openJiuwen 主仓一行代码，通过 entry_points 自动注册
- ✅ **完整契约** —— 实现 `BaseVectorStore` / `BaseKVStore` / `BaseDbStore` 全部方法
- ✅ **共享连接池** —— 三个 store 共用一个 `DbayDbStore` 的 asyncpg 连接池，总连接数可控
- ✅ **友好错误** —— DSN 错 / pgvector 缺失 / 权限不足都翻译成可操作的中文提示，密码自动脱敏
- ✅ **测试充分** —— 36 单元测试 + 19 E2E 测试（对真实 pgvector）
- ✅ **性能基线公开** —— 10K 向量 / 384 维 / HNSW 默认参数：**p50 检索 4.45ms**，完整 benchmark 数据见仓库

### 链接

- 📦 代码仓：https://gitcode.com/jackylk/openjiuwen-dbay-store
- 📖 插件作者指南（中英）：[zh](https://gitcode.com/openJiuwen/agent-core/blob/develop/docs/zh/2.开发指南/高阶用法/插件开发-存储后端.md) / [en](https://gitcode.com/openJiuwen/agent-core/blob/develop/docs/en/2.Development Guide/Advanced Usage/Store Plugin Development.md)

### 欢迎更多 backend 插件加入生态

按插件作者指南的模式，你可以：
- 发布自己的 Qdrant / Weaviate / Elasticsearch / GaussDB / 自研数据库 插件
- 独立 release cadence，不受 openJiuwen 主仓版本约束
- PyPI 直接安装，用户体验与内置 backend 一致

如果你做了插件，欢迎在 [插件作者指南](link) 的 "参考示例" 章节加一行。

---

## 发布后动作

- [ ] 在 openJiuwen community 贴公告
- [ ] 在 `agent-core/docs/zh/.../插件开发-存储后端.md` 的 "参考示例" 章节 PR 加一行链接
- [ ] 发 dbay.cloud 博客（正文草稿见 `2026-04-23-phase3-blog-post.md`）
- [ ] 在 jiuwenclaw 群 / openJiuwen 讨论区转一遍
