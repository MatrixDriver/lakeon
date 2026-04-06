# KB Wiki Engine 设计文档

> 基于 Karpathy "LLM Wiki" 理念，为 DBay 知识库增加 LLM 自动维护的 wiki 层，实现知识的结构化沉淀、可视化浏览和智能问答。

## 背景

DBay KB 当前是一个文档为中心的语义搜索引擎：文档上传 → 切片 → embedding → 向量检索。它缺少知识的结构化理解和持续积累能力。

Karpathy 提出的 LLM Wiki 模式：人负责"策展"（决定什么值得读），LLM 负责"管家"（读完后整理、关联、维护）。每篇文章进来后 LLM 自动更新 10-15 个 wiki 页面，知识像复利一样持续积累。

## 核心设计决策

| 决策项 | 结论 | 理由 |
|--------|------|------|
| 文章收集 | 用户上传为主，URL 抓取为辅 | 浏览器端上传最可靠，URL 抓取有反爬/登录墙限制 |
| 知识形态 | 自由 Markdown wiki 页面 | 不预定义结构，LLM 自己组织，适配各种领域 |
| Wiki 页面存储 | 作为特殊文档存在 documents 表中 | 复用现有文档管理、存储、切片、搜索能力 |
| 图谱来源 | 从 `[[wikilink]]` 自动提取 | 不额外维护关系表，和自由 Markdown 理念一致 |
| 处理时机 | 每篇文章 ingest 时立即更新 wiki | 与 Karpathy 做法一致，实时性好 |
| 并发控制 | 现有 KbWriteQueue 串行执行 | 10 人规模串行延迟可接受，避免并发覆盖 |
| 问答策略 | Router Agent 按复杂度选路径 | 简单问题查 wiki，深度问题查 wiki + 原始文档 |
| 对话沉淀 | 用户手动触发"沉淀到知识库" | 避免低价值内容自动写入 |
| LLM | DeepSeek 官网 API | 128K context，写作能力好，成本低 |
| Embedding | 现有 BGE-M3 不变 | wiki 页面走同样的 embedding 流程 |

## 阶段划分

### Phase 1：Wiki 引擎（个人验收）

用户上传文章后，系统自动生成/维护 wiki 知识网络，可浏览、可查图谱、可对话，有价值的对话结果可沉淀回 wiki。

### Phase 2：团队协作（给规划部用）

10 人团队共享知识库，权限管理，Web Clipper 浏览器插件。

### Phase 3：洞察分析（规划部深度使用）

分析模板（人物画像、技术趋势、竞争力沙盘），Lint Agent，分析结果反写 wiki。

---

## Phase 1 详细设计

### 1. 数据模型

#### 1.1 DocumentEntity 新增 type 字段

```sql
ALTER TABLE documents ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'raw';
-- type: 'raw' (用户上传的原始文档), 'wiki' (LLM 生成的 wiki 页面), 'index' (index.md / log.md)
```

Wiki 页面作为 `type='wiki'` 的文档存储，复用现有的 documents 表、OBS 存储、chunk 表。

#### 1.2 Wiki 页面的文档约定

- `filename`：wiki 页面标题，如 `Code Agent.md`、`Devin.md`
- `folder`：空（wiki 页面不分目录，通过 wikilink 组织）
- `tags`：LLM 自动生成（如 `["技术概念", "AI"]`）
- `metadata`：`{"wiki_version": "3", "last_source": "doc_xxx"}`（记录版本和最近更新来源）
- `format`：`MD`

#### 1.3 特殊文档

- `index.md`（type='index'）：wiki 目录，每个 wiki 页面一行（标题 + 一句话摘要），按分类组织
- `log.md`（type='index'）：操作日志，只追加，格式 `## [2026-04-06] ingest | 文章标题`

### 2. Ingest 流程

一篇文章上传后的完整处理链路：

```
文章上传 / URL 抓取
  ↓
① 存 OBS + 切片 + embedding              ← 现有能力
  ↓
② 生成 L1 文档摘要                        ← 现有 SummaryService
  ↓
③ Wiki Agent: 读 index.md                 ← 新增
   判断涉及哪些已有 wiki 页面
  ↓
④ Wiki Agent: 读相关 wiki 页面 + 文章全文   ← 新增
   决定创建新页面 / 更新已有页面
  ↓
⑤ 写回 wiki 页面到 OBS                    ← 新增
   wiki 页面切片 + embedding
  ↓
⑥ 更新 index.md                          ← 新增
  ↓
⑦ 追加 log.md                            ← 新增
```

步骤 ③-⑦ 作为 KbWriteQueue 的新任务类型 `WIKI_UPDATE`，在现有的 `DOCUMENT_SUMMARIZE` 之后执行。

#### 2.1 Wiki Agent Prompt 设计

Wiki Agent 是一个 LLM 调用链，核心 prompt 指导原则：

```
你是一个知识库管理员。一篇新文章已经进入知识库，你需要：

1. 阅读 index.md 了解当前知识库有哪些 wiki 页面
2. 阅读新文章全文
3. 判断这篇文章涉及哪些实体和概念：
   - 每个独立的实体（人物、公司、项目、技术）值得一个 wiki 页面
   - 已有页面需要更新的，合并新信息，不要重复
   - 矛盾观点标注来源并存（"来源 A 认为...，来源 B 认为..."）
4. 用 [[页面名]] 引用其他 wiki 页面
5. 输出：需要创建/更新的 wiki 页面列表，每个页面的完整 Markdown 内容
6. 输出：更新后的 index.md
7. 输出：log.md 追加条目
```

#### 2.2 LLM 调用细节

- API：DeepSeek 官网 `https://api.deepseek.com/v1/chat/completions`
- Model：`deepseek-chat`（DeepSeek-V3.2，128K context）
- 输入估算：index.md (~5K token) + 相关 wiki 页面 (~10-20K token) + 文章全文 (~5-15K token) = ~20-40K token
- 输出估算：~3-5K token（多个 wiki 页面内容）
- 成本：约 ¥0.05-0.1 / 篇

#### 2.3 结构化输出

Wiki Agent 的 LLM 输出需要结构化解析。使用 JSON 模式：

```json
{
  "wiki_pages": [
    {
      "title": "Code Agent",
      "action": "update",
      "content": "# Code Agent\n\nCode Agent 是...\n\n## 相关项目\n- [[Devin]]\n- [[Cursor]]\n..."
    },
    {
      "title": "Devin",
      "action": "create",
      "content": "# Devin\n\n[[Cognition]] 开发的..."
    }
  ],
  "index_entry": "- [Code Agent](Code Agent.md) — AI 驱动的自动编程代理技术\n- [Devin](Devin.md) — Cognition 开发的全自主 AI 编程助手",
  "log_entry": "## [2026-04-06] ingest | 《Devin: The First AI Software Engineer》\n更新: [[Code Agent]], 新建: [[Devin]], [[Cognition]]"
}
```

### 3. URL 抓取

#### 3.1 服务端抓取流程

```
用户提交 URL
  ↓
① HTTP GET 获取 HTML
  ↓
② Readability 提取正文（去导航、广告、侧栏）
  ↓
③ 转为 Markdown
  ↓
④ 提取图片 URL，下载图片到 OBS
  ↓
⑤ 替换 Markdown 中的图片 URL 为 OBS 路径
  ↓
⑥ 作为 type='raw' 文档存入知识库
  ↓
⑦ 进入正常 ingest 流程（切片 → 摘要 → wiki 更新）
```

#### 3.2 实现

在 orchestrator（Python）中实现，依赖：
- `httpx`：HTTP 请求
- `readability-lxml` 或 `trafilatura`：正文提取
- `markdownify`：HTML → Markdown

#### 3.3 限制

URL 抓取尽力而为。无法抓取时（反爬、登录墙等）返回错误，提示用户手动上传。不做复杂的反反爬虫。

### 4. Query Router Agent

#### 4.1 路由逻辑

```
用户提问
  ↓
LLM 判断问题复杂度（基于问题本身 + index.md）
  ↓
简单问题 → 读相关 wiki 页面 → 生成回答
深度问题 → 读相关 wiki 页面 + 检索相关 chunk → 需要时拉原始全文 → 生成回答
```

#### 4.2 检索策略

1. 先读 index.md，定位相关 wiki 页面
2. 读 wiki 页面获得框架性知识
3. 如需深入：用问题做向量检索，找到相关 chunk
4. 如 chunk 上下文不够：通过 chunk 的 document_id 拉原始全文（OBS）
5. 综合所有信息生成回答

#### 4.3 对话沉淀

回答返回给前端时附带 `can_save: true` 标记。前端展示"沉淀到知识库"按钮。

用户点击后调用 `POST /api/v1/knowledge/wiki/save-response`：
- 将回答内容交给 Wiki Agent
- Wiki Agent 决定创建新 wiki 页面还是更新已有页面
- 走正常的 wiki 更新流程（写 OBS → 切片 → embedding → 更新 index.md）

### 5. 前端

#### 5.1 Markdown 渲染

知识库详情页新增 wiki 页面浏览功能：
- 点击 wiki 页面，渲染 Markdown 内容
- `[[wikilink]]` 渲染为可点击链接，跳转到对应 wiki 页面
- 技术方案：`markdown-it` + 自定义 wikilink 插件

#### 5.2 Graph View

知识库详情页新增图谱标签页：
- 节点：每个 wiki 页面是一个节点
- 边：`[[wikilink]]` 引用关系
- 交互：拖拽、缩放、点击节点打开 wiki 页面
- 技术方案：D3 force layout（比 Vue Flow 更适合自由图谱布局）
- 数据来源：后端 API 解析所有 wiki 页面的 wikilink，返回 `{nodes: [...], edges: [...]}`

#### 5.3 文档类型区分

文档列表页：
- wiki 页面和原始文档用不同图标标记
- 新增筛选：全部 / 原始文档 / Wiki 页面
- wiki 页面显示版本号和最近更新来源

#### 5.4 对话界面

知识库详情页新增对话标签页（或在搜索标签页中增强）：
- 对话输入框
- 回答展示（Markdown 渲染，wikilink 可跳转）
- 回答下方"沉淀到知识库"按钮
- 对话历史（当前会话内）

### 6. API 新增端点

| 端点 | 方法 | 功能 |
|------|------|------|
| `POST /knowledge/ingest-url` | POST | URL 抓取：下载文章 + 图片，存入 KB |
| `GET /knowledge/wiki/pages` | GET | 列出 wiki 页面（type=wiki 的文档） |
| `GET /knowledge/wiki/graph` | GET | 返回图谱数据（nodes + edges，从 wikilink 提取） |
| `POST /knowledge/wiki/chat` | POST | 对话问答（Query Router Agent） |
| `POST /knowledge/wiki/save-response` | POST | 将对话回答沉淀为 wiki 页面 |

### 7. 与现有能力的关系

| 现有能力 | 变化 |
|---------|------|
| 文档上传 | 不变，上传的文档 type='raw' |
| 切片 + embedding | 不变，wiki 页面也走同样流程 |
| L1 文档摘要（SummaryService） | 保留，作为 Wiki Agent 的输入之一 |
| L2 KB 摘要 | 被 index.md 替代 |
| 向量搜索 | 不变，wiki 页面的 chunk 也参与搜索 |
| 文件夹结构 | 不变，wiki 页面不分文件夹 |
| 标签/元数据 | 不变，wiki 页面也有标签（LLM 自动生成） |

### 8. KbWriteQueue 任务类型新增

```java
WIKI_UPDATE    // Wiki Agent: 创建/更新 wiki 页面 + index.md + log.md
```

执行时机：在 `DOCUMENT_SUMMARIZE` 完成后自动触发。

归类为轻量级任务（直接在 API 进程中执行 LLM 调用），不需要单独的 Job Pod。

---

## Phase 1 验收标准

1. 上传 10 篇关于 Code Agent 的文章
2. 系统自动生成 wiki 页面（Devin、Cursor、Code Agent、OpenAI 等）
3. 浏览 wiki 页面：Markdown 渲染正确，`[[wikilink]]` 可点击跳转
4. Graph View 展示实体关系图谱，可拖拽交互
5. 对话提问"Code Agent 的发展趋势"，基于 wiki + 原始文档给出合理回答
6. 上传第 11 篇文章，已有 wiki 页面被正确更新（不重复创建）
7. 对话回答点击"沉淀到知识库"，内容写回 wiki 并在图谱中可见

## Phase 2 概要（团队协作）

- 团队/组织模型
- 知识库级别的共享和权限（管理员/编辑/只读）
- 多人同时上传和浏览
- Web Clipper 浏览器插件

## Phase 3 概要（洞察分析）

- 分析模板（人物画像、技术趋势、竞争力沙盘）
- Lint Agent（定期检查矛盾、过时信息、知识空白）
- Reflect Agent（跨文档发现隐含关联）
- 分析结果反写 wiki

## 依赖

- LLM：DeepSeek 官网 API（`https://api.deepseek.com`）
- Embedding：现有 BGE-M3
- 前端：markdown-it（Markdown 渲染）、D3 force layout（Graph View）
- Python（orchestrator）：httpx、trafilatura（URL 抓取）
- 无需额外基础设施
