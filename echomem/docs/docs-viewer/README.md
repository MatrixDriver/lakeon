# echomem 文档中心

> **echomem** — 本地优先的 Agent 记忆中枢。让 Claude Code、openclaw、hermes 共享同一份"会反思"的本地大脑。

这是 echomem 的对外文档站点。左侧 sidebar 列出所有可读文档，markdown 实时渲染。

---

## 第一次来？建议这样读

| # | 文档 | 时长 | 备注 |
|---|---|---|---|
| 1 | **[架构图](/architecture/)** | 5 min | 视觉化地理解分层结构 |
| 2 | **[项目概览](overview/)** | 8 min | 动机、范围、设计原则、与 dbay 的关系 |
| 3 | **[路线图](/roadmap/)** | 5 min | 五阶段时间线与状态 |
| 4 | [Phase 1 · Backbone](plans/phase1-backbone) | 10 min | Memory store + API 已交付细节 |
| 5 | [Phase 2 · Derivatives](plans/phase2-derivatives) | 10 min | 衍生物 pipeline 已交付细节 |
| 6 | [Phase 3 · Context API](plans/phase3-context-api) | 10 min | 进行中阶段的规划 |

通读约 50 分钟。

---

## 关键概念速查

| 概念 | 一句话 |
|---|---|
| **echomem daemon** | 本地 FastAPI 进程，监听 127.0.0.1:8473，三家 Agent 共用 |
| **StorageDriver** | 抽象层。SQLite / PostgreSQL / Cloud 三种实现共享同一接口 |
| **衍生物（Derivative）** | Memory 之上的视图：时间流 / 树（L0/L1/L2）/ 图（三元组）/ 程序性（skill）|
| **Worker pool** | asyncio 队列 + 重试 + dead_letter 兜底，所有衍生物异步生成 |
| **Blob FS** | `~/.echomem/blobs/<sha256>` 内容寻址存储（Phase 3）|
| **path_alias** | 用户友好路径 → blob hash 映射（Phase 3）|
| **MCP shim** | 给 Claude Code 走 stdio 的薄壳，内部转 HTTP 调 daemon |
| **CloudDriver** | echomem ↔ 云端 dbay 双向挂载（Phase 5+ 才打通）|

---

## 与同类项目的关系

| 项目 | echomem 的差异 |
|---|---|
| **dbay (云端)** | echomem 是它的本地近亲。schema 同源、API 概念一致、CloudDriver 可双向挂载。**dbay 解决"持久化与跨设备"，echomem 解决"本地 demo + 跨 Agent + 衍生物本地化"** |
| **mem0 / Letta** | 这些偏服务化记忆框架，存储抽象通常绑定一种后端。echomem 的核心卖点是"本地优先 + 同源云端 + 多 Agent 共享" |
| **chromadb / sqlite-vec** | 是 echomem 的底层组件之一，但 echomem 还做衍生物提取、Skill 索引、上下文 API 这些组件级服务 |

---

## 反馈

文档在 `lakeon/echomem/docs/`。看到错误或想讨论某个决策——直接在 PR 里 comment，或微信找 jacky。

*文档由 Docsify 渲染 · 2026-05-06*
