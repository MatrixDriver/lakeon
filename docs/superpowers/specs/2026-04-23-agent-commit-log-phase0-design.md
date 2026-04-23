# Agent Commit Log Phase 0 Design

## 定位

这份 spec 的主角是 **agent commit log**——一个 LLM-native、file-based、OBS-synced 的 agent session/skill/memory 数据层。两个 agent（SRE agent、Reading companion）是它的第一批 dogfood 客户，用来 battle-test 抽象，并证明 "同一份数据层跨场景通用"。

这 **不是** 一个生产级 data platform。Phase 0 分两小步，不 big-bang：

**Phase 0a（1 周，先做）**：SRE agent + hermes runtime + feishu + commit log 基础跑通
- 证明 agent 在 dbay.cloud 上真能帮你减负
- commit log 被一个真实 consumer 用起来

**Phase 0b（紧接 0a，1 周，不推迟）**：Reading companion + 通用性验证
- 同一份 `agent_session_log` 代码无需 hack 就能跑第二个完全不同的场景
- "80% 通用" 从假设变成事实（或被打脸）

**关键纪律**：即使 Phase 0a 只有 SRE agent 实现，commit log 的 API 设计必须按"两个 agent 都会用"去设计，**不允许为了 SRE 抄近路写死 incident-only schema**。Reading companion 是设计阶段的"对照组"，实现阶段的"下一步"。每个 API 方法引入前自问："reading companion 会怎么用它？"——答不出来就说明抽象不对。

两周后评估，决定下一步：继续扩展、抽成独立 package、还是推倒重来。

## 非目标

- 不 replace 现有 dbay-admin 里的 pull-based SRE AI 助手（那个是"你问它答"，我们这个是"它主动看 + 向你汇报 + 可对话"）
- Phase 0 不做 auto-remediation——只做 detect + 诊断 + 建议 + 告警
- 不 refactor dbay 现有的 Lakebase / Neon 架构
- 不 fork hermes——所有扩展以 skill + MCP + 旁挂模块形式存在
- 不做团队共享 / ACL / 多用户——Phase 0 单用户（你）

## 系统总览

```
┌──────────────────────── Railway ────────────────────────┐
│                                                         │
│   ┌─────────────── hermes runtime ──────────────────┐   │
│   │                                                 │   │
│   │  feishu gateway (WebSocket)                     │   │
│   │  cron scheduler (croniter)                      │   │
│   │  personality routing (sre / reading)            │   │
│   │                                                 │   │
│   │  skills:                                        │   │
│   │    sre/cold-start-watcher                       │   │
│   │    sre/outcome-checker                          │   │
│   │    reading/url-handler                          │   │
│   │    reading/daily-reflection                     │   │
│   │                                                 │   │
│   │  MCP clients:                                   │   │
│   │    dbay-sre-mcp (existing)                      │   │
│   │    fetch-mcp (for URL content)                  │   │
│   │                                                 │   │
│   │  LLM: deepseek-chat (api.deepseek.com)          │   │
│   └────────┬─────────────────────────┬──────────────┘   │
│            │                         │                  │
│   ┌────────▼────────┐       ┌────────▼────────┐        │
│   │ hermes memory   │       │ agent commit log│        │
│   │ (FTS5, ~/.hermes│       │ (~/.hermes/data │        │
│   │  /memory/)      │       │  /sessions/...) │        │
│   │                 │       │                 │        │
│   │ "关于 Jacky"     │       │ session-level   │        │
│   │ 跨对话记忆       │       │ reasoning log   │        │
│   └─────────────────┘       └────────┬────────┘        │
│                                      │ async sync      │
└──────────────────────────────────────┼──────────────────┘
                                       │
                       ┌───────────────▼──────────────────┐
                       │  OBS bucket: dbay-agent-log      │
                       │  (持久化 + 版本化)                │
                       └──────────────────────────────────┘

┌────────────────── 数据源 ────────────────────┐
│                                              │
│  dbay-logs (PG)  ← dbay-sre-mcp 读            │
│  lakeon-api metrics                           │
│  CCE k8s API (Phase 0 只读, 可选)              │
│                                              │
└──────────────────────────────────────────────┘

┌────────────────── 用户 (你) ────────────────┐
│                                              │
│  飞书 DM hermes bot:                          │
│    - pull: 问问题 → agent 查 log 或实时数据    │
│    - push: agent 主动告警 / 日报              │
│    - reading: @ bot 发 URL                   │
│                                              │
└──────────────────────────────────────────────┘
```

## 核心：agent commit log 数据层

### 概念模型

四个一等对象：

**Session**（会话 / incident / reading）
- 一次 agent 被触发 → 干完活 → 结案的完整轨迹
- 有 type（`incident` / `reading` / 未来其他）、trigger、tags、status
- 包含若干 turn（branching 可选）+ evidence + conclusion + outcome

**Turn**（LLM 交互原子）
- agent 的一次"思考 + 工具调用 + 结果"
- append-only，不可变
- 携带元数据：model、skill、tokens、cost、latency、status

**Branch**（推理分支）
- 同一 session 内，agent 并列探索多个假设
- 每个 branch 独立 append turn
- session 收尾时 resolve：keep 一个、discard 其他（但保留轨迹）

**Evidence**（证据 blob）
- 绑定到 turn 或 session
- content-addressed（sha256），去重存储
- 任意类型：log 片段、metric JSON、文本摘抄、截图、工具输出

以及一个次要对象：

**Skill invocation**（技能调用记录）
- 每次 skill 触发写一条，ref 到它产生的 session
- 时间一长可统计：触发次数、成功率、p50/p95 耗时、成本

### 目录布局

```
~/.hermes/data/
├── sessions/
│   └── 2026/04/23/
│       └── sess_20260423T091230_a1b2c3/
│           ├── manifest.yaml          # metadata: type, trigger, status, tags
│           ├── events.jsonl           # main branch turn events (append-only)
│           ├── branches/
│           │   ├── h1-image-pull.jsonl
│           │   └── h2-pageserver.jsonl
│           ├── branch-decisions.jsonl # 哪个 branch 保留 / 丢弃 / 原因
│           ├── evidence/
│           │   ├── by-hash/
│           │   │   ├── a1b2...ef.log
│           │   │   └── d9e8...f1.json
│           │   └── index.json         # ref: turn_id → evidence hashes
│           ├── conclusion.md          # 最终结论（可多次改写）
│           ├── conclusion-history/    # 改写历史
│           │   ├── v1.md
│           │   └── v2.md
│           └── outcome.md             # 24h/7d 后填，可能不存在
│
├── skills-ledger/
│   └── cold-start-watcher/
│       ├── versions/
│       │   ├── v0.1/
│       │   │   ├── definition.md     # 干什么、什么时候触发
│       │   │   ├── runbook.md        # 怎么诊断
│       │   │   └── prompt.md         # LLM 诊断 prompt
│       │   └── v0.2/...
│       ├── invocations.jsonl         # {ts, session_id, outcome, version}
│       └── stats.json                # 定期重算, p50/p95/success-rate
│
├── indexes/                           # 按需建
│   ├── by-tag.sqlite
│   ├── by-time.sqlite
│   └── embeddings/
│       └── session-summaries.parquet
│
└── .sync/
    ├── obs-manifest.json              # 哪些目录已同步到 OBS 哪个 version
    └── obs.log
```

### 文件格式

**`manifest.yaml`**（session 根级）

```yaml
id: sess_20260423T091230_a1b2c3
type: incident                        # incident | reading | ...
created_at: 2026-04-23T09:12:30Z
closed_at: 2026-04-23T09:14:51Z
status: closed                        # open | closed | abandoned
trigger:
  source: cron/cold-start-watcher
  skill_version: v0.1
  context:
    alert: "compute cold start 8234ms exceeds threshold 5000ms"
    tenant_id: t_abc123
    db_id: db_xyz789
tags:
  - severity:medium
  - component:compute
  - skill:cold-start-watcher
parent_sessions: []                   # for multi-session chains later
model: deepseek-chat
runtime: hermes@0.10.0
```

**`events.jsonl`**（main branch turn 流）

```json
{"turn":0,"t":"2026-04-23T09:12:30Z","type":"trigger","content":{"alert":"..."}}
{"turn":1,"t":"2026-04-23T09:12:31Z","type":"thought","content":"Need to narrow scope..."}
{"turn":2,"t":"2026-04-23T09:12:32Z","type":"tool_call","tool":"log_search","args":{...},"latency_ms":230}
{"turn":3,"t":"2026-04-23T09:12:33Z","type":"tool_result","ref_turn":2,"evidence":["a1b2...ef.log"],"truncated":false}
{"turn":4,"t":"2026-04-23T09:12:34Z","type":"llm_completion","model":"deepseek-chat","tokens_in":2104,"tokens_out":312,"cost_usd":0.0018,"skill":"cold-start-watcher","skill_version":"v0.1"}
{"turn":5,"t":"2026-04-23T09:12:34Z","type":"branch_open","branch":"h1-image-pull"}
{"turn":6,"t":"2026-04-23T09:12:34Z","type":"branch_open","branch":"h2-pageserver"}
{"turn":12,"t":"2026-04-23T09:14:20Z","type":"branch_resolve","keep":"h2-pageserver","discard":["h1-image-pull"],"reason":"metric X showed Y"}
{"turn":13,"t":"2026-04-23T09:14:51Z","type":"conclude","ref":"conclusion.md"}
```

**`branches/h2-pageserver.jsonl`** — 同 schema, 独立 turn id 命名空间

**`branch-decisions.jsonl`** — 仲裁记录

```json
{"t":"2026-04-23T09:14:20Z","kept":"h2-pageserver","discarded":["h1-image-pull"],"reason":"Grafana shows pod-spec-create duration 120ms OK; pageserver-reattach took 6800ms during incident window","evidence":["d9e8...f1.json"]}
```

**`conclusion.md`** (人类可读)

```markdown
# Cold start 8.2s for db_xyz789

## Root cause (confidence 0.72)
Pageserver re-attach gap for tenant t_abc123: suspended during idle window,
re-attached 6.8s when first connection arrived. Known issue per memory
project_pageserver_reattach_gap.

## Suggested actions
1. Manual PUT location_config for tenant t_abc123 (see runbook)
2. Longer term: reuse startup scan fix tracked in plans/2026-04-17-...

## Evidence
- events.jsonl turn 8-11 (pageserver logs)
- evidence/d9e8...f1.json (metric snapshot)
```

**`outcome.md`**（可能不存在；由后续 cron 或用户反馈写入）

```markdown
## 2026-04-24 09:00 check
- Cold start p95 last 24h: 2.1s (was 8.2s)
- Manual fix applied at 09:30 by Jacky
- Did conclusion hold: yes
- Skill-feedback: useful
```

### API 设计

```python
# === Session lifecycle ===
s = log.new_session(
    type="incident",
    trigger={"source": "cron/cold-start-watcher", ...},
    tags=["severity:medium", "component:compute"],
)

s.append_turn(
    type="thought" | "tool_call" | "tool_result" | "llm_completion",
    content=..., metadata={...},
    evidence=[Blob(...), Blob(...)],   # 可选
)

b1 = s.branch("h1-image-pull-slow")
b2 = s.branch("h2-pageserver-reattach")
b2.append_turn(...)
s.resolve_branches(keep=b2.id, discard=[b1.id], reason="...", evidence=[...])

s.conclude(markdown=..., structured={"root_cause": ..., "actions": [...], "confidence": 0.72})
s.close()

# === Outcome (filled later) ===
s.record_outcome(did_work=True, notes="...", evidence=[...])

# === Query ===
log.list_sessions(type="incident", tags=["component:compute"], since="30d", limit=50)
log.get_session(session_id)
log.replay(session_id, at_turn=7)              # 重建当时 state
log.similar(session_id, k=5)                   # 语义相似 (读 embeddings/)
log.search_text("pageserver re-attach")        # FTS
log.skill_stats("cold-start-watcher", period="30d")

# === Evidence ===
blob = Blob.from_bytes(content=b"...", mime="application/json", source="log_search@dbay-sre-mcp")
# 写入时自动 sha256 + 去重; 返回 ref
```

### 实现要点

- **纯 Python 库** `agent_session_log`，依赖少（仅 pyyaml + 一点点 sqlite）
- 本地写入同步；OBS sync 异步（另一进程或 task）
- events.jsonl append-only：用 `O_APPEND` + fsync；不支持修改、只支持作废 turn（特殊 event type）
- conclusion.md 写新版本到 `conclusion-history/vN.md`，主文件覆盖
- 评估用 git 作为底层：Phase 0 **不用**。纯文件系统更简单，未来如果需要优化再换
- 索引 (indexes/) 是**衍生物**，可从 sessions/ 重建；SQLite + parquet 按需建
- OBS sync 粒度是**目录**：一个 session 关闭后整目录打 tar.gz 传 OBS，并在 manifest 记录 obs_ref

### 并发和一致性

- Phase 0 只有一个 hermes 进程，单 agent 同时只写一个 session——无冲突
- Branch 是目录级隔离，不存在交叉
- Skill invocation 是 append 到 `invocations.jsonl`，不同 skill 互不影响
- 未来（Phase 2+）引入多 agent 共享读时才需要设计并发控制；现在不设计

### 和 git 的关系

**不用 git，但借鉴它**。同一篇讨论里已经分析过（见对话），这里只列结论：

- **底层原语** (immutable append-only + content-addressable + DAG) 和 git 一致
- **API / UX / 查询 / merge / forget** 和 git 完全不同，因为使用者是 LLM 不是人
- Phase 0 用纯文件系统实现（最简单），未来如果性能不够再换 libgit2 后端——**对上层透明**

### 和 hermes 自带 memory 的关系

**两份东西，共存不冲突。**

| | hermes memory (~/.hermes/memory/) | agent commit log (~/.hermes/data/) |
|---|---|---|
| 存什么 | 关于"你是谁、偏好、持续话题" | 一次次 session 的完整推理轨迹 |
| 写入时机 | agent 主动选择 remember | 每次 session 自动 append |
| 查询方式 | FTS5 + LLM judgment | 上面的 API |
| 大小 | 小（几 MB）| 大（预计几 GB / 月）|
| 生命周期 | 长期、可编辑 | immutable，可压缩归档 |

hermes memory 相当于"人物传记"；commit log 相当于"事件编年史"。

## Hermes 配置

### Skills（我们加的）

```
~/.hermes/skills/
├── sre/
│   ├── cold-start-watcher/
│   │   ├── SKILL.md           # hermes 读这个决定触发条件
│   │   ├── watcher.py         # cron 调用, 调 dbay-sre-mcp 找异常
│   │   ├── diagnose.md        # LLM 诊断 prompt
│   │   └── report.md          # feishu 消息格式模板
│   └── outcome-checker/
│       ├── SKILL.md
│       └── checker.py         # 24h 后回查 session, 写 outcome.md
└── reading/
    ├── url-handler/
    │   ├── SKILL.md
    │   ├── handler.py         # 飞书 @ 触发, 抓 URL → 提炼 → commit
    │   └── extract.md
    └── daily-reflection/
        ├── SKILL.md
        └── reflect.py         # 22:00 cron, 读当日 reading session
```

每个 skill 的 `SKILL.md` 长这样（hermes agentskills.io 格式）：

```markdown
---
name: cold-start-watcher
description: Watch dbay.cloud compute cold starts, alert when >5s
triggers:
  - cron: "*/2 * * * *"       # every 2 min
tools:
  - dbay-sre-mcp.*
  - agent_session_log.*
---

<instructions>...</instructions>
```

### MCP server 配置

`~/.hermes/config/mcp.json`:

```json
{
  "servers": {
    "dbay-sre-mcp": {
      "command": "uvx",
      "args": ["--from", "dbay-sre-mcp", "dbay-sre-mcp"],
      "env": { "LOG_DB_DSN": "${DBAY_LOGS_DSN}" }
    },
    "fetch-mcp": {
      "command": "npx",
      "args": ["@modelcontextprotocol/server-fetch"]
    }
  }
}
```

### LLM 配置

- Provider: deepseek (api.deepseek.com)
- Model: deepseek-chat（默认）, deepseek-reasoner（疑难诊断时切换）
- Hermes 如果不直接支持 deepseek provider，加一个 openai-compatible provider 配置即可（deepseek API 是 OpenAI 兼容的），必要时给 hermes 提 PR

### Feishu gateway 配置

Hermes 的 feishu.py 原生支持：
- Bot credentials（app_id, app_secret, verification_token, encrypt_key）
- 允许用户列表（只有 Jacky 能触发）
- WebSocket 长连（不需要开放 inbound webhook）
- 交互式 card（action 按钮，供未来 auto-remediation 阶段用）

Bot 需在飞书开放平台注册一次（自建应用），把 credentials 填进 Railway env。

### Personality 路由

Hermes 支持多 personality。两个 agent 用两个 personality（`sre` / `reading`），但**共享**：
- 同一个 feishu bot
- 同一份 commit log
- 同一个 LLM 账号
- 不同的 skills 集合 + system prompt

用户发消息时，通过 mention 或前缀（比如 `sre:` / `read:`）路由；cron 触发时按 skill 归属的 personality 走。

## Agent 1: SRE agent (Phase 0a)

### 触发与探测

- Cron `*/2 * * * *`
- `watcher.py` 调 `dbay-sre-mcp.log_search(component="compute", keyword="started in", since="3m")`
- 从 log 文本 parse 出 `startup_ms`（需 lakeon-api 侧有对应 log——**前置依赖：先 check 是否有此 log，否则加一行 log**）
- 筛 `startup_ms > 5000`
- 去重：如果过去 10 分钟已为同一 (tenant, db) 开过 session，不重复开

### 诊断流程

1. `new_session(type="incident", trigger={alert, tenant_id, db_id, startup_ms})`
2. LLM 读 alert 上下文 + 相关 log 片段（调 `log_trace` 拉 request_id 链）
3. LLM 输出 1-3 个 hypothesis → 每个开一个 branch
4. 每个 branch 按 hypothesis 调对应工具验证（例：h2-pageserver 调 `log_search(component="pageserver", since="5m")`）
5. Resolve branches：哪个证据支持
6. Write `conclusion.md`（根因 + 建议）
7. 发 feishu card 给 Jacky
8. `session.close()`

### Feishu 告警格式

交互式 card（hermes 原生支持），包含：
- 标题：⚡ 冷启动 {ms}ms @ {tenant}.{db}
- 根因猜测 + 置信度
- 建议 action（1-2 条）
- 按钮：`[查看完整 trace]` `[标记误报]` `[提醒我 24h 后复查]`
- 按钮回调路由到 hermes，可追加 turn 到同一 session

### Outcome 反馈（24h 后）

- Cron `0 9 * * *` 扫过去 24h 内 status=closed 但 outcome 未填的 incident
- 调 `log_stats` 查该 tenant/db 当前冷启动 p95
- 如果改善 → `record_outcome(did_work=True, ...)`
- 如果未改善 → push 飞书提示 Jacky"建议未生效"
- 写 skill invocation feedback

### Phase 0a scope

- **只做** cold-start-watcher + outcome-checker 两个 skill
- **不做** 其他 incident 类型（pageserver re-attach 直接检测、compute pod drift 等留到 Phase 1）
- **不做** auto-remediation
- **不改** lakeon-api（除非"需要加一行 log"）

### Phase 0a 验收标准

- hermes 在 Railway 运行稳定；feishu bot 可双向通信
- Deepseek provider 配通，可发起工具调用
- `agent_session_log` 基础 API（new_session / append_turn / branch / conclude / close / record_outcome / list / get / replay）可用
- dbay-sre-mcp 通过 MCP 被 skill 调用成功
- OBS sync 最基础版工作（一个 session close 后能在 OBS 找到 tar.gz）
- 一周内至少捕获 5 次真实冷启动异常（不足则主动造触发），误报率 < 20%
- 至少一次 outcome-checker 闭环成功（建议修复 → 24h 后指标改善）
- commit log 目录结构、文件格式、API 无需 hack 就能应付 SRE 所有场景

## Agent 2: Reading companion (Phase 0b)

### 输入

你在飞书 @hermes 发一条 URL（或纯文本）。Phase 0 不做浏览器 extension。

### 处理流程

1. `url-handler` skill 被飞书事件触发
2. `new_session(type="reading", trigger={source:"feishu", url, received_at})`
3. 调 `fetch-mcp` 抓 URL 正文
4. LLM 任务 A：提炼要点（3-5 条 bullet）+ 关键引用
5. LLM 任务 B：`log.search_text(key_phrases)` 找过去 30 天 reading session 里的相关 session，生成"这让我想起你 4/12 读的 X"这种 link
6. `session.conclude` + `session.close()`
7. 飞书回复：
   ```
   📖 {标题}
   要点:
   • ...
   • ...
   相关: 4/12《...》, 4/18《...》
   ```

### Daily reflection

- Cron `0 22 * * *`
- 列今日所有 reading session
- LLM 读 conclusions → 生成一条反思（今天读了什么、形成了什么观点、还有什么未解决）
- 发飞书给 Jacky
- 该反思本身写成一个 `type=reflection` 的 session（套娃 OK）

### 主动查询

你在飞书问"我最近读了什么关于 agent commit log 的"：
- 路由到 reading personality
- LLM 调 `log.list_sessions(type="reading", tags=...)` 或 `log.search_text(...)`
- 生成回答

### Phase 0b scope

- URL 抓取 + 摘要 + 跨 session 关联 + daily reflection + query
- **不做** 浏览器 extension
- **不做** PDF / 书籍
- **不做** 跨设备同步（Phase 0 全在 Railway 一个进程）

### Phase 0b 验收标准

- 复用 Phase 0a 的 runtime、feishu、LLM、commit log——**无需改动任何共享代码**
- 只新增：2 个 skill + fetch-mcp 配置
- 如果 commit log API 需要**新增方法**来支持 reading 场景，OK；但**不能修改或 rename 已有 SRE 用到的方法**
- 如果出现"SRE 和 reading 对同一概念有不同需求"的情况，**必须**重新统一抽象（不 fork API）
- 一周内：至少 7 天主动推 URL；daily reflection 被你觉得"值得看" ≥ 50% 天数；跨 session 关联至少一次命中有用 link
- "80% 通用"命题判定：API 无破坏性改动即通过；需要破坏性改动即打脸，回头审视抽象

## Railway 部署

### 镜像

- Base: `python:3.11-slim`
- Install: hermes-agent + 我们的 `agent_session_log` 包 + skills 目录
- Entrypoint: `hermes gateway start`（持续运行）+ hermes 内置 cron 后台线程
- Hermes data dir: `/data/hermes`（Railway volume mount）

### 服务配置

- 一个 service（`dbay-sre-reader`），一个进程
- Railway volume 30GB（commit log 增长预留 + OBS sync 兜底）
- Resources: 0.5 vCPU / 1GB（低负载，cron 密集时 spike 到 1 vCPU）

### Secrets (Railway env vars)

- `DEEPSEEK_API_KEY` — 你已有
- `DBAY_LOGS_DSN` — dbay-logs PG 连接串（dbay-sre-mcp 用）
- `FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `FEISHU_VERIFICATION_TOKEN`
- `OBS_ACCESS_KEY` / `OBS_SECRET_KEY` / `OBS_BUCKET` / `OBS_ENDPOINT`
- `ALLOWED_FEISHU_USERS` — Jacky 一个人

### 网络

- **只读**访问：dbay-logs PG（5432 出站 → 华为云）——**需要**把 Railway 出口 IP 加到 dbay PG 的 ACL。或者更优雅：用 dbay 的公网 HTTPS 入口查询（如果 dbay-sre-mcp 能走 HTTP）
- **不** kubectl：Phase 0 不需要直接 k8s API，节省 CCE ACL 工作量
- feishu: 出站 WebSocket 到飞书服务器
- OBS: 出站 HTTPS

### CCE ACL 动作

Phase 0 **只需要** dbay-logs PG 暴露给 agent 出口，**不需要** 5443 kubectl 端口暴露。风险最小化。

**Railway 出口 IP 不固定是已知问题**（见 open Q7）——Day 1 要先验证是否可用，如果不行退路是改走 ECS 或反向代理。

## 安全与隐私

- Agent 只能**读** dbay 基础设施（dbay-logs、可选 metrics），**不能写**
- Feishu 仅允许 Jacky 个人 open_id 触发
- 所有 secrets 走 Railway env，不进代码仓
- Commit log 里的 evidence 可能包含生产日志片段——OBS bucket 必须私有，不能共享链接
- Reading companion 抓的 URL 正文可能含版权内容——私有使用 OK，未来 multi-user 要再审

## 成本估算（Phase 0 单月）

- Railway service: ~$10-20 / 月
- Deepseek API: SRE agent ~500 次诊断 * 3k tokens ≈ $2-5；reading ~30 次 URL * 5k tokens ≈ $1-3；**总 $10 以内**
- OBS 存储：2 周估 <5GB → 几块钱
- Feishu bot：免费

**全部 < $30/月**。失败的成本很小。

## 验证 / 成功标准（Phase 0 整体，两周后评审）

Phase 0a 和 0b 各自的验收在各自节里；整体评审还要判断：

1. **SRE agent 是否真有用**（Phase 0a 出口）——至少 1 次帮你在真实 incident 里节省了时间
2. **Reading companion 是否形成习惯**（Phase 0b 出口）——一周内你还在主动用 or 已经放弃
3. **Commit log 抽象是否立得住**（Phase 0b 出口，最关键）：
   - 两个 agent 共享同一份 API，没有任何一个场景需要 "hack 进抽象"
   - Query 在两个场景都工作（"类似 incident"、"类似 reading"）
   - Branch 至少被 SRE agent 用过一次（Reading 大概率用不到——这本身是个信号，不是问题）
   - 引入 reading 时 API 没有破坏性改动
4. **数据层的开销是否可控**——commit log 目录大小、OBS sync 延迟、查询性能

如果 3 通过（80% 通用命题成立），进入 Phase 2 把 `agent_session_log` 抽成独立 package；如果不通过，回来重新审视抽象。

## 已知 open questions / 风险

**Q1: Hermes skill 执行上下文是否方便注入 commit log helper？**
- 需要实验。最坏情况：我们的 skill 里 `import agent_session_log` 自行初始化，不依赖 hermes
- 中等情况：hermes 有 skill-level context dict，可以注入
- 最好情况：写一个 hermes plugin，skill 里自动可用 `log` 对象

**Q2: Hermes 是否支持 deepseek provider？**
- 不支持的话，补 OpenAI-compatible provider config
- 仍不行则 fork hermes 加 provider（最后选项）
- Day 1 先 spike 验证

**Q3: 冷启动 log 是否已有 `started in {ms}` 格式？**
- 要看 ComputeLifecycleService / ComputePodManager 现有 log
- 如果没有，加一行 `log.info("compute started in {}ms for tenant={} db={}", ms, tenantId, dbId)` 到 lakeon-api

**Q4: OBS sync 失败如何处理？**
- Phase 0：写一个 `.sync/obs.log` 记录失败，重试 3 次。失败不阻塞本地写入
- Phase 1：改成更健壮的 sync worker

**Q5: Deepseek 稳定性？**
- 之前 memory 说 4/20 验证可用。观察 Phase 0 表现，有问题再加 fallback 到华为云 MaaS

**Q6: "80% 通用" 验证只有两个场景够吗？**
- 诚实说：不够。两个场景通过只是**否则立刻破**的门槛——还不能证明一定通用
- Phase 1 如果能做第三个场景（比如 coding review agent），通用性判断才更可信
- 但 Phase 0 先 build 两个，至少能否决掉明显不通用的抽象

**Q7: Railway 出口 IP 不固定怎么办？**
- Railway services 用 NAT pool，出口 IP 是一段范围，不是单一静态 IP——用华为云安全组白名单精确放行会困难
- 备选方案：
  - (a) 不直连 dbay-logs PG，改走 dbay-sre-mcp 的 HTTP 接口（如果有）——需要 dbay-sre-mcp 新增 HTTP 层
  - (b) 通过 Cloudflare Tunnel / FRP 在 CCE 集群内开一个反向代理，Railway 连这个代理，IP 问题由代理侧解决
  - (c) 换部署目标——比如买一台华为云同 region 的小 ECS（$5-10/月），和 dbay.cloud 同 VPC 但不同集群，解决网络 + fate 隔离两个问题
- Day 1 spike 就测这个；如果 Railway 走不通，退路是 (c)

## 决策记录

| ID | 决策 | 理由 |
|---|---|---|
| D1 | 不 fork hermes | 所有需求用 skills/MCP/旁挂模块实现；维护成本最小 |
| D2 | 不直接用 git | 使用者是 LLM 不是人；API、查询、merge 语义都不同；未来可把 git 作为底层替换 |
| D3 | 两个 agent 共享 hermes 进程 | Phase 0 简洁；共享 log 就能跨 agent 查；Railway 单进程部署 |
| D4 | Reading companion 作为第二场景 | 最大限度不同于 SRE，"通用性"压力最大 |
| D5 | Phase 0 不做 auto-remediation | 信任未建立；先做只读 + 建议 |
| D6 | LLM 用 deepseek 官网 | 用户指定 |
| D7 | OBS 作为持久化后端 | dbay 已有华为云生态；Phase 0 单用户够用 |
| D8 | 文件系统实现 commit log（非 git 后端）| Phase 0 最简；后续可替换 |
| D9 | 不暴露 k8s kubectl 给 Railway | 最小化权限面；Phase 0 不需要 |
| D10 | 共享 feishu bot, 两个 personality | 简化用户心智；personality 由 mention 或前缀路由 |

## 未来演进（非 Phase 0）

- **Phase 1**：第三个场景（coding review agent or trend watcher）验证通用性；引入 action 执行的 Tier 1（低风险可逆操作）
- **Phase 2**：`agent_session_log` 从 `lakeon/sre-agent/` 抽成独立 Python package（`~/code/agent-session-log/` 或直接发 PyPI），可被任何 hermes 用户装（哪怕不用 dbay）
- **Phase 3**：多设备同步 + 跨设备 session 接续；OBS 成为真正 source of truth
- **Phase 4**：ACL + 共享 refs，支持团队协作 / 订阅别人的 skill 库
- **Phase 5**：可选 Neon pageserver 作为高并发后端（给需要事务 / 并发一致性的 agent 用）
- **Phase 6**：商业化决策——是给 dbay 自家 SRE 用，还是抽象成通用 agent commit log 对外开源 / SaaS

Phase 0 不承诺任何 Phase 1+ 方向。两周后根据实际数据决定。
