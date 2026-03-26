# Memory Scene Type Design

## Overview

Add a `scene` field to memory bases so the system can tailor extraction prompts, memory types, decay, and reflection strategies to the agent's use case.

## Scene Types

Two scene types at launch:

| Scene | Value | Target Agent | Extraction Types | Decay | Auto Digest |
|-------|-------|-------------|-----------------|-------|-------------|
| 开发者工具 | `DEVELOPER_TOOL` | Claude Code, Cursor, CLI agents | fact, procedural, decision, rejection, convention | None | No |
| 对话助理 | `CHAT_ASSISTANT` | Chatbots, personal assistants, customer service | All (including episode) | Time decay | Periodic |

## Data Layer

`memory_bases` table: add `scene VARCHAR(32) NOT NULL DEFAULT 'CHAT_ASSISTANT'`.

`MemoryBaseEntity.java`: add `scene` field (String).

## API Layer

### Create Memory Base

`POST /api/v1/memory/bases` body adds required `scene` field:

```json
{
  "name": "my-memory",
  "scene": "DEVELOPER_TOOL",
  "type": "BUILTIN"
}
```

Validation: `scene` must be one of `DEVELOPER_TOOL`, `CHAT_ASSISTANT`. Return 400 if missing or invalid.

### Response

All memory base responses include `scene`:

```json
{
  "id": "mem_xxx",
  "name": "my-memory",
  "scene": "DEVELOPER_TOOL",
  ...
}
```

### MCP Tools

No change to MCP tool signatures. Scene is transparent to callers — the backend uses the memory base's scene to determine extraction strategy.

## Memory Microservice

### Header Passing

`MemoryService.proxyPost/proxyGet` passes `X-Scene` header to the Python memory microservice, read from the memory base entity's `scene` field.

### Extraction Prompt Switching

`engine.py` receives scene and uses different extraction prompts:

**DEVELOPER_TOOL prompt focus:**
- Extract facts, procedures, decisions, rejections, conventions
- Skip episode/episodic memories
- Structured, concise extraction
- No emotional or contextual tagging

**CHAT_ASSISTANT prompt focus:**
- Extract all memory types including episodes
- Capture emotional context and user sentiment
- Richer contextual extraction
- Support trait reflection

### Decay and Digest

| Behavior | DEVELOPER_TOOL | CHAT_ASSISTANT |
|----------|---------------|----------------|
| Time decay on recall | Disabled | Enabled (exponential) |
| Auto digest/reflect | Disabled | Enabled (periodic) |
| Episode extraction | Disabled | Enabled |

## Console UI

### Create Memory Base Dialog

Add scene selection as two cards before the name input. Each card shows:

- **开发者工具** — 适用于 Claude Code、Cursor 等编码助手。记录事实、流程、决策和教训，不记录对话情景，不自动衰减。
- **对话助理** — 适用于聊天机器人、个人助理、客服等。记录完整对话情景，自动提炼用户特征，支持时间衰减。

Scene is required — user must select one before proceeding.

### Memory Base List & Detail

Display scene as a tag/badge next to the memory base name.

## Files Changed

### Java (lakeon-api)
- `MemoryBaseEntity.java` — add `scene` field
- `MemoryController.java` — read `scene` from create body, include in response
- `MemoryService.java` — pass scene to proxy calls
- `MemoryDbHelper.java` or `MemoryService.java` — add `X-Scene` header to proxy requests

### Python (memory microservice)
- `main.py` — read `X-Scene` header, pass to engine
- `engine.py` — switch extraction prompt based on scene

### Console (lakeon-console)
- Create memory base dialog — add scene card selector with descriptions
- Memory base list/detail — show scene badge
- API client — add `scene` field to create request

## Migration

No migration of existing memory bases. Old bases without `scene` default to `CHAT_ASSISTANT`. Users can delete and recreate with the desired scene.
