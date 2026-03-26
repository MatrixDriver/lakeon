# Memory Scene Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `scene` field to memory bases that controls extraction prompts, memory types, decay, and reflection behavior.

**Architecture:** `scene` stored on `MemoryBaseEntity`, passed as `X-Scene` header to Python memory microservice. Microservice switches extraction prompt based on scene. Console shows scene selector on create.

**Tech Stack:** Spring Boot 3.3.5 (Java), FastAPI (Python), Vue 3 (Console)

**Spec:** `docs/superpowers/specs/2026-03-26-memory-scene-design.md`

---

## File Structure

```
lakeon-api/src/main/java/com/lakeon/memory/
├── MemoryBaseEntity.java     # Add scene field
├── MemoryController.java     # Read/return scene
└── MemoryService.java        # Pass X-Scene header to proxy

memory/service/
├── main.py                   # Read X-Scene header
├── engine.py                 # Pass scene to background_extract
├── extraction_prompt.py      # Split into scene-specific prompts
└── models.py                 # Add scene to IngestRequest (optional)

lakeon-console/src/
├── api/memory.ts             # Add scene to MemoryBase type + createMemoryBase
└── views/memory/MemoryBases.vue  # Scene selector cards in create dialog
```

---

### Task 1: Add `scene` field to Entity + API

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java`

- [ ] **Step 1: Add scene field to MemoryBaseEntity**

Add after the `oneLlmMode` field (line 54):

```java
@Column(name = "scene", length = 32)
private String scene = "CHAT_ASSISTANT";
```

Add getter/setter after line 115:

```java
public String getScene() { return scene; }
public void setScene(String scene) { this.scene = scene; }
```

- [ ] **Step 2: Update MemoryController to accept and return scene**

In `createBase` method (line 33-44), read `scene` from body:

```java
@PostMapping("/bases")
public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    boolean oneLlmMode = Boolean.TRUE.equals(body.get("one_llm_mode"));
    String scene = (String) body.getOrDefault("scene", "CHAT_ASSISTANT");
    if (!List.of("DEVELOPER_TOOL", "CHAT_ASSISTANT").contains(scene)) {
        throw new com.lakeon.service.exception.BadRequestException("Invalid scene: " + scene + ". Must be DEVELOPER_TOOL or CHAT_ASSISTANT");
    }
    return toMemResponse(memoryService.createBase(
        tenant,
        (String) body.get("name"),
        (String) body.get("description"),
        MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN").toString()),
        (String) body.get("embedding_model"),
        oneLlmMode,
        scene
    ));
}
```

In `toMemResponse` method (~line 135), add scene:

```java
map.put("scene", mem.getScene());
```

- [ ] **Step 3: Update MemoryService.createBase to accept scene**

Change method signature and set scene on entity:

```java
public MemoryBaseEntity createBase(TenantEntity tenant, String name, String description,
                                    MemoryBaseType type, String embeddingModel, boolean oneLlmMode,
                                    String scene) {
    // ... existing code ...
    entity.setScene(scene != null ? scene : "CHAT_ASSISTANT");
    // ... rest unchanged ...
}
```

- [ ] **Step 4: Pass X-Scene header in proxy methods**

In `proxyPost` method, add scene header:

```java
public Object proxyPost(String tenantId, String memId, String path, Object body) {
    MemoryBaseEntity mem = getBase(tenantId, memId);
    String connstr = dbHelper.resolveConnstr(tenantId, memId);
    ensureSchemaInitialized(connstr);
    String url = props.getMemory().getServiceUrl() + path;
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Database-Connstr", connstr);
    headers.set("X-One-Llm-Mode", String.valueOf(Boolean.TRUE.equals(mem.getOneLlmMode())));
    headers.set("X-Scene", mem.getScene() != null ? mem.getScene() : "CHAT_ASSISTANT");
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<?> entity = new HttpEntity<>(body, headers);
    ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
    return resp.getBody();
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/
git commit -m "feat(memory): add scene field to MemoryBaseEntity + API + proxy header"
```

---

### Task 2: Python microservice — scene-aware extraction prompts

**Files:**
- Modify: `memory/service/main.py`
- Modify: `memory/service/engine.py`
- Modify: `memory/service/extraction_prompt.py`

- [ ] **Step 1: Read X-Scene header in main.py**

Update `/ingest` endpoint to read and pass scene:

```python
@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...),
                 x_one_llm_mode: str = Header("false"),
                 x_scene: str = Header("CHAT_ASSISTANT")):
    one_llm = x_one_llm_mode.lower() == "true"
    auto_extract = req.auto_extract if req.auto_extract is not None else (not one_llm)

    message_id = await engine.store_raw_message(x_database_connstr, req.content, req.role, req.source)

    if auto_extract:
        asyncio.create_task(engine.background_extract(x_database_connstr, message_id, req.content, x_scene))
        return {"message_id": message_id, "extraction_required": False, "status": "extracting"}
    else:
        from extraction_prompt import build_extraction_prompt
        prompt = build_extraction_prompt(req.content, scene=x_scene)
        return {"message_id": message_id, "extraction_required": True, "extraction_prompt": prompt}
```

- [ ] **Step 2: Update engine.py background_extract to accept scene**

```python
async def background_extract(connstr: str, message_id: str, content: str, scene: str = "CHAT_ASSISTANT"):
    """Background task: call LLM to extract memories, then store them."""
    import logging
    from extraction_prompt import build_extraction_prompt
    from llm_client import chat_extract

    logger = logging.getLogger(__name__)
    try:
        prompt = build_extraction_prompt(content, scene=scene)
        result = await chat_extract(prompt)
        counts = await ingest_extracted(connstr, message_id, result)
        logger.info("Background extraction for %s (scene=%s): %s", message_id, scene, counts)
    except Exception as e:
        logger.error("Background extraction failed for %s: %s", message_id, e, exc_info=True)
```

- [ ] **Step 3: Update extraction_prompt.py with scene-based prompt switching**

Add scene parameter to `build_extraction_prompt`:

```python
def build_extraction_prompt(content: str, scene: str = "CHAT_ASSISTANT") -> str:
    """Build the extraction prompt, auto-detecting language and switching by scene."""
    language = detect_language(content)
    if scene == "DEVELOPER_TOOL":
        if language == "en":
            return _build_developer_en_prompt(content)
        return _build_developer_zh_prompt(content)
    else:
        if language == "en":
            return _build_en_prompt(content)
        return _build_zh_prompt(content)
```

Add developer-focused prompts. These are simplified versions that skip episodes and emotional tagging:

```python
def _build_developer_en_prompt(content: str) -> str:
    return f"""Extract structured memory information from the following content.
Return results strictly in JSON format.

<content>
{content}
</content>

Extract ONLY these memory types (skip episodes and emotional context):

1. **Facts**: Persistent information about the user — preferences, credentials, project details
   - Format: {{"content": "fact description", "category": "category", "confidence": 0.0-1.0, "importance": 1-10}}
   - Category: identity, work, skill, hobby, personal, values
   - Must be atomic (one fact per item) and self-contained (no pronouns)

2. **Procedural**: Commands, workflows, deployment steps, tool usage patterns
   - Format: {{"content": "procedural description", "category": "category"}}
   - Category: workflow, tool_usage, coding_pattern, configuration, process

3. **Decisions**: Deliberate technical or architectural choices with rationale
   - Format: {{"content": "decision description", "rationale": "why", "project": "project or null", "confidence": 0.0-1.0}}

4. **Rejections**: Approaches explicitly excluded with stated reason
   - Format: {{"content": "what was rejected", "reason": "why", "project": "project or null", "confidence": 0.0-1.0}}

5. **Conventions**: Rules, coding standards, naming conventions, operational norms
   - Format: {{"content": "convention description", "scope": "applicability", "project": "project or null", "confidence": 0.0-1.0}}

Requirements:
- Only extract explicitly mentioned information
- All content must be self-contained (explicit subject, no pronouns)
- Return valid JSON only

```json
{{
  "facts": [...],
  "procedural": [...],
  "decisions": [...],
  "rejections": [...],
  "conventions": [...]
}}
```"""


def _build_developer_zh_prompt(content: str) -> str:
    return f"""分析以下内容，提取结构化记忆信息。请严格按照 JSON 格式返回结果。

<content>
{content}
</content>

只提取以下记忆类型（跳过情景记忆和情感标注）：

1. **Facts（事实）**: 用户的持久性信息 — 偏好、凭据、项目细节
   - 格式: {{"content": "事实描述", "category": "分类", "confidence": 0.0-1.0, "importance": 1-10}}
   - category: identity, work, skill, hobby, personal, values
   - 每条必须原子化（一条信息）且自包含（不使用代词）

2. **Procedural（流程）**: 命令、工作流、部署步骤、工具使用模式
   - 格式: {{"content": "流程描述", "category": "分类"}}
   - category: workflow, tool_usage, coding_pattern, configuration, process

3. **Decisions（决策）**: 有明确理由的技术或架构选择
   - 格式: {{"content": "决策描述", "rationale": "原因", "project": "项目名或null", "confidence": 0.0-1.0}}

4. **Rejections（排除项）**: 明确被排除的方案及原因
   - 格式: {{"content": "被排除的方案", "reason": "原因", "project": "项目名或null", "confidence": 0.0-1.0}}

5. **Conventions（约定）**: 规则、编码标准、命名规范、运维惯例
   - 格式: {{"content": "约定描述", "scope": "适用范围", "project": "项目名或null", "confidence": 0.0-1.0}}

要求:
- 只提取明确提到的信息
- 所有 content 必须自包含（有明确主语，不使用代词）
- 只返回 JSON

```json
{{
  "facts": [...],
  "procedural": [...],
  "decisions": [...],
  "rejections": [...],
  "conventions": [...]
}}
```"""
```

The existing `_build_en_prompt` and `_build_zh_prompt` (CHAT_ASSISTANT) remain unchanged — they already extract all 7 types including episodes, emotions, and triples.

- [ ] **Step 4: Commit**

```bash
git add memory/service/main.py memory/service/engine.py memory/service/extraction_prompt.py
git commit -m "feat(memory): scene-aware extraction prompts — DEVELOPER_TOOL skips episodes"
```

---

### Task 3: Console — scene selector in create dialog

**Files:**
- Modify: `lakeon-console/src/api/memory.ts`
- Modify: `lakeon-console/src/views/memory/MemoryBases.vue`

- [ ] **Step 1: Add scene to API types and createMemoryBase**

In `memory.ts`, add `scene` to `MemoryBase` interface:

```typescript
export interface MemoryBase {
  id: string
  tenant_id: string
  name: string
  description: string | null
  type: 'BUILTIN' | 'MEM0' | 'HINDSIGHT' | 'CUSTOM'
  scene: 'DEVELOPER_TOOL' | 'CHAT_ASSISTANT'
  // ... rest unchanged
}
```

Update `createMemoryBase`:

```typescript
export function createMemoryBase(name: string, description?: string, options?: {
  type?: MemoryBase['type']
  scene?: MemoryBase['scene']
  embedding_model?: string
  one_llm_mode?: boolean
}) {
  return api.post<MemoryBase>('/memory/bases', { name, description, ...options })
}
```

- [ ] **Step 2: Add scene selector cards to create dialog**

In `MemoryBases.vue`, add `scene` to `createForm`:

```typescript
const createForm = ref({
  name: '',
  description: '',
  type: 'BUILTIN' as MemoryBase['type'],
  scene: '' as string,  // Must select
  embedding_model: 'BAAI/bge-m3',
  agent_extract: false,
})
```

In the template, add scene cards after the dialog header (before the type selector). Insert inside `<div class="dialog-body">` as the first form-group:

```html
<div class="form-group">
  <label class="form-label">应用场景 <span style="color:#e6393d">*</span></label>
  <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
    <div class="scene-card" :class="{ selected: createForm.scene === 'DEVELOPER_TOOL' }"
         @click="createForm.scene = 'DEVELOPER_TOOL'">
      <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px;">🛠 开发者工具</div>
      <div style="font-size: 12px; color: #666; line-height: 1.5;">
        适用于 Claude Code、Cursor 等编码助手。记录事实、流程、决策和教训，不记录对话情景，不自动衰减。
      </div>
    </div>
    <div class="scene-card" :class="{ selected: createForm.scene === 'CHAT_ASSISTANT' }"
         @click="createForm.scene = 'CHAT_ASSISTANT'">
      <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px;">💬 对话助理</div>
      <div style="font-size: 12px; color: #666; line-height: 1.5;">
        适用于聊天机器人、个人助理、客服等。记录完整对话情景，自动提炼用户特征，支持时间衰减。
      </div>
    </div>
  </div>
</div>
```

Update `resetCreateForm` to reset scene:

```typescript
function resetCreateForm() {
  createForm.value = {
    name: '',
    description: '',
    type: 'BUILTIN',
    scene: '',
    embedding_model: 'BAAI/bge-m3',
    agent_extract: false,
  }
}
```

Update `handleCreate` to pass scene:

```typescript
async function handleCreate() {
  try {
    const { name, description, type, scene, embedding_model, agent_extract } = createForm.value
    const options: { type?: MemoryBase['type']; scene?: MemoryBase['scene']; embedding_model?: string; one_llm_mode?: boolean } = { type, scene: scene as MemoryBase['scene'] }
    if (type === 'BUILTIN' && embedding_model) {
      options.embedding_model = embedding_model
    }
    if (type === 'BUILTIN' && agent_extract) {
      options.one_llm_mode = true
    }
    await createMemoryBase(name, description || undefined, options)
    showCreate.value = false
    resetCreateForm()
    await loadMemoryBases()
  } catch (e: any) {
    alert('创建失败: ' + (e.response?.data?.error?.message || e.message))
  }
}
```

Update the create button to require scene selection:

```html
<button class="btn btn-primary" @click="handleCreate" :disabled="!createForm.name.trim() || !createForm.scene">创建</button>
```

- [ ] **Step 3: Add scene-card CSS**

Add to the `<style scoped>` section:

```css
.scene-card {
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.scene-card:hover {
  border-color: #0073e6;
}
.scene-card.selected {
  border-color: #0073e6;
  background: #f0f7ff;
  box-shadow: 0 0 0 1px #0073e6;
}
```

- [ ] **Step 4: Show scene badge in memory base list**

In the table row where memory bases are listed, add scene badge after the name. Find the `<td>` that shows `item.name` and add:

```html
<span v-if="item.scene" style="font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-left: 8px;"
      :style="item.scene === 'DEVELOPER_TOOL' ? 'background:#e8f5e9;color:#2e7d32' : 'background:#e3f2fd;color:#1565c0'">
  {{ item.scene === 'DEVELOPER_TOOL' ? '开发者工具' : '对话助理' }}
</span>
```

- [ ] **Step 5: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: no errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/api/memory.ts lakeon-console/src/views/memory/MemoryBases.vue
git commit -m "feat(console): scene selector in memory base creation + scene badge in list"
```

---

### Task 4: Build, deploy, and verify

- [ ] **Step 1: Build and push API image**

```bash
# Update tag in values.yaml, then:
IMAGE_TAG=<next> ./deploy/cce/build-and-push-api.sh
```

- [ ] **Step 2: Build and push memory service image**

```bash
# If memory service image needs rebuild:
# Check current tag, bump, build, push
```

- [ ] **Step 3: Deploy**

```bash
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 4: Push console changes**

```bash
git push origin main
# Railway auto-deploys
```

- [ ] **Step 5: Verify end-to-end**

1. Open Console → 记忆库 → 创建记忆库
2. Verify scene selector cards appear with descriptions
3. Select "开发者工具", create memory base
4. Verify scene badge shows in list
5. Test ingest via MCP — verify extraction prompt doesn't include episodes
