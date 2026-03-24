# DBay Landing — 记忆库介绍 + 横向导航 设计文档

**日期**: 2026-03-24
**状态**: 已确认

## 背景

记忆库（原 neuromem-cloud 项目）已合入 DBay 平台。需要在 DBay Landing 页中正式介绍记忆库，同时将网站导航从单页滚动锚点升级为多路由横向导航，对标 neuromem-cloud 的产品网站结构。

## 目标

1. 将记忆库从"即将推出"升级为 DBay 第四个正式模块
2. 添加顶层横向导航：产品 / 集成 / 博客 / 文档（含下拉菜单）
3. 新建对应路由和页面，内容从 neuromem-cloud 迁移并融入 DBay 现有内容

---

## 架构设计

### 新增公共布局

新建 `lakeon-console/src/layouts/PublicLayout.vue`，包含横向顶部导航，供所有公共页面（landing、product、integrations、blog、docs）共享。

**导航结构**：

| 顶层项 | 类型 | 下拉内容 |
|--------|------|----------|
| 产品 | 下拉菜单 | Lakebase / 知识库 / 记忆库（New）/ AI 数据湖 |
| 集成 | 下拉菜单 | OpenClaw（精选）/ Claude Code / Claude Desktop / Cursor / Gemini CLI / ChatGPT |
| 博客 | 直接链接 | — |
| 文档 | 下拉菜单 | 快速开始 / REST API / Python SDK / 部署指南 / MCP 接入 |

右侧：语言切换（中/EN）+ 登录按钮

移动端（断点 `< 768px`）：hamburger 按钮，点击后展开覆盖层侧边菜单（不推移主内容），各分组可折叠。

**双语方案**：复用 LandingView 现有的 `t(zh, en)` 辅助函数模式，不引入 vue-i18n。所有新页面内联双语字符串，`locale` 状态通过 `useLocale()` composable 共享（新建 `src/composables/useLocale.ts`，内部使用 `localStorage` 持久化语言选择）。

### 路由变更（router/index.ts）

使用嵌套路由结构，PublicLayout 作为所有公共页的父 component：

```js
{
  path: '/',
  component: PublicLayout,   // 公共顶部导航
  meta: { noAuth: true },
  children: [
    { path: '', name: 'Landing', component: LandingView },
    { path: 'product', name: 'Product', component: ProductView },
    { path: 'integrations', name: 'Integrations', component: IntegrationsView },
    { path: 'integrations/openclaw', name: 'OpenClaw', component: OpenClawView },
    { path: 'blog', name: 'BlogList', component: BlogListView },
    { path: 'blog/:slug', name: 'BlogPost', component: BlogPostView },
    {
      path: 'docs',
      component: DocsLayout,   // 嵌套第二层：左侧导航
      children: [
        { path: '', name: 'DocsHome', component: DocsHome },
        { path: 'rest-api', name: 'DocsRestApi', component: DocsRestApi },
        { path: 'python-sdk', name: 'DocsPythonSdk', component: DocsPythonSdk },
        { path: 'deploy', name: 'DocsDeploy', component: DocsDeploy },
        { path: 'mcp', name: 'DocsMcp', component: DocsMcp },
      ]
    }
  ]
}
```

LandingView 移除内联 `<nav class="nav-bar">`，导航由 PublicLayout 提供。

**布局嵌套关系**：
- `PublicLayout`（顶部横向导航）→ `<router-view>` 渲染各页面
- `/docs/*` 路径：`PublicLayout` → `DocsLayout`（左侧文档导航）→ `<router-view>` 渲染具体文档页

---

## 页面内容设计

### /product — 产品页

**结构（从上到下）**：

1. **页面标题** — "DBay 四大产品模块"
2. **四模块导航卡片**（2×2 网格，点击跳到对应 anchor）：
   - Lakebase（蓝色）/ 知识库（紫色）/ 记忆库（紫罗兰，New 标签）/ AI 数据湖（青色）
3. **记忆库专题区** `#memory`（核心新增，内容来自 neuromem-cloud）：
   - 副标题：为 AI Agent 提供结构化长期记忆，越用越懂你
   - 三个核心操作：`ingest` / `recall` / `digest`（卡片 + 描述）
   - 四种记忆类型：事实📋 / 事件🕐 / 特征🧠 / 文档📄（2×2 卡片）
   - 特征生命周期：趋势→候选→萌发→确立→核心→消解（线性可视化）
   - LoCoMo 基准测试：81.7% 综合得分 + 横向对比图（vs Framework A/B/C/D）
4. **Lakebase 区** `#lakebase` — 现有内容保留
5. **知识库区** `#knowledge` — 现有内容保留
6. **AI 数据湖区** `#datalake` — 现有内容保留

### /integrations — 集成页

**结构**：
- 标题 + 副标题（通过 MCP 协议接入 DBay 记忆、知识和数据能力）
- 集成卡片网格（7 个）：
  - OpenClaw（精选标签，来自 neuromem）
  - Claude Code（精选标签）
  - Claude Desktop
  - Cursor
  - Gemini CLI
  - ChatGPT
  - 更多（占位）
- 每个卡片含：名称 + 描述 + 详情链接
- MCP 快速接入代码示例（`dbay-mcp` 配置片段）
- OpenClaw 有独立详情路由 `/integrations/openclaw`（从 neuromem 迁移内容）

### /blog — 博客

**内容来源**：neuromem-cloud 的 10 篇 MDX 文章，转为 TypeScript 静态数组（`src/data/blog-posts.ts`），品牌名替换为 DBay/记忆库。

**blog-posts.ts 数据结构**：

```ts
export interface BlogPost {
  slug: string        // URL slug，与文件名对应
  title: string       // 标题（中文）
  titleEn: string     // 标题（英文）
  date: string        // ISO 日期字符串 "YYYY-MM-DD"
  category: string    // 分类（产品 / 技术 / 公告）
  summary: string     // 摘要（1-2 句）
  summaryEn: string
  content: string     // Markdown 正文（中文）
  contentEn: string   // Markdown 正文（英文）
}

export const blogPosts: BlogPost[] = [ ... ]
```

Markdown 渲染使用 `marked` 库 + `DOMPurify` sanitization（`npm install marked dompurify @types/dompurify`）。内容为内部迁移可信内容，但仍做 sanitize 作为最佳实践。

**文章列表**（迁移并改名）：
- 记忆架构：事实、事件、特征与文档
- 特征生命周期：从趋势到核心的 6 个阶段
- 平台更新：记忆库正式合入 DBay（platform-update 改写）
- 对话导入功能介绍
- 用户画像（User Profile）
- 部署模式：云端 vs 自托管
- LoCoMo 基准测试解读
- Python SDK v0.1.1 新功能
- One-LLM 模式说明
- OpenClaw 插件集成指南

**BlogListView**：文章列表，显示标题 + 日期 + 分类
**BlogPostView**：Markdown 渲染（使用 `marked` 库，已在项目中或轻量引入）

### /docs — 文档

**布局**：左侧固定导航 + 右侧滚动内容

**侧边导航**：
- 快速开始（/docs）
- REST API（/docs/rest-api）— 从 neuromem 迁移，API 端点改为 DBay 地址
- Python SDK（/docs/python-sdk）— 同上，包名改为 `dbay`
- 部署指南（/docs/deploy）— 改为 DBay 自托管部署说明
- MCP 接入（/docs/mcp）— dbay-mcp 配置指南

内容从 neuromem 文档页迁移，品牌名统一替换。

---

## Landing 页（/ 或 /landing）改动

1. 移除现有 `<nav class="nav-bar">` 内联导航，改为使用 PublicLayout 的公共导航
2. 产品模块区（`#modules`）：
   - 标题改为"四大产品模块"
   - 新增记忆库卡片（第四个，带 New 标签，放在知识库和数据湖之间）
   - 移除底部"记忆库（即将推出）"提示条
3. 架构图 SVG：新增记忆库模块节点（居中位置，连接 Lakebase）

---

## 文件清单

**新建文件**：
```
src/layouts/PublicLayout.vue
src/composables/useLocale.ts          ← 语言状态（替换 LandingView 中的内联 locale）
src/views/product/ProductView.vue
src/views/integrations/IntegrationsView.vue
src/views/integrations/OpenClawView.vue
src/views/blog/BlogListView.vue
src/views/blog/BlogPostView.vue
src/views/docs/DocsLayout.vue         ← 左侧文档导航，嵌套在 PublicLayout 内
src/views/docs/DocsHome.vue
src/views/docs/DocsRestApi.vue
src/views/docs/DocsPythonSdk.vue
src/views/docs/DocsDeploy.vue
src/views/docs/DocsMcp.vue
src/data/blog-posts.ts
src/components/public/NavDropdown.vue
src/components/public/MobileNav.vue
```

**修改文件**：
```
src/router/index.ts         — 新增公共路由
src/views/landing/LandingView.vue  — 升级导航 + 添加记忆库模块
```

---

## 内容迁移规则

1. 品牌名替换：`neuromem` → `DBay 记忆库`，`neuromem.cloud` → `dbay.cloud`
2. API 端点：`https://api.neuromem.cloud` → `https://api.dbay.cloud`
3. SDK 包名：`neuromem` → `dbay`
4. MCP 工具名：`neuromem-mcp` → `dbay-mcp`（已有 `dbay-mcp` 目录）
5. 保留所有技术内容（架构、基准测试数据、代码示例逻辑不变）
6. 双语（中/英）保留，沿用现有 `t()` 辅助函数模式

---

## 不在范围内

- 博客 CMS 或动态内容管理（静态 TS 数组足够）
- 认证后页面的改动
- SEO meta tags 优化（后续迭代）
- 深色/浅色主题切换（保持现有深色风格）
