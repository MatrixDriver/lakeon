# Claude Code + ZhiXing：开发者记忆的最高价值场景

> 本文分析 Claude Code 开发者的记忆痛点，论证为什么"让 Claude Code 越来越懂你"是 ZhiXing 的主战场，并给出面向**开发者效率**（而非个人情感陪伴）的记忆类型、Trait 模型和 MCP 工具的完整重设计。

---

## 1. 痛点验证：这不是假设，是已确认的强需求

### 1.1 社区信号的规模

Claude Code 的"上下文遗忘"问题已经在社区产生了大量自发的讨论和工具：

- **GitHub Issues**（anthropics/claude-code 官方仓库）：
  - [#3508](https://github.com/anthropics/claude-code/issues/3508)：「Claude is constantly forgetting what we did 5 minutes ago, or yesterday」（BUG）
  - [#3841](https://github.com/anthropics/claude-code/issues/3841)：Auto Compact 后记忆清空（BUG）
  - [#14227](https://github.com/anthropics/claude-code/issues/14227)：Feature Request — Persistent Memory Between Sessions
  - [#27298](https://github.com/anthropics/claude-code/issues/27298)：Feature Request — Layered memory system for cross-session context
  - [#29746](https://github.com/anthropics/claude-code/issues/29746)：新 session 不重新读 CLAUDE.md（BUG）

- **Hacker News** 上至少 3 个 Show HN 项目专门解决这个问题（"Stop Claude Code from forgetting everything"、"Working memory for Claude Code"、"Claude Cognitive"），均获得显著关注。

- **DEV.to** 有 6+ 篇文章写「我如何解决 Claude Code 遗忘问题」，都是社区自发写作。

**这个规模说明：这不是少数用户的边缘问题——是大多数 Claude Code 用户每天遭遇的核心摩擦。**

### 1.2 具体的痛点形态

开发者实际抱怨的不是抽象的「记忆丢失」，而是非常具体的场景：

**场景 1：跨 session 从零开始**
> "三个小时架构出来的方案，第二天打开 Claude Code，全部遗忘。要重新解释一遍：我们用 Next.js，Server Components，用 Tailwind 不用 shadcn，不是典型选择但就是这样——每次都要说。"

**场景 2：Auto Compact 之后瞬间遗失**
> session 内上下文满了触发 /compact 后，Claude 立刻忘记：已经从 MongoDB 切换到 PostgreSQL 了、命名规范是什么、刚刚发现的 bug 根因是什么。

**场景 3：长 session 里的上下文污染**
> 随着对话积累，Claude 开始把调试对话和架构对话混在一起，输出质量下降。开发者形容为「context pollution」。

**场景 4：重复解释偏好**
> 「我们用 asyncio.gather 不用 TaskGroup」「错误处理不要 try/except everywhere，集中在入口」——每个 session 都要重新教一遍。

### 1.3 量化数据

| 指标 | 数据 | 来源 |
|------|------|------|
| 每周重复解释上下文的时间 | **3.7 小时/周** | Tyler Folkman, self-tracked |
| 开发者认为 AI 编程助手「缺少相关上下文」的比例 | **65%**（重构任务）/ **60%**（测试和写作任务） | Qodo State of AI Code Quality 2025 |
| 最被需求的 AI 编程工具改进 | **「改善上下文理解」（26% 票数 #1）** | 同上 |
| 短上下文窗口导致的生产力损失 | **19%**（vs 长上下文工具） | Augment Code |
| 有经验的开发者用 AI 反而更慢的比例 | 部分研究显示慢 **20%** | Fortune/NBER study |

---

## 2. 现有解法的局限性

社区已有多种应对方式，但都有结构性缺陷：

### 2.1 CLAUDE.md — 最主流，但依赖人工维护

```
优点：
  - 官方支持，每次 session 自动加载
  - 可以放项目架构、技术选型、禁忌事项

缺陷：
  - 完全依赖开发者手动维护——你记得写才有用
  - 200 行之后遵从率下降（Claude 自己说的）
  - 捕获的是「你认为重要的东西」，而不是「Claude 发现重要的东西」
  - 不能跨项目共享开发者偏好
  - 不支持语义检索——每次全量加载，浪费 token
```

**根本问题：CLAUDE.md 是静态文档，不是智能记忆。**

### 2.2 社区工具（claude-mem、Claude Cortex、OpenMemory 等）

这些工具存在但都是初级实现：

| 工具 | 方案 | 局限 |
|------|------|------|
| **Mem0 OpenMemory** | 本地 SQLite MCP，记事实 | 无 Trait 反思，无结构化记忆分类，无开发者专属智能 |
| **claude-mem** | 每次工具调用后静默记录观察 | 简单 SQLite，无向量检索，无跨项目画像 |
| **Claude Cortex** | 3 层记忆（STM/LTM/Episodic） | 社区项目，无商业支撑，概念设计多于工程 |
| **devpace** | Markdown 状态文件作为 Skill | 本质还是手动管理，换了个包装 |

**这些工具的共同局限**：它们解决的是「存储」，没有解决「智能」——没有 Trait 反思（让 AI 发现你的行为模式），没有 Q-value 排序（学习哪些记忆真正有用），没有知识图谱（理解你的决策之间的关联）。

### 2.3 Anthropic 自己的方向

Claude Code 的 auto-memory（MEMORY.md）是 Anthropic 的官方回应，但：
- 仍是平铺文本，200 行上限
- 无语义检索，全量加载
- 无 Trait 反思能力
- 不跨项目

**Anthropic 在 context 压缩上投入大，在记忆智能上投入少。** 这是 ZhiXing 的空间。

---

## 3. ZhiXing for Claude Code 的具体差异化

### 3.1 两层价值：项目记忆 + 开发者画像

ZhiXing 能做的事，分两层：

```
第一层：项目记忆（解决「每次 session 从零开始」）
├── 自动 capture：架构决策、技术选型理由、已排除的方案、命名规范
├── 自动 recall：session 开始时注入相关项目上下文（L0/L1/L2 分层，节省 token）
└── Compact 感知：/compact 后恢复关键决策和调试发现，而不是一片空白

第二层：开发者画像（解决「重复教 Claude 你的偏好」）
├── 跨项目 Trait 反思：「你在异步代码里始终用 asyncio.gather 而非 TaskGroup」
├── 偏好归纳：「你的 Python 项目一律用 FastAPI + pydantic-settings + asyncpg」
├── 禁忌记忆：「你明确拒绝过 GraphQL 三次，不要再建议」
└── 跨 Agent 统一画像：Claude Code / Cursor / Gemini CLI 看到同一个「你」
```

### 3.2 与 CLAUDE.md 的本质区别

```
CLAUDE.md（现状）                    ZhiXing（增强）
──────────────────────────────────────────────────────
手动写                               自动 capture
静态文档                             动态更新，按语义检索
200 行上限，全量加载                  分层加载，L0 摘要 < 500 token
你记得写才有                         Claude 发现了才存
只存项目约束                         存决策 + 偏好 + Trait + 知识图谱
单项目                               跨项目画像统一
```

**关键洞察：CLAUDE.md 解决的是「让 Claude 不犯基础错误」，ZhiXing 解决的是「让 Claude 越来越懂这个开发者」。**

### 3.3 开发者 Trait 反思——ZhiXing 独有的能力

Trait 引擎是 ZhiXing 对竞品（Mem0、OpenMemory）最强的差异化。在开发者场景下，Trait 反思会发现：

**技术偏好类：**
- 「你的 Python 项目从不用 ORM，始终手写 SQL」
- 「你用 TypeScript strict 模式，且从不用 any」
- 「测试风格：集成测试优先，几乎不写单元测试」

**决策模式类：**
- 「你倾向于先跑通再重构，而非一次写好」
- 「遇到外部 API 你的第一反应是封装 client 类，不直接调用」
- 「你喜欢用 dataclass 而非 TypedDict 来定义数据结构」

**沟通偏好类：**
- 「你不喜欢 Claude 解释"为什么这样做"，直接给代码」
- 「你习惯先看示例再看解释」

这些 Trait 一旦形成，Claude Code 的行为就不再是通用的——它是专属于你的。这是 **个人版的「企业知识库」**，但适用于开发者的工作方式。

### 3.4 具体的用户 Journey

```
第 1 天：
  安装 ZhiXing MCP，连接 zhixing.cloud
  → 正常开发，ZhiXing 静默 capture 架构决策和技术选型

第 3 天：
  新 session 开始
  → ZhiXing 自动 recall：「上次你在这个项目里排除了 Redis，原因是部署复杂度」
  → Claude Code 不会再建议加 Redis

第 2 周：
  第一个 Trait 出现：「你在这个项目里一直用 asyncpg 直连 PG，不用 SQLAlchemy」
  → 之后的所有建议都默认这个约束

第 1 个月：
  开始新项目
  → ZhiXing 注入跨项目画像：「这个开发者用 FastAPI，不用 Django；
     喜欢 pydantic v2，不用 marshmallow；错误处理集中在 app 入口」
  → 新项目 Day 1，Claude Code 就像已经了解你三个月
```

**体验核心：每次开 Claude Code，你感觉它比上次更懂你。** 而不是每次都要重新解释。

---

## 4. 竞品格局

### 4.1 在 Claude Code 场景的竞争态势

| 产品 | 当前在 CC 上的能力 | 差距 |
|------|-------------------|------|
| **Mem0 OpenMemory** | MCP server，本地记事实，私有 | 无 Trait 反思，无图谱，无开发者专属智能，无 Q-value |
| **Supermemory** | 通用记忆 MCP，已有 CC 配置模板 | 无 Trait 反思，无开发者场景优化 |
| **claude-mem** | 轻量 SQLite，每次工具调用后记录 | 社区项目，无商业产品，功能极简 |
| **Claude Code 原生** | auto-memory MEMORY.md | 200 行上限，纯文本，无 Trait，无图谱 |
| **ZhiXing（目标）** | MCP + Trait 反思 + 图谱 + Q-value + 跨 Agent 画像 | — |

### 4.2 ZhiXing 的核心护城河

1. **Trait 反思引擎**：没有竞品能做到「从行为模式里归纳出你是什么样的开发者」
2. **Q-value 飞轮**：越用越准，迁移走等于放弃所有积累
3. **LoCoMo 82 分**：记忆检索质量本身就是护城河（Mem0 66.9）
4. **跨 Agent 统一画像**：在 Claude Code 里积累的开发者画像，Cursor 和 Gemini CLI 也能用

---

## 5. 为什么这比 OpenClaw 更值得押注

### 5.1 根本逻辑：你自己是用户

Dr. K 是 Claude Code 的重度用户，几乎不用 OpenClaw。这一个事实决定了：

| 维度 | OpenClaw 插件 | Claude Code + ZhiXing |
|------|--------------|----------------------|
| **能不能自己用** | 几乎不用 | 每天都用 |
| **反馈循环** | 两周才能有感受 | 两天就能发现问题 |
| **迭代速度** | 慢（需要找外部用户验证） | 快（自己就是 beta 用户） |
| **OpenClaw 产品成熟度** | 还在打磨期，不稳定 | Claude Code 是 Anthropic 主力产品 |
| **竞争窗口** | Supermemory 已有插件，在争 | ZhiXing 已有 MCP，先发优势可加速 |
| **用户群质量** | 消费者用户为主 | 开发者，付费意愿强 |

### 5.2 「自己是用户」的战略价值

以下是行业先例：
- Cursor 团队每天用自己的产品写代码 → 极快的迭代速度
- Linear 团队用自己的工具管理项目 → 产品感极强
- ZhiXing 的作者用 ZhiXing + Claude Code 开发 ZhiXing → 无限正反馈循环

这个「飞轮」不只是数据飞轮，还是**产品感知飞轮**：用得越多，发现问题越快，改得越好，用得越爽。

### 5.3 市场规模对比

| 市场 | 估算 |
|------|------|
| OpenClaw 活跃用户 | 未知，可能几千到几万 |
| Claude Code 用户 | Anthropic 称「数十万」，2025 增长迅速 |
| AI 编程工具市场 | GitHub Copilot 1800 万用户；Cursor 数百万 |

Claude Code 的市场规模是 OpenClaw 的数量级倍数。

---

## 6. 行动建议

### 6.1 立即可做（1 周内）

- **Dr. K 自己装上 ZhiXing MCP 用于日常开发**——这是最重要的一步
- 记录下第一周内哪些记忆被自动 capture 了、哪些 recall 有帮助、哪些没有
- 确认 MCP 集成的配置摩擦有多高（目前安装是否足够简单？）

### 6.2 产品优先级调整（Phase 1）

根据 Claude Code 场景的重要性，调整优先级：

| 优先级 | 任务 | 对 CC 场景的价值 |
|--------|------|----------------|
| **P0** | L0/L1/L2 分层加载 | 减少 recall 注入的 token 消耗，让开箱即用 |
| **P0** | 开发者场景的 auto-capture hooks | 捕获架构决策、技术选型、排除方案 |
| **P1** | Trait 反思在开发者偏好上的调优 | 让 Trait 能归纳出有意义的开发者行为模式 |
| **P1** | /compact 感知（Compact 后自动恢复） | 解决 Auto Compact 痛点 |
| **P2** | 跨项目开发者画像（Space 共享） | 新项目 Day 1 就懂你 |
| **P3** | zhixing.cloud 开发者面板 | 可视化自己的开发 Trait 和决策记录 |

### 6.3 差异化叙事

不要说「给 Claude Code 加记忆」——Mem0 已经在这么说了。

要说：

> **「让 Claude Code 变成专属于你的编程搭档。装了 ZhiXing，它知道你用 FastAPI 不用 Django，知道你上周排除了 Redis，知道你更喜欢集成测试——每次 session 它都比上次更懂你。」**

这个叙事：
- 不和「记忆存储」竞争（Mem0 的定位）
- 强调的是**行为变化**（"更懂你"）而不是功能（"记住了什么"）
- 对开发者有直接的情感共鸣——厌倦了每次都要重新介绍自己

---

## 7. 「不破坏」原则：ZhiXing 的集成底线

> **开发者采用 ZhiXing 的前提是：它不能让 Claude Code 变得不正常。** 一旦 ZhiXing 干扰了正常的开发流程，开发者会立刻卸载，不会给第二次机会。

### 7.1 具体的破坏风险

| 风险 | 场景描述 | 后果 |
|------|---------|------|
| **注入过期记忆** | ZhiXing recall 了「项目用 MongoDB」，但三个月前已切换到 PG | Claude 在新 session 里建议 MongoDB 用法，开发者懵了 |
| **Token 占用过多** | 每次 session 注入大量历史，context window 有 1/3 是 ZhiXing 的内容 | Claude 实际推理质量下降，工作上下文被挤走 |
| **Trait 导致行为僵化** | ZhiXing 说「这个开发者始终用 FastAPI」，开发者这次要写 Django | Claude 反复推荐回 FastAPI，开发者感觉被 AI 束缚 |
| **Capture 打断 session** | 后台 capture 触发 MCP 调用，导致响应停顿或插入奇怪内容 | 开发流程被打断，信任感丧失 |
| **MCP 故障级联** | ZhiXing 服务挂了，MCP 调用超时 | 整个 Claude Code session 卡死 |

### 7.2 硬约束设计原则

这些是实现时的强制约束，不是可选项：

**原则 1：Recall 只在第一轮，之后沉默**

```
Session 第 1 轮 → ZhiXing recall，注入摘要（< 300 token）
第 2 轮起       → ZhiXing 完全沉默，不主动做任何事
```

开发者进入工作状态后，ZhiXing 要「消失」。OpenClaw 插件里的 `recallOnlyFirstTurn: true` 就是这个逻辑，Claude Code 集成必须默认开启。

**原则 2：严格 token 预算，默认极小**

```
L0（自动注入）：< 300 token — 最关键的几条 Trait + 本项目决策摘要
L1（按需）    ：Claude 或用户主动调用 recall 工具时才返回
L2（完整内容）：显式请求才给
```

ZhiXing 的注入不能超过 context window 的 5%。开发者感受不到它的存在，但需要时它在那里。

**原则 3：记忆是「提示」不是「命令」**

注入时加明确的 framing，让 Claude 知道这是背景参考，不是约束：

```
[ZhiXing 历史上下文 — 仅供参考，用户明确指令优先]
· 本项目使用 FastAPI + asyncpg
· 上次排除了 Redis（原因：增加部署复杂度）
```

用户说「这次用 Django」，Claude 立刻切换，不纠缠历史记录。

**原则 4：Capture 完全异步，绝不阻塞响应**

```
Claude 给出回复 → 用户立刻看到
                     ↓（同时，后台静默）
               ZhiXing 异步分析是否值得 capture
               → 有价值 → 静默写入，不在对话中有任何体现
               → 无价值 → 什么都不做
```

Capture 永远在响应之后发生，不影响响应速度，不在对话里插入任何内容。

**原则 5：MCP 故障时 Claude Code 完全正常运行**

ZhiXing MCP 不可达 → Claude Code 继续 100% 正常工作，ZhiXing 仿佛不存在。所有 MCP 工具调用都是 optional/best-effort，没有任何 blocking 依赖。

**原则 6：只捕决策和偏好，不碰代码内容**

```
✅ 捕获：「决定用 pgvector 而不是 Redis，原因是减少依赖」
✅ 捕获：「API 错误统一用 HTTPException，不用自定义异常类」
✅ 捕获：「这个开发者喜欢集成测试，几乎不写单元测试」
❌ 不捕获：具体代码片段、函数实现、变量名
❌ 不捕获：临时调试内容、「试一下 X」这类探索性语句
❌ 不捕获：用户明确标记为临时的内容
```

代码内容在 repo 里；捕进记忆只会带来噪声和隐私风险。

### 7.3 一个判断标准

> **「如果 ZhiXing 的 MCP 服务器今天突然断线，开发者的 Claude Code 体验是否完全正常？」**
>
> 答案必须是「是」。

能通过这个测试，才算满足「不破坏」底线。ZhiXing 对 Claude Code 只能是锦上添花，绝不是依赖项。

### 7.4 与 CLAUDE.md 的共存

ZhiXing 不替换 CLAUDE.md——它们是互补的：

| | CLAUDE.md | ZhiXing |
|-|-----------|---------|
| **维护方式** | 开发者手动写 | 自动 capture |
| **内容** | 项目规则、禁忌、结构 | 决策历史、偏好 Trait、跨项目画像 |
| **加载时机** | 每次 session 全量加载 | 按相关性分层加载 |
| **跨项目** | 不跨项目 | 跨项目统一画像 |

最终形态：CLAUDE.md 管「这个项目的规则」，ZhiXing 管「这个开发者是谁」。两者叠加，不冲突。

---

## 8. 为开发者效率场景重新定义记忆类型与 Trait

> 原有 neuromem 的记忆类型和 Trait 反思是为**个人情感陪伴**设计的（fact/episode/trait 记情绪、关系、生活偏好）。Claude Code 开发者场景完全不同——记忆的目的是**让 Claude 更高效地执行开发任务**，不是了解开发者的情感状态。
>
> 这一章定义面向开发者效率的新记忆类型、Trait 模型和 MCP 工具设计。

### 8.1 原有类型系统的问题

neuromem 的记忆类型：

| 类型 | 原始定义 | 在开发者场景的问题 |
|------|---------|-----------------|
| `fact` | 用户的个人事实（生日、家庭、偏好食物） | 与开发任务无关 |
| `episode` | 情感性记忆（某天发生了什么、心情如何） | 完全不适用 |
| `trait` | 性格特征（外向/内向、情绪模式） | 不是开发者需要的维度 |
| `procedural` | 习惯性行为流程 | 部分有用，但粒度不对 |
| `document` | 知识文档 RAG | 有用，但需要面向代码库重新设计 |

**根本问题：原有类型系统是为「了解这个人」设计的，不是为「帮这个人写代码」设计的。**

### 8.2 开发者效率场景的新记忆类型

| 类型 | 定义 | 示例 |
|------|------|------|
| `decision` | 已做出的技术/架构决策，含理由 | 「选择 asyncpg 而非 SQLAlchemy——因为项目全异步，不需要 ORM 抽象层」 |
| `rejection` | 被明确排除的方案，含原因 | 「排除 Redis——增加了一个需要运维的服务，现阶段不值」 |
| `convention` | 项目级的规范和约定 | 「错误处理统一用 HTTPException，不自定义异常类」「测试文件命名 test_*.py」 |
| `preference` | 跨项目的开发者偏好（技术选型倾向） | 「Python 后端首选 FastAPI；不用 ORM；喜欢 pydantic v2」 |
| `constraint` | 已知的限制条件 | 「ECS 是 Python 3.9，所有代码需要加 from __future__ import annotations」 |
| `context` | 当前工作状态（短期，session 级） | 「正在实现 OAuth2 登录，已完成 token 签发，下一步做 refresh」 |

**这 6 种类型覆盖了 Claude Code 需要知道的全部开发者上下文，没有一条是情感性的。**

类型之间的区别：
- `decision` vs `convention`：decision 是「为什么选这个」，convention 是「统一怎么做」
- `rejection` 独立成类型：「不做什么」和「做什么」同样重要，是防止 Claude 反复建议已被否决的方案的关键
- `preference` vs `decision`：preference 是跨项目的，decision 是项目内的
- `context` 是临时的，session 结束后降权或清除；其他类型是持久的

### 8.3 开发者 Trait 反思的重新定义

原有 Trait 反思从对话里归纳「情感模式」和「性格特征」，在开发者场景要改为归纳**技术行为模式**。

**开发者 Trait 的五个维度：**

**① 技术栈偏好（Stack Profile）**
```
归纳内容：在多个项目里重复出现的技术选型
示例 Trait：
  · Python 后端：FastAPI + asyncpg + pydantic（出现 4 次，强度：established）
  · 前端：Next.js App Router（出现 2 次，强度：emerging）
  · 数据库：PostgreSQL + pgvector，从不用 NoSQL（出现 6 次，强度：core）
用途：新项目 Day 1，Claude 不需要问「你用什么框架」
```

**② 架构风格（Architecture Style）**
```
归纳内容：跨项目一致的架构决策模式
示例 Trait：
  · 倾向 monolith，对 microservices 持保留态度（3 次明确表达）
  · 优先跑通 MVP，再做重构（vs 先设计再实现）
  · 错误处理集中在入口层，不在业务逻辑层到处 try/except
用途：Claude 给出的架构建议符合开发者的思维方式，减少来回
```

**③ 测试哲学（Testing Philosophy）**
```
归纳内容：测试风格和覆盖策略
示例 Trait：
  · 集成测试优先，单元测试极少（出现 5 次）
  · 不 mock 数据库，测试打真实 DB（多次明确拒绝 mock 方案）
  · 测试覆盖关键路径，不追求 100% 覆盖率
用途：Claude 建议测试方案时不会推荐不符合习惯的方式
```

**④ 与 Claude 的交互偏好（Interaction Style）**
```
归纳内容：开发者如何与 Claude Code 工作
示例 Trait：
  · 不需要解释「为什么这样做」，直接给代码
  · 喜欢先看完整方案，再讨论细节
  · 遇到问题先想看 error message 原文，不要 Claude 猜
用途：Claude 的回复风格自动适配，减少噪声
```

**⑤ 禁忌清单（Rejection Patterns）**
```
归纳内容：被多次明确拒绝的技术、方案、风格
示例 Trait：
  · GraphQL：明确拒绝 3 次（「REST 够用，不引入 GraphQL 的复杂度」）
  · ORM：拒绝 4 次（「我更喜欢直接写 SQL，清楚知道发生什么」）
  · 过度抽象：多次撤回 Claude 给出的抽象层，偏好直接实现
用途：Claude 不再反复建议已被否决的方案——这是减少摩擦的核心
```

**Trait 的生命周期（与原有系统对齐但语义改变）：**

```
trend      → 刚出现 1-2 次，还在观察
candidate  → 出现 3 次，有归纳价值
emerging   → 出现 4-5 次，开始影响建议
established → 出现 6+ 次，稳定特征，高置信度注入
core       → 跨多项目、多时间段一致，无条件注入
```

### 8.4 MCP 工具设计：为 Claude Code 任务执行服务

与原有 neuromem MCP（ingest/recall/digest/list/feedback...）不同，面向 Claude Code 的工具要更贴合开发工作流。

**工具设计原则：**
- 工具名和参数对 Claude 来说语义清晰，能自主判断何时调用
- 高频操作（recall、record_decision）必须低延迟
- 少即是多：6 个精准工具胜过 13 个模糊工具

**核心工具集：**

```
zhixing_project_context(project_path)
  → 用途：session 开始时调用，返回当前项目的 decisions + rejections + conventions
  → 触发：Claude Code 启动新 session，检测到 .git 目录时自动调用
  → 返回：L0 摘要（< 300 token），相关度排序

zhixing_developer_profile()
  → 用途：返回跨项目的 preference Traits（技术栈偏好、架构风格、交互偏好）
  → 触发：新项目首次 session，或用户明确要求时
  → 返回：established + core 级别的 Trait，< 200 token

zhixing_record_decision(summary, rationale, project?)
  → 用途：记录一个技术/架构决策
  → 触发：Claude 感知到对话里出现了明确的选型决定（「我们用 X」「决定不用 Y」）
  → 行为：完全异步，不阻塞响应

zhixing_record_rejection(approach, reason, project?)
  → 用途：记录明确排除的方案
  → 触发：Claude 感知到明确拒绝（「不用 X」「X 太复杂了不要」「我们之前说不用 Y」）
  → 重要性：rejection 记忆是防止 Claude 重复错误建议的关键，需单独类型

zhixing_recall(query, scope?)
  → 用途：按需检索，Claude 或用户主动查询历史决策
  → 触发：手动调用，或 Claude 感知到「我们之前怎么处理这个」类问题
  → scope：project（项目内）/ global（跨项目）/ rejections（只看排除项）

zhixing_update_context(summary)
  → 用途：更新当前工作状态（短期 context 类型）
  → 触发：完成一个阶段性工作时（「auth 模块完成，下一步做 rate limiting」）
  → 生命周期：session 内有效，跨 session 降权
```

**与原有 neuromem MCP 的对比：**

| 原有工具 | 开发者场景对应 | 变化 |
|---------|--------------|------|
| `ingest` (存记忆) | `record_decision` + `record_rejection` | 拆分为语义明确的两个动作 |
| `recall` (查记忆) | `project_context` + `developer_profile` + `recall` | 按使用场景分三个入口 |
| `digest` (反思提炼) | 后台自动，不暴露工具 | 开发者不感知，全自动 |
| `list` / `update` / `delete` | zhixing.cloud 界面操作 | 不通过 MCP 暴露，减少干扰 |
| `feedback` | 隐式：rejection 记忆本身就是反馈 | 不需要单独工具 |

### 8.5 session 完整工作流

```
开发者打开 Claude Code，开始新 session
    ↓
[第 1 轮，自动]
Claude 调用 zhixing_project_context("/path/to/project")
→ 注入：上次决定用 asyncpg、排除了 Redis、命名规范是 snake_case
→ 注入：developer profile（FastAPI 偏好、集成测试优先）
→ 总计 < 400 token，Claude 静默知晓，不向用户展示

[开发工作进行中]
用户：「给 user 表加个 role 字段，支持 admin/user 两种」
Claude：（知道这个项目用 asyncpg，直接生成 asyncpg 风格的迁移代码）
         → ZhiXing 后台静默 capture：无新决策，不记录

用户：「不用 Enum 类型，直接用 VARCHAR 加 CHECK 约束就好」
Claude：好的 → ZhiXing 后台静默 capture：
  record_decision("role 字段用 VARCHAR + CHECK 约束", "不引入 PG Enum 类型复杂度")

用户：「我们加个 Redis 做 session 缓存吧」
Claude：可以，方案是... → 用户：「算了，不加了，保持简单」
→ ZhiXing: record_rejection("Redis session cache", "保持简单，不增加依赖")

[session 结束]
ZhiXing 后台：
  → Trait 引擎分析：又一次拒绝了 Redis（第 3 次）→ redis_rejection Trait: candidate
  → 更新 context：「role 字段迁移完成」
```

这个流程里，开发者对 ZhiXing 的感知接近于零——它在做事，但不打扰人。

---

## 9. 结论

Claude Code 开发者的上下文遗忘问题是**已充分验证的强需求**：数十个 GitHub issues、社区自建工具、每周 3.7 小时的时间浪费、65% 的开发者有感。

现有解法（CLAUDE.md、Mem0、社区 MCP 工具）都停留在「存储」层面，没有「智能」层面的能力。

**ZhiXing 在这个场景的差异化路径是清晰的：**
- 记忆类型从「了解这个人」转向「帮这个人写代码」
- Trait 从「性格特征」转向「技术行为模式」
- MCP 工具从「通用记忆 API」转向「贴合开发工作流的 6 个精准工具」
- 「不破坏」原则保证开发者不会因为 ZhiXing 而放弃它

这是 ZhiXing 打开局面的起点。先在这个场景做深、做好，让开发者自己感受到「Claude Code 越来越懂我了」——然后再扩展到更多场景。

---

*来源：GitHub Issues anthropics/claude-code、Hacker News、DEV.to、Tyler Folkman Substack、Qodo State of AI Code Quality 2025、Augment Code、Mem0 OpenMemory*
