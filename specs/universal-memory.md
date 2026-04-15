# Universal Memory Spec · DBay 泛记忆规范

> Status: draft v0.1 · 2026-04-14
> Owner: Jacky

统一定义「泛记忆」(universal memory) 的数据模型与 agent 目录映射规则。
FUSE 文件系统与 MCP 接口都基于此规范工作。

---

## 1. 核心概念

泛记忆 = 用户 agent 工作目录下与上下文相关的所有资产。分 5 类：

| 类别 | 定位 | 生命周期 |
|---|---|---|
| 会话上下文 (context) | 当前/近期 session 交互 | 短期，可过期 |
| 记忆 (memory) | 关于用户的长期事实、偏好、经历 | 长期 |
| 知识 (knowledge) | 可复用、可脱离用户读的内容 | 长期 |
| 技能 (skill) | 可执行资产（skills/commands/plugins/hooks） | 长期 |
| 认知 (cognition) | 反思产出、跨 session 模式、元级洞察 | 长期 |

---

## 2. 数据模型

### 2.1 memory_item

最小可召回单元。

```
memory_item {
  id            uuid             -- 主键
  category      enum             -- context | memory | knowledge | skill | cognition
  type          enum             -- 下表
  scope         enum             -- global | per_agent | per_project
  scope_ref     text             -- scope 限定的 agent id 或 project id
  origin        enum             -- user_authored | system_authored
  content       text             -- 原子内容（独立可召回）
  source_ref    jsonb            -- 来源定位，见 2.3
  tags          text[]
  created_at    timestamptz
  updated_at    timestamptz
  last_used_at  timestamptz
  usage_count   int
  ttl           interval         -- null = 不过期
  q_value       float            -- 记忆策略学出的召回权重
  embedding     vector           -- pgvector，异步建立
}
```

### 2.2 type 枚举（对应 5 类 memory 的细分）

- `context.session` - session 内交互记录
- `context.working` - 工作短期记忆
- `memory.factual` - 事实：「我住在杭州」
- `memory.preference` - 偏好：「我用 vim」
- `memory.episodic` - 事件：「昨天我调了一个 X 问题」
- `memory.procedural` - 程序：「改 Python 代码前先写测试」
- `knowledge.document` - 用户上传的文档
- `knowledge.wiki` - 对话沉淀出的 wiki 条目
- `knowledge.graph_node` - 知识图谱节点
- `skill.definition` - skill/command/plugin 定义
- `cognition.reflection` - 反思洞察
- `cognition.pattern` - 跨 session 模式

### 2.3 source_ref

保真回写。

```json
{
  "agent": "claude" | "openclaw",
  "path": "CLAUDE.md" | "projects/X/memory/MEMORY.md" | "workspace/AGENTS.md",
  "anchor": {            // 可选，item 是文件的一部分时用
    "type": "heading" | "paragraph" | "line_range",
    "value": "#偏好设置" | "para#3" | "L10-L25"
  }
}
```

### 2.4 scope 规则

- `global` — 跨 agent 共享。例：`~/.claude/CLAUDE.md` 里的通用偏好
- `per_agent` — 仅对特定 agent。例：OpenClaw 的 `AGENTS.md` / `SOUL.md`
- `per_project` — 随项目走。例：某 repo 里的 `CLAUDE.md`、`projects/X/memory/*`

Adapter 在读时按 scope 合并投影；写时根据目标文件位置反推 scope。

### 2.5 origin 规则

- `user_authored` — 用户手写（CLAUDE.md / AGENTS.md / SOUL.md / 用户上传的 knowledge 文档）。认知 Agent **不得随意改写**，最多追加或标记过时。
- `system_authored` — 系统累积（session 事件、反思产出、MEMORY.md 条目）。可按策略衰减、合并。

---

## 3. Agent 目录映射

### 3.1 Claude Code (~/.claude/)

| 路径 | category.type | scope | origin |
|---|---|---|---|
| `CLAUDE.md` | memory.preference / procedural | global | user_authored |
| `memory/*.md` | memory.* (见下文前缀规则) | global | system_authored |
| `projects/<id>/memory/MEMORY.md` | **生成视图**（不单独建 item，由 items 聚合渲染） | per_project | system_authored |
| `projects/<id>/memory/*.md` | memory.* (见下文前缀规则) | per_project | system_authored |
| `projects/<id>/*.jsonl` | context.session | per_project | system_authored |
| `<repo>/CLAUDE.md`（项目内） | memory.procedural | per_project | user_authored |
| `skills/*/` | skill.definition | global | user_authored |
| `commands/*.md` | skill.definition | global | user_authored |
| `plugins/*` | skill.definition | global | user_authored |
| `hooks/*` | skill.definition | global | user_authored |

**文件名前缀 → type 推断**（对应 auto-memory skill 的约定）:

| 前缀 | 推断 type |
|---|---|
| `feedback_*.md` | memory.preference / memory.procedural |
| `project_*.md` | memory.episodic / memory.factual |
| `reference_*.md` | knowledge.document |
| `user_*.md` | memory.factual (user profile) |

**MEMORY.md 是生成视图**：不存为独立 item，由 adapter 实时从 memory_items 聚合渲染（按 `- [title](file.md) — hook` 格式）。用户若直接写 MEMORY.md，adapter 解析 diff 回写 items 或者新建 items。

### 3.2 OpenClaw (~/.openclaw/)

| 路径 | category.type | scope | origin |
|---|---|---|---|
| `workspace/AGENTS.md` | memory.preference / procedural | per_agent=openclaw | user_authored |
| `workspace/{SOUL,IDENTITY,USER,TOOLS,HEARTBEAT}.md` | memory.preference | per_agent=openclaw | user_authored |
| `workspace/MEMORY.md` | **生成视图**（由 items 聚合） | per_agent=openclaw | system_authored |
| `workspace/memory/*.md` | memory.episodic / procedural | per_agent=openclaw | system_authored |
| `workspace/` 其他文件 | context.session（待细化） | per_agent=openclaw | system_authored |
| `extensions/*` | skill.definition | per_agent=openclaw | user_authored |
| `agents/*` | skill.definition | per_agent=openclaw | user_authored |

**第一阶段只接管**：CLAUDE.md / memory 相关目录 / projects（CC），AGENTS.md / persona 文件 / memory 相关（OpenClaw）。其余后续阶段。

---

## 4. Adapter 契约

### 4.1 读（DB → 文件）

Adapter 根据 agent 请求的路径，从 memory_items 表查询并拼装成 markdown/jsonl 返回。

**CLAUDE.md 拼装规则**:
```
查询:  scope IN (global, per_project=<current>)
       AND origin = user_authored
       AND category = memory
排序:  scope (global first), then section order
拼装:  按 source_ref.anchor.value 的原始 heading 结构还原
```

**MEMORY.md 索引拼装**:
```
查询:  scope = per_project=<current> AND origin = system_authored AND category = memory
输出:  类似 auto-memory skill 的索引格式，每条一行 `- [title](file.md) — hook`
```

### 4.2 写（文件 → DB）

Adapter 接收文件写入，diff 出新增/修改的 item，更新 memory_items 表。

- 新增段落 → 新 item，origin=user_authored
- 修改段落 → 更新对应 item 的 content 和 updated_at
- 删除段落 → 标记删除（不物理删除，留审计）

### 4.3 同一 scope 跨 agent 合并

同一条 `memory.preference`（比如"别用 emoji"）若 scope=global，两个 agent 都能读到。
Adapter 负责：CC 读 CLAUDE.md 时拼入；OpenClaw 读 AGENTS.md 时**不拼入**（因为 AGENTS.md 是 per_agent=openclaw）。

**例外**：用户可以显式把 CC 的 global preference 声明为 cross-agent，这时 OpenClaw 的 AGENTS.md 才会看到。（v0.2 再定）

---

## 5. 一致性与性能

- **写返回即可见**：FUSE write 返回成功 = 本地 cache + 入队上云
- **索引最终一致**：embedding / full-text / graph 索引异步建立
- **高频 append**（session jsonl）：FUSE 层攒批，写入单独的 session_events 表（不走 memory_items）
- **离线**：Lakebase 不通时走本地 SQLite cache，重连后同步

---

## 6. 测试与 benchmark 规则（重要）

**禁止**在任何用户的生产 memory base 上做写测试。所有 benchmark / smoke test：

1. 新建临时 base：`POST /memory/bases` with `name: "bench-ephemeral-<date>"`
2. 所有写入只能指向这个 base
3. 测试完整个 base 删除：`DELETE /memory/bases/<id>`

原因：memory_ingest 的条目会出现在用户的 recall/list 结果里，污染真实记忆。
之前违反过这条（把 40 条 `bench ...` 写进了用户的 cc-mem），已清理。
以后 AgentFS 写测试同理——不允许用生产 tenant 做 E2E，必须新 tenant 或 throwaway store。

## 7. 第一阶段范围收敛

第一阶段只做 memory 类（CLAUDE.md / MEMORY.md / memory/*.md）+ context.session 类（projects jsonl，blob 存储）。
skill / knowledge / cognition 的 adapter 在后续阶段实现。
