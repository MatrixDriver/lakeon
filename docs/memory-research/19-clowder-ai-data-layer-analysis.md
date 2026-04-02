# Clowder AI（猫猫咖啡馆）数据层全景分析

> 分析时间：2026-03-28
> 目的：用 Agent 数据七层模型（01-agent-data-panorama）审视猫猫咖啡馆的数据能力现状，识别增强点，为 DBay 集成提供依据。

---

## 1. 猫猫咖啡馆定位

Clowder AI（猫猫咖啡馆）是一个 **多 Agent 协作平台**，协调 9 只"猫"（Claude / GPT / Gemini / DARE 等）组队工作。

**Agent 类型**：对照全景图的 5 类 Agent，猫猫是 **多 Agent 协作 + 编码助手** 的混合体：

| 特征 | 对应类型 | 说明 |
|------|---------|------|
| 9 只猫协作，队列调度，@mention 路由 | 多 Agent 协作 | Agent 间共享 thread，有状态同步 |
| 底层是编码助手 CLI（Claude Code / Codex / Gemini CLI） | 编码助手 | 项目级任务，代码生成 |
| 持久身份、人格、声音 | 长期陪伴 | 每只猫有独立性格和 voice config |

**技术栈**：TypeScript monorepo（Fastify API + Next.js 前端 + MCP Server），pnpm workspace。

---

## 2. 七层数据覆盖分析

### 覆盖总览

```
          ①知识  ②记忆  ③历史  ④组装  ⑤轨迹  ⑥环境  ⑦状态
猫猫现状:   ▓▓    ▓▓    ████   ████    ▓▓    ░░░░    ▓▓
```

对照全景图产品覆盖表，猫猫最接近 **OpenClaw 原生** 定位，但在 ①⑤⑦ 上略强：

| | ①知识 | ②记忆 | ③历史 | ④组装 | ⑤轨迹 | ⑥环境 | ⑦状态 |
|---|---|---|---|---|---|---|---|
| **OpenClaw 原生** | ░░░░ | ▓▓ | ████ | ████ | ░░░░ | ░░░░ | ░░░░ |
| **猫猫咖啡馆** | ▓▓ | ▓▓ | ████ | ████ | ▓▓ | ░░░░ | ▓▓ |

`████` = 核心能力　　`▓▓` = 部分覆盖　　`░░░░` = 不涉及

---

### ① 知识 (Knowledge) — `▓▓ 部分覆盖`

**有什么：**

| 组件 | 文件 | 能力 |
|------|------|------|
| `IndexBuilder` | `packages/api/src/domains/memory/IndexBuilder.ts` | 扫描 `docs/` 目录，索引 markdown 文件到 SQLite |
| `SqliteEvidenceStore` | `packages/api/src/domains/memory/SqliteEvidenceStore.ts` | FTS5 全文检索 + anchor 精确查找 + 关键词回退 |
| `EmbeddingService` | `packages/api/src/domains/memory/EmbeddingService.ts` | HTTP 客户端，调外部 Python GPU 进程（qwen3-embedding-0.6b / multilingual-e5-small，768 维） |
| `VectorStore` | `packages/api/src/domains/memory/VectorStore.ts` | sqlite-vec 扩展，MATCH 近似最近邻 |
| `KnowledgeResolver` | `packages/api/src/domains/memory/KnowledgeResolver.ts` | project + global 双源 RRF 融合（k=60） |
| `SemanticReranker` | `packages/api/src/domains/memory/SemanticReranker.ts` | 向量距离重排 FTS 候选结果 |

**缺什么：**
- 无文档上传 / RAG pipeline（无分块、无 PDF/DOCX 解析）
- 无连接器（Google Drive / Notion / GitHub）
- 无 Agentic Search（多步推理检索）
- 无知识图谱增强
- Embedding 依赖额外 GPU 进程，模型小（768 维 vs 行业 1024 维）
- `KnowledgeResolver` 的 `globalStore` 实际未配置
- 知识来源仅限本地 `docs/` 目录下的 markdown

---

### ② 记忆 (Memory) — `▓▓ 部分覆盖`

**有什么：**

| 组件 | 文件 | 能力 |
|------|------|------|
| `RedisMemoryStore` | `packages/api/src/domains/cats/services/stores/redis/RedisMemoryStore.ts` | per-thread KV 短期记忆，Redis Hash，50 key 上限，LRU 淘汰，TTL 过期 |
| `MemoryStore` | `packages/api/src/domains/cats/services/stores/ports/MemoryStore.ts` | 内存版 IMemoryStore（开发模式） |
| `MarkerQueue` | `packages/api/src/domains/memory/MarkerQueue.ts` | 记忆提取流水线：captured → normalized → approved → materialized → indexed |
| `MaterializationService` | `packages/api/src/domains/memory/MaterializationService.ts` | 将 marker 物化为 evidence 文档 |
| `SqliteEvidenceStore` | （同上） | 8 种 EvidenceKind：feature / decision / plan / session / lesson / thread / discussion / research |
| `AbstractiveSummaryClient` | `packages/api/src/domains/memory/AbstractiveSummaryClient.ts` | 长历史摘要压缩 |
| `SummaryCompactionTask` | `packages/api/src/domains/memory/SummaryCompactionTask.ts` | 定期合并旧 evidence |

**记忆类型对比：**

| 全景图记忆类型 | 猫猫是否有 | 说明 |
|--------------|-----------|------|
| Fact（持久事实） | ❌ | EvidenceKind 无 fact 类型 |
| Episode（时间事件） | ▓ 部分 | `session` kind 记录会话摘要，但不是结构化 episode |
| Trait（行为特征） | ❌ | `ReflectionService` 未配置（返回占位文本） |
| Procedural（过程记忆） | ❌ | 无此类型 |
| Decision（技术决策） | ▓ 部分 | `decision` kind 存在，但无 rationale 元数据 |
| Rejection（排除方案） | ❌ | 无此类型 |
| Convention（项目规范） | ❌ | 无此类型 |

**缺什么：**
- **无 Trait 反思**：`ReflectionService` 是空实现
- **无 Q-value 效用评分**：检索只靠 BM25 + 向量距离，无任务反馈加权
- **无时间衰减**：只有 `updatedAt` 单字段，无 valid_from / valid_until
- **无自动提取**：MarkerQueue 需人工审批（approved/rejected），流水线长
- **元数据贫乏**：keywords 是 JSON 数组，无 JSONB 级别的结构化元数据
- 短期记忆是纯 KV，无语义能力

---

### ③ 对话历史 (History) — `████ 核心能力`

**猫猫在此层最强。** 完整组件：

| 组件 | 文件 | 能力 |
|------|------|------|
| `RedisMessageStore` | `packages/api/src/domains/cats/services/stores/redis/RedisMessageStore.ts` | 实时追加每条消息 + tool 事件，Redis 持久化 |
| `RedisSessionChainStore` | `packages/api/src/domains/cats/services/stores/redis/` | 多轮 session chain 管理，支持压缩 |
| `TranscriptReader` / `TranscriptWriter` | `packages/api/src/domains/cats/services/agents/session/` | 会话历史读写 |
| `SessionSealer` | `packages/api/src/domains/cats/services/agents/session/SessionSealer.ts` | 封存已完成 session，生成 handoff digest |
| `SessionManager` | `packages/api/src/domains/cats/services/agents/session/SessionManager.ts` | 创建/获取 session |
| `AbstractiveSummaryClient` | `packages/api/src/domains/memory/AbstractiveSummaryClient.ts` | 长对话摘要压缩 |

**评价**：符合全景图建议——"对话历史交给 Agent 平台原生管理"。消息持久化、会话链、摘要压缩、封存交接一应俱全。

---

### ④ 上下文组装 (Context Assembly) — `████ 核心能力`

**另一个核心强项。** 完整组件：

| 组件 | 文件 | 能力 |
|------|------|------|
| `ContextAssembler` | `packages/api/src/domains/cats/services/agents/context/ContextAssembler.ts` | 组装完整上下文：身份 + 历史消息 + backlog |
| `SystemPromptBuilder` | `packages/api/src/domains/cats/services/agents/context/SystemPromptBuilder.ts` | 构建系统提示：静态身份 + 动态上下文 |
| `IntentParser` | `packages/api/src/domains/cats/services/agents/context/IntentParser.ts` | 解析 @ideate / @execute 意图标签 |
| `AgentRouter` | `packages/api/src/domains/cats/services/agents/routing/AgentRouter.ts` | @mention 解析 → 路由到正确猫 |
| Token Budget | `cat-config.json` → `contextBudget` | 每猫配置 maxPromptTokens / maxContextTokens / maxMessages |
| MCP Evidence Tool | `packages/mcp-server/src/tools/evidence-tools.ts` | `cat_cafe_search_evidence`：scope/mode/depth 三维检索控制 |

**与全景图对照：**
- ✅ 知识检索决策（通过 scope 参数）
- ✅ 记忆召回决策（通过 mode 参数：lexical/semantic/hybrid）
- ✅ 历史管理（TranscriptReader + context budget）
- ✅ Token 预算分配（per-cat contextBudget）
- ✅ 最终 prompt 组装（SystemPromptBuilder）
- ❌ 无 L0/L1/L2 分层加载
- ❌ 无 Q-value 加权排序

---

### ⑤ 轨迹 (Trajectory) — `▓▓ 部分覆盖`

**有什么：**

| 组件 | 文件 | 能力 |
|------|------|------|
| `RedisInvocationRecordStore` | `packages/api/src/domains/cats/services/stores/redis/RedisInvocationRecordStore.ts` | 每次 agent 调用的完整审计记录 |
| `EventAuditLog` | `packages/api/src/domains/cats/services/agents/orchestration/EventAuditLog.ts` | invocation start/complete/error/auth 事件 |
| `AutoSummarizer` | `packages/api/src/domains/cats/services/agents/orchestration/AutoSummarizer.ts` | invocation 结束后自动生成摘要 |
| 消息级 tool 事件 | `RedisMessageStore` | 消息中包含 tool_use / tool_result 事件 |

**缺什么：**
- **无 reward 信号**：调用完成后无成功/失败反馈
- **无 Q-value 更新**：轨迹数据不用于改进记忆检索
- **无训练数据导出**：轨迹数据躺在 Redis，无法导出为训练格式
- **只做了"可观测性"（Trace），没做"学习"（Trajectory）**

---

### ⑥ 运行环境 (Environment) — `░░░░ 不涉及`

猫猫不创建独立数据库或沙箱。环境管理完全委托给底层 CLI 工具（Claude Code 的文件系统、Codex 的沙箱等）。

---

### ⑦ 工作状态 (State) — `▓▓ 部分覆盖`

**有什么：**

| 组件 | 能力 | 评价 |
|------|------|------|
| `worktree` skill | git worktree 隔离 + 独立 Redis 端口 | 代码级分支，非数据级 |
| `InvocationQueue` + `QueueProcessor` | 队列化调度，防并发冲突 | 序列化执行 |
| Thread / Invocation 状态机 | 状态机验证合法转换 | 有 |
| `RedisDraftStore` | 草稿自动保存 | 有 |

**缺什么：**
- 无数据库级 checkpoint / rollback
- 分支试错是 git 级别，不是数据级别（无 copy-on-write branching）
- 无 Agent 间状态快照/恢复

---

## 3. 底层存储技术定位

对照全景图的底层存储对比表：

| 技术 | 检索方式 | 时序能力 | 关系能力 | 猫猫用在 |
|------|---------|---------|---------|---------|
| **Redis Hash** | Key 精确查找 | TTL 过期 | 无 | 短期记忆、消息、session、任务 |
| **SQLite + FTS5 + sqlite-vec** | BM25 近似 + 向量 | updatedAt 单字段 | edges 表（5 种关系） | 长期知识 / evidence |
| **Markdown 文件** | 全量加载 / 文件名 | 无 | 无 | docs/ 目录文档 |

猫猫当前的存储层位于全景图定位矩阵的 **"SQLite + FTS5 + sqlite-vec"** 位置——适合 OpenClaw 原生级别，但不足以支撑多 Agent 协作的需求（并发、共享、持久化）。

---

## 4. 记忆系统三层模型对照

对照全景图的记忆系统三层模型：

```
记忆策略层（何时记、记什么、何时忘）
├── 猫猫现状：MarkerQueue 手动流水线（需人工审批）
├── 缺失：无自动提取、无遗忘衰减、无 Q-value
└── DBay 增强：one_llm_mode 自动提取 + digest 反思 + 未来 Q-value

记忆组织层（类型、元数据、关联）
├── 猫猫现状：8 种 EvidenceKind + edges 表（5 种关系）+ keywords 数组
├── 缺失：无 fact/episode/procedural/trait 类型、无结构化元数据、无实体图谱
└── DBay 增强：6 种记忆类型 + JSONB 元数据 + graph_nodes/edges 实体图谱 + traits 5 级成熟度

底层存储层（向量、图、全文检索）
├── 猫猫现状：SQLite + FTS5 + sqlite-vec（768 维）
├── 缺失：单机、向量能力弱、无真正 BM25
└── DBay 增强：pgvector HNSW（1024 维）+ ParadeDB BM25 + RRF + Serverless PG
```

---

## 5. DBay 集成增强方案

### 5.1 按层优先级

| 层 | 现状 | DBay 增强 | 优先级 |
|----|------|----------|--------|
| ②记忆 | ▓▓ 最大 gap | Memory Base（6 类型 + trait + pgvector + BM25） | **P0** |
| ①知识 | ▓▓ 无 RAG | Knowledge Base（upload → chunk → embed → hybrid search） | **P0** |
| ⑤轨迹 | ▓▓ 无飞轮 | 未来 Q-value recall→ingest 隐式闭环 | **P1** |
| ⑥环境 | ░░░░ | Serverless PostgreSQL（per-cat / per-project database） | **P2** |
| ⑦状态 | ▓▓ git 级 | Database branching + time-travel checkpoint | **P2** |

### 5.2 具体集成点

**② 记忆层集成**

| 猫猫组件 | 替换/增强 | DBay 能力 |
|----------|----------|----------|
| `SqliteEvidenceStore` | 后端替换为 `DbayMemoryAdapter` | `/recall` hybrid search（pgvector + BM25 + RRF） |
| `MarkerQueue` 手动流水线 | 替换为自动 ingest | `/ingest` + `one_llm_mode` 自动提取 |
| `ReflectionService`（空实现） | 接入 DBay digest | `/digest` → traits 5 级成熟度 |
| `EmbeddingService`（需 GPU 进程） | 不再需要 | DBay 内置 BGE-M3 1024 维 |
| `VectorStore`（sqlite-vec 768d） | 不再需要 | DBay 内置 pgvector HNSW 1024d |
| `SemanticReranker`（距离重排） | 增强 | DBay RRF 融合 + 可选 BGE-Reranker |

**① 知识层集成**

| 猫猫组件 | 增强方式 | DBay 能力 |
|----------|---------|----------|
| `IndexBuilder`（本地 docs/ 扫描） | 补充外部知识源 | Knowledge Base：上传 PDF/DOCX → chunk → embed → hybrid search |
| `KnowledgeResolver`（global store 未配置） | 配置 global store 为 DBay KB | `knowledge_search` MCP tool |
| MCP `search_evidence` | 增加 scope: knowledge 走 DBay KB | `knowledge_search` / `knowledge_upload` |

**⑤ 轨迹层集成**

| 猫猫组件 | 增强方式 | DBay 能力 |
|----------|---------|----------|
| `AutoSummarizer` 输出 | session 结束后 ingest 到 DBay | `/ingest` 自动提取结构化记忆 |
| `RedisInvocationRecordStore` | 导出为 DBay episode 记忆 | memory_type: episode |
| 无 reward 信号 | 未来接入 Q-value | recall→ingest 隐式闭环 |

### 5.3 集成架构

```
┌──────────── Clowder AI ────────────────┐
│                                         │
│  Session 结束                           │
│    ↓                                    │
│  AutoSummarizer                         │
│    ↓ (对话摘要)                          │
│  DbayMemoryAdapter.ingest()  ←── 新增   │
│    ↓                                    │
│  ┌─────────────────────────────┐        │
│  │ MCP: search_evidence        │        │
│  │   scope: memory → DBay      │        │
│  │   scope: docs   → 本地      │        │
│  │   scope: knowledge → DBay KB│        │
│  │   ↓                         │        │
│  │ DbayMemoryAdapter.recall() ←── 新增  │
│  └─────────────────────────────┘        │
│                                         │
│  ReflectionService                      │
│    ↓                                    │
│  DbayMemoryAdapter.digest()  ←── 新增   │
│    ↓                                    │
│  Traits 注入 SystemPromptBuilder        │
└────────────────┬────────────────────────┘
                 │ HTTP (DBay SDK)
                 ▼
┌──────────── DBay.cloud ────────────────┐
│  Memory Base (per-team)                 │
│  ┌──────────┬──────────┬──────────┐    │
│  │ memories │ traits   │ graph    │    │
│  │ pgvector │ 5-stage  │ nodes+   │    │
│  │ + BM25   │ maturity │ edges    │    │
│  └──────────┴──────────┴──────────┘    │
│                                         │
│  Knowledge Base (per-project)           │
│  ┌──────────────────────────────┐      │
│  │ PDF/DOCX → chunk → embed    │      │
│  │ hybrid search + rerank       │      │
│  └──────────────────────────────┘      │
│                                         │
│  Serverless PG (scale-to-zero)          │
└─────────────────────────────────────────┘
```

---

## 6. 关键发现

1. **猫猫的核心优势在 ③对话历史 和 ④上下文组装**——完善的消息持久化、session chain、摘要压缩、token budget 管理。这两层不需要 DBay 介入。

2. **最大的 gap 在 ②记忆**——当前更像"项目文档索引"而非"Agent 记忆"。缺少记忆的核心智能：无 trait 反思、无 Q-value、无自动提取、无时间衰减。DBay Memory Base 可以直接补齐。

3. **①知识层有骨架但无 RAG pipeline**——只能索引本地 markdown。DBay Knowledge Base 可以提供完整的文档上传→解析→检索能力。

4. **⑤轨迹层只做了 Trace，没做 Trajectory**——有审计日志但不用于学习。这是未来 Q-value 飞轮的基础数据。

5. **存储层从 SQLite 升级到 Serverless PG 是自然演进**——单机 SQLite + sqlite-vec 已成为多猫协作的瓶颈（无并发、无共享、无持久保障）。

6. **猫猫作为多 Agent 协作平台，是 DBay 记忆库的理想客户**——多猫共享知识、跨 session 记忆持久化、团队级 trait 发现，这些需求在单 Agent 场景下不明显，但在多 Agent 协作中极为关键。
