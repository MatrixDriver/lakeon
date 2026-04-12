# Wiki Agent Interactive Mode

## Summary

将 WikiChat 从"LLM 直连问答"升级为"agent 交互式对话"，统一 wiki 问答和 wiki 编辑两个场景。用户在同一个对话界面中既能问 wiki 问题，也能审核 agent 的 wiki 修改方案。所有对话都走 wiki-agent 的 tool-calling loop，前端展示 agent 的完整动作过程（思考、工具调用、修改计划）。

## 核心变更

### 1. 文档流程变更

**当前**：文档 parse → summarize → 自动入队 WIKI_UPDATE → agent fire-and-forget 生成 wiki

**新流程**：文档 parse → summarize → 状态变为 `WIKI_REVIEW`（停在这里等用户决策）

用户在文档 tab 的"待入 Wiki"过滤器中看到这些文档，有两个选择：
- **勾选 → "自动入库"**：走原有 fire-and-forget 流程
- **点击某篇 → "审核入 Wiki"**：跳转 Wiki 对话页，与 agent 交互式审核

文档 tab 过滤器新增一个"待入 Wiki (N)"tab，位于"已就绪"和"失败"之间。

### 2. 统一对话架构

**当前链路**：
```
Browser → lakeon-api WikiService.chatStream() → 直接调 LLM → SSE token stream
```

**新链路**：
```
Browser ← SSE（下行事件流）→ lakeon-api（SseEmitter proxy）← SSE → wiki-agent pod（per-tenant）
Browser → POST（上行用户消息）→ lakeon-api → wiki-agent pod
```

lakeon-api 不再直接调 LLM 做 wiki 问答，变成 SSE 透传层。wiki-agent pod 负责所有智能逻辑。

### 3. SSE 事件类型

| 事件类型 | 含义 | 前端渲染 |
|---------|------|---------|
| `thinking` | agent 的推理文本 | 灰色可折叠区域，默认折叠 |
| `tool_call` | 调用工具 | 工具名 + 参数摘要（如"读取: openclaw-架构"），可折叠 |
| `tool_result` | 工具返回结果 | 折叠展示，点击可展开查看 |
| `content` | 给用户的回复文本（流式） | 正常 markdown 渲染 |
| `proposal` | wiki 修改计划 | 结构化展示：修改类型 + 页面名 + 修改描述 |
| `ask` | agent 需要用户决策 | 显示问题，等用户回复后 agent 继续 |
| `done` | 对话轮结束 | 完成标记，显示统计（更新 N 页，创建 N 页） |

### 4. 编辑流程的事件序列

用户选择"审核入 Wiki"后，对话流如下：

```
系统消息: "新文档【OpenClaw Memory Concepts】待入 Wiki"
  ↓
thinking: "分析文档内容，这篇讲的是 OpenClaw 的记忆机制..."
tool_call: list_pages()
tool_result: [openclaw, openclaw-架构, openclaw-组件, ...]
tool_call: read_source(document_id)
tool_result: "(文档全文)"
tool_call: search_pages("记忆")
tool_result: "(无匹配)"
  ↓
proposal: "我计划：
  1. 更新【openclaw-架构】：在'核心模块'章节新增'记忆子系统'段落
  2. 创建【openclaw-记忆】：详细描述记忆机制、存储、召回流程
  3. 更新【openclaw-组件】：在组件列表中增加 MemoryStore"
ask: "确认执行？或者你想调整？"
  ↓
（用户确认 或 回复调整意见）
  ↓
tool_call: update_page("openclaw-架构", old_text, new_text)
tool_result: {ok: true}
tool_call: create_page("openclaw-记忆", content)
tool_result: {ok: true, filename: "openclaw-记忆.md"}
tool_call: update_page("openclaw-组件", old_text, new_text)
tool_result: {ok: true}
  ↓
done: "完成。更新了 2 页，创建了 1 页。"
```

文档状态从 `WIKI_REVIEW` 变为 `READY`，metadata 设置 `wiki_processed_at`。

### 5. Per-Tenant Wiki Agent Pod

**管理方式**：按需创建，空闲回收。

- 用户打开 Wiki 对话页（或系统判定需要 agent）时，lakeon-api 通过 ComputePodManager 创建该租户的 wiki-agent pod
- Pod 创建后保活 15 分钟（每次对话交互重置计时）
- 空闲超时后自动回收
- 同一租户同时只有一个 wiki-agent pod，多个浏览器 tab 共享

**Pod 规格**：wiki-agent 是纯 Python + HTTP 调用（不跑模型），资源需求很小：
- CPU: 200m request / 500m limit
- Memory: 256Mi request / 512Mi limit
- 无 GPU

**冷启动优化**：
- 镜像预拉到节点（ImagePullPolicy: IfNotPresent）
- FastAPI 启动 < 2s
- 首次对话时前端显示"正在启动 Wiki Agent..."

### 6. Wiki-Agent 内部改造

**当前**：fire-and-forget 的 `_run()` loop，跑完写 runlog。

**新增**：SSE 会话模式。

```python
# 新增 /v1/wiki/chat/stream 端点
@app.post("/v1/wiki/chat/stream")
async def chat_stream(req: ChatRequest):
    """SSE 端点，每轮 tool call 推送事件，遇到 ask 暂停等用户输入"""
    ...
```

**Agent loop 改造**：
- 每轮 LLM 调用前/后，通过 SSE 推送 `thinking`、`tool_call`、`tool_result` 事件
- LLM 返回纯文本时，流式推送 `content` 事件
- Agent 决定做 wiki 修改前，推送 `proposal` + `ask` 事件，暂停 loop（asyncio.Event.wait）
- 用户消息通过 POST 送到 agent pod，触发 asyncio.Event.set，agent 继续
- 对话历史保存在 agent pod 内存中（pod 生命周期内有效）

**Tool schemas 扩展**：
- 问答模式下 agent 只有只读 tools（list_pages, read_page, search_pages, get_schema）
- 编辑模式下 agent 拥有全部 tools（含 create_page, update_page, delete_page 等）
- 区分方式：ChatRequest 带 `mode: "chat" | "ingest"` 字段 + 关联的 document_id

### 7. lakeon-api 改造

**新增端点**：
- `POST /knowledge/wiki/chat/connect` — 创建/获取 per-tenant wiki-agent pod，返回 pod 地址
- `POST /knowledge/wiki/chat/stream` — SSE proxy，透传 wiki-agent 的 SSE 流
- `POST /knowledge/wiki/chat/message` — 转发用户消息到 wiki-agent pod

**废弃**：
- `WikiService.chatStream()` 和 `WikiService.callDeepSeekStream()` — 不再直接调 LLM
- 保留一段时间做降级：如果 wiki-agent pod 不可用，fallback 到旧的直连 LLM 模式

### 8. 前端 WikiChat 改造

**消息渲染**：每条 assistant 消息内部包含多种事件，按类型渲染：
- `thinking` → 灰色可折叠块，默认折叠，标题显示"思考中..."
- `tool_call` → 图标 + 工具名 + 参数摘要，可折叠
- `tool_result` → 缩进折叠块，可展开查看完整内容
- `content` → 正常 markdown
- `proposal` → 带结构的修改计划卡片
- `ask` → 文本 + 输入框激活

**对话入口**：
- Wiki tab 的对话子 tab（现有位置）
- 文档 tab "待入 Wiki" 状态的文档，点击"审核入 Wiki"按钮跳转到对话页
- 对话页顶部显示 agent pod 状态（启动中 / 就绪 / 空闲超时）

**连接管理**：
- 打开对话页时调 `/wiki/chat/connect`，获取 pod 状态
- SSE 连接建立后，前端持续监听事件流
- 用户发消息时 POST 到 `/wiki/chat/message`
- 页面关闭时 SSE 连接断开（pod 不会立即回收，等超时）

### 9. DocumentStatus 扩展

新增 `WIKI_REVIEW` 状态：

```java
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
    WIKI_PENDING,  // 保留，兼容旧流程
    WIKI_REVIEW    // 新增：等待用户决策
}
```

KbWriteQueue 中 summarize 完成后的逻辑改为：
- 设置文档状态为 `WIKI_REVIEW`（不再自动入队 WIKI_UPDATE）
- 前端文档列表"待入 Wiki"tab 显示这些文档

用户选择"自动入库"时：
- 批量将选中文档入队 WIKI_UPDATE（走旧 fire-and-forget 流程）
- 状态从 `WIKI_REVIEW` → `WIKI_PENDING` → （agent 完成后）`READY`

用户选择"审核入 Wiki"时：
- 跳转 Wiki 对话，agent 交互式处理
- 状态从 `WIKI_REVIEW` → （agent 完成后）`READY`

## 分阶段实施

### Phase 1：SSE 基础 + 问答升级
- wiki-agent 新增 `/v1/wiki/chat/stream` SSE 端点
- 问答模式走 agent loop（只读 tools），前端展示完整动作
- lakeon-api SSE proxy
- 暂用共享 wiki-agent pod（不做 per-tenant）
- 前端 WikiChat 渲染 SSE 事件

### Phase 2：Per-tenant pod + 编辑模式
- ComputePodManager 管理 per-tenant wiki-agent pod
- agent 支持编辑模式（proposal + ask 暂停）
- 文档状态 WIKI_REVIEW + "待入 Wiki"tab
- "自动入库" 和 "审核入 Wiki" 两条路径

### Phase 3：体验优化
- 对话历史持久化（当前在 pod 内存，pod 回收后丢失）
- 多文档批量审核（agent 连续处理多篇，中间暂停审核）
- schema 与 agent 的协同进化
