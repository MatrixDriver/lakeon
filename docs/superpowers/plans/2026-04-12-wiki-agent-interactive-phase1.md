# Wiki Agent Interactive Mode — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make WikiChat conversations go through the wiki-agent's tool-calling loop with full activity streaming (thinking, tool_call, tool_result, content), so users see the agent's complete reasoning process.

**Architecture:** Wiki-agent gets a new SSE `/v1/wiki/chat` endpoint that runs a chat-mode agent loop and streams events. lakeon-api proxies this SSE stream to the browser. WikiChat.vue renders each event type with appropriate styling (collapsible thinking blocks, tool call indicators, markdown content).

**Tech Stack:** Python/FastAPI (SSE via `StreamingResponse`), Java/Spring Boot (`SseEmitter` proxy), Vue 3/TypeScript (frontend), DeepSeek V3.2 (LLM)

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `lakeon-wiki-agent/app/agent/loop.py` | Modify | Add `_run_stream()` generator method + `CHAT_SYSTEM_PROMPT` + `run_chat()` |
| `lakeon-wiki-agent/app/api/routes.py` | Modify | Add `POST /v1/wiki/chat` SSE endpoint |
| `lakeon-wiki-agent/app/agent/tools.py` | Modify | Add `CHAT_TOOL_SCHEMAS` (read-only subset) |
| `lakeon-api/.../KnowledgeController.java` | Modify | Add `POST /wiki/chat/agent` SSE proxy endpoint |
| `lakeon-api/.../WikiAgentClient.java` | Modify | Add `streamChat()` method that opens SSE to wiki-agent |
| `lakeon-console/src/api/knowledge.ts` | Modify | Add `wikiAgentChatStream()` function |
| `lakeon-console/src/views/knowledge/WikiChat.vue` | Modify | Render new event types (thinking, tool_call, tool_result) |

---

### Task 1: Add CHAT_SYSTEM_PROMPT and CHAT_TOOL_SCHEMAS

**Files:**
- Modify: `lakeon-wiki-agent/app/agent/loop.py:62-127` (prompts section)
- Modify: `lakeon-wiki-agent/app/agent/tools.py:1-3` (add chat tool list)

- [ ] **Step 1: Add CHAT_SYSTEM_PROMPT to loop.py**

After the existing LINT_SYSTEM_PROMPT (line ~127), add:

```python
CHAT_SYSTEM_PROMPT = """你是一个 wiki 知识库助手，帮助用户理解和探索知识库内容。

你可以使用工具来查找和阅读 wiki 页面，然后基于真实内容回答用户问题。

工作流程：
1. 理解用户问题。
2. 用 search_pages 或 list_pages 找到相关页面。
3. 用 read_page 读取页面全文。
4. 基于页面内容回答用户。如果信息不足，再搜索更多页面。

硬性规则：
- 回答必须基于 wiki 页面中的真实内容，不要凭空编造。
- 引用来源时使用 [[页面标题]] 格式。
- 所有回答使用简体中文。
"""
```

- [ ] **Step 2: Add CHAT_TOOL_SCHEMAS to tools.py**

At the end of `tools.py`, add a read-only subset:

```python
# Read-only tools for chat mode (no create/update/delete/log_note/done)
CHAT_TOOL_SCHEMAS: list[dict] = [
    t for t in TOOL_SCHEMAS
    if t["function"]["name"] in ("list_pages", "read_page", "search_pages", "read_source", "get_schema")
]
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-wiki-agent/app/agent/loop.py lakeon-wiki-agent/app/agent/tools.py
git commit -m "feat(wiki-agent): add CHAT_SYSTEM_PROMPT and CHAT_TOOL_SCHEMAS"
```

---

### Task 2: Add `_run_stream()` generator to AgentRunner

**Files:**
- Modify: `lakeon-wiki-agent/app/agent/loop.py:136-280` (AgentRunner class)

This is the core change. `_run_stream()` is an async generator version of `_run()` that `yield`s SSE events instead of returning a final dict.

- [ ] **Step 1: Add `run_chat()` method after `run_lint()` (line ~162)**

```python
async def run_chat(
    self,
    req: RunRequest,
    question: str,
    history: list[dict[str, str]],
):
    """Async generator that yields SSE event dicts for a chat session."""
    from app.agent.tools import CHAT_TOOL_SCHEMAS
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": CHAT_SYSTEM_PROMPT},
    ]
    # Append conversation history
    for h in history:
        messages.append({"role": h["role"], "content": h["content"]})
    messages.append({"role": "user", "content": question})

    async for event in self._run_stream(req, messages, CHAT_TOOL_SCHEMAS, forbid=set()):
        yield event
```

- [ ] **Step 2: Add `_run_stream()` method after `_run()` (line ~280)**

```python
async def _run_stream(
    self,
    req: RunRequest,
    messages: list[dict[str, Any]],
    tools: list[dict],
    forbid: set[str],
):
    """Async generator that yields event dicts during an agent loop."""
    start = time.time()
    result = RunResult()

    try:
        for round_idx in range(self._max_rounds):
            # ── LLM call ──
            llm_resp = await self._llm.chat(messages=messages, tools=tools)
            result.token_count += llm_resp["usage"]["total"]
            msg = llm_resp["message"]
            tool_calls = msg.get("tool_calls") or []

            # If the LLM returned text content alongside tool calls, emit it as thinking
            if msg.get("content"):
                yield {"type": "thinking", "content": msg["content"]}

            if not tool_calls:
                # Plain content response — stream as final answer
                content = msg.get("content") or ""
                yield {"type": "content", "content": content}
                result.status = "success"
                result.summary = content
                break

            messages.append(msg)

            # Separate done from other tools
            done_call = None
            regular_calls = []
            for tc in tool_calls:
                if tc["function"]["name"] == "done":
                    done_call = tc
                else:
                    regular_calls.append(tc)

            for tc in regular_calls:
                name = tc["function"]["name"]
                try:
                    args = json.loads(tc["function"]["arguments"] or "{}")
                except json.JSONDecodeError as e:
                    messages.append(
                        self._tool_message(tc["id"], {"ok": False, "error": f"invalid JSON: {e}"})
                    )
                    result.tool_calls_count += 1
                    continue

                result.tool_calls_count += 1

                # Emit tool_call event
                yield {
                    "type": "tool_call",
                    "tool": name,
                    "args": _summarize_args(name, args),
                }

                if name in forbid:
                    tool_result = {"ok": False, "error": f"tool {name} is not allowed"}
                else:
                    tool_result = await self._execute_tool(req, name, args)
                    self._track_counts(result, name, args, tool_result)

                # Emit tool_result event
                yield {
                    "type": "tool_result",
                    "tool": name,
                    "ok": tool_result.get("ok", True) if isinstance(tool_result, dict) else True,
                    "summary": _summarize_result(name, tool_result),
                }

                messages.append(self._tool_message(tc["id"], tool_result))

            if done_call is not None:
                try:
                    done_args = json.loads(done_call["function"]["arguments"] or "{}")
                except json.JSONDecodeError:
                    done_args = {}
                result.tool_calls_count += 1
                result.status = "success"
                result.summary = done_args.get("summary", "")
                messages.append(
                    self._tool_message(done_call["id"], {"ok": True, "acknowledged": True})
                )
                break
        else:
            result.status = "max_rounds_exceeded"
            result.error = f"agent did not finish within {self._max_rounds} rounds"

    except Exception as e:
        log.exception("agent stream %s failed: %s", req.run_id, e)
        result.status = "error"
        result.error = f"{type(e).__name__}: {e}"
        yield {"type": "error", "message": str(e)}

    duration_ms = int((time.time() - start) * 1000)

    yield {
        "type": "done",
        "status": result.status,
        "summary": result.summary,
        "pages_created": result.pages_created,
        "pages_updated": result.pages_updated,
        "tool_calls_count": result.tool_calls_count,
        "duration_ms": duration_ms,
    }
```

- [ ] **Step 3: Add helper functions for summarizing args/results**

After the `AgentRunner` class (before the existing helpers section):

```python
def _summarize_args(tool_name: str, args: dict) -> dict:
    """Return a compact version of tool args for the SSE event (avoid sending full page content)."""
    if tool_name in ("create_page", "update_page", "append_page"):
        summary = {k: v for k, v in args.items() if k != "content"}
        if "content" in args:
            summary["content_length"] = len(args["content"])
        if "old_text" in args:
            summary["old_text"] = args["old_text"][:80] + "..." if len(args.get("old_text", "")) > 80 else args.get("old_text", "")
        if "new_text" in args:
            summary["new_text"] = args["new_text"][:80] + "..." if len(args.get("new_text", "")) > 80 else args.get("new_text", "")
        return summary
    return args


def _summarize_result(tool_name: str, result: Any) -> str:
    """Return a short string summary of a tool result for the SSE event."""
    if isinstance(result, dict):
        if not result.get("ok", True):
            return f"error: {result.get('error', 'unknown')}"
        if tool_name == "list_pages":
            pages = result.get("pages", result)
            if isinstance(pages, list):
                return f"{len(pages)} pages"
        if "content" in result:
            content = result["content"]
            if isinstance(content, str) and len(content) > 100:
                return content[:100] + "..."
            return str(content)[:200]
        if "filename" in result:
            return f"ok: {result['filename']}"
    if isinstance(result, list):
        return f"{len(result)} items"
    return str(result)[:200]
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-wiki-agent/app/agent/loop.py
git commit -m "feat(wiki-agent): add _run_stream() async generator for SSE chat"
```

---

### Task 3: Add `/v1/wiki/chat` SSE endpoint

**Files:**
- Modify: `lakeon-wiki-agent/app/api/routes.py:1-149`

- [ ] **Step 1: Add ChatRequest model and SSE endpoint**

After the existing `LintRequest` class (line ~40), add:

```python
class ChatRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    kb_id: str = Field(..., min_length=1)
    question: str = Field(..., min_length=1)
    history: list[dict[str, str]] = Field(default_factory=list)
```

After the `task_status` endpoint (line ~148), add:

```python
from fastapi.responses import StreamingResponse
import json as _json


@router.post(
    "/v1/wiki/chat",
    dependencies=[Depends(require_token)],
    summary="Interactive wiki chat with SSE streaming",
)
async def chat_stream(
    req: ChatRequest,
    runner: AgentRunner = Depends(get_runner),
) -> StreamingResponse:
    from app.agent.loop import RunRequest, new_run_id

    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source="chat",
        run_type="chat",
    )

    async def event_generator():
        async for event in runner.run_chat(run_req, req.question, req.history):
            yield f"data: {_json.dumps(event, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-wiki-agent/app/api/routes.py
git commit -m "feat(wiki-agent): add POST /v1/wiki/chat SSE endpoint"
```

---

### Task 4: Add SSE proxy in lakeon-api

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java:40-47`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java:953-998`

- [ ] **Step 1: Add `streamChat()` to WikiAgentClient**

This method opens an SSE connection to the wiki-agent and returns a reader. Add after the `triggerLint()` method:

```java
/**
 * Open an SSE stream to the wiki-agent /v1/wiki/chat endpoint.
 * Returns an InputStream that the caller reads line-by-line.
 */
public java.io.InputStream streamChat(String tenantId, String kbId, String question,
                                       java.util.List<Map<String, String>> history) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenantId);
    body.put("kb_id", kbId);
    body.put("question", question);
    body.put("history", history);

    try {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getWiki().getAgentUrl() + "/v1/wiki/chat"))
                .header("Authorization", "Bearer " + props.getWiki().getAgentInternalToken())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<java.io.InputStream> resp = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            log.warn("Wiki agent chat returned HTTP {}", resp.statusCode());
            return null;
        }
        return resp.body();
    } catch (Exception e) {
        log.warn("Wiki agent chat failed: {}", e.getMessage());
        return null;
    }
}
```

Note: `httpClient.send()` (not `sendAsync`) is used because this runs in a background thread — the SseEmitter callback pattern handles the async aspect.

- [ ] **Step 2: Add `wikiAgentChatStream` endpoint in KnowledgeController**

Add after the existing `wikiChatStream` endpoint (line ~998):

```java
@PostMapping("/wiki/chat/agent")
public SseEmitter wikiAgentChatStream(HttpServletRequest req,
                                       @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    String kbId = (String) body.get("kb_id");
    String question = (String) body.get("question");
    if (kbId == null || question == null || question.isBlank()) {
        throw new com.lakeon.service.exception.BadRequestException("kb_id and question are required");
    }
    kbAccessService.getKbWithAccess(kbId, tenant.getId());

    @SuppressWarnings("unchecked")
    var history = (java.util.List<Map<String, String>>) body.getOrDefault("history", List.of());

    var emitter = new SseEmitter(300_000L);  // 5-minute timeout for agent interactions

    new Thread(() -> {
        try (var stream = wikiAgentClient.streamChat(tenant.getId(), kbId, question, history)) {
            if (stream == null) {
                // Fallback: wiki-agent unavailable, use legacy direct LLM chat
                wikiService.chatStream(tenant.getId(), kbId, question, history, event -> {
                    try { emitter.send(SseEmitter.event().data(event)); }
                    catch (Exception ignored) {}
                });
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
                return;
            }
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    emitter.send(SseEmitter.event().data(line.substring(5).trim()));
                }
            }
            emitter.complete();
        } catch (Exception e) {
            log.warn("Wiki agent chat stream error: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().data(
                    "{\"type\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
        }

        // Update chat count
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setChatCount((kb.getChatCount() != null ? kb.getChatCount() : 0) + 1);
                knowledgeBaseRepository.save(kb);
            });
        } catch (Exception ignored) {}
    }).start();

    return emitter;
}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat(api): add wiki agent chat SSE proxy endpoint"
```

---

### Task 5: Add frontend API function

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts:393-403`

- [ ] **Step 1: Add `wikiAgentChatStream()` function**

After the existing `wikiChatStream()` function:

```typescript
export function wikiAgentChatStream(kbId: string, question: string, history: { role: string; content: string }[] = []) {
  const apiKey = localStorage.getItem('lakeon_api_key') || ''
  return fetch(`${api.defaults.baseURL}/knowledge/wiki/chat/agent`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`,
    },
    body: JSON.stringify({ kb_id: kbId, question, history }),
  })
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts
git commit -m "feat(console): add wikiAgentChatStream API function"
```

---

### Task 6: Update WikiChat.vue to render agent events

**Files:**
- Modify: `lakeon-console/src/views/knowledge/WikiChat.vue:1-229`

This is the largest frontend change. The component needs to:
1. Use the new `wikiAgentChatStream` API
2. Parse new event types (thinking, tool_call, tool_result, content, done)
3. Render each event type with appropriate styling

- [ ] **Step 1: Update Message interface and imports**

Replace lines 1-16:

```typescript
<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { wikiAgentChatStream, wikiChatStream, saveWikiResponse } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

interface AgentEvent {
  type: 'thinking' | 'tool_call' | 'tool_result' | 'content' | 'done' | 'error'
  content?: string
  tool?: string
  args?: Record<string, any>
  ok?: boolean
  summary?: string
  message?: string
  status?: string
  pages_created?: number
  pages_updated?: number
  tool_calls_count?: number
  duration_ms?: number
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  events?: AgentEvent[]  // agent events for this message
  saved?: boolean
  saving?: boolean
}
```

- [ ] **Step 2: Update `send()` function to use agent SSE and parse events**

Replace the `send()` function (lines 53-122):

```typescript
async function send() {
  const question = input.value.trim()
  if (!question || loading.value) return

  messages.value.push({ role: 'user', content: question })
  input.value = ''
  loading.value = true
  await scrollToBottom()

  const assistantMsg: Message = {
    role: 'assistant',
    content: '',
    events: [],
    saved: false,
    saving: false,
  }
  messages.value.push(assistantMsg)

  try {
    const history = messages.value.slice(0, -2).map(m => ({
      role: m.role, content: m.content
    }))

    const response = await wikiAgentChatStream(props.kbId, question, history)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (data === '[DONE]' || !data) continue

        try {
          const event: AgentEvent = JSON.parse(data)

          // Legacy fallback: old API sends {type: "chunk"} and {type: "meta"}
          if ((event as any).type === 'chunk') {
            assistantMsg.content += (event as any).content || ''
            if (assistantMsg.content.length % 50 < 5) await scrollToBottom()
            continue
          }
          if ((event as any).type === 'meta') continue

          // New agent events
          assistantMsg.events!.push(event)

          if (event.type === 'content') {
            assistantMsg.content += event.content || ''
          } else if (event.type === 'error') {
            assistantMsg.content = '\u62b1\u6b49\uff0c\u51fa\u9519\u4e86: ' + (event.message || '')
          }

          await scrollToBottom()
        } catch { /* skip malformed */ }
      }
    }
  } catch (e: any) {
    if (!assistantMsg.content) {
      assistantMsg.content = '\u62b1\u6b49\uff0c\u51fa\u9519\u4e86: ' + (e.message || '\u672a\u77e5\u9519\u8bef')
    }
  } finally {
    loading.value = false
    saveMessages()
    await scrollToBottom()
  }
}
```

- [ ] **Step 3: Update template to render agent events**

Replace the assistant message rendering section (the `<div v-else>` block inside the message loop, lines 183-202):

```html
        <div v-else>
          <!-- Agent events -->
          <div v-if="msg.events?.length" style="margin-bottom: 8px;">
            <div v-for="(ev, ei) in msg.events" :key="ei">
              <!-- Thinking -->
              <details v-if="ev.type === 'thinking'" class="agent-event" style="margin-bottom: 4px;">
                <summary class="agent-event-header" style="color: #b0a090; cursor: pointer;">
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: -1px; margin-right: 4px;"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
                  思考中...
                </summary>
                <div style="padding: 6px 12px; margin: 4px 0; color: #999; font-size: 12px; border-left: 2px solid #e8e0d8;">
                  {{ ev.content }}
                </div>
              </details>

              <!-- Tool call -->
              <div v-else-if="ev.type === 'tool_call'" style="margin-bottom: 4px; display: flex; align-items: center; gap: 6px; font-size: 12px; color: #8c7a68;">
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="#c19a6b" stroke-width="2" style="flex-shrink: 0;"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
                <span>{{ ev.tool }}</span>
                <span v-if="ev.args?.title" style="color: #bbb;">&middot; {{ ev.args.title }}</span>
                <span v-else-if="ev.args?.query" style="color: #bbb;">&middot; "{{ ev.args.query }}"</span>
              </div>

              <!-- Tool result -->
              <details v-else-if="ev.type === 'tool_result'" class="agent-event" style="margin-bottom: 4px;">
                <summary class="agent-event-header" style="font-size: 12px; cursor: pointer;"
                         :style="{ color: ev.ok !== false ? '#7a9e5a' : '#c25a3c' }">
                  <span v-if="ev.ok !== false">&#10003;</span><span v-else>&#10007;</span>
                  {{ ev.tool }} &middot; {{ ev.summary?.substring(0, 60) }}{{ (ev.summary?.length || 0) > 60 ? '...' : '' }}
                </summary>
                <div style="padding: 6px 12px; margin: 4px 0; color: #999; font-size: 12px; border-left: 2px solid #e8e0d8; white-space: pre-wrap; max-height: 200px; overflow-y: auto;">
                  {{ ev.summary }}
                </div>
              </details>

              <!-- Done -->
              <div v-else-if="ev.type === 'done' && ev.status === 'success' && (ev.pages_created || ev.pages_updated)" style="margin-bottom: 4px; font-size: 12px; color: #7a9e5a;">
                &#10003; 完成
                <span v-if="ev.pages_created">&middot; 创建 {{ ev.pages_created }} 页</span>
                <span v-if="ev.pages_updated">&middot; 更新 {{ ev.pages_updated }} 页</span>
              </div>
            </div>
          </div>

          <!-- Content bubble -->
          <div v-if="msg.content" style="background: #fff; border: 1px solid #e8e0d8; padding: 12px 16px; border-radius: 8px;">
            <MarkdownRenderer :content="msg.content" :kb-id="kbId" @navigate="(t) => emit('navigate', t)" />
          </div>
          <div v-else-if="loading && i === messages.length - 1 && !msg.events?.length" style="color: #b0a090; padding: 8px;">
            思考中...
          </div>
          <!-- Save button -->
          <div v-if="msg.content && !loading && !msg.saved" style="margin-top: 6px;">
            <button :disabled="msg.saving"
                    style="font-size: 12px; color: #8b6914; background: none; border: 1px solid #8b6914; border-radius: 4px; padding: 3px 10px; cursor: pointer;"
                    @click="saveToWiki(msg)">
              {{ msg.saving ? '保存中...' : '沉淀到知识库' }}
            </button>
          </div>
          <span v-if="msg.saved" style="margin-top: 6px; font-size: 12px; color: #7a9e5a; display: inline-block;">
            已沉淀
          </span>
        </div>
```

- [ ] **Step 4: Add CSS for agent events**

At the end of the file add a `<style scoped>` section:

```html
<style scoped>
.agent-event summary {
  font-size: 12px;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 4px;
}
.agent-event summary::-webkit-details-marker { display: none; }
.agent-event summary::before {
  content: '\25b6';
  font-size: 8px;
  color: #ccc;
  transition: transform 0.15s;
  margin-right: 4px;
}
.agent-event[open] summary::before {
  transform: rotate(90deg);
}
</style>
```

- [ ] **Step 5: Run TypeScript check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

Expected: No errors.

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/WikiChat.vue
git commit -m "feat(console): render agent events in WikiChat (thinking, tool_call, tool_result)"
```

---

### Task 7: Build, deploy, and verify

**Files:** No code changes — deployment and manual verification.

- [ ] **Step 1: Push all commits**

```bash
git pull --rebase && git push
```

- [ ] **Step 2: Build and deploy wiki-agent**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-wiki-agent.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-wiki-agent -n lakeon
```

- [ ] **Step 3: Build and deploy API**

```bash
IMAGE_TAG=0.9.222 SITE=hwstaff bash deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
```

- [ ] **Step 4: Wait for rollouts**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-wiki-agent -n lakeon --timeout=120s
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=120s
```

- [ ] **Step 5: Test the SSE endpoint directly**

```bash
curl -N -H "Authorization: Bearer <wiki-agent-token>" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"<tenant>","kb_id":"<kb>","question":"这个知识库有哪些内容?","history":[]}' \
  http://<wiki-agent-pod-ip>:8000/v1/wiki/chat
```

Expected: SSE events streaming back with `data:` prefix, ending with `data: [DONE]`.

- [ ] **Step 6: Test via the API proxy**

Open the console in browser, navigate to a KB → Wiki → 对话 tab, type a question. Should see:
- Thinking blocks (collapsible)
- Tool calls (list_pages, search_pages, read_page)
- Tool results (collapsible)
- Final markdown answer

- [ ] **Step 7: Verify fallback**

If the wiki-agent pod is down, the `/wiki/chat/agent` endpoint should fall back to the legacy `WikiService.chatStream()` and still return a working response (without agent events).
