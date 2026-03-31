# Console Redesign — 工具内核 + 港湾外衣

**日期**: 2026-03-31
**方向**: D — 工具内核 + 港湾外衣
**目标**: 用 impeccable 标准重做用户控制台，更漂亮、更简洁

---

## 1. 整体方向

取 Vercel/Linear 的信息密度和操作效率，披上 DBay 港湾暖色调外衣。布局更紧凑（去掉 icon rail，合并为单层侧边栏），色彩保持港湾暖调但更克制，排版向现代工具类产品看齐。

## 2. 导航结构

### 2.1 去掉 icon rail，改为单层侧边栏

当前的 icon rail（52px）+ sidebar（180px）= 232px 双层导航，改为单层侧边栏（~200px），带图标 + 分组标题。

### 2.2 侧边栏菜单（A3 精简方案，11 项）

```
数据库
  我的数据库
  时间旅行
  SQL 编辑器

知识库
  知识库
  知识搜索

记忆库
  记忆库
  记忆浏览              ← 含反思洞察，有机融合

数据湖
  数据集
  作业管理
  Notebook

───────────────
  API Key
  账户
```

### 2.3 收纳到详情页/⌘K 的功能

| 原菜单项 | 收纳位置 |
|----------|----------|
| 数据源 | 知识库详情页 tab |
| 反思洞察 | 融入记忆浏览页面 |
| 消息日志 | 记忆库详情页 tab |
| 用量统计 | 记忆库详情页 tab |
| 数据迁移 | 数据库详情页内 |
| 监控面板 | ⌘K 快捷跳转 或 数据库详情 |
| 日志管理 | ⌘K 快捷跳转 或 数据库详情 |
| 备份管理 | ⌘K 快捷跳转 或 数据库详情 |

## 3. Header

- 背景: navy #2a4d6a，无底线（深蓝→白色自然过渡）
- 左侧: DBay logo（琥珀色）+ "数据港湾" tagline
- 右侧: ⌘K 命令面板入口 + 文档链接 + 用户头像圆圈（首字母）+ 退出
- 移除: 九宫格图标、"控制台"文字、区域选择器

## 4. ⌘K 命令面板

范围: 页面跳转 + 常用操作（不含全局数据搜索）

### 4.1 页面跳转

所有侧边栏菜单项 + 被收纳的页面（监控面板、日志管理、备份管理、数据迁移等）。用户输入关键词模糊匹配。

### 4.2 常用操作

- 创建数据库
- 创建知识库
- 创建记忆库
- 创建 Notebook
- 复制连接串（如果当前在数据库详情页）
- 创建 API Key

### 4.3 交互

- ⌘K（Mac）/ Ctrl+K（Windows）打开
- 输入即搜索，上下键选择，Enter 执行
- Esc 关闭
- 分组显示: "页面" / "操作"

## 5. 内容区

### 5.1 背景

纯白底，通过间距、分隔线和排版创造视觉节奏。不加额外背景层。

### 5.2 列表展示

提供卡片/表格视图切换（右上角图标按钮），默认卡片视图。

**卡片视图**: 2 列网格，每张卡片显示名称、状态标签、核心指标（规格/连接数/存储）。末尾有"+ 创建"虚线卡片。

**表格视图**: 精排表格，双行名称（名称 + ID），更大行距，操作列 hover 显示。

适用页面: 数据库列表、知识库列表、记忆库列表、数据集列表、Notebook 列表。

## 6. 视觉设计

### 6.1 色板（延续港湾暖色调）

| 变量 | 色值 | 用途 |
|------|------|------|
| --c-primary | #2a4d6a | 按钮、header、active 状态 |
| --c-primary-hover | #1e3a52 | hover |
| --c-accent | #c67d3a | 装饰、logo、高亮边框 |
| --c-accent-text | #9a5b25 | 链接、交互文字 |
| --c-danger | #e6393d | 错误、删除 |
| --c-text | #2c3e50 | 主文字 |
| --c-text-2 | #64748b | 次要文字 |
| --c-border | #e8e4df | 边框 |
| --c-bg-alt | #faf8f5 | 表头背景等 |

### 6.2 字体

DM Sans（Google Fonts），400/500/600/700 四个字重。

### 6.3 圆角

- 按钮/输入框: 4px
- 卡片: 8px
- Dialog: 8px
- 头像: 50%

### 6.4 侧边栏样式

- 宽度: ~200px
- 背景: #fff
- 分组标题: 10px 大写字母，#94a3b8
- 菜单项: 13px，带 14px SVG 图标，8px gap
- Active: navy 色文字 + 右侧 2px navy 边框 + #f0f4f8 背景
- Hover: #f8f5f1 背景

## 7. 操作按钮层次

| 类型 | 默认色 | Hover 色 | 示例 |
|------|--------|----------|------|
| Primary | navy #2a4d6a 底白字 | #1e3a52 | 创建数据库 |
| Default | 白底灰边 | 琥珀色边+文字 | 挂起、导出 |
| Accent text | #9a5b25 | #8b5222 | 唤醒 |
| Danger text | #94a3b8 | #e6393d | 删除 |

删除按钮默认灰色，hover 才变红——降低视觉干扰。

## 8. 页面清单

需要重做的页面（按优先级）:

### P0 — 核心框架
1. ConsoleLayout（header + sidebar + main）
2. ⌘K 命令面板组件

### P1 — 主要列表页（卡片/表格切换）
3. DashboardView（我的数据库）
4. KnowledgeBases（知识库列表）
5. MemoryBases（记忆库列表）
6. DatalakeDatasets（数据集列表）
7. DatalakeNotebookList（Notebook 列表）
8. DatalakeJobs（作业管理列表）

### P2 — 详情页（吸收收纳功能）
9. DatabaseDetail（+ 数据迁移 tab + 监控/日志/备份 tab）
10. KnowledgeBaseDetail（+ 数据源 tab）
11. MemoryBaseDetail（+ 消息日志 tab + 用量统计 tab）
12. MemoryBrowse（+ 反思洞察融合）

### P3 — 其他页面
13. TimeTravelView
14. SqlEditorEntry
15. DatalakeNotebook
16. ApiKeyView
17. AccountSettingsView
18. 各种 Dialog（统一样式）

## 9. 不变的部分

- 路由结构和 URL 不变
- API 层不变
- 数据模型不变
- Public 页面（landing/product/docs/blog）不变
- 登录页不变
