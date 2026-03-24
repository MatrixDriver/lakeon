export interface BlogPost {
  slug: string
  title: string
  titleZh: string
  date: string
  category: string
  summary: string
  summaryZh: string
  content: string
  contentZh: string
}

export const blogPosts: BlogPost[] = [
  {
    slug: 'memory-architecture',
    title: 'How DBay Memory Store Models Memory: A Cognitive Psychology Approach',
    titleZh: 'DBay 记忆库如何建模记忆：认知心理学视角下的 AI 记忆架构',
    date: '2026-03-10',
    category: '技术',
    summary: "A deep dive into DBay Memory Store's 4-type memory classification, trait lifecycle, and self-maintaining memory quality — inspired by Tulving's framework and built for AI agents.",
    summaryZh: '深入解析 DBay 记忆库的四类记忆分类、特质生命周期与自维护记忆质量机制——灵感源自 Tulving 框架，专为 AI Agent 打造。',
    contentZh: `大多数 AI 记忆系统将记忆视为扁平的键值存储：保存所有内容，按相似度检索，然后听天由命。DBay 记忆库采用了截然不同的方法。我们从认知心理学的第一性原理出发设计记忆架构，然后在五个独立研究方向上进行了验证——从 Tulving 的记忆分类体系到斯坦福的 Generative Agents，再到 Mem0 和 Zep 等商业系统。

最终成果是一个具备四种记忆类型、六阶段特质生命周期、证据质量分级和内置遗忘机制的系统——因为记住所有东西并不等于记得好。

## 四种记忆类型

DBay 记忆库将所有记忆分为四种类型，每种都对应认知心理学中一个成熟的概念：

- **事实（Fact）** — 从单次对话中提取的离散、客观、可验证的信息。对应 Tulving 的*语义记忆*。例如："用户在 Google 工作"、"用户有一只叫咪咪的猫"。

- **事件（Episode）** — 绑定到特定时间、地点或人物的一次性事件。对应 Tulving 的*情景记忆*。例如："2024-02-15：用户参加了一场 Python meetup"。

- **特征（Trait）** — 跨多次对话推断出的行为模式、偏好和人格特征。特征*永远不会*从单次对话中提取——它们只能由反思引擎在多个事实和事件中观察到一致模式后才能产生。

- **文档（Document）** — 来自上传文件的内容，用于 RAG（检索增强生成）场景。

## 三层特征分类

DBay 记忆库将特征组织为三个层级：

\`\`\`
稳定性（高 → 低）    可观察性（低 → 高）

  核心层（Core）       难以直接观察，从偏好推断
    ↑
  偏好层（Preference） 通过选择可观察
    ↑
  行为层（Behavior）   可直接观察
\`\`\`

每个层级有不同的衰减速率，反映了心理学研究中表层行为比深层人格特质更容易变化的结论。

## 特征生命周期：六个阶段

每个特征都经历六个阶段的生命周期：

\`\`\`
趋势（trend） → 候选（candidate） → 萌发（emerging） → 确立（established） → 核心（core） → 消解（dissolved）
\`\`\`

| 阶段 | 置信度 | 检索行为 |
|------|--------|---------|
| **趋势（Trend）** | 由时间窗口管理 | 不参与 |
| **候选（Candidate）** | < 0.3 | 不参与 |
| **萌发（Emerging）** | 0.3 – 0.6 | 低权重 |
| **确立（Established）** | 0.6 – 0.85 | 正常权重 |
| **核心（Core）** | > 0.85 | 高权重，优先返回 |
| **消解（Dissolved）** | — | 归档，排除 |

## 证据质量：并非所有信号都等价

反思引擎在评估特征的证据时，会分配四个质量等级：

| 等级 | 类型 | 强化因子 |
|------|------|---------|
| **A** | 跨情境一致性 | 0.25 |
| **B** | 显式陈述 | 0.20 |
| **C** | 跨对话行为 | 0.15 |
| **D** | 同对话 / 隐式推断 | 0.05 |

置信度更新公式是刻意设计为非对称的——建立置信度是渐进的，但矛盾证据打击更大。

## 自维护记忆质量

DBay 记忆库通过四种机制主动维护质量：带间隔效应的时间衰减、矛盾触发的反思、趋势过期和阶段门控检索。`,
    content: `Most AI memory systems treat memory as a flat key-value store: save everything, retrieve by similarity, and hope for the best. DBay Memory Store takes a fundamentally different approach.

## Four Memory Types

DBay Memory Store classifies all memories into four types:

- **Fact** — Discrete, objective, verifiable information extracted from conversations. Corresponds to Tulving's *semantic memory*.
- **Episode** — One-off events bound to specific time, place, or person. Corresponds to Tulving's *episodic memory*.
- **Trait** — Behavioral patterns, preferences, and personality characteristics inferred across multiple conversations. Traits are *never* extracted from a single conversation.
- **Document** — Content from uploaded files for RAG-style retrieval.

## Three-Layer Trait Classification

Traits are organized into three levels: Behavior (directly observable), Preference (inferred from behaviors), and Core (deep personality dimensions).

## Six-Stage Trait Lifecycle

\`\`\`
trend → candidate → emerging → established → core → dissolved
\`\`\`

| Stage | Confidence | Retrieval |
|-------|-----------|-----------|
| Trend | time-windowed | excluded |
| Candidate | < 0.3 | excluded |
| Emerging | 0.3–0.6 | low weight |
| Established | 0.6–0.85 | normal weight |
| Core | > 0.85 | high priority |
| Dissolved | — | archived |

## Evidence Quality

Four quality tiers for evidence weighting: A (cross-context consistency, +0.25), B (explicit statement, +0.20), C (cross-session behavior, +0.15), D (implicit inference, +0.05).

The confidence update formula is intentionally asymmetric — building confidence is gradual, but contradicting evidence hits harder.

## Self-Maintaining Memory Quality

DBay Memory Store actively maintains quality through four mechanisms: time decay with spacing effects, contradiction-triggered reflection, trend expiration, and stage-gated retrieval.`,
  },
  {
    slug: 'trait-lifecycle',
    title: 'Trait Lifecycle Visualization: Transparent AI Understanding',
    titleZh: '特征生命周期可视化：让 AI 的理解透明可见',
    date: '2026-03-01',
    category: '技术',
    summary: 'How DBay Memory Store visualizes the full lifecycle of behavioral traits — from trend to core to dissolved — with confidence bars, evidence tracking, and stage progression.',
    summaryZh: 'DBay 记忆库如何可视化行为特征的完整生命周期——从趋势到核心再到消散，包含置信度条、证据追踪和阶段演进。',
    contentZh: `大多数 AI 系统给你贴标签："你是一个技术控"、"你喜欢简约风格"。但这些标签从哪里来？有多可靠？什么时候会过时？你完全不知道。

DBay 记忆库认为，如果 AI 要对用户做出判断，这个判断必须是透明的。特征生命周期可视化就是实现这种透明的方式。

## 特征不是静态标签

在 DBay 记忆库中，特征（Trait）不是一次性生成的标签，而是有生命周期的动态洞察。每个特征从被发现到被验证，再到可能的消散，都经历一个明确的阶段演进过程。

## 六个生命周期阶段

每个特征会经历以下阶段：

\`\`\`
trend → candidate → emerging → established → core → dissolved
趋势      候选       浮现        已建立       核心     已消散
\`\`\`

- **Trend（趋势）**：短期内观察到的模式，通常在 30 天时间窗口内。不参与 recall 搜索。
- **Candidate（候选）**：置信度低于 0.3。同样不参与 recall。
- **Emerging（浮现）**：置信度 0.3 到 0.6。开始参与 recall 搜索，但权重较低。
- **Established（已建立）**：置信度 0.6 到 0.85。以正常权重参与 recall。
- **Core（核心）**：置信度超过 0.85。高权重、优先返回。
- **Dissolved（已消散）**：特征被归档，不再参与任何搜索。

## 可视化设计

每个特征卡片包含一个生命周期可视化组件，设计采用圆点加连线的方式。每个阶段有独立的配色方案：trend 和 candidate 用中性灰色，emerging 用蓝色，established 用绿色，core 用金色。

## 置信度条

每个特征下方都有一个置信度进度条，颜色根据置信度值动态变化：

- 80% 以上：绿色——高度确信
- 50% 到 80%：蓝色——较为确信
- 30% 到 50%：黄色——需要更多证据
- 30% 以下：红色——证据薄弱`,
    content: `Most AI systems label you: "you're a tech enthusiast", "you like minimalist design." But where do these labels come from? How reliable are they? When do they expire? You have no idea.

DBay Memory Store believes that if AI is going to make judgments about users, those judgments must be transparent.

## Traits Are Not Static Labels

In DBay Memory Store, a trait is not a one-off label but a dynamic insight with a lifecycle. Every trait goes through a clear stage progression from discovery to validation to potential dissolution.

## Six Lifecycle Stages

\`\`\`
trend → candidate → emerging → established → core → dissolved
\`\`\`

- **Trend**: Short-term patterns within a 30-day window. Not included in recall.
- **Candidate**: Confidence < 0.3. Also excluded from recall.
- **Emerging**: Confidence 0.3–0.6. Included in recall at low weight.
- **Established**: Confidence 0.6–0.85. Normal retrieval weight.
- **Core**: Confidence > 0.85. High priority, returned first.
- **Dissolved**: Archived, excluded from all searches.

## Visualization Design

Each trait card includes a lifecycle visualization with colored dots and connecting lines. Color scheme: grey for trend/candidate, blue for emerging, green for established, gold for core.

## Confidence Bar

A progress bar below each trait changes color dynamically: green (> 80%), blue (50–80%), yellow (30–50%), red (< 30%).`,
  },
  {
    slug: 'user-profile',
    title: 'User Profile: AI That Understands Who You Are',
    titleZh: '用户画像：AI 不只记住你说了什么，还能理解你是谁',
    date: '2026-03-02',
    category: '产品',
    summary: 'DBay Memory Store builds a cognitive profile from your conversations — facts, traits with confidence levels, and emotional patterns — not just a list of things you mentioned.',
    summaryZh: 'DBay 记忆库从对话中构建认知画像——事实摘要、带置信度的特征和情绪模式，而不只是你提过的事情的列表。',
    contentZh: `传统的 AI 记忆系统只做一件事：存储事实。"用户喜欢 Python"、"用户住在北京"——这些都是有用的信息，但它们拼不出一个完整的人。

DBay 记忆库的用户画像功能走得更远。它不只记录你说了什么，还通过 digest 反思机制理解你是什么样的人。

## 画像的三个维度

### 事实摘要（Facts）

事实是从对话中提取的离散信息，按类别组织，每条事实都可以追溯到原始对话。

### 特征概览（Traits）

特征是 DBay 记忆库最独特的能力。与事实不同，特征不是从单次对话中提取的，而是 digest 引擎在多次对话中观察到的行为模式。

特征包含以下信息：内容描述、子类型（行为/偏好/核心）、生命周期阶段、置信度百分比、支持次数 vs 反驳次数。

特征按置信度分层：

\`\`\`
行为（Behavior）→ 偏好（Preference）→ 核心（Core）
直接可观察         从行为中推断          深层人格维度
\`\`\`

### 近期情绪（Recent Mood）

DBay 记忆库追踪对话中的情绪信号：

- **效价（Valence）**：情绪的正负方向，-1 到 1 的范围
- **唤醒度（Arousal）**：情绪的激烈程度，0 到 1 的范围

情绪追踪不是为了"监控"用户，而是为了让 AI 更好地理解上下文。`,
    content: `Traditional AI memory systems do one thing: store facts. "User likes Python", "user lives in Beijing" — useful information, but it doesn't paint a complete picture of a person.

DBay Memory Store's user profile goes further. It doesn't just record what you said — through the digest reflection mechanism, it understands who you are.

## Three Profile Dimensions

### Facts Summary

Facts are discrete information extracted from conversations, organized by category, each traceable to the original conversation.

### Traits Overview

Traits are DBay Memory Store's most unique capability. Unlike facts, traits aren't extracted from single conversations — they're behavioral patterns observed by the digest engine across multiple sessions.

Each trait includes: content description, subtype (Behavior/Preference/Core), lifecycle stage, confidence percentage, supporting vs. contradicting evidence count.

### Recent Mood

DBay Memory Store tracks emotional signals: **Valence** (-1 to 1, positive/negative direction) and **Arousal** (0 to 1, intensity level). This helps AI adapt its communication style to context.`,
  },
  {
    slug: 'conversation-import',
    title: 'Conversation Import: Bring Your Memory From Anywhere',
    titleZh: '对话导入：从任何平台迁移你的记忆',
    date: '2026-03-04',
    category: '产品',
    summary: 'Import your conversation history from ChatGPT, Claude, WeChat, and more — DBay Memory Store automatically extracts facts, episodes, and knowledge graph triples.',
    summaryZh: '从 ChatGPT、Claude、微信等平台导入对话历史，DBay 记忆库自动提取事实、事件和知识图谱三元组。',
    contentZh: `你和 ChatGPT 聊了一年，积累了数百条对话。里面包含你的工作偏好、技术决策、生活习惯。然后你换到了 Claude，或者开始用一个新的 AI Agent——这些记忆就全部丢失了。

DBay 记忆库的对话导入功能解决的就是这个问题：把你在其他平台积累的对话历史变成结构化的记忆。

## 支持的平台

- **ChatGPT**：直接上传 \`conversations.json\` 导出文件
- **Claude**：支持包含 \`chat_messages\` 的 JSON 导出格式
- **微信**：支持常见的微信聊天记录导出格式
- **Markdown**：支持多种 Markdown 对话格式
- **通用 JSON**：标准的 \`conversations\` + \`messages\` 结构

系统会自动检测上传文件的格式，无需手动指定。

## 异步处理流程

1. 上传与解析：自动检测格式并解析为标准对话结构
2. 任务创建：创建后台导入任务，立即返回任务 ID
3. 逐条提取：后台逐条处理 user 消息，每条消息独立触发记忆提取
4. 状态跟踪：随时查询导入进度

## 去重机制

通过内容哈希（SHA-256）实现去重。每条对话的所有消息内容会被拼接后计算哈希值。如果一条对话的哈希值已经出现过，系统会自动跳过它。

## 导入后的效果

导入完成后，对话中的内容会被自动提取为三种类型的记忆：

- **事实（Fact）**：离散的、可验证的信息
- **事件（Episode）**：绑定时间的一次性事件
- **知识图谱三元组**：事实之间的关联关系`,
    content: `You've been chatting with ChatGPT for a year, building up hundreds of conversations containing your work preferences, technical decisions, and habits. Then you switch to Claude or a new AI Agent — and all that memory is gone.

DBay Memory Store's conversation import solves this: turn your conversation history from other platforms into structured memory.

## Supported Platforms

- **ChatGPT**: Upload \`conversations.json\` export directly
- **Claude**: JSON export with \`chat_messages\` structure
- **WeChat**: Common chat export formats
- **Markdown**: Various Markdown conversation formats
- **Generic JSON**: Standard \`conversations\` + \`messages\` structure

Format is detected automatically.

## Async Processing

1. Upload & parse: auto-detect format, parse to standard structure
2. Task creation: create background import task, return task ID immediately
3. Per-message extraction: process each user message independently
4. Progress tracking: check import status at any time

## Deduplication

SHA-256 content hashing prevents duplicate imports. Conversations with the same hash are automatically skipped.

## Extracted Memory Types

- **Facts**: Discrete, verifiable information
- **Episodes**: Time-bound one-off events
- **Knowledge graph triples**: Relationships between facts`,
  },
  {
    slug: 'deployment-modes',
    title: 'Three Deployment Modes: From Cloud to Self-Hosted',
    titleZh: '三种部署模式：从云托管到本地部署',
    date: '2026-02-28',
    category: '技术',
    summary: 'DBay Memory Store offers three deployment modes — cloud, self-hosted, and hybrid encrypted — each balancing convenience, privacy, and multi-device sync.',
    summaryZh: 'DBay 记忆库提供三种部署模式——云托管、本地部署和混合加密，分别平衡便捷性、隐私和多设备同步需求。',
    contentZh: `不同用户对数据隐私和部署便捷性有不同的需求。DBay 记忆库提供三种部署模式，覆盖这三种典型需求。

## 云托管（Cloud）

**隐私等级**：标准

云托管是最简单的使用方式。你的记忆存储在 DBay 托管的 PostgreSQL（带 pgvector 向量扩展）中，自动扩容、自动备份、零运维。

**适用场景**：快速集成、零运维、团队协作。

## 本地部署（Self-Hosted）

**隐私等级**：最高

通过 Docker 在你的机器上运行完整的 DBay 记忆库技术栈。所有数据留在 localhost，不会发送到任何外部服务器。

\`\`\`bash
docker compose up -d
\`\`\`

一条命令启动，数据完全在本地。

## 混合加密（Hybrid Encrypted）

**隐私等级**：高

混合加密模式结合了本地计算和云端存储。本地 MCP 服务器负责计算 embedding 和加密内容，然后将加密后的数据推送到 DBay Cloud。

关键设计：向量 embedding 保持可搜索状态，而文本内容是端到端加密的。只有持有加密密钥的你才能解密存储的记忆。

**适用场景**：多设备同步、企业级部署。`,
    content: `Different users have different needs for data privacy and deployment convenience. DBay Memory Store offers three deployment modes to cover each.

## Cloud

**Privacy level**: Standard

The simplest option. Your memory is stored in DBay-hosted PostgreSQL with pgvector, with auto-scaling, auto-backup, and zero ops.

**Best for**: Quick integration, zero-ops, team collaboration.

## Self-Hosted

**Privacy level**: Maximum

Run the complete DBay Memory Store stack on your machine via Docker. All data stays on localhost.

\`\`\`bash
docker compose up -d
\`\`\`

One command, fully local.

## Hybrid Encrypted

**Privacy level**: High

Combines local computation with cloud storage. Local MCP server computes embeddings and encrypts content before pushing to DBay Cloud.

Key design: vector embeddings remain searchable, while text content is end-to-end encrypted. Only you can decrypt stored memories.

**Best for**: Multi-device sync, enterprise deployment.`,
  },
  {
    slug: 'one-llm-mode',
    title: 'ONE LLM Mode: Zero Extra Cost Memory Extraction',
    titleZh: 'ONE LLM Mode：零额外成本的记忆提取',
    date: '2026-03-07',
    category: '技术',
    summary: "How ONE LLM Mode reuses your MCP client's built-in LLM for memory extraction and digest, eliminating the need for a separate LLM API key.",
    summaryZh: 'ONE LLM Mode 复用 MCP 客户端自带的 LLM 完成记忆提取和 digest 反思，彻底省去额外 LLM API 费用。',
    contentZh: `传统的 AI 记忆系统有一个隐性成本问题：每次存储对话内容时，服务端都要调用一个额外的 LLM 来提取事实、事件和知识图谱三元组。

DBay 记忆库的 ONE LLM Mode 彻底解决了这个问题。

## 核心思路：复用客户端 LLM

ONE LLM Mode 的设计哲学非常简单——你已经有一个 LLM 在运行了（比如 Claude Code 或 Cursor 背后的模型），为什么还需要第二个？

**传统模式（2 个 LLM）：**

1. 客户端调用 ingest
2. 服务端调用额外的 LLM 提取（需配置 + 付费）
3. 存入数据库

**ONE LLM Mode（1 个 LLM）：**

1. 客户端调用 ingest
2. 服务端返回提取提示（不调用 LLM）
3. 客户端 LLM 执行提示（零额外成本）
4. 回传 ingest_extracted，存入数据库

## Digest 也支持 ONE LLM

Digest（反思）同样采用两步调用模式，服务端返回反思提示，客户端 LLM 执行，零额外服务端 LLM 费用。

## 智能提醒：何时该 Digest

当未反思的记忆累积到一定数量（默认 5 条），\`ingest\` 的返回结果中会附带一个 digest 推荐提示，让 MCP 客户端知道应该主动调用 \`digest\`。

## 如何开启

在 dbay.cloud 控制台的 Space 设置中一键切换。`,
    content: `Traditional AI memory systems have a hidden cost problem: every time you store a conversation, the server calls an additional LLM to extract facts, episodes, and knowledge graph triples.

DBay Memory Store's ONE LLM Mode solves this completely.

## Core Idea: Reuse the Client LLM

The design philosophy is simple — you already have an LLM running (e.g., the model behind Claude Code or Cursor), so why do you need a second one?

**Traditional mode (2 LLMs):**
1. Client calls ingest
2. Server calls extra LLM for extraction (requires config + payment)
3. Store in database

**ONE LLM Mode (1 LLM):**
1. Client calls ingest
2. Server returns extraction prompt (no LLM call)
3. Client LLM executes prompt (zero extra cost)
4. Client posts back to ingest_extracted, stored in database

## Digest Also Supports ONE LLM

The digest (reflection) operation follows the same two-step pattern — server returns a reflection prompt, client LLM executes it.

## Smart Reminder: When to Digest

When unreflected memories accumulate to a threshold (default 5), the \`ingest\` response includes a digest recommendation prompt.

## How to Enable

Toggle in the Space settings on dbay.cloud console.`,
  },
  {
    slug: 'openclaw-plugin',
    title: 'OpenClaw Plugin: Give Your Agent Real Memory',
    titleZh: 'OpenClaw 插件：让你的 Agent 拥有真正的记忆',
    date: '2026-03-09',
    category: '产品',
    summary: 'How to integrate DBay Memory Store with OpenClaw via MCP direct connect or the dedicated plugin, enabling auto-recall, auto-capture, and auto-digest.',
    summaryZh: '通过 MCP 直连或专用插件将 DBay 记忆库集成到 OpenClaw，实现自动回忆、自动捕获和自动反思。',
    contentZh: `OpenClaw 是一个开源的 AI Agent 框架，支持丰富的插件生态和 MCP 协议。然而，它的原生记忆系统基于 Markdown 文件，存在一些根本性的局限。

## 原生记忆的问题

- **上下文压缩会丢失记忆**：当对话超过上下文窗口，压缩过程中细节不可避免地被丢弃
- **无法跨会话记忆**：每次新会话启动，之前的记忆需要重新加载
- **不能发现模式**：纯文本存储无法从历史对话中提炼行为模式
- **没有知识图谱**：事实之间的关联关系无法被捕获

DBay 记忆库为 OpenClaw 提供了一套完整的记忆增强方案。

## 两种集成方式

### 方式一：MCP 直连（零代码）

\`\`\`json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-key" }
    }
  }
}
\`\`\`

### 方式二：OpenClaw 插件（自动化）

\`\`\`json
"openclaw-dbay": {
  "enabled": true,
  "config": {
    "apiKey": "your-key",
    "mode": "one-llm",
    "autoRecall": true,
    "autoCapture": true,
    "autoDigest": "session-end",
    "topK": 10
  }
}
\`\`\`

## 记忆闭环

每次对话：Auto-Recall 注入相关记忆 → Agent 生成个性化回复 → Auto-Capture 存储新记忆 → Auto-Digest 在会话结束时发现行为模式。`,
    content: `OpenClaw is an open-source AI Agent framework with a rich plugin ecosystem and MCP protocol support. However, its native memory system is based on Markdown files with fundamental limitations.

## Problems with Native Memory

- **Context compression loses memories**: Details are dropped when conversations exceed the context window
- **No cross-session memory**: Previous memories require reloading each new session
- **Cannot discover patterns**: Plain text storage can't distill behavioral patterns
- **No knowledge graph**: Relationships between facts are not captured

DBay Memory Store provides a complete memory enhancement solution for OpenClaw.

## Two Integration Methods

### Method 1: MCP Direct Connect (zero code)

\`\`\`json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-key" }
    }
  }
}
\`\`\`

### Method 2: OpenClaw Plugin (automated)

\`\`\`json
"openclaw-dbay": {
  "enabled": true,
  "config": {
    "apiKey": "your-key",
    "mode": "one-llm",
    "autoRecall": true,
    "autoCapture": true,
    "autoDigest": "session-end",
    "topK": 10
  }
}
\`\`\`

## Memory Loop

Each conversation: Auto-Recall injects relevant memories → Agent generates personalized response → Auto-Capture stores new memories → Auto-Digest discovers behavioral patterns at session end.`,
  },
  {
    slug: 'platform-update-march-2026',
    title: 'Platform Update: Credentials Auth, Trait Feedback & OpenClaw Minimum Intrusion',
    titleZh: '平台更新：邮箱注册、特质反馈与 OpenClaw 最小侵入模式',
    date: '2026-03-16',
    category: '公告',
    summary: 'March 2026 platform update — email/password registration, trait quality feedback (👍👎), space-level ONE LLM mode, smarter batch delete, and OpenClaw v0.3.0 with minimum intrusion mode.',
    summaryZh: '2026 年 3 月平台更新——邮箱注册登录、特质质量反馈（👍👎）、Space 级 ONE LLM 开关、智能批量删除、OpenClaw v0.3.0 最小侵入模式。',
    contentZh: `三月份我们集中发力，围绕易用性、质量控制和 OpenClaw 集成优化，一口气上线了 6 项重要功能。

## 邮箱注册登录

之前 DBay 记忆库只支持 GitHub OAuth 登录。现在我们新增了完整的邮箱注册登录体系：

- **注册、登录、忘记密码、重置密码**完整流程
- **Email 验证**——通过邮件服务发送验证邮件
- **bcrypt 密码哈希**——在 executor 中运行，不阻塞事件循环
- **与现有 OAuth 共存**——同一邮箱可以关联 GitHub、Google 和密码三种登录方式

## 特质反馈

用户和 Agent 都可以对特质提供反馈：

- **👍 有用**——增加 reinforcement_count，强化该特质
- **👎 无用**——增加 contradiction_count，可能降低特质阶段

反馈方式：REST API、MCP 工具、Dashboard UI、OpenClaw 插件。

## Space 级 ONE LLM 模式

现在每个 Space 可以独立设置 ONE LLM 模式，配置解析链为：

**Space 设置 → 租户设置 → 系统默认**

## 智能批量删除

- 跨页全选——选中所有符合条件的记忆
- 日期范围删除——按时间段批量清理
- Dry-run 预览——删除前先查看影响范围`,
    content: `In March, we shipped 6 major features focused on usability, quality control, and OpenClaw integration.

## Email Registration

Previously only GitHub OAuth was supported. Now we have full email auth:

- Complete signup, login, forgot-password, reset-password flow
- Email verification via mail service
- bcrypt password hashing in executor (non-blocking)
- Coexists with GitHub/Google OAuth

## Trait Feedback

Users and Agents can now provide feedback on traits:

- **👍 Useful** — increases reinforcement_count, strengthens the trait
- **👎 Not useful** — increases contradiction_count, may lower trait stage

Available via: REST API, MCP tool, Dashboard UI, OpenClaw plugin.

## Space-Level ONE LLM Mode

Each Space now has its own ONE LLM toggle. Resolution chain: **Space setting → Tenant setting → System default**.

## Smart Batch Delete

- Cross-page select-all for filtered memories
- Date-range deletion for bulk cleanup
- Dry-run preview before committing deletion`,
  },
  {
    slug: 'sdk-v011-features',
    title: 'SDK v0.11: Sliding Window Extraction, Procedural Memory & Smarter Recall',
    titleZh: 'SDK v0.11：滑动窗口提取、程序性记忆与更智能的召回',
    date: '2026-03-16',
    category: '产品',
    summary: 'DBay SDK v0.11 introduces sliding window extraction for short messages, procedural memory for workflows, retention scoring, dual timeline queries, and automatic deduplication.',
    summaryZh: 'DBay SDK v0.11 引入滑动窗口提取（短消息合并提取）、程序性记忆（工作流步骤）、保留分数、双时间线查询和自动去重。',
    contentZh: `SDK v0.11 是自 v0.8 以来最大的一次版本更新。围绕三个方向：提取质量（滑动窗口提取）、记忆类型（程序性记忆）和召回精度（保留分数 + 双时间线 + 自动去重）。

## 滑动窗口提取 (Sliding Window Extraction)

问题：当用户发送"好的"、"嗯"、"收到"这样的短消息时，逐条提取几乎不可能产出有意义的记忆。

解决方案：\`extraction_mode='window'\`。SDK 将消息缓冲到累积字符数达到 \`window_char_threshold\`（默认 500 字符）时再统一提取。

\`\`\`python
async with DBayMemory(
    ...,
    extraction_mode="window",
    window_char_threshold=500,
) as nm:
    await nm.ingest(user_id="alice", role="user", content="好的")
    # → 缓冲，不触发提取
    await nm.ingest(user_id="alice", role="user", content="下周要去北京出差")
    # → 累积达到阈值，一起提取
\`\`\`

窗口提取不仅提升了质量，还显著降低了 LLM 调用次数——对于短消息为主的对话，调用量可以减少 70-80%。

## 程序性记忆 (Procedural Memory)

v0.11 新增了第五种记忆类型：**程序性记忆（Procedural）**。

专门捕获多步骤流程，如"每天早上先看邮件，然后开站会，再 review PR"。提取时 LLM 会生成结构化的 \`procedure_steps\`，每个步骤包含顺序、描述和可选条件。

## 保留分数与双时间线

- **保留分数（Retention Score）**：综合记忆的重要性、新近度和使用频率计算的质量分数
- **双时间线查询**：同时支持记忆事件时间（何时发生）和创建时间（何时存入）查询

## 自动去重

v0.11 新增服务端去重：相似度超过阈值的记忆会被自动合并或标记为重复。`,
    content: `SDK v0.11 is the largest update since v0.8, with improvements across three areas: extraction quality (sliding window), memory types (procedural), and recall precision (retention score + dual timeline + deduplication).

## Sliding Window Extraction

Problem: short messages like "ok", "got it", "sure" yield no meaningful memories when extracted individually.

Solution: \`extraction_mode='window'\` buffers messages until \`window_char_threshold\` (default 500 chars) is reached, then extracts them together as complete context.

\`\`\`python
async with DBayMemory(
    extraction_mode="window",
    window_char_threshold=500,
) as nm:
    await nm.ingest(user_id="alice", role="user", content="ok")
    # → buffered, no extraction
    await nm.ingest(user_id="alice", role="user", content="next week going to Beijing for work")
    # → threshold reached, extract together
\`\`\`

Window extraction reduces LLM calls by 70-80% for short-message-heavy conversations.

## Procedural Memory

v0.11 adds a fifth memory type: **Procedural**. Captures multi-step workflows like "every morning: check email, then standup, then PR review." The LLM generates structured \`procedure_steps\` with order, description, and optional conditions.

## Retention Score & Dual Timeline

- **Retention Score**: Quality metric combining importance, recency, and usage frequency
- **Dual timeline queries**: Query by both event time (when it happened) and creation time (when it was stored)

## Automatic Deduplication

Server-side deduplication in v0.11: memories exceeding a similarity threshold are automatically merged or flagged as duplicates.`,
  },
]

export function getBlogPost(slug: string): BlogPost | undefined {
  return blogPosts.find(p => p.slug === slug)
}
