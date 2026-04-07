# Admin Wiki 管理功能设计

## 概述

为 Admin 控制台补全 Wiki Agent 管理能力：wiki 页面管理、多 prompt 配置、LLM 连接测试。

## 1. Wiki 页面管理

### 后端 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/admin/wiki/pages?kb_id=xxx` | GET | 列出指定 KB 的 wiki 页面（含 index/log） |
| `/admin/wiki/pages/{docId}?kb_id=xxx` | DELETE | 删除单个 wiki 页面 |
| `/admin/wiki/rebuild?kb_id=xxx` | POST | 清空所有 wiki 页面并重新触发全量生成 |

所有端点需 admin token 验证（`X-Admin-Token` + `Authorization: Bearer`）。

rebuild 流程：删除该 KB 所有 doc_type=wiki 的文档 → 对每个 doc_type=raw 且 status=READY 的文档 enqueue WIKI_UPDATE。

### Admin 前端

在 KnowledgeList.vue 的知识库列表展开行中，新增 "Wiki 页面" 区域：
- 表格列：标题（去掉 .md）、类型（wiki/index）、大小、创建时间、操作（删除）
- 表格上方：[清空 Wiki] [全量重建] 按钮
- 数据来源：`GET /admin/wiki/pages?kb_id=xxx`

## 2. Chat + Index Prompt 分别可配

### 后端

LakeonProperties.WikiConfig 新增字段：
- `chatRoutingPrompt`：Query Router 路由 prompt
- `chatAnswerPrompt`：回答生成 prompt

WikiService 修改：
- chat() 方法中 routing prompt 和 answer prompt 改为 `getChatRoutingPrompt()` 和 `getChatAnswerPrompt()`，逻辑同 `getIngestPrompt()`（有自定义用自定义，否则用默认）

Admin API 扩展：
- `GET /admin/wiki/config` 返回增加 `chat_routing_prompt` 和 `chat_answer_prompt`
- `PUT /admin/wiki/config` 接受这两个新字段

### Admin 前端

Wiki Agent tab 的 prompt 区域改为 3 个可切换面板：
- [Ingest Prompt] [Chat Routing Prompt] [Chat Answer Prompt]
- 每个面板一个 textarea
- 统一保存按钮

## 3. LLM 连接测试

### 后端

`POST /admin/wiki/test-connection`：
- 用当前配置的 apiKey/baseUrl/model 发送 `messages: [{role: "user", content: "hi"}], max_tokens: 5`
- 返回 `{success: true, latency_ms: 123, model: "deepseek-chat"}` 或 `{success: false, error: "..."}`
- 需 admin token

### Admin 前端

LLM 配置区加"测试连接"按钮：
- 点击后 loading 状态
- 成功：绿色文字 "连接成功 (123ms)"
- 失败：红色文字 + 错误信息

## 测试

- Admin API 的 3 个 wiki 页面管理端点加入 E2E 测试
- prompt 配置的 GET/PUT 已有测试，扩展验证新字段
- 连接测试端点加入 E2E
