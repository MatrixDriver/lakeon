# Phase 2 · Derivatives

> **状态**：已交付（2026-05-01 ~ 2026-05-06）
> **目标**：每条 ingest 触发后台 worker，在同一份 store 上"长出"4 种衍生物——时间流、分层摘要、实体关系图、程序性 skill。Agent 检索时按上下文需要切换查询视角。

---

## 一句话目标

让 ingest 完一段文本后，timeline / tree / graph / skills 这四个端点都能基于本地小模型自动产出有用结果，不靠云。

## 设计前提

记忆"原文"已经躺在 Phase 1 的 SQLite 里了。衍生物**不是替代记忆，是同一份 store 之上的多副视图**——按时间组织、按层级组织、按图组织、按可执行性组织。

## 交付清单

### 1. 数据层
- [x] m002 迁移：9 张衍生物表
  - `derivative_event`（时间流 episode）
  - `derivative_summary`（L0 / L1 / L2 三级摘要）
  - `derivative_entity` + `derivative_triple`（图）
  - `derivative_triple_pending`（置信度 < 0.7 的待审）
  - `derivative_skill` + `skill_vec`（程序性记忆 + 检索向量）
  - `derivative_task`（任务派生）
  - `dead_letter`（worker 失败兜底）
- [x] SQLiteDriver 8 个 derivative CRUD 方法
- [x] `query_subgraph` BFS 边去重（修复重复返回的 bug）

### 2. Workers（4 active + 1 placeholder）
- [x] **SummarizerWorker** — gemma 生成 L0 (≤100 tok) / L1 (≤500 tok)，L2 是原文。短文本自动跳过 L1，超时进 truncate-prefix 兜底
- [x] **EntityExtractorWorker** — gemma 抽 (subject, predicate, object) 三元组。conf ≥ 0.7 入图，否则进 pending
- [x] **TimelineWorker** — 纯 Python：同 agent + 30 min 内 + 主题 cosine ≥ 0.7 → 同一个 event；否则开新 event。无 LLM 调用
- [x] **ReflectorWorker** — 占位（commit `9e8ae35a` 明确标记 placeholder，P3 之后再 wire LLM 做反思）

### 3. WorkerPool 编排
- [x] `asyncio.Queue` + 3 次重试 + dead_letter 兜底
- [x] `concurrency=1`（先稳后调，避免 Ollama 串行模型被并发饿死）
- [x] Orchestrator：bind 3 worker + `on_memory_ingested` hook + daemon lifespan wiring

### 4. API
- [x] `GET /derivatives/timeline?agent=...`
- [x] `GET /derivatives/tree?source_kind=memory&source_ref=...`
- [x] `GET /derivatives/graph?seed=ent:xxx&hops=2`
- [x] `GET /derivatives/skills?ctx=writing+a+test`
- [x] `POST /skills/import`（扫目录把 frontmatter 灌入 derivative_skill）

### 5. Skill 导入
- [x] 扫 `~/.claude/skills/**/*.md` 的 frontmatter
- [x] description 做 embedding → `skill_vec` 索引
- [x] 按调用上下文 cosine 检索 → surface 候选 skill

## 关键技术决策

| 决策 | 理由 |
|------|------|
| **TimelineWorker 不调 LLM** | 时窗 + 主题 cosine 已经够用；少一个外部依赖、少一类失败 |
| **三元组分两表（active / pending）** | 小模型置信度不稳，污染一次主图代价高。pending 可以人工 promote 或用更大模型重抽 |
| **Skill 进衍生物表，不单独建模块** | "可执行的记忆"还是记忆。统一抽象避免重复轮子 |
| **WorkerPool concurrency=1** | Ollama 单模型串行；并发 worker 抢同一个模型只会变慢。先稳后调 |
| **dead_letter 兜底** | 失败任务可见、可重放、不悄悄丢；调试代价可控 |

## 验收

- [x] e2e：ingest 一段对话 → 几秒内可查到 summary / event / triple
- [x] `query_subgraph` 跨 hop 边去重（commit `200da94b`）
- [x] Ollama 慢/超时不阻塞下一条 ingest（异步队列 + 死信）
- [x] Skill import 后 `/derivatives/skills?ctx=...` 能 cosine 命中

## 已知边界

- **ReflectorWorker 还是占位**——这是 plan 里就预期的，P3 之后再 wire
- **L0/L1 质量依赖 gemma**——小模型偶尔产出"机器味"摘要；置信度低不入主表的兜底机制还没引入到 summary 上（只在 triple 上有）
- **Timeline 30 min 窗口写死**——之后要做成 per-agent 配置

## 后续修复（已合入）

- `200da94b` — `query_subgraph` 边去重
- `d4b84e4f` — Summarizer 短文本跳 L1 + worker 错误日志增强
- `a3c54258` — Ollama 超时 300s + WorkerPool concurrency=1

---

## 阅读延伸

- [架构图](/architecture/) — 衍生物在分层中的位置
- [Phase 1 · Backbone](/docs-viewer/#/plans/phase1-backbone) — 衍生物的 store 基础
- [Phase 3 · Context API](/docs-viewer/#/plans/phase3-context-api) — 给衍生物 pipeline 喂 URL/PDF/repo
