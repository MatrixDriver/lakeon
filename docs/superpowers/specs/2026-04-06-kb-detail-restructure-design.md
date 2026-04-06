# KB 详情页重构 + Admin Wiki Agent 管理

## 背景

当前 KB 详情页存在以下问题：
1. 二级 tab 嵌套（文档管理下有 文档/数据源/搜索/切片/概览），层级太深
2. 搜索 tab 与侧边栏"知识搜索"重复
3. 导入按钮分散在不同 sub-tab，新用户难找
4. Wiki Agent 作为核心功能被 tab 切换淹没
5. index.md 和 log.md 作为普通 wiki 页面展示不合理

## 设计原则

对齐 Karpathy LLM Wiki 三层架构：
- **存储层** → 文档 tab（原始文档 + 切片管理）
- **知识层** → Wiki tab（wiki 页面 + 知识图谱）
- **维护规则层** → Admin 控制台（Agent prompt 配置，不在用户界面展示）

用户旅程：**导入 → 浏览(Wiki+图谱) → 对话 → 沉淀知识**

## 一、Console KB 详情页

### Tab 结构

从原来的二级嵌套改为扁平 3 个一级 tab：

| Tab | 对应层 | 内容 |
|-----|--------|------|
| **概览** (默认) | — | KB 基础信息 + 空 KB 引导 |
| **文档** | 存储层 | 文档管理（上传/目录/URL/OBS）+ 切片下钻 |
| **Wiki** | 知识层 | wiki 页面列表 + 内容 + 知识图谱 |

去掉的内容：
- 搜索 sub-tab（与侧边栏"知识搜索"重复）
- 二级 sub-tabs（文档/数据源/搜索/切片/概览 全部打平或合并）
- 独立的切片 tab（切片作为文档的下钻视图，点击文档行的"切片"链接进入）

### Wiki Tab — 3 栏布局

```
┌──────────────┬─────────────────────────────┬──────────────────┐
│ Wiki 页面列表 │  Wiki 页面内容               │  知识图谱         │
│ (~190px)     │  (flex, 主内容区)             │  (~320px, 可折叠) │
└──────────────┴─────────────────────────────┴──────────────────┘
```

**左栏 — Wiki 页面列表**
- 显示所有 wiki 页面（不含 index.md 和 log.md）
- 每项显示标题和标签（"AI 生成"）
- 点击切换右侧内容

**中栏 — Wiki 页面内容**
- 顶部工具栏：标题"Wiki 页面" + 页面统计 + "更新记录"按钮
- 内容区使用 MarkdownRenderer 渲染，[[wikilink]] 可点击导航
- blockquote 显示生成来源信息

**右栏 — 知识图谱（可折叠）**
- D3 力导向图，节点 = wiki 页面，边 = wikilink 引用
- 点击节点 → 左栏自动选中对应 wiki 页面并显示内容
- 右上角 × 收起，收起后右上角出现"图谱"按钮恢复
- 折叠后中栏自动扩展占满空间

**index.md / log.md 处理**
- index.md：不显示为页面，其内容通过左栏页面列表可视化呈现
- log.md：不显示为页面，改为顶部"更新记录"按钮，点击弹出右侧抽屉面板显示 AI 操作日志时间线

**空 KB 引导页**
- 当 KB 无文档时，Wiki tab 显示引导页面替代空白
- 四步流程图：导入 → Wiki → 对话 → 沉淀
- 拖拽上传区 + URL 导入按钮
- 引导用户完成第一步（导入文档）

### 文档 Tab — 全宽布局

保留现有文档管理全部功能：
- 顶部工具栏：[上传文件] [上传目录] [导入 URL] [OBS 数据源] + 搜索框
- 上传/处理进度条（批量上传时显示）
- 状态筛选（全部/处理中/已就绪/失败）
- 文件夹网格导航
- 文档表格（checkbox + 文件名 + 格式 + 大小 + chunks + 状态 + 时间 + 操作）
- 操作列增加"切片"链接，点击下钻查看该文档的切片列表（含定位原文功能）
- 批量操作（删除选中、清空、重试失败）
- OBS 数据源管理（创建、同步、凭据、删除）
- 分页

### 侧边栏调整

知识库 nav group 保持不变：
- 知识库（KB 列表）
- 知识搜索（独立搜索页，从 KB 详情页搜索 sub-tab 移出）
- 对话（独立对话页，已实现）

## 二、Admin 控制台 — Wiki Agent 管理

在现有 `/knowledge` admin 页面新增 tab：

### 新增 Tab: Wiki Agent 配置

**Wiki Agent Prompt 编辑器**
- 可编辑的 textarea，内容为告诉 Agent 如何维护 wiki 的 system prompt
- 当前硬编码在 WikiService.java 的 processIngest() 和 chat() 方法中
- 改为从数据库/配置读取，admin 可在线编辑
- 包含以下可配置的 prompt 段：
  - **Ingest Prompt**：文档导入后如何生成/更新 wiki 页面（创建 vs 更新判断、页面粒度、wikilink 提取规则）
  - **Chat Prompt**：Query Router 的路由策略和回答生成规则
  - **Index Prompt**：index.md 的维护规则
- 每个 prompt 有"重置为默认"按钮
- 修改后需保存确认，立即生效

**Wiki 页面管理**
- 在知识库列表的展开行中增加 wiki 页面子表
- 显示：页面标题、doc_type(wiki/index)、大小、创建时间、最后更新
- 操作：删除单页、清空该 KB 所有 wiki 页面、触发全量重建

**LLM 配置**
- DeepSeek API Key（密文显示，可修改）
- Base URL（默认 https://api.deepseek.com/v1）
- Model（默认 deepseek-chat）
- 连接测试按钮

### 现有 Tab 扩展

**写入任务队列 tab**
- 确保 WIKI_UPDATE 任务类型能正常显示和重试
- 新增 wiki 相关任务的筛选选项

## 三、后端改动

### WikiService 改造
- system prompt 从硬编码改为从配置表读取
- 新增 API 端点：
  - `GET /api/v1/admin/wiki/prompts` — 获取当前 prompt 配置
  - `PUT /api/v1/admin/wiki/prompts` — 更新 prompt 配置
  - `GET /api/v1/admin/wiki/stats` — wiki 统计（各 KB 页面数、图谱节点数、LLM 调用统计）

### 数据库
- 新增 `wiki_config` 表（或复用已有配置表）存储 prompt 模板
- 字段：config_key, config_value, updated_at, updated_by

## 四、原型文件

- v4 原型：`docs/mockup-kb-detail-v4.html`
- 包含 Wiki tab（3 栏）、文档 tab（全宽）、空 KB 引导、更新记录抽屉

## 五、实施优先级

1. **P0**: Console 概览 tab（默认首页，KB 信息 + 空 KB 引导）
2. **P0**: Console 文档 tab 整合（合并原来的 sub-tabs、切片作为文档下钻）
3. **P0**: Console Wiki tab 3 栏重构（去掉文档来源栏、去掉二级 tab、index/log 融入 UI）
4. **P1**: Admin Wiki Agent Prompt 编辑器
5. **P2**: Admin Wiki 页面管理、LLM 配置
6. **P2**: URL 导入 502 修复（改进 User-Agent / 错误处理）
