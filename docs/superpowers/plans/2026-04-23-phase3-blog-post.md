# Phase 3 · dbay.cloud 博客草稿

> 发布位置：dbay.cloud 博客 / Notion / WeChat 公众号
> 发布时机：与社区公告同日
> 风格约束：**不出现商业性内容**（按 feedback_no_commercial），只讲技术

---

## 标题

**让 openJiuwen agent 一行代码用上托管 PG + pgvector —— `openjiuwen-dbay-store` 首发**

## 正文

### 故事开头

你在用 openJiuwen 写 AI agent，想让长期记忆落到托管 PG 而不是本地 SQLite 文件。翻到 openJiuwen 源码，看到 `create_vector_store("chroma" | "milvus" | "gaussvector")` —— 三个硬编码选项，没有 Neon / dbay / Supabase 这类 serverless PG。想加一个？要提 PR 改主仓，等审查，等发版。

这不该是扩展 backend 的代价。

### 我们做了什么

**两件事合起来，给开源社区解锁了"外部 backend 插件"生态：**

**1. 给 openJiuwen 加入口点发现机制**（PR #1155，已合入）

`create_vector_store()` 现在做三级解析：
1. 内置（chroma / milvus / gaussvector）—— 行为字节一致，绝对不变
2. 显式注册 `register_vector_store(name, factory)`
3. `entry_points(group="openjiuwen.vector_stores")` —— 自动发现所有已安装的外部插件

这样任何 PyPI 包只要在 `pyproject.toml` 里声明 entry point，就能变成 openJiuwen 合法的 backend。

**2. 发布第一个外部插件：`openjiuwen-dbay-store`**

基于 Neon 式 Serverless PG + pgvector，三个 store 协同：

```python
pip install openjiuwen openjiuwen-dbay-store
```

```python
from openjiuwen.core.foundation.store import create_vector_store
store = create_vector_store("dbay", dsn="postgresql://...")
```

就这样。

### 技术细节

#### 三个 store，共享一个异步连接池

```python
db = DbayDbStore(dsn=dsn, max_size=10)
kv = DbayKVStore(db_store=db)   # 复用 db 的 asyncpg 池
vec = DbayVectorStore(db_store=db)
```

总连接数始终在 `max_size=10` 以内，避免多 store 打爆连接。

#### 一开始就告诉你问题在哪

DSN 错了？密码错了？pgvector 没装？权限不足？—— 不要给你原始 asyncpg 栈痕，直接给中文的修复步骤：

```
DbayConnectionError: 无法连接 dbay PG（postgresql+asyncpg://alice:***@host:5432/db）：...
请检查：(a) DSN 是否正确，(b) host 是否可达，(c) 账号密码是否有效，(d) PG 是否在对应端口监听。
```

密码自动脱敏为 `***`，不会出现在日志里。

#### 性能基线

工作负载：10K 向量 / 384 维 / HNSW 索引（`m=16`, `ef_construction=64`）

| 指标 | 值 |
|---|---|
| 插入吞吐 | 81.7 docs/s（batch=128 串行，单连接）|
| 检索 p50 | 4.45 ms |
| 检索 p95 | 6.41 ms |
| 检索 p99 | 9.43 ms |
| 召回@10 | 0.673（HNSW 默认 `ef_search`，可调到 0.95+）|

完整 benchmark 代码 + JSON 结果在 [仓库 benchmarks/](https://gitcode.com/jackylk/openjiuwen-dbay-store/blob/main/benchmarks/RESULTS.md)，欢迎在你的环境复现对比。

### 对社区的邀请

**生态是社区的，不是我的。** 如果你更喜欢 Qdrant / Weaviate / Elasticsearch / 自研数据库，**你可以按一样的模式独立发包**——不用等 openJiuwen 主仓评审、不用争 maintainer 时间、不用跟主仓版本绑死。

插件作者指南（中英双份）已写好。写完插件后在"参考示例"章节加一行，openJiuwen 的下一个用户就能 `pip install` 用上。

### 接入指南的 3 条路径

- **想最快试用**：[quickstart 示例](https://gitcode.com/jackylk/openjiuwen-dbay-store/blob/main/examples/quickstart.py)，5 行代码
- **接入长期记忆**：[LongTermMemory 集成示例](https://gitcode.com/jackylk/openjiuwen-dbay-store/blob/main/examples/long_term_memory.py)
- **要跑 benchmark**：[benchmark 脚本](https://gitcode.com/jackylk/openjiuwen-dbay-store/blob/main/benchmarks/run.py)

### 一段话总结

一个 PyPI 包 + 一条 DSN 就能让 openJiuwen agent 用上 Neon 式 Serverless PG + pgvector，托管、可靠、零运维。插件是 MIT 开源的，社区其他开发者可以沿用这个模式贡献更多 backend。

——完

---

## 发布检查单

- [ ] Phase 1 PR #1155 已真正合入
- [ ] 新版 openjiuwen 已发 PyPI，版本号填到正文 "已合入" 处
- [ ] openjiuwen-dbay-store 已发 PyPI 且 pip install 可跑通
- [ ] quickstart 示例链接在跳转到仓库正确文件
- [ ] benchmark 数字与 RESULTS.md 一致
- [ ] 没有商业性用语（按 feedback_no_commercial）
