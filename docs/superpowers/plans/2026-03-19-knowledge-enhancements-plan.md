# Knowledge Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add document tags/filtering, query rewrite, ReRank, and table knowledge base to the knowledge base feature.

**Architecture:** 4 independent features layered onto existing knowledge base. Tags adds JSONB filtering in RDS before vector search. Query rewrite inserts an LLM call before search. ReRank adds a reranker model to embedding-service and a post-search step. Table KB adds a new KB type that routes to AiSqlService instead of vector search.

**Tech Stack:** Java 17 / Spring Boot (API), Python / FastAPI (embedding-service), Vue 3 / TypeScript (console), PostgreSQL JSONB (tags), BGE-Reranker-v2-m3 (rerank model)

**Spec:** `docs/superpowers/specs/2026-03-19-knowledge-enhancements-design.md`

---

## File Structure

### Feature 1: Tags
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java` — add `tags` JSONB field
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java` — add tag filter query
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` — add tags endpoint
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — tag filtering in search
- Create: `lakeon-api/src/main/resources/db/migration/V14__add_document_tags.sql`
- Modify: `lakeon-console/src/api/knowledge.ts` — add tag API functions
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — tag UI in documents/search tabs

### Feature 2: ReRank
- Modify: `knowledge/embedding-service/main.py` — add `/rerank` endpoint + BGE-Reranker model
- Modify: `knowledge/embedding-service/requirements.txt` — (if exists, add dependencies)
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` — add rerank config
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — insert rerank step after RRF
- Modify: `lakeon-api/src/main/resources/application.yml` — add rerank config defaults
- Modify: `lakeon-console/src/api/knowledge.ts` — add rerank param to search

### Feature 3: Query Rewrite
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/QueryRewriteService.java` — LLM query rewrite
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — call rewrite before search
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` — accept conversation_history
- Modify: `lakeon-console/src/api/knowledge.ts` — update search function signature
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — chat-style search tab

### Feature 4: Table KB
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseType.java` — DOCUMENT/TABLE enum
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseEntity.java` — add type, sourceDatabaseId, tableNames
- Create: `lakeon-api/src/main/resources/db/migration/V15__add_table_kb_fields.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — TABLE type create/search logic
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` — accept type params
- Modify: `lakeon-console/src/api/knowledge.ts` — add type-related interfaces/functions
- Create: `lakeon-console/src/components/knowledge/TableKbDetail.vue` — table KB detail view (tables tab + query tab)
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — route to TableKbDetail for TABLE type

---

## Task 1: Document Tags — Backend

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Create: `lakeon-api/src/main/resources/db/migration/V14__add_document_tags.sql`

- [ ] **Step 1: Create Flyway migration V14**

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS tags JSONB DEFAULT '[]'::jsonb;
CREATE INDEX IF NOT EXISTS idx_documents_tags ON documents USING gin (tags);
```

- [ ] **Step 2: Add tags field to DocumentEntity**

Add to `DocumentEntity.java`:

```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "tags", columnDefinition = "jsonb")
private List<String> tags = new ArrayList<>();
```

Add getter/setter following existing pattern.

- [ ] **Step 3: Add tag filter query to DocumentRepository**

```java
@Query("SELECT d.id FROM DocumentEntity d WHERE d.kbId = :kbId AND d.tenantId = :tenantId")
List<String> findIdsByKbIdAndTenantId(@Param("kbId") String kbId, @Param("tenantId") String tenantId);
```

For tag filtering, use a native query since JSONB `?|` operator isn't supported in JPQL:

```java
@Query(value = "SELECT id FROM documents WHERE kb_id = :kbId AND tenant_id = :tenantId AND tags ?| :tags",
       nativeQuery = true)
List<String> findIdsByKbIdAndTenantIdAndTagsContaining(
    @Param("kbId") String kbId,
    @Param("tenantId") String tenantId,
    @Param("tags") String[] tags);
```

- [ ] **Step 4: Add tags endpoint to KnowledgeController**

```java
@PutMapping("/documents/{id}/tags")
public ResponseEntity<?> setTags(HttpServletRequest request,
        @PathVariable String id, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
    List<String> tags = (List<String>) body.get("tags");
    // Validate tags is a list of strings, max 20 tags, each max 50 chars
    DocumentEntity doc = documentRepository.findByIdAndTenantId(id, tenant.getId())
        .orElseThrow(() -> new NotFoundException("Document not found"));
    doc.setTags(tags);
    documentRepository.save(doc);
    return ResponseEntity.ok(Map.of("tags", tags));
}
```

Also update `getUploadUrl()` to accept optional `tags` parameter and set on the created DocumentEntity.

- [ ] **Step 5: Add tag filtering to KnowledgeService.search()**

Update `search()` method signature to accept optional `List<String> tags` parameter.

Before the existing search logic, add:

```java
// Tag filtering: resolve document IDs from RDS, then pass to vector search
List<String> filteredDocIds = documentIds;
if (tags != null && !tags.isEmpty()) {
    List<String> tagFilteredIds = documentRepository
        .findIdsByKbIdAndTenantIdAndTagsContaining(kbId, tenantId, tags.toArray(new String[0]));
    if (filteredDocIds != null) {
        tagFilteredIds.retainAll(filteredDocIds); // intersection
    }
    filteredDocIds = tagFilteredIds;
    if (filteredDocIds.isEmpty()) {
        return Collections.emptyList(); // no documents match tags
    }
}
```

Then pass `filteredDocIds` (instead of `documentIds`) to the SQL query.

- [ ] **Step 6: Update KnowledgeController.search() to accept tags parameter**

In the search endpoint, extract `tags` from the request body:

```java
List<String> tags = body.containsKey("tags") ? (List<String>) body.get("tags") : null;
```

Pass to `knowledgeService.search(tenantId, kbId, query, topK, documentIds, tags)`.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/ \
        lakeon-api/src/main/resources/db/migration/V14__add_document_tags.sql
git commit -m "feat(knowledge): document tags — JSONB field, GIN index, tag filter in search"
```

---

## Task 2: Document Tags — Frontend

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Add tag API functions to knowledge.ts**

```typescript
export function setDocumentTags(docId: string, tags: string[]) {
  return api.put<{ tags: string[] }>(`/knowledge/documents/${docId}/tags`, { tags })
}
```

Update `searchKnowledge` to accept optional `tags`:

```typescript
export function searchKnowledge(kbId: string, query: string, topK = 5, options?: {
  tags?: string[]
  document_ids?: string[]
}) {
  return api.post<{ results: SearchResult[] }>('/knowledge/search', {
    kb_id: kbId, query, top_k: topK, ...options
  })
}
```

Add `tags` field to `Document` interface:

```typescript
export interface Document {
  // ... existing fields ...
  tags: string[]
}
```

- [ ] **Step 2: Add tag badges to Documents tab**

In `KnowledgeBaseDetail.vue`, Documents tab table:
- Each document row shows tags as small colored badges after filename
- Add a "edit tags" icon button per row that opens a simple input dialog
- Tag edit dialog: comma-separated input, saves via `setDocumentTags()`

- [ ] **Step 3: Add tag filter to Search tab**

In the Search tab:
- Add a multi-select dropdown above the search input for tag filtering
- Populate with unique tags across all documents (collect from `listDocuments()` response)
- When tags are selected, pass to `searchKnowledge()` via `tags` option

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): document tags UI — badges, edit dialog, search tag filter"
```

---

## Task 3: ReRank — Embedding Service

**Files:**
- Modify: `knowledge/embedding-service/main.py`
- Modify: `knowledge/embedding-service/requirements.txt` (if exists)

- [ ] **Step 1: Add reranker model loading to embedding-service**

In `main.py`, alongside the existing embedding model loading:

```python
from sentence_transformers import CrossEncoder

RERANK_MODEL_NAME = os.environ.get("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")
rerank_model = None

@app.on_event("startup")
async def load_models():
    global model, rerank_model
    model = SentenceTransformer(MODEL_NAME)
    # Load reranker if enabled
    if os.environ.get("RERANK_ENABLED", "true").lower() == "true":
        rerank_model = CrossEncoder(RERANK_MODEL_NAME)
        logger.info(f"Reranker loaded: {RERANK_MODEL_NAME}")
```

- [ ] **Step 2: Add /rerank endpoint**

```python
@app.post("/rerank")
async def rerank(request: dict):
    if rerank_model is None:
        raise HTTPException(status_code=503, detail="Reranker not loaded")

    query = request["query"]
    passages = request["passages"]
    top_k = request.get("top_k", len(passages))

    pairs = [[query, p] for p in passages]
    scores = rerank_model.predict(pairs).tolist()

    # Sort by score descending, return top_k
    ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)[:top_k]
    rankings = [idx for idx, _ in ranked]
    ranked_scores = [score for _, score in ranked]

    return {"rankings": rankings, "scores": ranked_scores}
```

- [ ] **Step 3: Update /health to report reranker status**

```python
@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "rerank_model": RERANK_MODEL_NAME if rerank_model else None,
        "ready": model is not None
    }
```

- [ ] **Step 4: Commit**

```bash
git add knowledge/embedding-service/
git commit -m "feat(embedding): add /rerank endpoint with BGE-Reranker-v2-m3"
```

---

## Task 4: ReRank — API Integration

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/resources/application.yml`
- Modify: `lakeon-console/src/api/knowledge.ts`

- [ ] **Step 1: Add rerank config to LakeonProperties**

Add inner class to `KnowledgeConfig`:

```java
private RerankConfig rerank = new RerankConfig();

public static class RerankConfig {
    private boolean enabled = true;
    private String url = "http://embedding-service:8000/rerank";
    // getters/setters
}
```

- [ ] **Step 2: Add rerank config to application.yml**

```yaml
lakeon:
  knowledge:
    rerank:
      enabled: ${LAKEON_RERANK_ENABLED:true}
      url: ${LAKEON_RERANK_URL:http://embedding-service:8000/rerank}
```

- [ ] **Step 3: Add rerank step to KnowledgeService.search()**

After RRF fusion produces the candidate list, add rerank step:

```java
// Add rerank parameter to search method signature
public List<Map<String, Object>> search(String tenantId, String kbId, String query,
        int topK, List<String> documentIds, List<String> tags, boolean rerank)

// After RRF fusion, before returning:
if (rerank && props.getKnowledge().getRerank().isEnabled()) {
    results = rerankResults(query, results, topK);
}
```

Implement `rerankResults()`:

```java
private List<Map<String, Object>> rerankResults(String query,
        List<Map<String, Object>> results, int topK) {
    String url = props.getKnowledge().getRerank().getUrl();
    List<String> passages = results.stream()
        .map(r -> (String) r.get("content")).toList();

    // POST to rerank service
    Map<String, Object> reqBody = Map.of(
        "query", query, "passages", passages, "top_k", topK);
    // HTTP call using same pattern as getQueryEmbedding()
    // Parse response: {rankings: [int], scores: [float]}
    // Reorder results by rankings, replace score with rerank score
    // Keep original rrf_score as separate field
}
```

- [ ] **Step 4: Update search controller to accept rerank param**

In `KnowledgeController.search()`:

```java
boolean rerank = body.containsKey("rerank") ? (Boolean) body.get("rerank") : false;
```

- [ ] **Step 5: Update frontend searchKnowledge**

In `knowledge.ts`, add `rerank` to search options:

```typescript
export function searchKnowledge(kbId: string, query: string, topK = 5, options?: {
  tags?: string[]
  document_ids?: string[]
  rerank?: boolean
})
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/resources/application.yml \
        lakeon-console/src/api/knowledge.ts
git commit -m "feat(knowledge): rerank integration — config, API call, search pipeline step"
```

---

## Task 5: Query Rewrite — Backend

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/QueryRewriteService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

- [ ] **Step 1: Create QueryRewriteService**

```java
@Service
public class QueryRewriteService {
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final String REWRITE_MODEL = "Qwen/Qwen3.5-4B";
    private static final int MAX_HISTORY_TURNS = 5; // 5 turns = 10 messages max
    private static final String SYSTEM_PROMPT = """
        你是查询改写助手。根据对话历史，将用户的最新问题改写为一个独立的、上下文完整的搜索查询。
        只输出改写后的查询，不要解释。
        """;

    public String rewriteQuery(String query, List<Map<String, String>> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return query;
        }

        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            return query; // graceful degradation
        }

        // Truncate to last MAX_HISTORY_TURNS turns
        int maxMessages = MAX_HISTORY_TURNS * 2;
        List<Map<String, String>> truncated = conversationHistory.size() > maxMessages
            ? conversationHistory.subList(conversationHistory.size() - maxMessages, conversationHistory.size())
            : conversationHistory;

        // Build user message
        StringBuilder sb = new StringBuilder("对话历史：\n");
        for (Map<String, String> msg : truncated) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        sb.append("\n用户问题：").append(query).append("\n\n改写后的查询：");

        // Call LLM (same pattern as AiSqlService)
        // POST to baseUrl + "/chat/completions"
        // model: REWRITE_MODEL, temperature: 0.1, max_tokens: 200
        // Return the rewritten query string, or original query on failure
    }
}
```

- [ ] **Step 2: Integrate query rewrite into search flow**

In `KnowledgeService.search()`, add `conversationHistory` parameter:

```java
public List<Map<String, Object>> search(String tenantId, String kbId, String query,
        int topK, List<String> documentIds, List<String> tags, boolean rerank,
        List<Map<String, String>> conversationHistory)
```

At the start of the method:

```java
String searchQuery = query;
String rewrittenQuery = null;
if (conversationHistory != null && !conversationHistory.isEmpty()) {
    rewrittenQuery = queryRewriteService.rewriteQuery(query, conversationHistory);
    if (!rewrittenQuery.equals(query)) {
        searchQuery = rewrittenQuery;
    }
}
// Use searchQuery for embedding + BM25
// Include rewrittenQuery in response
```

Update the return to include `rewritten_query` field when applicable.

- [ ] **Step 3: Update KnowledgeController to accept conversation_history**

```java
List<Map<String, String>> conversationHistory = body.containsKey("conversation_history")
    ? (List<Map<String, String>>) body.get("conversation_history") : null;
```

Update search response to include `rewritten_query`:

```java
Map<String, Object> response = new LinkedHashMap<>();
response.put("results", results);
if (rewrittenQuery != null) {
    response.put("rewritten_query", rewrittenQuery);
}
return ResponseEntity.ok(response);
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/QueryRewriteService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat(knowledge): query rewrite — LLM-based rewrite with conversation history"
```

---

## Task 6: Query Rewrite — Frontend (Chat-style Search)

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Update searchKnowledge API function**

```typescript
export function searchKnowledge(kbId: string, query: string, topK = 5, options?: {
  tags?: string[]
  document_ids?: string[]
  rerank?: boolean
  conversation_history?: { role: string; content: string }[]
}) {
  return api.post<{ results: SearchResult[]; rewritten_query?: string }>('/knowledge/search', {
    kb_id: kbId, query, top_k: topK, ...options
  })
}
```

- [ ] **Step 2: Rewrite Search tab as chat-style interface**

Replace the current single-query search UI with a conversation-style interface:

- Message list: alternating user messages (query) and assistant messages (search results)
- Input box at bottom (like a chat input)
- Each search call includes up to last 5 turns of QA as `conversation_history`
- When `rewritten_query` is returned and differs from original query, show it as a small note: "搜索改写为: {rewritten_query}"
- Results within each assistant message keep existing card format (content, score, metadata)
- "清除对话" button to reset conversation history

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): chat-style search with query rewrite display"
```

---

## Task 7: Table KB — Backend

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseType.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseEntity.java`
- Create: `lakeon-api/src/main/resources/db/migration/V15__add_table_kb_fields.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

- [ ] **Step 1: Create KnowledgeBaseType enum**

```java
package com.lakeon.knowledge;

public enum KnowledgeBaseType {
    DOCUMENT, TABLE
}
```

- [ ] **Step 2: Create Flyway migration V15**

```sql
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS type VARCHAR(16) DEFAULT 'DOCUMENT';
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS source_database_id VARCHAR(32);
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS table_names JSONB DEFAULT '[]'::jsonb;
```

- [ ] **Step 3: Add fields to KnowledgeBaseEntity**

```java
@Enumerated(EnumType.STRING)
@Column(name = "type")
private KnowledgeBaseType type = KnowledgeBaseType.DOCUMENT;

@Column(name = "source_database_id")
private String sourceDatabaseId;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "table_names", columnDefinition = "jsonb")
private List<String> tableNames = new ArrayList<>();
```

- [ ] **Step 4: Update createKnowledgeBase to handle TABLE type**

In `KnowledgeService.createKnowledgeBase()`, add type handling:

```java
public KnowledgeBaseEntity createKnowledgeBase(TenantEntity tenant, String name,
        String description, KnowledgeBaseType type, String sourceDatabaseId, List<String> tableNames) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setTenantId(tenant.getId());
    kb.setName(name);
    kb.setDescription(description);
    kb.setType(type != null ? type : KnowledgeBaseType.DOCUMENT);

    if (kb.getType() == KnowledgeBaseType.TABLE) {
        // Validate sourceDatabaseId exists and belongs to tenant
        // Validate tableNames by connecting to compute and checking information_schema
        kb.setSourceDatabaseId(sourceDatabaseId);
        kb.setTableNames(tableNames);
        kb.setStatus(KnowledgeBaseStatus.READY); // No provisioning needed
        return knowledgeBaseRepository.save(kb);
    }

    // Existing DOCUMENT flow: async provisioning...
}
```

- [ ] **Step 5: Add TABLE search logic**

In `KnowledgeService`, add a new method for TABLE type search:

```java
public Map<String, Object> searchTable(String tenantId, String kbId, String query, String modelId) {
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
        .orElseThrow(() -> new NotFoundException("KB not found"));
    if (kb.getType() != KnowledgeBaseType.TABLE) {
        throw new BadRequestException("Not a table knowledge base");
    }

    // 1. Get schema using parameterized query
    String connstr = dbHelper.resolveConnstr(tenantId, kb.getSourceDatabaseId());
    String schemaInfo = getTableSchema(connstr, kb.getTableNames());

    // 2. Generate SQL via AiSqlService
    Map<String, Object> aiResult = aiSqlService.generateSql(query, schemaInfo, modelId);
    String sql = (String) aiResult.get("sql");

    // 3. Validate: only SELECT allowed
    if (!sql.trim().toLowerCase().startsWith("select")) {
        return Map.of("error", "Only SELECT queries are allowed");
    }

    // 4. Execute SQL
    // Use JDBC connection to execute and return columns + rows
    // Return: {type: "sql", sql, columns, rows, model, tokens}
}
```

Implement `getTableSchema()`:

```java
private String getTableSchema(String connstr, List<String> tableNames) {
    // Connect to compute, run parameterized query:
    // SELECT table_name, column_name, data_type FROM information_schema.columns
    // WHERE table_name = ANY(?)
    // Format as "table_name: col1 (type), col2 (type), ..."
}
```

- [ ] **Step 6: Add tables info endpoint**

```java
@GetMapping("/bases/{id}/tables")
public ResponseEntity<?> getTableInfo(HttpServletRequest request, @PathVariable String id) {
    // Return schema info for each table in the KB
}
```

- [ ] **Step 7: Update search endpoint to route by KB type**

In `KnowledgeController.search()`:

```java
KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenant.getId())
    .orElseThrow(() -> new NotFoundException("KB not found"));

if (kb.getType() == KnowledgeBaseType.TABLE) {
    String modelId = (String) body.getOrDefault("model", null);
    return ResponseEntity.ok(knowledgeService.searchTable(tenant.getId(), kbId, query, modelId));
} else {
    // Existing DOCUMENT search flow
}
```

- [ ] **Step 8: Update create endpoint to accept type params**

In `KnowledgeController.createKnowledgeBase()`:

```java
String typeStr = (String) body.getOrDefault("type", "DOCUMENT");
KnowledgeBaseType type = KnowledgeBaseType.valueOf(typeStr);
String sourceDatabaseId = (String) body.get("source_database_id");
List<String> tableNames = (List<String>) body.get("table_names");
```

- [ ] **Step 9: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/ \
        lakeon-api/src/main/resources/db/migration/V15__add_table_kb_fields.sql
git commit -m "feat(knowledge): table KB type — create, search via AiSqlService, schema introspection"
```

---

## Task 8: Table KB — Frontend

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`
- Create: `lakeon-console/src/components/knowledge/TableKbDetail.vue`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Update TypeScript interfaces and API functions**

Add to `knowledge.ts`:

```typescript
export interface KnowledgeBase {
  // ... existing fields ...
  type: 'DOCUMENT' | 'TABLE'
  source_database_id: string | null
  table_names: string[]
}

export interface TableSearchResult {
  type: 'sql'
  sql: string
  columns: string[]
  rows: any[][]
  model: string
  tokens: { input: number; output: number }
}

export function getTableInfo(kbId: string) {
  return api.get<{ tables: any[] }>(`/knowledge/bases/${kbId}/tables`)
}
```

Update `createKnowledgeBase`:

```typescript
export function createKnowledgeBase(name: string, description?: string, options?: {
  type?: 'DOCUMENT' | 'TABLE'
  source_database_id?: string
  table_names?: string[]
})
```

- [ ] **Step 2: Create TableKbDetail.vue**

Component for TABLE-type knowledge base detail:
- **数据表 tab**: List associated tables with their columns (name, type). Fetched via `getTableInfo()`.
- **查询 tab**: Chat-style natural language input → displays generated SQL + results table.
  - Input box at bottom, conversation history above
  - Each response shows: SQL query (in code block), then results as HTML table
  - Model selector dropdown (reuse AVAILABLE_MODELS from AiSqlService)
  - "清除对话" button

- [ ] **Step 3: Route to TableKbDetail in KnowledgeBaseDetail**

In `KnowledgeBaseDetail.vue`:
- After fetching KB data, check `kb.type`
- If `TABLE`: render `<TableKbDetail :kb="kb" />` instead of the normal tab content
- If `DOCUMENT`: show existing tabs (overview, documents, search, chunks)

- [ ] **Step 4: Update create KB dialog**

In `KnowledgeBases.vue` (KB list page), update the create dialog:
- Add type selector: "文档知识库" / "数据表知识库"
- When "数据表" selected: show database selector dropdown (fetch user's databases) + table checkboxes
- Call `createKnowledgeBase()` with type params

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/components/knowledge/TableKbDetail.vue \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue \
        lakeon-console/src/views/knowledge/KnowledgeBases.vue
git commit -m "feat(console): table KB UI — table info view, natural language query, create dialog"
```
