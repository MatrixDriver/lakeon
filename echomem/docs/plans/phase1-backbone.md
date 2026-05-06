# Phase 1 · Backbone

> **状态**：已交付（2026-04-30 ~ 2026-05-01）
> **目标**：把云端 dbay 的 Memory API 剥到本地，用 SQLite + sqlite-vec + FTS5 + Ollama 做出一个能跑、能查、零云依赖的本地记忆 store。

---

## 一句话目标

让 `echomem mem ingest "..." && echomem mem recall "..."` 在没外网时也能跑通，并通过 MCP 接入 Claude Code。

## 范围

**做：**
- Memory API：ingest / recall / list / get / delete
- SQLite + sqlite-vec + FTS5 三件套
- Ollama 嵌入（`qwen3-embedding:0.6b`，1024 维）
- MCP shim 给 Claude Code 用
- CLI 入口
- StorageDriver 抽象（为 Phase 5 cloud 做铺垫）

**不做（留给后续 phase）：**
- 衍生物（Phase 2）
- Context API / Blob FS（Phase 3）
- Dashboard（Phase 4）
- 一键 onboarding（Phase 5）

## 交付清单

### 1. 数据层
- [x] m001 迁移：`memory` / `memory_vec` / `memory_fts` 三张基础表
- [x] forward-only `apply_all` 迁移框架（`migrations/__init__.py`）
- [x] StorageDriver Protocol（`drivers/base.py`），含 `Memory.source_kind / source_ref` 字段为 Phase 3 预留
- [x] SQLiteDriver 完整实现（upsert / get / recall / list / delete）

### 2. 检索
- [x] 向量检索（cosine via sqlite-vec）
- [x] FTS5 全文检索
- [x] RRF 混合排名（reciprocal rank fusion）

### 3. Worker 框架
- [x] EmbedderWorker：`memory.upsert` → 异步 embedding → `memory_vec`
- [x] OllamaClient：`/api/embed` + `/api/generate`，`trust_env=False` 绕过系统代理
- [x] 超时 300s + 异步重试

### 4. 接入入口
- [x] HTTP API：`POST /memory/{ingest,recall,delete}` + `GET /memory/{list,get}`
- [x] CLI：`echomem init|start|status|mem ingest|recall|list|get|delete`
- [x] MCP shim：5 个工具（`memory_ingest / recall / list / get / delete`）
- [x] 配置：`~/.echomem/config.toml`（端口、Ollama URL、agent 注册状态）

### 5. 配置 & 工具链
- [x] ULID（`ulid.py`）+ 结构化日志（`logging.py`）
- [x] `pyproject.toml`：`echomem`、`echomem-mcp-shim` 两个入口

## 关键技术决策

| 决策 | 理由 |
|------|------|
| **SQLite + sqlite-vec** 而非独立 vector DB | 单文件、单进程、`cp` 即备份；本地 demo 不需要 Postgres |
| **FTS5 配 RRF** 而非纯向量 | 短查询和精确词查询纯向量召回差；混合排名兼顾两端 |
| **Ollama HTTP** 而非 in-process 模型 | 模型生命周期由 Ollama 管理，daemon 重启不重新加载；同事可以共享同一份 ollama |
| **StorageDriver Protocol** | 业务代码只见接口，未来切到 PG 或 cloud 不动上层 |
| **MCP shim 单独二进制** | Claude Code 通过 stdio 启动，shim 内部转 HTTP 调 daemon。daemon 死了不影响其他客户端（HTTP / CLI）|
| **trust_env=False on httpx** | 系统级 HTTP_PROXY 会捕获 localhost 流量；Ollama 走 127.0.0.1 必须绕开 |

## 验收

- [x] 83 个测试通过（unit + integration）
- [x] e2e（`ECHOMEM_E2E=1`）：CLI ingest → Ollama embed → SQLite 落地 → recall 命中
- [x] Ollama 离线时 ingest 不失败（worker 异步重试，最终进 dead_letter）
- [x] MCP 工具能被 Claude Code 发现并调用
- [x] `echomem status` 能正确报告 daemon + Ollama 状态

## 后续修复（已合入）

- `7d5e4b4e` — `OllamaClient trust_env=False` — 绕过系统 HTTP 代理
- `a3c54258` — Ollama 超时 300s + WorkerPool concurrency=1（先稳后调）
- `200da94b` — `query_subgraph` BFS 跨 hop 边去重

---

## 阅读延伸

- [架构图](/architecture/) — 分层结构与数据流
- [Phase 2 · Derivatives](/docs-viewer/#/plans/phase2-derivatives) — 衍生物 pipeline，下一阶段
- [echomem README](https://github.com/jackylk/lakeon)（仓内）— 安装与开发指引
