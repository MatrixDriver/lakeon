# SRE AI Assistant Design

**Date:** 2026-03-27
**Status:** Draft

## Problem

SRE engineers troubleshooting issues on the DBay platform must manually navigate multiple pages (health, logs, databases, events) to correlate signals and identify root causes. An AI assistant can automate this data gathering and analysis.

## Solution: AI-Powered Diagnostics with Tool Calling

An AI chat assistant embedded in the SRE console. It uses SiliconFlow's DeepSeek V3.2 model with function calling to autonomously query system data and provide root cause analysis.

Two interaction modes:
- **Global chat panel** — always available, handles any diagnostic question
- **Context diagnose buttons** — appear on abnormal resources, pre-fill the chat with targeted questions

## Architecture

### Data Flow

```
User types question
  → Frontend sends POST /api/v1/admin/ai/chat (SSE)
  → Backend builds messages with system prompt + tool definitions
  → Calls SiliconFlow /chat/completions with tools
  → LLM returns tool_calls
  → Backend executes tools (direct Java service calls, no HTTP)
  → Appends tool results to messages, calls LLM again
  → LLM returns final analysis
  → SSE streams thinking steps + tool calls + final answer to frontend
```

Tool execution happens server-side. The LLM never sees raw API keys or credentials. Tool results are summarized before being sent back to the LLM to control token usage.

### Backend

**New files:**
- `SreAiService.java` — core service: manages conversation loop, tool definitions, tool execution, SiliconFlow API calls
- `SreAiController.java` — SSE endpoint `POST /api/v1/admin/ai/chat`

**Tool execution:** SreAiService holds references to existing services/repositories (DatabaseRepository, AdminController helpers, etc.) and executes tool calls as direct method invocations. No internal HTTP calls.

**Multi-turn tool calling:** The LLM may request multiple tool calls in one response, or need multiple rounds of tool calls before producing a final answer. The service loops until the LLM returns a regular content message (no more tool_calls), with a max of 5 rounds to prevent runaway loops.

### API Contract

```
POST /api/v1/admin/ai/chat
Authorization: Bearer {admin_token}
Content-Type: application/json
Accept: text/event-stream

Request:
{
  "messages": [
    {"role": "user", "content": "..."}
  ],
  "context": {
    "resource_type": "database",
    "resource_id": "db_xxx"
  }
}
```

`messages` contains the full conversation history (frontend manages this). `context` is optional — set by diagnose buttons to provide resource context.

SSE response events:

| type | content | description |
|------|---------|-------------|
| `thinking` | text | Status update: "正在查询..." |
| `tool_call` | `{name, args}` | Tool being called |
| `tool_result` | `{name, summary}` | Brief result summary |
| `content` | text | Final or streaming answer text |
| `error` | text | Error message |
| `done` | — | Stream complete |

### Tools (MVP: 8 tools)

| Tool Name | Description | Internal Call | Returns |
|-----------|-------------|---------------|---------|
| `get_system_health` | Check all component health | AdminService.getSystemHealth() | Component statuses (api, pageserver, safekeeper, proxy, rds, obs) |
| `get_component_logs` | Get pod logs | AdminService.getComponentLogs(component, tail) | Last N lines of logs for specified component |
| `list_databases` | List all databases | databaseRepository.findAll() via dbToMap() | Database list with status, tenant, compute info |
| `get_database_detail` | Get single database info | databaseRepository + branches | Full database detail with status_message, pod name |
| `list_recent_operations` | Recent operation logs | operationLogRepository with pagination | Last 20 operations with type, status, error |
| `get_infra_events` | K8s events | AdminService.getInfraEvents(namespace) | Recent pod events (warnings, errors) |
| `get_alerts` | Current active alerts | AlertService.getActiveAlerts() | Active alert list with severity and message |
| `get_compute_stats` | Compute resource summary | ComputeLifecycleService stats | Running/suspended/total pods, resource usage |

Tool parameter schemas follow OpenAI function calling format:

```json
{
  "type": "function",
  "function": {
    "name": "get_component_logs",
    "description": "获取指定组件的 Pod 日志",
    "parameters": {
      "type": "object",
      "properties": {
        "component": {
          "type": "string",
          "enum": ["lakeon-api", "pageserver", "safekeeper", "proxy"],
          "description": "组件名称"
        },
        "tail": {
          "type": "integer",
          "description": "日志行数，默认 100",
          "default": 100
        }
      },
      "required": ["component"]
    }
  }
}
```

### Tool Result Summarization

Raw tool results can be large (especially logs). Before feeding results back to the LLM:
- **Logs**: Truncate to last 50 lines, or filter to ERROR/WARN lines if > 100 lines
- **Database list**: If > 20 databases, only include FAILED/CREATING ones + count summary
- **Events**: Only include Warning events from last 1 hour
- **Other results**: JSON serialize, truncate to 2000 chars if necessary

### System Prompt

```
你是 DBay 平台的 SRE 智能助手。帮助运维工程师快速定位和诊断系统问题。

你的工作流程：
1. 理解用户的问题
2. 调用工具收集相关系统信息
3. 分析数据，判断根因
4. 给出具体的修复建议和操作步骤

规则：
- 用中文回答，简洁直接
- 先收集数据再下结论，不要猜测
- 给出具体的 kubectl 命令或操作步骤
- 如果信息不足，说明还需要什么信息
- 如果问题不在你能力范围内，直接说明
```

When `context` is provided in the request, append to user message:
```
[系统上下文] 当前关注的资源: {resource_type}={resource_id}
```

### Frontend

**AiChatPanel.vue** — Right-side sliding panel:
- Floating button (bottom-right corner) with chat icon
- Click to expand/collapse panel (400px wide)
- Message list showing user messages and AI responses
- AI responses show thinking steps (collapsible): tool calls and brief results
- Text input at bottom with send button
- Conversation kept in component state (not persisted)
- Clear conversation button

**AiDiagnoseButton.vue** — Small inline component:
- Shows on resources with abnormal status (FAILED, ERROR, etc.)
- Click emits event with `{resource_type, resource_id, question}`
- AdminLayout listens for event, opens AiChatPanel with pre-filled context

Diagnose buttons added to:
- DatabaseList.vue / DatabaseDetail.vue — when status is FAILED
- KnowledgeList.vue — when status is ERROR
- MemoryList.vue — when status is ERROR
- SystemHealth.vue — when any component is unhealthy

### Model Configuration

Uses existing `LakeonProperties.AiConfig`:
- `baseUrl`: SiliconFlow endpoint (default: `https://api.siliconflow.cn/v1`)
- `apiKey`: SiliconFlow API key

Model hardcoded to `deepseek-ai/DeepSeek-V3.2` for the SRE assistant (best balance of function calling capability and cost). Temperature: 0.1 (deterministic diagnostics).

### Security

- Endpoint protected by admin token (same as all `/api/v1/admin/*` endpoints)
- Tool execution is server-side only — LLM cannot execute arbitrary code
- Tool results are filtered/summarized before returning to LLM
- No write operations in tools — all tools are read-only
- Max 5 tool-calling rounds to prevent infinite loops
- 60-second timeout on SiliconFlow API calls

### What This Does NOT Include

- Conversation persistence (memory across sessions)
- Write operations (AI cannot delete/modify resources)
- Custom model selection (fixed to DeepSeek V3.2)
- Proactive alerting (AI only responds to questions)
- Knowledge base integration (no RAG over documentation)
