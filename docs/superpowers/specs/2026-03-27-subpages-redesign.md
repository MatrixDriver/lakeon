# 子页面风格统一设计文档

## 概述

将产品页、集成页、博客、文档等子页面统一到首页的设计语言：白底蓝色、渐变 SVG、大留白、现代感。所有页面完全重写。

**设计语言**：沿用首页已建立的 CSS 变量（`--pub-primary` 系列）、配色、排版、卡片样式、CTA 按钮。

## 页面清单

### 1. 产品总览页 `/product`

**布局**：
- Hero 居中：标题"一个平台，四种数据能力" + 副标题
- 四个产品卡片 2x2 网格，每个卡片：
  - 左侧渐变色竖条（Lakebase 蓝、知识库绿、记忆库橙、数据湖紫）
  - 图标 + 标题 + 一句话描述 + 2-3 个 feature tag
  - "了解更多 →" 链接到详情页
- 底部 CTA："立即试用"

### 2. 产品详情页 x4

新增路由：`/product/lakebase`, `/product/knowledge`, `/product/memory`, `/product/datalake`

每个详情页统一模板结构：
- **Hero**：标题 + 副标题 + 对应颜色渐变背景条
- **Feature section**：功能亮点列表，每个功能一行（图标 + 标题 + 描述）
- **代码示例 / 截图区**（如有）
- **底部 CTA** + "← 返回产品总览"链接

各页面保留现有的文案内容（从 ProductView.vue 迁移）：
- **Lakebase**：3ms 热启动、存算分离、分支、时间旅行、多租户、AI SQL
- **知识库**：文档解析、向量搜索、全文搜索、表知识库、混合检索、内置 embedding
- **记忆库**：3 个核心 API（ingest/recall/digest）、4 种记忆类型、Trait 生命周期、LoCoMo benchmark（81.7%）
- **数据湖**：Python/Ray 调度、Parquet 导出、微调、Kata 隔离、数据飞轮、CDC

### 3. 集成页 `/integrations`

**布局**：
- Hero 居中：标题"连接你的 AI 工具" + 副标题
- **Quickstart section**：保留现有 pip install + claude mcp add 代码块，新风格（深色代码块 + copy 按钮）
- **工具卡片网格**：6 个工具卡片（OpenClaw/Claude Code/Claude Desktop/Cursor/Gemini CLI/ChatGPT），新风格：
  - 白色卡片 + 圆角 + 投影
  - 工具名 + 一句话描述 + featured 标签（OpenClaw/Claude Code）
  - "查看指南 →" 链接
- OpenClaw 保持独立详情页 `/integrations/openclaw`
- **底部**：四个协议快速参考（MCP / Skill / PG / REST），每个一行代码示例

**OpenClaw 详情页** `/integrations/openclaw`：保留现有内容，只做视觉风格统一（配色、卡片、间距、代码块样式）。

### 4. 博客列表 `/blog`

**布局**：
- Hero 简洁：标题"Blog" + 副标题
- **Featured 文章**：最新一篇全宽大卡片（标题 + 摘要 + 日期 + 分类 tag + "阅读 →"）
- **其他文章**：两列卡片网格（标题 + 摘要 + 日期 + 分类 tag）
- 卡片带 hover 投影效果

### 5. 博客文章 `/blog/:slug`

**布局**：
- 居中内容区（max-width 720px）
- 顶部：分类 tag + 日期 + 大标题 + 摘要（灰色）
- 正文 Markdown：统一到新设计语言
  - h2/h3 用 `--pub-text` 颜色
  - 代码块深色背景 + 圆角
  - 引用块左蓝色边框
  - 链接蓝色
- 底部："← 返回博客" 链接

### 6. 文档页 `/docs`

**布局**：
- 左侧固定侧边栏（240px）：
  - 分组标题（"入门"/"参考文档"）
  - 当前页高亮蓝色 + 浅蓝背景
  - 圆角 hover 效果
- 右侧内容区：全宽，白色背景
- 内容区的标题、段落、代码块、表格统一新风格

**保持现有文档内容不变**（DocsHome/DocsRestApi/DocsPythonSdk/DocsDeploy/DocsMcp），只改外层布局和全局样式。

## 路由变更

新增：
- `/product/lakebase` → ProductLakebaseView.vue
- `/product/knowledge` → ProductKnowledgeView.vue
- `/product/memory` → ProductMemoryView.vue
- `/product/datalake` → ProductDatalakeView.vue

现有路由保持不变。`/product` 从详情页改为总览页。

## 视觉规范

沿用首页 CSS 变量和配色，不再重复。新增的共性样式：

### 子页面 Hero
- padding: 48px
- 居中标题 32px font-weight 700
- 副标题 16px `--pub-text-2`
- 背景 `--pub-surface`

### 卡片样式
- background: `--pub-surface`
- border: 1px solid `--pub-border`
- border-radius: 12px
- padding: 24px
- hover: box-shadow 加深

### 代码块
- background: #1e293b
- border-radius: 8px
- padding: 16px
- color: #a5f3fc（cyan）
- 右上角 copy 按钮

## 技术实现

### 文件改动
- Rewrite: `ProductView.vue` → 产品总览页
- Create: `ProductLakebaseView.vue`, `ProductKnowledgeView.vue`, `ProductMemoryView.vue`, `ProductDatalakeView.vue`
- Rewrite: `IntegrationsView.vue`
- Style update: `OpenClawView.vue`（只改视觉，不改内容）
- Rewrite: `BlogListView.vue`
- Rewrite: `BlogPostView.vue`
- Rewrite: `DocsLayout.vue`
- Modify: `router/index.ts`（新增 4 个产品详情路由）

### 保留
- 所有现有文案内容（中英双语）
- blog-posts.ts 数据源
- 文档内容页（DocsHome 等）不改
- OpenClaw 页内容不改
- Trial 功能
- i18n / theme 系统

### 首期范围
- 以上全部页面的完全重写/风格统一

### 不在范围
- 新增文档内容
- 新增博客文章
- 暗色模式细调
