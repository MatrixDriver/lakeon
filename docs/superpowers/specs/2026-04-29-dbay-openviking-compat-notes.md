---
status: brainstorming-notes
date: 2026-04-29
topic: dbay 兼容 OpenViking — 中期路线调研笔记
next_step: 等同事确认 Hyper Mem 对 OpenViking 的依赖形态后，转正式 design spec
---

# dbay 兼容 OpenViking 调研笔记

## 背景

同事 Jacky 同事在用火山引擎开源的 **OpenViking** (`~/code/openviking`, AGPLv3, Python+Rust+C++)
作为华为内部产品 sprint **Hyper Mem** 的底座。希望评估能否用 dbay 替代。

PPT 里圈的关注点几乎覆盖整个 OpenViking 架构图：

- Service Layer（ls/read + search/find + 会话 commit + add/import 资源技能 + health）
- Context Types：Session（对话 + 抽 memory）/ Skill（可调用能力）/ Resource（外部知识）
- Context Layers：L0/L1/L2 渐进披露
- VikingFS：VectorDB + AGFS（Rust 自研）
- Persistence：VectorDB + S3FS（明确不要 LocalFS — 端云共用，但落盘云上）

左侧三条选型原则：**结构化 / 分层 / 端云共用代码架构**。

## 选型

**Hyper Mem 是 Q3/Q4 上线**（中期项目，选项 B），分阶段：

- **B1 — Compat 兼容层**（~3-4 周）：发 dbay 这边的 OpenViking 风格 client，覆盖 60-70% 场景，缺 L0/L1/L2 / Skill / viking://
- **B2 — 抽象对齐**（~1-2 个月）：dbay 后端加 session 模型 + L0/L1/L2 + Skill + Hierarchical Retriever
- **B3 — 端云共用 + OBS**（~1 季度）：dbay 拆 embeddable runtime + 华为云 OBS persistence backend

**License 风险**：OpenViking 是 AGPLv3。Hyper Mem 当前依赖形态决定切换难度和传染风险。

## 关键调研发现（决定工程量大幅收敛）

OpenViking 两条核心抽象（**filesystem 范式** + **session 抽 memory**），dbay 已经有等价的生产级底座：

### 1. AgentFS（filesystem 范式底座）

**位置**：
- 服务端：`lakeon-api/src/main/java/com/lakeon/agentfs/`（Java，Postgres `agent_files` 表）
- HTTP API spec：`specs/agentfs-openapi.yaml` v0.2
- FUSE 客户端：`dbay-fuse/`（Rust，本地 mount）
- 用户文档：`docs/agentfs-user-guide.md`

**已有 API**（POSIX 完整覆盖）：
`/agentfs/files`(GET/PUT) `/list`(prefix+recursive+cursor) `/files/head`(stat)
`/files/append` `/files/delete` `/mkdir` `/rename` `/batch` `/files/properties`
`/stats`

**与 OpenViking viking:// 抽象的差距**：

| 项 | 工程量 | 备注 |
|---|---|---|
| scope 约定（resources/user/agent/session 顶层目录） | 几行 | 写在 SDK 里 |
| `viking://` URI 解析 | 1 天 | 纯客户端 |
| `find` | 0 | `/agentfs/list?recursive=true` 已能 |
| `mv` | 0 | `/agentfs/rename` 已有 |
| `grep` 内容搜索 | 1-2 天 | AgentFS 故意不放热路径，新增 `/agentfs/search-text`（pg_trgm / LIKE） |
| L0/L1/L2 渐进披露 | ~1 周 | 复用 AgentFSEventForwarder 模式：摘要 worker 消费文件变更 → LLM 写 `.abstract.md` `.overview.md` 到原目录旁 |
| 文件级权限 | ~1 周 | properties 已有，加 acl schema |

**结论**：不需要写 Rust 文件系统。AgentFS HTTP API 就是 viking:// 的等价底座。

### 2. memory-svc /agentfs/derive（session 抽 memory 底座）

**位置**：
- `memory/service/`（FastAPI，Python，DeepSeek V3.2）
- 部署：`deploy/cce/build-and-push-memory.sh`
- 新端点 `POST /agentfs/derive` 在 commit `6afda8ce` 加入（2026-04-23，刚一周）

**架构**（比 OpenViking 更通用）：

```
Agent 写文件到 AgentFS
  → AgentFSEventForwarder（lakeon-api，per-tenant outbox + retry）
  → memory-svc /agentfs/derive
  → LLM 抽取（DeepSeek V3.2）
  → memories 表（idempotency on source_path+source_etag）
```

OpenViking 是 **explicit `session.commit()`**；dbay 是 **implicit "写文件就抽"**。
同事把对话 log 写到 `agent/<id>/sessions/2026-04-29.jsonl` 就自动抽取。

**当前 7 类**（Facts / Episodes / Procedural / Triples / Decisions / Rejections / Conventions），
两套 scene prompt（CHAT_ASSISTANT / DEVELOPER_TOOL），中英自动检测。

**vs OpenViking 8 类**（profile/preferences/entities/events/cases/patterns/tools/skills），
覆盖大致对齐，但 **skill 类要新增**（或扩 Procedural）：

| OpenViking | dbay 现 |
|---|---|
| profile / preferences | Facts (category=identity / preferences) |
| entities | Triples |
| events | Episodes |
| cases | Episodes / Decisions |
| patterns | Conventions / Procedural |
| tools | Procedural (category=tool_usage) |
| skills | **缺 — 加一类或扩 Procedural** |

**结论**：管道全在。要兼容 OpenViking，就是 (a) 加 `session.commit(messages)` 语法糖 = "写一条 jsonl + 等 derive 回执"，(b) 改 extraction_prompt 把 7 类映射成 8 类。**~2 周**。

## AgentFS 三种 client 形态（决定要不要发 SDK）

AgentFS 暴露面是 HTTP；FUSE / Python SDK / MCP / WebDAV 都是 HTTP 之上的 client。

```
              AgentFS HTTP API（lakeon-api）
                       │
       ┌──────────┬────┴────────────┬────────────┐
       ▼          ▼                 ▼            ▼
  FUSE mount  Python SDK         (新) MCP    (新) WebDAV
  (dbay-fuse, (新, viking 兼容)  server      server
   已发)                          
       ↑          ↑                 ↑            ↑
   ~/.claude  import dbay_viking  Claude /     Finder /
   挂上       as ov               Cursor       Files.app
```

OpenViking 自己也是这种架构（FastAPI 是底，`ov_cli` Rust / Python wizard / MCP / WebDAV 都是客户端）。

**Hyper Mem 同事代码形态决定要发哪种 client**：

| 同事代码形态 | 用哪个 client | dbay 这边要做 |
|---|---|---|
| HTTP/MCP 调用 OpenViking | 直接 HTTP + `viking://` 解析 gateway | ~1 周：路由兼容 gateway 在 lakeon-api |
| `import openviking` Python | 新 PyPI 包 `dbay-viking-fs` | ~2 周：类/方法同名，底层 HTTP |
| `open("/path/...")` 当真 fs 用 | 现有 `dbay-fuse` 复用 | 0 周（已就绪），补 viking:// → mount path 文档 |

最坏三个都用，3-4 周全补上。

## 工程量收敛

之前估 1-2 季度 → 调研后 **B1+B2 合计 4-6 周**（不含 B3 端云共用）：

- 1-2 周：AgentFS 端补 search-text + L0/L1/L2 worker + 7→8 类 prompt + session.commit 语法糖
- 1-2 周：选定的 client（HTTP gateway / Python SDK / 复用 FUSE）
- 1 周：viking:// scope 约定 + URI 解析 + 兼容 e2e 测试

B3（端云共用 + 华为云 OBS）单独评估，~1 季度，要等 dbay 后端拆 embeddable runtime。

## 待解决问题（明天问同事）

> **Hyper Mem 现在依赖 OpenViking 的形态**：
> - A. 只调 HTTP / MCP API
> - B. `import openviking` Python SDK
> - C. fork 改了 OpenViking 内核
> - D. Rust crate 直接 link
> - E. 代码 `open("...")` 当真 fs 用

回答决定：

1. **B1 兼容层做哪个 client**（HTTP gateway / SDK / 复用 FUSE）
2. **AGPL 传染风险评估**（C/D 严重，A/E 几乎无）
3. **"切换路径"小节**写法（一行换 base_url？一行换 import？rebuild Rust？）

## 后续

明天回答上面问题后，把这份 notes 转成正式 spec：
`docs/superpowers/specs/2026-04-30-dbay-openviking-compat-design.md`，
然后进 writing-plans 出实施计划。

## 参考

- `~/code/openviking/README.md` —— OpenViking 架构
- `specs/agentfs-openapi.yaml` —— AgentFS API spec
- `lakeon-api/src/main/java/com/lakeon/agentfs/` —— AgentFS 服务端
- `memory/service/main.py:42` —— `/agentfs/derive` 端点
- `memory/service/extraction_prompt.py` —— 7 类抽取 prompt
- `dbay-fuse/README.md` —— FUSE 客户端
- `docs/superpowers/specs/2026-04-22-agentfs-phase2-design.md` —— AgentFS Phase 2 设计（含 EventForwarder）
- `docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md` —— agent 写 session jsonl 设计
