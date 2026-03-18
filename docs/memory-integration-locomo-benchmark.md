# 记忆系统集成 & LoCoMo Benchmark 复现计划

> 2026-03-18 调研结论

## 背景

目标：让用户在获得记忆系统 token 下降好处的同时，获得 dbay.cloud Serverless PG 的弹性、免运维、scale-to-zero 好处。

## 竞品 LoCoMo 基准对比

| 系统 | LoCoMo 准确率 | Token 下降 | 存储后端 | 能接 dbay？ |
|------|-------------|-----------|---------|-----------|
| OpenClaw baseline (memory-core) | 35.65% | — | SQLite + sqlite-vec | 不能 |
| Mem0 | 66.9% | 90% | 向量+图 (Neo4j) | 不能 |
| OpenViking (字节跳动) | 52.08% | 83% | 自研 C++ + LevelDB + AGFS | 不能 |
| MemOS | — (72% token 下降) | 72% | **PG+pgvector 后端已有** | **可以** |
| Hindsight | 89.61% | recall 零 LLM 成本 | 内嵌 PG | 待评估 |
| ZhiXing (我们) | 82分 | 待测 | PG+pgvector | 原生 |

## 测试方法差异

### OpenViking 的方法（在 OpenClaw 里跑）

工具：[ZaynJarvis/openclaw-eval](https://github.com/ZaynJarvis/openclaw-eval)

```bash
# 1. Ingest — 按时间顺序回放对话到 OpenClaw，积累记忆
uv run python eval.py ingest ./locomo10.json --sample 0 --sessions 1-4

# 2. QA — 通过 OpenClaw 提问，记录回答
uv run python eval.py qa ./locomo10.json --sample 0 --user <UUID>

# 3. Judge — LLM 评判回答质量 vs 标准答案
uv run python eval.py judge ...
```

- 端到端在 OpenClaw 里跑，token 从 LLM API `usage.input_tokens` 读取
- 数据集：`locomo10.json`（10 段对话，272 session，1540 QA 对）

### MemOS 的方法（独立管线，不走 OpenClaw）

代码：`MemTensor/MemOS` 仓库 `evaluation/scripts/locomo/` 目录

```
locomo_ingestion.py  → 灌对话到 MemOS
locomo_search.py     → 对每个问题检索相关记忆上下文
locomo_responses.py  → 拿检索结果直接问 OpenAI API 生成回答
locomo_eval.py       → GPT-4o-mini judge + NLP 指标 (ROUGE/BLEU/BERTScore)
locomo_metric.py     → 汇总分数
```

- token 计量：tiktoken `cl100k_base` 计数检索返回的 context 长度
- 可对比多个后端：memos-api, zep, mem0, memobase, supermemory

## 路径 A：MemOS + dbay（当前执行）

### 架构

```
locomo10.json → MemOS evaluation pipeline → MemOS API → dbay.cloud (Serverless PG + pgvector)
                                                         ↑
                                                    scale-to-zero
                                                    免运维
                                                    弹性扩缩
```

### MemOS PG 后端现状

文件：`src/memos/graph_dbs/postgres.py`（976 行）

**可用**：
- `tree_text` 记忆类型只用 graph_db，不需要 Qdrant/Milvus
- 纯标准 SQL + pgvector `<=>` 余弦距离
- psycopg2 连接池 + 健康检查 + TCP keepalive
- 完全兼容 Neon/dbay

**需补齐的 4 个方法（blocker）**：

| 方法 | 用途 | 实现方案 |
|------|------|---------|
| `search_by_fulltext()` | 全文检索（默认搜索路径） | PG 原生 `tsvector/tsquery` |
| `get_subgraph()` 签名对齐 | 当前返回 `list[str]`，需返回 `{core_node, neighbors, edges}` dict | 参考 PolarDB 实现 |
| `delete_node_by_prams()` | 按条件删除节点 | SQL DELETE + WHERE |
| `drop_database()` | 清理 | `DROP SCHEMA CASCADE` |

**调优项**：
- IVFFlat → HNSW（serverless 冷启动更优）
- 连接池 minconn 2→1, maxconn 20→5-10
- 考虑 Neon suspend/resume 后连接池全部失效的场景

### 执行步骤

1. Fork MemOS，补齐 PG 后端 4 个方法
2. 在 dbay.cloud 创建数据库，启用 pgvector
3. 配置 MemOS `tree_text` 后端指向 dbay
4. 用 MemOS 自带的 `evaluation/scripts/locomo/` 跑 `locomo10.json`
5. 记录准确率 + context_tokens，与 MemOS 官方结果对比
6. 验证 dbay scale-to-zero 行为（灌入后休眠，查询时唤醒）

### 期望结果

- token 下降 ≥ 72%（与 MemOS 官方一致）
- 准确率与 MemOS 官方一致（证明 dbay 后端无损）
- 附加价值：scale-to-zero 零成本 + 免运维

## 路径 B：OpenClaw + MemOS 插件 + dbay（后续）

### 架构

```
openclaw-eval → OpenClaw → MemOS OpenClaw Plugin → MemOS API → dbay.cloud
                  ↑                                               ↑
             locomo10.json                                   Serverless PG
```

### 步骤

1. 路径 A 完成后，MemOS + dbay 后端已验证
2. 部署 MemOS API（指向 dbay）
3. 安装 MemOS-Cloud-OpenClaw-Plugin 到 OpenClaw
4. 用 `openclaw-eval` 跑 `locomo10.json`
5. 与 OpenViking 的结果直接对比（同工具、同数据集）

### 价值

- 端到端证明：OpenClaw agent 使用 MemOS+dbay 后 token 下降 ≥ 72%
- 与 OpenViking 83% 可直接对比
- 更有说服力的集成故事

## 路径 C：OpenViking + dbay（探索性）

### 可行性

OpenViking 存储完全自包含（C++ 向量引擎 + LevelDB + AGFS），不支持 PG。

唯一集成点：`http` 类型的 VectorDB 后端。可以构建一个 HTTP adapter 服务：

```
OpenViking → HTTP VectorDB protocol → Adapter Service → dbay.cloud (pgvector)
```

### 工作量

- 需要逆向 OpenViking 的 HTTP VectorDB 协议
- 构建 adapter 服务（翻译 OpenViking 查询 → pgvector SQL）
- AGFS 内容存储仍在本地，只有向量索引走 dbay
- 投入大，收益不确定，优先级低

### 何时考虑

- OpenViking 社区活跃度高、用户多时
- 或 OpenViking 未来原生支持 PG 后端时

## 附录：LoCoMo10 数据集

- 来源：[snap-research/locomo](https://github.com/snap-research/locomo)
- 10 段长对话，272 session，5882 条消息，910 张图片
- 1986 QA 对（去掉 category5 后 ~1540 个）
- 问题类型：单跳、多跳、时序、常识、对抗性
- 每段对话模拟 6-12 个月交互，~300 轮、~9K tokens
