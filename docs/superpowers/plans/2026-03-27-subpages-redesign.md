# 子页面风格统一实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将产品页、集成页、博客、文档等子页面统一到首页的设计语言，完全重写所有子页面。

**Architecture:** 产品页拆为总览页 + 4 个详情页。集成页、博客列表/文章、文档布局重写。所有页面共享首页 CSS 变量和设计语言。OpenClaw 只做样式统一。每个页面是独立 Vue SFC。

**Tech Stack:** Vue 3 Composition API, TypeScript, CSS 变量主题, vue-router

**Spec:** `docs/superpowers/specs/2026-03-27-subpages-redesign.md`

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `lakeon-console/src/router/index.ts` | 修改 | 新增 4 个产品详情页路由 |
| `lakeon-console/src/views/product/ProductView.vue` | 重写 | 产品总览页（四卡片入口） |
| `lakeon-console/src/views/product/ProductLakebaseView.vue` | 新建 | Lakebase 详情页 |
| `lakeon-console/src/views/product/ProductKnowledgeView.vue` | 新建 | 知识库详情页 |
| `lakeon-console/src/views/product/ProductMemoryView.vue` | 新建 | 记忆库详情页（含 benchmark） |
| `lakeon-console/src/views/product/ProductDatalakeView.vue` | 新建 | 数据湖详情页 |
| `lakeon-console/src/views/integrations/IntegrationsView.vue` | 重写 | 集成页（工具卡片网格） |
| `lakeon-console/src/views/integrations/OpenClawView.vue` | 修改 | 仅样式统一 |
| `lakeon-console/src/views/blog/BlogListView.vue` | 重写 | 博客列表（featured + 网格） |
| `lakeon-console/src/views/blog/BlogPostView.vue` | 重写 | 博客文章（居中全宽） |
| `lakeon-console/src/views/docs/DocsLayout.vue` | 重写 | 文档侧边栏布局 |

---

### Task 1: 路由 + 产品总览页

**Files:**
- Modify: `lakeon-console/src/router/index.ts`
- Rewrite: `lakeon-console/src/views/product/ProductView.vue`

- [ ] **Step 1: 添加产品详情路由**

在 `router/index.ts` 中，找到 `{ path: 'product', name: 'Product', component: ... }` 这一行，在它后面添加 4 个新路由：

```typescript
{ path: 'product/lakebase', name: 'ProductLakebase', component: () => import('../views/product/ProductLakebaseView.vue') },
{ path: 'product/knowledge', name: 'ProductKnowledge', component: () => import('../views/product/ProductKnowledgeView.vue') },
{ path: 'product/memory', name: 'ProductMemory', component: () => import('../views/product/ProductMemoryView.vue') },
{ path: 'product/datalake', name: 'ProductDatalake', component: () => import('../views/product/ProductDatalakeView.vue') },
```

- [ ] **Step 2: 重写 ProductView.vue 为总览页**

完全替换 `ProductView.vue` 为产品总览页，包含四个入口卡片。保留所有中英文文案。布局：Hero 居中 + 2x2 卡片网格 + 底部 CTA。

每个卡片带左侧渐变色条（蓝/绿/橙/紫），图标 + 标题 + 描述 + feature tags + "了解更多 →" 链接。

使用首页同样的 CSS 变量（`--pub-primary`, `--pub-surface` 等），同样的按钮样式、间距和字体。

关键文案（从现有代码迁移）：
- Lakebase: "Serverless PostgreSQL，存算分离，自动扩缩容"
- 知识库: "文档 + 表 + 向量检索，内置 Embedding 与 Reranker"
- 记忆库: "为 AI Agent 提供结构化长期记忆，越用越懂你"
- 数据湖: "数据处理 + 训练 + 飞轮"

- [ ] **Step 3: 验证构建**

Run: `cd lakeon-console && npx vite build --mode development 2>&1 | tail -5`

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/router/index.ts lakeon-console/src/views/product/ProductView.vue
git commit -m "feat(product): rewrite product overview page with card grid + add detail routes"
```

---

### Task 2: Lakebase 详情页

**Files:**
- Create: `lakeon-console/src/views/product/ProductLakebaseView.vue`

- [ ] **Step 1: 创建详情页**

新建 Vue SFC。结构：
- Hero: 蓝色渐变背景条 + 标题 "🐘 Lakebase" + 副标题 "Serverless PostgreSQL，存算分离，自动扩缩容"
- Feature 列表（6 项，从现有 ProductView 迁移）：
  - 3ms 热启动，3s 冷启动
  - 存算分离，自动扩缩容
  - 数据库分支与时间旅行
  - 多版本管理与回滚
  - 多租户隔离
  - AI SQL 助手
- 每个 feature: 图标 + 标题 + 一行描述，竖向排列
- 底部: "立即试用 →" CTA + "← 返回产品总览" 链接
- 使用 `useLocale` 的 `t()` 做双语
- CSS 用 `--pub-*` 变量

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/product/ProductLakebaseView.vue
git commit -m "feat(product): add Lakebase detail page"
```

---

### Task 3: 知识库详情页

**Files:**
- Create: `lakeon-console/src/views/product/ProductKnowledgeView.vue`

- [ ] **Step 1: 创建详情页**

同 Task 2 模板结构，绿色渐变。标题 "📚 知识库" + 副标题。
Feature 列表（6 项）：
- 文档自动解析（PDF / Word / Markdown）
- 向量检索（pgvector）
- 全文搜索（tsvector / RUM）
- 表知识库（结构化数据）
- 向量 + 全文混合检索
- 内置 Embedding 与 Reranker

底部 CTA + 返回链接。

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/product/ProductKnowledgeView.vue
git commit -m "feat(product): add Knowledge Base detail page"
```

---

### Task 4: 记忆库详情页

**Files:**
- Create: `lakeon-console/src/views/product/ProductMemoryView.vue`

- [ ] **Step 1: 创建详情页**

橙色渐变。标题 "🧠 记忆库" + 副标题。这个页面内容最多，包含：

**Section 1: 三个核心操作**（从现有 ProductView 迁移 coreApis computed）
- `ingest`: 将对话和事实存入长期记忆...
- `recall`: 混合检索记忆...
- `digest`: 将记忆合成洞察...
每个 API 一张卡片，3 列网格。

**Section 2: 四种记忆类型**（迁移 memoryTypes）
- 📋 事实 / 🕐 事件 / 🧠 特征 / 📄 文档
2x2 网格，每个带图标 + 标题 + 描述。

**Section 3: 特征生命周期**（迁移 traitStages）
趋势 → 候选 → 萌发 → 确立 → 核心 → 消解
横向 6 步进度条。

**Section 4: LoCoMo Benchmark**（迁移 subScores + benchmarkBars）
- 大数字 81.7%
- 4 个子分数卡片
- 5 个对比条形图

底部 CTA + 返回链接。

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/product/ProductMemoryView.vue
git commit -m "feat(product): add Memory Store detail page with benchmark"
```

---

### Task 5: 数据湖详情页

**Files:**
- Create: `lakeon-console/src/views/product/ProductDatalakeView.vue`

- [ ] **Step 1: 创建详情页**

紫色渐变。标题 "🌊 AI 数据湖" + 副标题。
Feature 列表（6 项）：
- Python / Ray 任务调度
- Dataset 导出（Parquet）
- 模型微调支持
- Kata VM 安全隔离
- DB ↔ 数据湖 数据飞轮
- 增量 CDC 调度

底部 CTA + 返回链接。

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/product/ProductDatalakeView.vue
git commit -m "feat(product): add Data Lake detail page"
```

---

### Task 6: 集成页重写

**Files:**
- Rewrite: `lakeon-console/src/views/integrations/IntegrationsView.vue`

- [ ] **Step 1: 重写集成页**

结构：
- Hero 居中: "连接你的 AI 工具" + 副标题
- Quickstart section: 保留现有 pip install + claude mcp add 代码（深色背景 #1e293b + copy 按钮）
- 工具卡片网格（3 列）: 6 张工具卡片，每个带：
  - 工具名（OpenClaw/Claude Code 带 featured badge）
  - 一句话描述（中英双语，从现有代码迁移）
  - "查看指南 →" 链接（OpenClaw → /integrations/openclaw，其他 → hash anchor）
- 底部协议参考: MCP / Skill / PG / REST 四列，每个一行代码示例
- 底部 CTA

迁移现有 6 个 integration 定义和所有中英文文案。

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/integrations/IntegrationsView.vue
git commit -m "feat(integrations): rewrite integrations page with new design"
```

---

### Task 7: OpenClaw 页样式统一

**Files:**
- Modify: `lakeon-console/src/views/integrations/OpenClawView.vue`

- [ ] **Step 1: 更新 CSS 变量引用**

保持所有内容和结构不变，只修改 `<style scoped>` 部分：
- Hero 背景改用 `--pub-surface` + `--pub-primary` 渐变
- 标题字体大小和粗细匹配新设计（32px/700）
- 卡片改用 `--pub-surface` 背景 + `--pub-border` 边框 + 12px 圆角
- 代码块改用 #1e293b 背景 + 8px 圆角
- CTA 按钮改用 `--pub-primary` 蓝色
- 间距统一到 48px padding

不要修改任何 `<template>` 或 `<script>` 内容。

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/integrations/OpenClawView.vue
git commit -m "feat(integrations): unify OpenClaw page visual style"
```

---

### Task 8: 博客列表 + 文章页重写

**Files:**
- Rewrite: `lakeon-console/src/views/blog/BlogListView.vue`
- Rewrite: `lakeon-console/src/views/blog/BlogPostView.vue`

- [ ] **Step 1: 重写博客列表页**

结构：
- Hero 简洁: "Blog" + 副标题 "产品更新、技术解析、使用指南"
- Featured 文章（第一篇）: 全宽大卡片，带标题 + 摘要 + 日期 + 分类 tag + "阅读 →"
- 其他文章: 两列卡片网格（标题 + 摘要 + 日期 + 分类 tag），hover 投影
- 保留现有 `import posts from '../../data/blog-posts'` 数据源
- 保留日期排序逻辑

- [ ] **Step 2: 重写博客文章页**

结构：
- 居中内容区 max-width 720px
- 顶部: 分类 tag（蓝色小字）+ 日期 + 大标题（32px）+ 摘要（灰色斜体）
- 正文 Markdown 深度样式:
  - h2: 24px bold, 上方 32px margin
  - h3: 18px bold
  - 代码块: #1e293b 背景 + 8px 圆角 + #a5f3fc 文字
  - 引用块: 左 3px `--pub-primary` 边框 + 浅蓝背景
  - 链接: `--pub-primary` 颜色
- 底部: "← 返回博客" 链接
- 保留现有 marked + DOMPurify 渲染逻辑

- [ ] **Step 3: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/blog/BlogListView.vue lakeon-console/src/views/blog/BlogPostView.vue
git commit -m "feat(blog): rewrite blog list and post pages with new design"
```

---

### Task 9: 文档布局重写

**Files:**
- Rewrite: `lakeon-console/src/views/docs/DocsLayout.vue`

- [ ] **Step 1: 重写文档布局**

结构：
- 两栏布局: 左侧固定侧边栏（240px）+ 右侧内容区
- 侧边栏:
  - 白色/surface 背景 + 右边框
  - 分组标题（"入门"/"参考文档"）: 小字 `--pub-text-3`, 大写
  - 导航项: 14px, padding 8px 12px, border-radius 6px
  - 当前项: `--pub-primary` 文字 + `--pub-primary-light` 背景
  - Hover: `--pub-hover` 背景
- 内容区: 白色背景, padding 32px 48px
- 保持 5 个子路由不变（DocsHome/RestApi/PythonSdk/Deploy/Mcp）
- 保持 router-view 渲染内容页不改

保留现有导航结构:
- 入门: 快速开始(/docs), 部署指南(/docs/deploy)
- 参考: REST API(/docs/rest-api), Python SDK(/docs/python-sdk), MCP 接入(/docs/mcp)

- [ ] **Step 2: 验证构建并 Commit**

```bash
cd lakeon-console && npx vite build --mode development 2>&1 | tail -3
git add lakeon-console/src/views/docs/DocsLayout.vue
git commit -m "feat(docs): rewrite docs layout with new sidebar design"
```

---

### Task 10: TypeScript 检查 + 最终验证

**Files:** 无新文件

- [ ] **Step 1: vue-tsc 类型检查**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -20`
Expected: 无错误

- [ ] **Step 2: 构建验证**

Run: `cd lakeon-console && npm run build 2>&1 | tail -10`
Expected: 构建成功

- [ ] **Step 3: 本地验证清单**

Run: `cd lakeon-console && npm run dev`

验证:
- [ ] /product — 四个产品卡片，点击跳转详情页
- [ ] /product/lakebase — 蓝色渐变 hero + 6 个 feature + CTA
- [ ] /product/knowledge — 绿色渐变 + 6 个 feature
- [ ] /product/memory — 橙色渐变 + API/类型/生命周期/benchmark
- [ ] /product/datalake — 紫色渐变 + 6 个 feature
- [ ] /integrations — quickstart 代码 + 6 个工具卡片
- [ ] /integrations/openclaw — 样式统一，内容不变
- [ ] /blog — featured 大卡片 + 两列网格
- [ ] /blog/:slug — 居中 720px 文章，Markdown 样式
- [ ] /docs — 左侧边栏高亮当前项 + 右侧内容
- [ ] 所有页面中英切换正常
- [ ] 移动端响应式正常

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: sub-pages redesign complete - final verification"
```
