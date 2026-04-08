# Wiki Lint 健康检查 — 设计文档

## 概述

为知识库 Wiki 添加 Lint（健康检查）功能，检测 6 类质量问题，以侧边面板形式展示报告，支持逐条修复和全部修复（交给 Curate 执行）。

灵感来源：Karpathy 的 LLM Wiki 模式中的 Lint 操作——定期检查矛盾、孤儿页、过时内容、缺失引用。

## 检查项

### 规则检查（无 LLM，即时完成）

| 类别 | 标签色 | 检测逻辑 |
|---|---|---|
| **语言一致性** | 暖色 #c9a96e | 检测非中文页面：用正则统计中文字符占比，< 50% 标记为"英文"，50-80% 标记为"部分英文" |
| **孤儿页** | 蓝色 #7eb8da | 从已有的图谱数据（getGraph）中找入度为 0 的节点 |
| **断链** | 红色 #e07070 | 提取所有页面的 [[wikilink]]，检查目标页面是否存在 |

### LLM 分析（需要调用 DeepSeek，较慢）

| 类别 | 标签色 | 检测逻辑 |
|---|---|---|
| **矛盾检测** | 紫色 #d4a8d5 | 把所有 wiki 页面摘要喂给 LLM，让它找出描述冲突 |
| **过时内容** | 绿色 #a8d5a2 | 对比 wiki 页面和最新源文档，检测被推翻的旧结论 |
| **缺失交叉引用** | 绿色 #a8d5a2 | LLM 分析哪些页面内容相关但没有互相链接 |

## API 设计

### POST /api/v1/knowledge/wiki/lint

请求：
```json
{
  "kb_id": "kb_xxx"
}
```

响应：
```json
{
  "issues": [
    {
      "category": "language",
      "severity": "error",
      "page": "API Authentication",
      "description": "整页英文，应翻译为中文",
      "related_pages": []
    },
    {
      "category": "language",
      "severity": "warning",
      "page": "数据库索引",
      "description": "部分段落为英文",
      "related_pages": []
    },
    {
      "category": "orphan",
      "severity": "warning",
      "page": "部署流程",
      "description": "无其他页面链接到此页",
      "related_pages": []
    },
    {
      "category": "broken_link",
      "severity": "error",
      "page": null,
      "description": "[[负载均衡]] 被 3 个页面引用但不存在",
      "related_pages": ["缓存策略", "性能优化", "API 鉴权"]
    },
    {
      "category": "contradiction",
      "severity": "error",
      "page": "缓存策略",
      "description": "TTL 默认值冲突：「缓存策略」说 5 分钟，「性能优化」说 10 分钟",
      "related_pages": ["性能优化"]
    },
    {
      "category": "missing_link",
      "severity": "info",
      "page": "API 鉴权",
      "description": "与「用户管理」内容相关但没有互相链接",
      "related_pages": ["用户管理"]
    }
  ],
  "summary": {
    "language": 3,
    "orphan": 2,
    "broken_link": 1,
    "contradiction": 1,
    "stale": 0,
    "missing_link": 1,
    "total": 8
  },
  "checked_at": "2026-04-08T12:00:00Z"
}
```

### POST /api/v1/knowledge/wiki/lint/fix

修复指定类别的问题（内部调用 Curate，传入 Lint 结果作为上下文）。

请求：
```json
{
  "kb_id": "kb_xxx",
  "categories": ["language", "orphan"],
  "issues": [...]
}
```

`categories` 为空数组表示全部修复。`issues` 来自 lint 响应，传给 Curate 作为修复指引。

响应：
```json
{
  "fixed": 5,
  "pages_updated": ["API Authentication", "Database Sharding", "数据库索引", "部署流程", "监控告警"],
  "pages_created": [],
  "pages_deleted": []
}
```

## 前端设计

### UI 交互

1. Wiki Tab 工具栏加"健康检查"按钮
2. 点击后调用 POST /wiki/lint，显示 loading
3. 规则检查结果先返回（即时），LLM 分析结果后续追加
4. 结果以**右侧滑出面板**展示（340px 宽）
5. 左侧 wiki 页面列表上，有问题的页面标注彩色小标签
6. 面板内按类别分组，每组有"修复"按钮
7. 面板顶部有"全部修复"按钮
8. 点击面板中的页面名 → 左侧跳转到该页面内容
9. 点 × 关闭面板

### 组件结构

- **WikiLintPanel.vue** — 侧边面板组件，接收 lint 结果 prop
- Wiki Tab 父组件管理面板的显示/隐藏状态和 lint 数据

## 后端实现

### WikiService 新增方法

**runLint(tenantId, kbId)**：
1. 加载所有 wiki 页面（排除 index.md/log.md）
2. 规则检查（同步，立即返回）：
   - 语言：正则统计每页中文字符占比
   - 孤儿：构建图谱，找入度=0 的节点
   - 断链：提取所有 [[wikilink]]，检查目标页面是否存在
3. LLM 分析（调用 DeepSeek）：
   - 构造 prompt，把所有页面摘要（每页前 500 字）+ index 传入
   - 让 LLM 输出 JSON：矛盾列表、过时内容列表、缺失引用建议
4. 合并结果返回

**fixLintIssues(tenantId, kbId, categories, issues)**：
1. 构造 Curate prompt，注入 lint 报告作为修复指引
2. 调用现有 runCurate 逻辑（复用现有的 curate prompt 框架，追加 lint 上下文）
3. 返回修复结果

### Lint LLM Prompt

```
You are a wiki quality auditor. Analyze the following wiki pages for quality issues.

Current wiki index:
---
{index}
---

Wiki page summaries:
---
{page_summaries}
---

Check for:
1. CONTRADICTIONS: Different pages describe the same concept differently or provide conflicting facts.
2. STALE CONTENT: Claims that may be outdated based on other pages with newer information.
3. MISSING CROSS-REFERENCES: Pages that discuss related topics but don't link to each other.

Output JSON:
{
  "contradictions": [
    {"page": "Page A", "related_page": "Page B", "description": "具体矛盾描述"}
  ],
  "stale": [
    {"page": "Page", "description": "过时内容描述"}
  ],
  "missing_links": [
    {"page": "Page A", "related_page": "Page B", "description": "为什么应该互相链接"}
  ]
}

Write descriptions in Simplified Chinese.
```

## 文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `lakeon-api/.../knowledge/WikiService.java` | 修改 | 新增 runLint + fixLintIssues 方法 |
| `lakeon-api/.../knowledge/KnowledgeController.java` | 修改 | 新增 2 个端点 |
| `lakeon-console/src/components/knowledge/WikiLintPanel.vue` | 新建 | 侧边面板组件 |
| `lakeon-console/src/views/knowledge/KbDetail.vue` 或对应 Wiki tab 组件 | 修改 | 集成面板 + 健康检查按钮 |
| `lakeon-console/src/api/knowledge.ts` | 修改 | 新增 2 个 API 调用 |

## 不做的事

- 不做自动定时 Lint（手动触发 + 导入后自动触发可以后续加）
- 不做 Lint 结果持久化（每次点击重新检查）
- 不做逐条选择性修复（按类别修复已足够）
- 不做过时内容检测的源文档对比（第一版只在 wiki 页面间做 LLM 分析）
