# Admin Wiki Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Admin Wiki management: wiki page CRUD, 3 configurable prompts (ingest/routing/answer), LLM connection test.

**Architecture:** Backend adds 4 new admin endpoints + extends existing config endpoint. Frontend extends Admin KnowledgeList.vue with wiki page table and prompt tabs. All admin endpoints require X-Admin-Token + Authorization Bearer.

**Tech Stack:** Spring Boot (Java 17), Vue 3 + TypeScript (admin console)

---

### Task 1: Backend — Chat Routing & Answer Prompts Configurable

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` (WikiConfig class ~line 403)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java` (buildRoutingPrompt ~line 531, buildAnswerPrompt ~line 557)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` (admin wiki config endpoints ~line 427)

- [ ] **Step 1: Add chatRoutingPrompt and chatAnswerPrompt to WikiConfig**

In `LakeonProperties.java`, WikiConfig class (~line 407), add after `curatePrompt`:

```java
private String chatRoutingPrompt = "";
private String chatAnswerPrompt = "";
```

Add getters/setters following existing pattern:
```java
public String getChatRoutingPrompt() { return chatRoutingPrompt; }
public void setChatRoutingPrompt(String chatRoutingPrompt) { this.chatRoutingPrompt = chatRoutingPrompt; }
public String getChatAnswerPrompt() { return chatAnswerPrompt; }
public void setChatAnswerPrompt(String chatAnswerPrompt) { this.chatAnswerPrompt = chatAnswerPrompt; }
```

- [ ] **Step 2: Add getter methods in WikiService**

In `WikiService.java`, after `getModel()` (~line 179), add:

```java
private static final String DEFAULT_ROUTING_PROMPT = """
        You are a wiki routing agent. Given the user's question and a wiki index,
        identify which wiki pages are relevant and whether the question needs deep analysis.
        
        Wiki Index:
        %s
        
        Question: %s
        
        Return JSON: {"relevant_pages": ["page1", "page2"], "depth": "simple|deep"}
        - "simple": question can be answered from wiki pages alone
        - "deep": question needs raw document search for detailed evidence
        - Return at most 5 relevant pages
        """;

private static final String DEFAULT_ANSWER_PROMPT = """
        You are a helpful knowledge assistant. Answer the question based on the provided context.
        Use markdown formatting. Reference wiki concepts with [[WikiLink]] syntax when mentioning topics that have wiki pages.
        If the context doesn't contain enough information, say so honestly.
        """;

public String getChatRoutingPrompt() {
    String custom = props.getWiki().getChatRoutingPrompt();
    if (custom != null && !custom.isBlank()) return custom;
    return DEFAULT_ROUTING_PROMPT;
}

public String getChatAnswerPrompt() {
    String custom = props.getWiki().getChatAnswerPrompt();
    if (custom != null && !custom.isBlank()) return custom;
    return DEFAULT_ANSWER_PROMPT;
}
```

Then update `buildRoutingPrompt()` (~line 531) to use `getChatRoutingPrompt()` instead of the hardcoded string. And update `buildAnswerPrompt()` (~line 557) to use `getChatAnswerPrompt()` for the system instruction part.

- [ ] **Step 3: Extend admin config endpoints**

In `KnowledgeController.java`, GET /admin/wiki/config (~line 432), add to response:
```java
config.put("chat_routing_prompt", wikiService.getChatRoutingPrompt());
config.put("chat_answer_prompt", wikiService.getChatAnswerPrompt());
```

In PUT /admin/wiki/config (~line 444), add handlers:
```java
if (body.containsKey("chat_routing_prompt")) {
    lakeonProperties.getWiki().setChatRoutingPrompt(body.get("chat_routing_prompt"));
}
if (body.containsKey("chat_answer_prompt")) {
    lakeonProperties.getWiki().setChatAnswerPrompt(body.get("chat_answer_prompt"));
}
```

- [ ] **Step 4: Compile and verify**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(api): make chat routing and answer prompts configurable"
```

---

### Task 2: Backend — Wiki Page Management + LLM Test Endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`

- [ ] **Step 1: Add admin wiki pages list endpoint**

In KnowledgeController.java, after the existing admin wiki config endpoints, add:

```java
@GetMapping("/admin/wiki/pages")
public ResponseEntity<?> adminListWikiPages(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @RequestParam("kb_id") String kbId) {
    validateAdminToken(adminToken);
    var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
    if (kb == null) return ResponseEntity.notFound().build();
    List<DocumentEntity> pages = documentRepository.findByTenantIdAndKbIdAndDocType(
            kb.getTenantId(), kbId, "wiki");
    // Also include index type docs
    pages.addAll(documentRepository.findByTenantIdAndKbIdAndDocType(
            kb.getTenantId(), kbId, "index"));
    return ResponseEntity.ok(pages.stream().map(this::toDocumentResponse).toList());
}
```

- [ ] **Step 2: Add admin wiki page delete endpoint**

```java
@DeleteMapping("/admin/wiki/pages/{docId}")
public ResponseEntity<?> adminDeleteWikiPage(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @PathVariable String docId,
        @RequestParam("kb_id") String kbId) {
    validateAdminToken(adminToken);
    var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
    if (kb == null) return ResponseEntity.notFound().build();
    documentRepository.deleteById(docId);
    return ResponseEntity.ok(Map.of("status", "deleted"));
}
```

- [ ] **Step 3: Add admin wiki rebuild endpoint**

```java
@PostMapping("/admin/wiki/rebuild")
public ResponseEntity<?> adminRebuildWiki(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @RequestParam("kb_id") String kbId) {
    validateAdminToken(adminToken);
    var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
    if (kb == null) return ResponseEntity.notFound().build();
    int deleted = wikiService.rebuildWiki(kb.getTenantId(), kbId);
    return ResponseEntity.ok(Map.of("status", "rebuilding", "wiki_pages_deleted", deleted));
}
```

In WikiService.java, add `rebuildWiki` method:

```java
public int rebuildWiki(String tenantId, String kbId) {
    // Delete all wiki pages
    List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, DOC_TYPE_WIKI);
    int count = wikiDocs.size();
    for (DocumentEntity doc : wikiDocs) {
        documentRepository.deleteById(doc.getId());
    }
    // Re-trigger wiki update for each raw+ready document
    List<DocumentEntity> rawDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, "raw");
    var kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId).orElse(null);
    if (kb != null) {
        for (DocumentEntity doc : rawDocs) {
            if (doc.getStatus() == DocumentStatus.READY) {
                try {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("tenant_id", tenantId);
                    params.put("kb_id", kbId);
                    params.put("document_id", doc.getId());
                    params.put("database_id", kb.getDatabaseId());
                    kbWriteQueue.enqueueTask(kb.getDatabaseId(), KbWriteTaskType.WIKI_UPDATE, params);
                } catch (Exception e) {
                    log.warn("Failed to enqueue WIKI_UPDATE for rebuild: {}", e.getMessage());
                }
            }
        }
    }
    log.info("Wiki rebuild for KB {}: deleted {} pages, re-queued {} raw docs", kbId, count, rawDocs.size());
    return count;
}
```

- [ ] **Step 4: Add LLM connection test endpoint**

```java
@PostMapping("/admin/wiki/test-connection")
public ResponseEntity<?> testLlmConnection(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
    validateAdminToken(adminToken);
    long start = System.currentTimeMillis();
    try {
        String result = wikiService.testConnection();
        long latency = System.currentTimeMillis() - start;
        return ResponseEntity.ok(Map.of(
            "success", true,
            "latency_ms", latency,
            "model", wikiService.getModel(),
            "response", result
        ));
    } catch (Exception e) {
        long latency = System.currentTimeMillis() - start;
        return ResponseEntity.ok(Map.of(
            "success", false,
            "latency_ms", latency,
            "error", e.getMessage()
        ));
    }
}
```

In WikiService.java, add `testConnection` method:

```java
public String testConnection() {
    return callDeepSeekText("Say 'OK' in one word.");
}
```

- [ ] **Step 5: Compile and verify**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(api): admin wiki page management + LLM connection test endpoints"
```

---

### Task 3: Admin Frontend — Prompt Tabs + Wiki Page Table + Connection Test

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts` (~line 98)
- Modify: `lakeon-admin/src/views/knowledge/KnowledgeList.vue` (~line 361 wiki-agent tab)

- [ ] **Step 1: Add admin API functions**

In `admin.ts`, after existing wiki functions (~line 99), add:

```typescript
adminListWikiPages: (kbId: string) => client.get('/wiki/pages', { params: { kb_id: kbId } }),
adminDeleteWikiPage: (kbId: string, docId: string) => client.delete(`/wiki/pages/${docId}`, { params: { kb_id: kbId } }),
adminRebuildWiki: (kbId: string) => client.post('/wiki/rebuild', null, { params: { kb_id: kbId } }),
testLlmConnection: () => client.post('/wiki/test-connection'),
```

- [ ] **Step 2: Rewrite Wiki Agent tab — 3 prompt tabs**

In `KnowledgeList.vue`, replace the Wiki Agent tab content with:

1. **LLM Config section** (keep existing model/base_url inputs, add test button)
2. **Prompt tabs**: [Ingest] [Chat Routing] [Chat Answer] — each with a textarea
3. **Save/reload buttons** (existing)

The prompt switching uses a local `promptTab` ref:
```typescript
const promptTab = ref<'ingest' | 'routing' | 'answer'>('ingest')
```

Template for prompt section:
```html
<!-- Prompt tabs -->
<div style="margin-bottom: 16px;">
  <div style="display: flex; gap: 0; margin-bottom: 8px;">
    <span v-for="pt in [
      { key: 'ingest', label: 'Ingest Prompt' },
      { key: 'routing', label: 'Chat Routing' },
      { key: 'answer', label: 'Chat Answer' },
    ]" :key="pt.key"
      style="padding: 6px 14px; font-size: 12px; cursor: pointer; border: 1px solid #e0d8ce;"
      :style="{
        background: promptTab === pt.key ? '#c25a3c' : '#fff',
        color: promptTab === pt.key ? '#fff' : '#5a4a3a',
        borderRadius: pt.key === 'ingest' ? '4px 0 0 4px' : pt.key === 'answer' ? '0 4px 4px 0' : '0',
      }"
      @click="promptTab = pt.key">{{ pt.label }}</span>
  </div>
  <textarea v-if="promptTab === 'ingest'" v-model="wikiConfig.ingest_prompt" style="width:100%;height:300px;font-family:monospace;font-size:12px;padding:12px;border:1px solid #d9d9d9;border-radius:6px;resize:vertical;" />
  <textarea v-if="promptTab === 'routing'" v-model="wikiConfig.chat_routing_prompt" style="width:100%;height:300px;font-family:monospace;font-size:12px;padding:12px;border:1px solid #d9d9d9;border-radius:6px;resize:vertical;" />
  <textarea v-if="promptTab === 'answer'" v-model="wikiConfig.chat_answer_prompt" style="width:100%;height:300px;font-family:monospace;font-size:12px;padding:12px;border:1px solid #d9d9d9;border-radius:6px;resize:vertical;" />
</div>
```

- [ ] **Step 3: Add LLM connection test button and state**

Add to LLM config section, after the base_url input:
```html
<button @click="testConnection" :disabled="testingConnection" style="padding: 6px 14px; font-size: 12px; border: 1px solid #d9d9d9; border-radius: 4px; cursor: pointer; background: #fff;">
  {{ testingConnection ? '测试中...' : '测试连接' }}
</button>
<span v-if="connectionResult" :style="{ fontSize: '12px', marginLeft: '8px', color: connectionResult.success ? '#52c41a' : '#e6393d' }">
  {{ connectionResult.success ? `连接成功 (${connectionResult.latency_ms}ms)` : `失败: ${connectionResult.error}` }}
</span>
```

State and function:
```typescript
const testingConnection = ref(false)
const connectionResult = ref<{ success: boolean; latency_ms: number; error?: string } | null>(null)

async function testConnection() {
  testingConnection.value = true
  connectionResult.value = null
  try {
    const { data } = await adminApi.testLlmConnection()
    connectionResult.value = data
  } catch (e: any) {
    connectionResult.value = { success: false, latency_ms: 0, error: e.message }
  } finally {
    testingConnection.value = false
  }
}
```

- [ ] **Step 4: Add wiki page management to KB expansion row**

In the KB list expansion section, add a "Wiki 页面" area. When a KB row is expanded, show:

```html
<!-- Wiki pages section in expanded KB row -->
<div style="margin-top: 12px; border-top: 1px solid #eee; padding-top: 12px;">
  <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
    <span style="font-weight: 600; font-size: 13px;">Wiki 页面</span>
    <button class="btn btn-small" @click="loadWikiPagesForKb(kb.id)">刷新</button>
    <button class="btn btn-small" style="color: #e6393d;" @click="handleClearWiki(kb.id)">清空 Wiki</button>
    <button class="btn btn-small btn-primary" @click="handleRebuildWiki(kb.id)">全量重建</button>
  </div>
  <table v-if="wikiPagesMap[kb.id]?.length > 0" class="data-table" style="font-size: 12px;">
    <thead><tr><th>标题</th><th>类型</th><th>大小</th><th>创建时间</th><th>操作</th></tr></thead>
    <tbody>
      <tr v-for="page in wikiPagesMap[kb.id]" :key="page.id">
        <td>{{ page.filename.replace('.md', '') }}</td>
        <td>{{ page.type }}</td>
        <td>{{ page.size_bytes > 1024 ? (page.size_bytes / 1024).toFixed(1) + ' KB' : page.size_bytes + ' B' }}</td>
        <td>{{ new Date(page.created_at).toLocaleString('zh-CN') }}</td>
        <td><button class="btn btn-small" style="color: #e6393d;" @click="handleDeleteWikiPage(kb.id, page.id)">删除</button></td>
      </tr>
    </tbody>
  </table>
  <div v-else-if="wikiPagesMap[kb.id]" style="color: #999; font-size: 12px;">暂无 Wiki 页面</div>
</div>
```

State and functions:
```typescript
const wikiPagesMap = ref<Record<string, any[]>>({})

async function loadWikiPagesForKb(kbId: string) {
  try {
    const { data } = await adminApi.adminListWikiPages(kbId)
    wikiPagesMap.value[kbId] = data
  } catch (e) {
    console.warn('Failed to load wiki pages', e)
  }
}

async function handleDeleteWikiPage(kbId: string, docId: string) {
  if (!confirm('确认删除此 Wiki 页面？')) return
  await adminApi.adminDeleteWikiPage(kbId, docId)
  await loadWikiPagesForKb(kbId)
}

async function handleClearWiki(kbId: string) {
  const pages = wikiPagesMap.value[kbId] || []
  if (!confirm(`确认清空 ${pages.length} 个 Wiki 页面？`)) return
  for (const page of pages) {
    try { await adminApi.adminDeleteWikiPage(kbId, page.id) } catch {}
  }
  await loadWikiPagesForKb(kbId)
}

async function handleRebuildWiki(kbId: string) {
  if (!confirm('确认全量重建 Wiki？将清空现有页面并重新生成。')) return
  await adminApi.adminRebuildWiki(kbId)
  alert('Wiki 重建已触发，请稍后刷新查看')
  await loadWikiPagesForKb(kbId)
}
```

Load wiki pages when KB row is expanded (hook into existing expand logic).

- [ ] **Step 5: Update wikiConfig initial state**

Update wikiConfig ref to include new fields:
```typescript
const wikiConfig = ref<Record<string, string>>({
  ingest_prompt: '', chat_routing_prompt: '', chat_answer_prompt: '',
  model: '', base_url: ''
})
```

- [ ] **Step 6: Type check**

```bash
cd lakeon-admin && npx vue-tsc -b --noEmit
```

- [ ] **Step 7: Commit**

```bash
git commit -am "feat(admin): wiki page management, 3 prompt tabs, LLM connection test"
```

---

### Task 4: Build, Deploy, E2E Test

**Files:**
- Modify: `tests/e2e/test_wiki.py`
- Modify: `deploy/cce/build-and-push-api.sh`, `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: Add E2E tests for new admin endpoints**

In `tests/e2e/test_wiki.py`, extend `TestAdminWikiConfig`:

```python
def test_get_config_has_all_prompts(self):
    """Config should return all 3 prompts."""
    r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                  headers=self._headers(), verify=False, timeout=TIMEOUT)
    assert r.status_code == 200
    data = r.json()
    assert "ingest_prompt" in data
    assert "chat_routing_prompt" in data
    assert "chat_answer_prompt" in data
    assert len(data["ingest_prompt"]) > 0

def test_update_routing_prompt(self):
    """Should be able to update chat routing prompt."""
    r = httpx.put(f"{BASE}/knowledge/admin/wiki/config",
                  json={"chat_routing_prompt": "test routing"},
                  headers=self._headers(), verify=False, timeout=TIMEOUT)
    assert r.status_code == 200

def test_llm_connection(self):
    """LLM connection test should return success."""
    r = httpx.post(f"{BASE}/knowledge/admin/wiki/test-connection",
                   headers=self._headers(), verify=False, timeout=60)
    assert r.status_code == 200
    data = r.json()
    assert "success" in data
    assert "latency_ms" in data
    if data["success"]:
        assert data["latency_ms"] > 0
```

- [ ] **Step 2: Build and deploy API**

```bash
# Update version
sed -i '' 's/0.9.212/0.9.213/g' deploy/cce/build-and-push-api.sh deploy/cce/sites/hwstaff/values.yaml
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl set image deployment/lakeon-api lakeon-api=swr.cn-north-4.myhuaweicloud.com/flex/lakeon-api:0.9.213 -n lakeon
```

- [ ] **Step 3: Push frontend + run E2E**

```bash
git push origin main
python3 -m pytest tests/e2e/test_wiki.py -v --timeout=600
```

All 14+ tests must PASS. No fake skips.

- [ ] **Step 4: Commit deploy config**

```bash
git commit -am "deploy: update to 0.9.213 with admin wiki management"
git push origin main
```
