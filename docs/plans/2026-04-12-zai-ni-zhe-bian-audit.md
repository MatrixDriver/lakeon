# 在你这边 · 立意审计 backlog

> 日期：2026-04-12
> 背景：确定品牌立意为"在你这边"（用户拥有自己的记忆和知识，不归任何 Agent 厂家）之后，对现有特性做了一次诚实审计。本文记录审计结论和未实现的改进建议，作为后续迭代的 backlog。
>
> 当前已落地的事：
> - Landing / Product / Integrations / Docs 等公网页面围绕"在你这边"立意重写
> - Memory 产品页改写为**基础库级**加密的诚实框架（反思型记忆库 vs 私密记忆库），不再过度宣称"服务端只看到密文"
>
> 本文以下各条都是**待实现**的，用来引导后续的产品和工程决策。

---

## Part 1 · 立意 violations / 当前张力

按严重度排。这几件事是当前架构或宣传和立意之间的真实张力，没完全解决掉。

### 🔴 严重：Memory digest 读明文（已通过基础库级框架部分化解）

**问题**：反思型记忆库里的 memory 以明文进入服务端数据库，服务端每晚读它、跑 LLM digest 抽 traits。如果对外说"服务器只看到密文"，那是谎话。

**当前处理**：Memory 产品页已经改为诚实的"两种记忆库"框架：
- 反思型记忆库 = 服务端能读 + 参与 digest
- 私密记忆库 = 客户端端到端加密 + 服务端完全看不见 + 不做 digest

**未尽事宜**：
- 私密记忆库目前**整库**加密，不支持混合。长期看，per-entry sensitive 是更细粒度的解法——一个库里有的条目进 digest，有的不进。见 Part 3 / #3。
- 服务端的 digest 只有基础的 scheduled job，用户不知道每晚发生了什么。加 audit log 对用户更透明。见 Part 3 / #4。

### 🟡 中：第三方 LLM 看用户内容

**问题**：DBay 用华为云 MaaS 的 DeepSeek V3.2 跑 digest / wiki 生成 / 知识链接。这意味着用户的对话和文档会被**第三方 LLM provider** 读到。用户看到 "在你这边" 会默认 "除了我和 DBay 没人看得见"，但 LLM provider 也看得见。

**隐藏问题**：华为云 MaaS 的数据留存策略、是否用于训练，用户很可能没读过，我们也没披露。

**解法方向**（从低到高门槛）：
1. **披露**（最低成本）：Docs 加一页 "谁读到了你的数据"，明确列出当前用的 LLM provider、数据路径、是否留存
2. **合约**（中成本）：和华为云 MaaS 谈一个 no-retention / no-training 合约，写进 docs
3. **BYO-LLM key**（高收益）：用户填自己的 OpenAI / Anthropic / 本地模型 key，DBay 用用户的 key 跑 digest。这样 DBay 服务端自己都不用付 LLM 成本，商业模型也变得干净。见 Part 3 / #2。

### 🟡 中：hosted on Huawei Cloud

**问题**：一切跑在华为云 CCE。"在你这边" ≠ self-hosted。硬核用户（尤其海外）会问"能不能 self-host"，答不出来就掉粉。

**解法方向**：
1. **澄清**（最低成本）：Docs 加一段明确区分 "ownership of data" vs "hosting of infra"。Signal 也是 AWS，用户照样信它——关键是数据归属权，不是机房位置。
2. **中长期**：有一个可行的 self-host 路线图——至少给出 docker-compose 起一套的方案，即便大多数用户不用。

### 🟢 轻：服务端处理过程对用户不透明

**问题**：服务端做了很多事（digest / curate / reflect / index），但用户看不到"发生了什么"。"我不知道它在我数据上做了什么"是一种慢性不信任。

**解法**：audit log 面板。见 Part 3 / #4。

---

## Part 2 · 已经对立意有贡献的特性（需要在网站和产品里更用力讲）

排序按对终端用户的吸引力：

| 特性 | 现状 | 网站上讲清楚了吗 | 
|---|---|---|
| 跨 Agent / 跨项目共用同一份 memory + knowledge | ✅ 已 ship | ✅ hero + ownership screen |
| 私密记忆库端到端加密 | ✅ 已 ship | ✅ Memory 产品页（2026-04-12 已诚实化） |
| 团队知识分享（opt-in，用户点一下） | ✅ 已 ship | ⚠️ 只在 Console 讲，公网没讲透——是 B2B 转化缺口 |
| Tenant 隔离 + 数据不做跨账户聚合 | ✅ 已 ship | ✅ ownership card 2 |
| 任何 MCP 兼容 Agent 可接入 | ✅ 已 ship | ✅ ownership card 3 + Integrations 页 |
| Copy-on-write 分支 + time travel | ✅ 已 ship | ⚠️ Lakebase 页提了，但没落在"用户视角"——在 Memory / Knowledge 场景下也能用 |
| Memory 反思洞察（digest + reflection） | ✅ 已 ship | ✅ Memory 产品页 |
| Wiki 条目间自动链接、持续维护一致性 | ✅ 已 ship | ✅ Knowledge 产品页 |

**主要缺口**：团队分享只在 Console 里讲。公网应该有一个"给团队用"的 section——它既是立意的自然延展（你拥有 → 你决定分享给谁），又是 B2B 转化钩子。见 Part 3 / #5。

---

## Part 3 · 应该加的特性（按立意贡献度排序）

这是 backlog 核心——把立意从"一句话口号"变成"用户能指着看的东西"。按推荐度排：

### ⭐⭐⭐⭐⭐ #1 · 一键全量导出 `dbay export --all`

**是什么**：一条命令把用户的所有数据（memory + wiki + knowledge + database dumps）打包导出到本地。格式建议：`jsonl` + `markdown` + `parquet`。

**为什么这是立意的核弹**：用户**能随时带着所有东西走**。这是 "ownership" 最不可辩驳的证明——不是口号，是一个能跑通的命令。Obsidian 赢 Notion 靠的就是这个。

**工程量**：中。后端的 API 都已经有了（memory list / knowledge list / kb export），CLI 加一个 `dbay export --all` 把它们串起来、打包、写本地。

**网站呈现**：
- CLI docs 里专门有一 section，标题 `dbay export —— 随时带走`
- Landing 或 "在你这边" screen 里提一句 "要走？一条命令，全带走"

**开发要点**：
- 导出要包含：memory 条目（含 embeddings 如果可能）、wiki 条目和链接、knowledge base 原始文档、database 的 SQL dump
- 对私密记忆库：导出的是密文 + 客户端的私钥（让用户能离线还原）
- 格式对**可回载**优化：能用 `dbay import --all` 导回来，完成迁移测试

---

### ⭐⭐⭐⭐⭐ #2 · BYO-LLM key（自带大模型）

**是什么**：用户在设置里填 OpenAI / Anthropic / 本地 LLM 的 API key。之后所有 digest / wiki 生成 / 知识链接用**用户自己的** LLM 跑，DBay 服务端不接触明文对话。

**为什么**：
- 直接解决 Part 1 的 🟡 #2（第三方 LLM 看用户内容）
- 对硬核用户是"放大招"信号
- 对普通用户也是一个"我不理解但听起来很严肃"的信任符号
- **商业 bonus**：DBay 省掉所有 LLM 成本，商业模型变得干净——用户付的是"结构化存储和调度"，不是"转售 token"

**工程量**：中高
- 需要在 digest / wiki / curate 这些地方接一个 LLM provider 抽象
- 支持 OpenAI / Anthropic / Ollama / 自托管 vLLM
- BYO-LLM key 要加密存储（或走用户的秘钥管理）

**网站呈现**：
- 独立的 BYO-LLM 页或 FAQ section，标题 `自己的 LLM，自己看自己的数据`
- Pricing / 商业页（如果有）的 tier 差异化点

**开发要点**：
- LLM provider abstraction（已部分存在于 `memory/service/llm_client.py`）
- 前端设置页加一个 "LLM provider" 配置入口
- 默认 provider 保留 DBay 托管的，用户可 override
- digest / wiki 生成任务队列要按 per-user LLM config 路由

---

### ⭐⭐⭐⭐ #3 · Per-entry sensitive（在一个反思型记忆库里混合）

**是什么**：给 `memories` 表加 `sensitive BOOLEAN DEFAULT FALSE` 列。一个反思型记忆库里，大部分条目是明文（参与 digest），少数条目标记为 sensitive（客户端加密、服务端看不见、不参与 digest）。

**为什么**：
- 当前 base-level 加密不够细——用户要私密一条记忆就得另起一个私密库，粒度粗
- 更符合直觉：用户说"记住一件私事"时，不该要求他先判断"这该放哪个库"
- 立意落地更彻底：任何一个库都能有 selective privacy

**工程量**：中高（跨 Java API、Python memory-svc、dbay-cli、dbay-mcp、Console）
- `memory/service/schema.py`：加 `sensitive BOOLEAN DEFAULT FALSE` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
- `memory/service/engine.py`：ingest 接受 sensitive、recall 返回 sensitive、digest 查询加 `WHERE sensitive = FALSE`
- `memory/service/models.py`：`IngestRequest` 加 `sensitive: bool = False`
- `lakeon-api`：proxy 层把 sensitive 透传；MCP tool description 更新
- `dbay-mcp`：正则兜底（检测 secrets 自动升级为 sensitive）+ LLM judgment via tool description
- `dbay-cli`：如果基础库不是 encrypted，sensitive 条目需要**惰性**初始化一套 per-base DEK 和公私钥，存到本地 `~/.dbay/`——这是最大的工程点
- Console：memory list 显示 🔒 badge；顶部统计条（"286 条记忆 · 47 条私密（不参与反思）"）

**子决策**：
- 默认值：plain 为默认 + 客户端正则兜底检测 secrets 自动升 sensitive
- MCP tool description 里明确 sensitive 触发条件（用户说"记住一件私事"、内容是秘钥 / 密码 / 私人健康 / 薪资 / 面试状态等）
- 中文场景要给 LLM 额外的中文触发词引导
- 一旦写入 plain 就不能回滚到 sensitive（服务端已经读过了）

**网站呈现**：
- Memory 产品页 "两种记忆库" section 可扩展为"两种记忆库 + 一条记忆的私密开关"
- MCP tools page 的 `memory_ingest` 加一个 `sensitive` 参数的说明

**子文档**：MCP tool description 草案（英文优先，LLM 判断更稳）：

```
sensitive (boolean, default false):
  Set to true when the content should never be read by the DBay server.
  Sensitive entries are encrypted on the client before upload; the server
  can search them (via pre-computed embeddings) but cannot read the
  plaintext, and they are excluded from nightly digest and reflection.

  Use sensitive=true when:
  - User explicitly asks to "remember this privately" / "记住一件私事" /
    "私密记忆" / "保密" / "别让 DBay 看到"
  - Content contains secrets: API keys, passwords, tokens, private keys,
    database URLs with credentials
  - Content is personally sensitive: health details, salary, relationship
    notes, therapist advice, interview status, unreleased plans, legal matters
  - Common sense says the user would not want a third party to read it

  Use sensitive=false (default) when:
  - User is recording coding preferences, conventions, decisions,
    architectural choices
  - Content is work context that benefits from being reflected in the
    nightly digest
  - User has not signaled any privacy preference and the content is not
    inherently secret

  Trade-off: sensitive entries do not get reflection insights. If the
  user marks everything sensitive, the nightly digest has nothing to work
  with and the "who you are" picture will be sparse.
```

---

### ⭐⭐⭐ #4 · Audit log 面板（"谁看了我的数据"）

**是什么**：Console 里一个时间线视图，用户能看到过去 N 天里：哪些服务组件访问了哪些数据、什么时间、为了什么（digest / wiki curate / recall / API call / MCP ingest）。

**为什么**：不是立意本身，但把立意**可验证**。用户一打开就能指着说"看，你真的只在 digest 的时候读了，别的时间没动过"。

**工程量**：中。大部分 log 已经存在 `dbay-logs`（system tenant 里的 log 数据库），主要工作是给用户自己的那一部分做个视图。

**网站呈现**：
- Console 产品 page 提一句"每一次读取都有记录"
- Memory 产品页加一句类似的保证

**开发要点**：
- 定义"数据访问事件"的 schema：`(timestamp, tenant_id, user_id, resource_type, resource_id, operation, component, reason)`
- 每次 digest / reflect / recall / ingest 时 emit 一条事件到 dbay-logs
- Console 加一个 `/audit` 或 `/activity` 页面做时间线视图

---

### ⭐⭐⭐ #5 · 团队共享作为独立 section

**是什么**：不是新特性，是把已有的 KB sharing 在**公网**上讲清楚：怎么邀请团队、谁看什么、怎么撤销、团队记忆和个人记忆怎么分开。

**为什么**：立意的自然延展——用户拥有，所以用户可以决定谁看。同时是 B2B 转化的钩子。

**工程量**：文档和页面就行，底层已经 ship。

**网站呈现**：
- Option A：Landing 加一 screen "带着团队一起"
- Option B：`/product/memory` + `/product/knowledge` 各加一个 "给团队用" 的短 section
- Option C：独立一页 `/teams` 讲 B2B 使用场景

---

### ⭐⭐ #6 · 透明度报告 / 数据请求页

**是什么**：一个静态页面（`/transparency` 或 `/trust`），每季度或半年更新：
- 有多少数据被执法机关要求
- 有多少被拒
- 当前用的 LLM provider 是谁
- 有没有 no-retention 协议
- 过去半年的安全事件（如果有）

**为什么**：Signal 和 Proton 都做这个。品牌层面的持续信任投入，把立意变成一个**周期性的承诺**，而不是一次性的口号。

**工程量**：文档页，零工程。

**建议**：先做一个空的模板页，等第一次有实际数据时填。即使内容是"过去 Q2 没有收到任何数据请求"也有信号价值。

---

### ⭐⭐ #7 · Open-source dbay-cli + dbay-mcp

**是什么**：把客户端和 MCP 代码放 GitHub，MIT 协议。

**为什么**：
- 硬核用户可以自己读代码，确认客户端加密是真的，确认没有偷偷上传什么
- 开发者社区建立信任的标准做法
- Bonus：社区贡献、口碑、PR 流量

**工程量**：几乎零（代码已存在），主要是：
- 法务（确保没有依赖闭源 SDK）
- 发布流程（版本号、changelog、CI/CD）
- README / CONTRIBUTING / LICENSE

**建议**：先开源 `dbay-cli` 和 `dbay-mcp`（客户端部分）。服务端部分（lakeon-api / memory-svc）暂不开源——保留商业差异化空间。

---

## 实施优先级

如果只能做 3 件事，按顺序：

1. **`dbay export --all`**（#1）—— 最便宜的立意核弹，一个命令就让 ownership 从抽象变具象
2. **BYO-LLM key**（#2）—— 最贵但也最解决根本问题，把"LLM 看用户数据"的张力一并解决
3. **Per-entry sensitive**（#3）—— 让立意的粒度从"库"降到"条"，用户心智负担最小

#4-#7 是中长期品牌投入，不急但值得做。

---

## 更新记录

- **2026-04-12**：初版。Memory 产品页已按 base-level 框架诚实化落地。Backlog 的其他项目均未实现。
