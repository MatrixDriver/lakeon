# KB Wiki Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an LLM-maintained wiki layer to DBay KB — when users upload articles, the system automatically generates/maintains wiki pages with wikilinks, viewable as Markdown and interactive graph.

**Architecture:** Wiki pages are stored as special documents (type='wiki') in the existing documents table, reusing OBS storage, chunking, and embedding. A Wiki Agent (LLM call chain) runs after document summarization to create/update wiki pages. Frontend adds Markdown rendering, Graph View, and chat interface.

**Tech Stack:** Java 17 (Spring Boot), Vue 3 + TypeScript, DeepSeek API, markdown-it (already installed), D3 force layout (already installed), Python FastAPI (orchestrator, for URL fetching), trafilatura (HTML→text)

**Spec:** `docs/superpowers/specs/2026-04-06-kb-wiki-engine-design.md`

---

## File Structure

### Backend (lakeon-api)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/com/lakeon/knowledge/DocumentEntity.java` | Modify | Add `type` field |
| `src/main/java/com/lakeon/knowledge/KbWriteTaskType.java` | Modify | Add `WIKI_UPDATE` enum |
| `src/main/java/com/lakeon/knowledge/KbWriteQueue.java` | Modify | Add WIKI_UPDATE to lightweight types and switch dispatch |
| `src/main/java/com/lakeon/knowledge/WikiService.java` | Create | Wiki Agent: ingest (create/update wiki pages), graph extraction, chat routing |
| `src/main/java/com/lakeon/knowledge/KnowledgeController.java` | Modify | Add wiki endpoints |
| `src/main/java/com/lakeon/knowledge/KnowledgeService.java` | Modify | Enqueue WIKI_UPDATE after summarize, URL ingest |
| `src/main/java/com/lakeon/knowledge/DocumentRepository.java` | Modify | Add queries for type filtering |

### Frontend (lakeon-console)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/api/knowledge.ts` | Modify | Add wiki API functions |
| `src/views/knowledge/KnowledgeBaseDetail.vue` | Modify | Add wiki/graph/chat tabs, document type filtering |
| `src/views/knowledge/WikiPage.vue` | Create | Markdown rendering with wikilink support |
| `src/views/knowledge/WikiGraph.vue` | Create | D3 force-directed graph view |
| `src/views/knowledge/WikiChat.vue` | Create | Chat interface with save-to-wiki button |
| `src/components/MarkdownRenderer.vue` | Create | Reusable markdown-it renderer with wikilink plugin |

### Orchestrator (lakeon-orchestrator)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/lakeon_orchestrator/api/url_fetch.py` | Create | URL fetching: download HTML, extract text, download images |
| `src/lakeon_orchestrator/main.py` | Modify | Register URL fetch router |

---

## Task 1: Data Model — Add document type field

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java:81-95`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`

- [ ] **Step 1: Add type field to DocumentEntity**

In `DocumentEntity.java`, add after the `folder` field (line 82):

```java
@Column(name = "doc_type", length = 16)
private String docType = "raw";
```

Add getter and setter:

```java
public String getDocType() { return docType; }
public void setDocType(String docType) { this.docType = docType; }
```

Note: use column name `doc_type` to avoid SQL reserved word `type`. The JSON serialization will use `@JsonProperty("type")`:

```java
@JsonProperty("type")
public String getDocType() { return docType; }
```

- [ ] **Step 2: Add database migration**

Run SQL against the metadata DB to add the column:

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS doc_type VARCHAR(16) NOT NULL DEFAULT 'raw';
```

This can be executed via the lakeon-api startup or manually.

- [ ] **Step 3: Add repository query for filtering by type**

In `DocumentRepository.java`, the existing `findPagedDocuments` query already uses native SQL. Add a type filter parameter. Find the existing query and add:

```sql
AND (:docType IS NULL OR doc_type = :docType)
```

Also add a method to find wiki pages by title within a KB:

```java
@Query("SELECT d FROM DocumentEntity d WHERE d.tenantId = :tenantId AND d.kbId = :kbId AND d.docType = :docType AND d.filename = :filename")
Optional<DocumentEntity> findByTypeAndFilename(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("docType") String docType,
    @Param("filename") String filename);
```

- [ ] **Step 4: Verify compilation**

Run:
```bash
cd lakeon-api && mvn compile -q
```
Expected: no errors

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java
git commit -m "feat(api): add doc_type field to DocumentEntity for wiki pages"
```

---

## Task 2: Wiki Agent — Core service

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskType.java:3-14`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:58-66,651-660`

- [ ] **Step 1: Add WIKI_UPDATE to task type enum**

In `KbWriteTaskType.java`, add after `KB_SUMMARIZE`:

```java
public enum KbWriteTaskType {
    DOCUMENT_PARSE,
    BATCH_DOCUMENT_PARSE,
    RECHUNK,
    EDIT_CHUNK,
    DELETE_CHUNK,
    CREATE_CHUNK,
    RECHUNK_ROLLBACK,
    DELETE_DOCUMENT_CHUNKS,
    DOCUMENT_SUMMARIZE,
    KB_SUMMARIZE,
    WIKI_UPDATE
}
```

- [ ] **Step 2: Register WIKI_UPDATE as lightweight task**

In `KbWriteQueue.java`, add to `LIGHTWEIGHT_TYPES` set (line 58-66):

```java
private static final Set<KbWriteTaskType> LIGHTWEIGHT_TYPES = Set.of(
    KbWriteTaskType.EDIT_CHUNK,
    KbWriteTaskType.DELETE_CHUNK,
    KbWriteTaskType.CREATE_CHUNK,
    KbWriteTaskType.RECHUNK_ROLLBACK,
    KbWriteTaskType.DELETE_DOCUMENT_CHUNKS,
    KbWriteTaskType.DOCUMENT_SUMMARIZE,
    KbWriteTaskType.KB_SUMMARIZE,
    KbWriteTaskType.WIKI_UPDATE
);
```

- [ ] **Step 3: Add switch case for WIKI_UPDATE**

In `KbWriteQueue.java`, add to the switch statement (line 651-660):

```java
case WIKI_UPDATE -> executeWikiUpdate(params);
```

Note: `executeWikiUpdate` does NOT need the `conn` parameter since it works at the document level (OBS + documents table), not directly on the compute DB.

Add the method:

```java
private void executeWikiUpdate(Map<String, Object> params) {
    String tenantId = (String) params.get("tenant_id");
    String kbId = (String) params.get("kb_id");
    String documentId = (String) params.get("document_id");
    wikiService.processIngest(tenantId, kbId, documentId);
}
```

Inject `WikiService` into `KbWriteQueue`:

```java
private final WikiService wikiService;
```

- [ ] **Step 4: Create WikiService**

Create `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`:

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WikiService {
    private static final Logger log = LoggerFactory.getLogger(WikiService.class);
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)]]");

    private final DocumentRepository documentRepository;
    private final KnowledgeService knowledgeService;
    private final ChunkService chunkService;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WikiService(DocumentRepository documentRepository,
                       KnowledgeService knowledgeService,
                       ChunkService chunkService,
                       LakeonProperties props,
                       ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.knowledgeService = knowledgeService;
        this.chunkService = chunkService;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Process a newly ingested document: read it, update wiki pages.
     * Called from KbWriteQueue after DOCUMENT_SUMMARIZE completes.
     */
    public void processIngest(String tenantId, String kbId, String documentId) {
        // 1. Read the source document fulltext
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null || fulltext.isBlank()) {
            log.warn("No fulltext for doc {}, skipping wiki update", documentId);
            return;
        }

        DocumentEntity sourceDoc = documentRepository.findById(documentId).orElse(null);
        if (sourceDoc == null) return;

        // 2. Read current index.md (if exists)
        String indexContent = readWikiPage(tenantId, kbId, "index.md");

        // 3. Call LLM to determine which wiki pages to create/update
        String llmResponse = callWikiAgent(fulltext, sourceDoc.getFilename(), indexContent, tenantId, kbId);

        // 4. Parse LLM response and write wiki pages
        applyWikiChanges(tenantId, kbId, documentId, sourceDoc.getFilename(), llmResponse);
    }

    /**
     * Save a chat response as wiki content.
     */
    public void saveResponse(String tenantId, String kbId, String responseContent) {
        String indexContent = readWikiPage(tenantId, kbId, "index.md");
        String llmResponse = callWikiSaveAgent(responseContent, indexContent, tenantId, kbId);
        applyWikiChanges(tenantId, kbId, null, "chat-response", llmResponse);
    }

    /**
     * Extract graph data (nodes + edges) from all wiki pages' wikilinks.
     */
    public Map<String, Object> getGraph(String tenantId, String kbId) {
        List<DocumentEntity> wikiPages = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, "wiki");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> nodeNames = new HashSet<>();

        for (DocumentEntity page : wikiPages) {
            String title = page.getFilename().replace(".md", "");
            nodeNames.add(title);
            nodes.add(Map.of(
                "id", title,
                "label", title,
                "document_id", page.getId()
            ));
        }

        // Extract wikilinks from each page's content
        for (DocumentEntity page : wikiPages) {
            String content = readWikiPageContent(page);
            if (content == null) continue;
            String sourceTitle = page.getFilename().replace(".md", "");
            Matcher matcher = WIKILINK_PATTERN.matcher(content);
            while (matcher.find()) {
                String target = matcher.group(1);
                if (!target.equals(sourceTitle)) {
                    // Add target node if not already present
                    if (!nodeNames.contains(target)) {
                        nodeNames.add(target);
                        nodes.add(Map.of("id", target, "label", target, "document_id", ""));
                    }
                    edges.add(Map.of("source", sourceTitle, "target", target));
                }
            }
        }

        return Map.of("nodes", nodes, "edges", edges);
    }

    // ── Internal helpers ──

    private String readWikiPage(String tenantId, String kbId, String filename) {
        return documentRepository.findByTypeAndFilename(tenantId, kbId, "wiki", filename)
                .or(() -> documentRepository.findByTypeAndFilename(tenantId, kbId, "index", filename))
                .map(this::readWikiPageContent)
                .orElse("");
    }

    private String readWikiPageContent(DocumentEntity doc) {
        // Read from OBS via the existing fulltext mechanism
        return chunkService.getFulltext(doc.getTenantId(), doc.getKbId(), doc.getId());
    }

    private String callWikiAgent(String fulltext, String filename,
                                  String indexContent, String tenantId, String kbId) {
        // Gather related wiki pages based on index
        String relatedPages = "";
        if (!indexContent.isBlank()) {
            // LLM will see the index and decide which pages to read
            // For simplicity, we include the index only; the LLM output tells us what to update
            relatedPages = indexContent;
        }

        String prompt = buildIngestPrompt(fulltext, filename, relatedPages);
        return callDeepSeek(prompt);
    }

    private String callWikiSaveAgent(String responseContent, String indexContent,
                                      String tenantId, String kbId) {
        String prompt = buildSavePrompt(responseContent, indexContent);
        return callDeepSeek(prompt);
    }

    private String buildIngestPrompt(String fulltext, String filename, String indexContent) {
        // Truncate if too long
        if (fulltext.length() > 50_000) {
            fulltext = fulltext.substring(0, 50_000);
        }

        return """
            你是一个知识库 Wiki 管理员。一篇新文章已进入知识库，你需要根据文章内容创建或更新 wiki 页面。

            ## 当前 Wiki 索引 (index.md)
            %s

            ## 新文章: %s
            %s

            ## 你的任务

            1. 阅读新文章，识别其中的关键实体（人物、公司、项目、技术概念等）
            2. 每个值得独立描述的实体应该有一个 wiki 页面
            3. 如果索引中已有该实体的页面，输出 action="update"，并提供完整的更新后内容（合并新旧信息）
            4. 如果是新实体，输出 action="create"
            5. 在 wiki 页面中用 [[页面名]] 引用其他相关页面
            6. 矛盾观点要标注来源并存
            7. 每个页面用 Markdown 格式，以 # 标题 开头

            ## 输出格式（严格 JSON）

            ```json
            {
              "wiki_pages": [
                {
                  "title": "页面标题",
                  "action": "create 或 update",
                  "content": "完整的 Markdown 内容"
                }
              ],
              "index_updates": [
                {
                  "title": "页面标题",
                  "summary": "一句话摘要"
                }
              ],
              "log_entry": "简要描述本次更新了什么"
            }
            ```

            只输出 JSON，不要其他文字。
            """.formatted(
                indexContent.isBlank() ? "(空，这是第一篇文章)" : indexContent,
                filename,
                fulltext
        );
    }

    private String buildSavePrompt(String responseContent, String indexContent) {
        return """
            你是一个知识库 Wiki 管理员。用户认为以下对话回答有价值，希望沉淀到知识库中。

            ## 当前 Wiki 索引 (index.md)
            %s

            ## 要沉淀的内容
            %s

            ## 你的任务

            判断这段内容应该创建新的 wiki 页面还是更新已有页面，然后输出结果。
            规则与 ingest 相同：用 [[]] 引用，Markdown 格式，合并而不重复。

            ## 输出格式（严格 JSON）

            ```json
            {
              "wiki_pages": [
                {
                  "title": "页面标题",
                  "action": "create 或 update",
                  "content": "完整的 Markdown 内容"
                }
              ],
              "index_updates": [
                {
                  "title": "页面标题",
                  "summary": "一句话摘要"
                }
              ],
              "log_entry": "简要描述本次更新了什么"
            }
            ```

            只输出 JSON，不要其他文字。
            """.formatted(
                indexContent.isBlank() ? "(空)" : indexContent,
                responseContent
        );
    }

    private void applyWikiChanges(String tenantId, String kbId,
                                   String sourceDocId, String sourceFilename,
                                   String llmResponse) {
        try {
            // Parse JSON response (strip markdown code fences if present)
            String json = llmResponse.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode root = objectMapper.readTree(json);

            // Process wiki pages
            JsonNode pages = root.path("wiki_pages");
            for (JsonNode page : pages) {
                String title = page.path("title").asText();
                String action = page.path("action").asText();
                String content = page.path("content").asText();

                if (title.isBlank() || content.isBlank()) continue;

                String filename = title + ".md";
                Optional<DocumentEntity> existing = documentRepository.findByTypeAndFilename(
                        tenantId, kbId, "wiki", filename);

                if ("update".equals(action) && existing.isPresent()) {
                    // Update existing wiki page
                    updateWikiDocument(existing.get(), content);
                } else {
                    // Create new wiki page
                    createWikiDocument(tenantId, kbId, title, content);
                }
            }

            // Update index.md
            updateIndex(tenantId, kbId, root.path("index_updates"));

            // Append to log.md
            String logEntry = root.path("log_entry").asText("");
            if (!logEntry.isBlank()) {
                appendLog(tenantId, kbId, sourceFilename, logEntry);
            }

            log.info("Wiki updated for KB {} from source {}: {} pages",
                    kbId, sourceFilename, pages.size());

        } catch (Exception e) {
            log.error("Failed to apply wiki changes for KB {}: {}", kbId, e.getMessage(), e);
        }
    }

    private void createWikiDocument(String tenantId, String kbId,
                                     String title, String content) {
        String docId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setKbId(kbId);
        doc.setFilename(title + ".md");
        doc.setDocType("wiki");
        doc.setFormat("MD");
        doc.setStatus("READY");
        doc.setTags(List.of("wiki"));
        doc.setMetadata(Map.of("wiki_version", "1"));

        // Save to OBS
        knowledgeService.writeDocumentContent(tenantId, kbId, docId, title + ".md", content);

        // Save entity
        documentRepository.save(doc);

        // Trigger chunking + embedding for the wiki page
        knowledgeService.processDocumentChunks(tenantId, kbId, docId);
    }

    private void updateWikiDocument(DocumentEntity doc, String content) {
        // Increment version
        Map<String, String> meta = new LinkedHashMap<>(doc.getMetadata());
        int version = 1;
        try { version = Integer.parseInt(meta.getOrDefault("wiki_version", "1")); }
        catch (NumberFormatException ignored) {}
        meta.put("wiki_version", String.valueOf(version + 1));
        doc.setMetadata(meta);
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);

        // Overwrite content in OBS
        knowledgeService.writeDocumentContent(
                doc.getTenantId(), doc.getKbId(), doc.getId(), doc.getFilename(), content);

        // Re-chunk + re-embed
        knowledgeService.processDocumentChunks(doc.getTenantId(), doc.getKbId(), doc.getId());
    }

    private void updateIndex(String tenantId, String kbId, JsonNode indexUpdates) {
        if (indexUpdates == null || indexUpdates.isMissingNode() || !indexUpdates.isArray()) return;

        String currentIndex = readWikiPage(tenantId, kbId, "index.md");
        StringBuilder sb = new StringBuilder(currentIndex);

        for (JsonNode entry : indexUpdates) {
            String title = entry.path("title").asText();
            String summary = entry.path("summary").asText();
            if (title.isBlank()) continue;

            String line = "- [[" + title + "]] — " + summary;
            // If title already in index, replace that line
            String pattern = "- \\[\\[" + Pattern.quote(title) + "\\]\\].*";
            String updated = sb.toString().replaceAll(pattern, line);
            if (updated.equals(sb.toString())) {
                // Not found, append
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
                sb.append(line).append("\n");
            } else {
                sb.setLength(0);
                sb.append(updated);
            }
        }

        writeSpecialPage(tenantId, kbId, "index.md", "index", sb.toString());
    }

    private void appendLog(String tenantId, String kbId,
                            String sourceFilename, String logEntry) {
        String currentLog = readWikiPage(tenantId, kbId, "log.md");
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneOffset.ofHours(8))
                .format(Instant.now());
        String entry = "## [" + timestamp + "] ingest | " + sourceFilename + "\n" + logEntry + "\n\n";
        String newLog = entry + currentLog;

        writeSpecialPage(tenantId, kbId, "log.md", "index", newLog);
    }

    private void writeSpecialPage(String tenantId, String kbId,
                                   String filename, String docType, String content) {
        Optional<DocumentEntity> existing = documentRepository.findByTypeAndFilename(
                tenantId, kbId, docType, filename);

        if (existing.isPresent()) {
            DocumentEntity doc = existing.get();
            doc.setUpdatedAt(Instant.now());
            documentRepository.save(doc);
            knowledgeService.writeDocumentContent(tenantId, kbId, doc.getId(), filename, content);
        } else {
            String docId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            DocumentEntity doc = new DocumentEntity();
            doc.setId(docId);
            doc.setTenantId(tenantId);
            doc.setKbId(kbId);
            doc.setFilename(filename);
            doc.setDocType(docType);
            doc.setFormat("MD");
            doc.setStatus("READY");
            doc.setTags(List.of("wiki-system"));
            documentRepository.save(doc);
            knowledgeService.writeDocumentContent(tenantId, kbId, docId, filename, content);
        }
    }

    private String callDeepSeek(String prompt) {
        String apiKey = props.getWiki() != null ? props.getWiki().getApiKey() : null;
        String baseUrl = props.getWiki() != null ? props.getWiki().getBaseUrl() : "https://api.deepseek.com/v1";

        if (apiKey == null || apiKey.isBlank()) {
            // Fallback to existing AI config
            apiKey = props.getAi().getApiKey();
            baseUrl = props.getAi().getBaseUrl();
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 8192);
        requestBody.put("response_format", Map.of("type", "json_object"));

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DeepSeek API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content")
                    .asText("").trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Add wiki config to LakeonProperties**

Add wiki API configuration so DeepSeek key can be set independently:

```java
// In LakeonProperties.java, add:
private Wiki wiki = new Wiki();

public Wiki getWiki() { return wiki; }
public void setWiki(Wiki wiki) { this.wiki = wiki; }

public static class Wiki {
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com/v1";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
```

- [ ] **Step 6: Verify compilation**

Run:
```bash
cd lakeon-api && mvn compile -q
```

Note: `knowledgeService.writeDocumentContent()` and `knowledgeService.processDocumentChunks()` may not exist yet. These are placeholder method names — check what KnowledgeService actually provides for writing content to OBS and triggering chunk processing. Adapt the method names accordingly.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskType.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "feat(api): add WikiService and WIKI_UPDATE task type"
```

---

## Task 3: Trigger Wiki Update after Document Summarize

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java`

- [ ] **Step 1: Enqueue WIKI_UPDATE after DOCUMENT_SUMMARIZE completes**

In `KbWriteQueue.java`, find the `executeDocumentSummarize` method. After it completes successfully, enqueue a WIKI_UPDATE task:

```java
private void executeDocumentSummarize(Connection conn, Map<String, Object> params) {
    String tenantId = (String) params.get("tenant_id");
    String kbId = (String) params.get("kb_id");
    String documentId = (String) params.get("document_id");
    summaryService.summarizeDocument(conn, tenantId, kbId, documentId);

    // After summarize, trigger wiki update
    enqueueTask(tenantId, kbId, KbWriteTaskType.WIKI_UPDATE,
            Map.of("tenant_id", tenantId, "kb_id", kbId, "document_id", documentId));
}
```

If `enqueueTask` is a private/different method, adapt to the existing pattern for enqueueing tasks in KbWriteQueue.

- [ ] **Step 2: Verify compilation**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "feat(api): trigger WIKI_UPDATE after document summarize"
```

---

## Task 4: API Endpoints for Wiki

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java:414-426`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

- [ ] **Step 1: Add wiki endpoints to KnowledgeController**

Add before the `// ── Helpers ──` section (line 427):

```java
// ── Wiki endpoints ───────────────────────────────────────────────

@GetMapping("/wiki/pages")
public ResponseEntity<?> listWikiPages(HttpServletRequest req,
        @RequestParam("kb_id") String kbId) {
    TenantEntity tenant = getTenant(req);
    List<DocumentEntity> pages = knowledgeService.listWikiPages(tenant.getId(), kbId);
    return ResponseEntity.ok(pages);
}

@GetMapping("/wiki/pages/{docId}/content")
public ResponseEntity<?> getWikiPageContent(HttpServletRequest req,
        @RequestParam("kb_id") String kbId,
        @PathVariable String docId) {
    TenantEntity tenant = getTenant(req);
    String content = knowledgeService.getWikiPageContent(tenant.getId(), kbId, docId);
    return ResponseEntity.ok(Map.of("content", content));
}

@GetMapping("/wiki/graph")
public ResponseEntity<?> getWikiGraph(HttpServletRequest req,
        @RequestParam("kb_id") String kbId) {
    TenantEntity tenant = getTenant(req);
    Map<String, Object> graph = wikiService.getGraph(tenant.getId(), kbId);
    return ResponseEntity.ok(graph);
}

@PostMapping("/wiki/chat")
public ResponseEntity<?> wikiChat(HttpServletRequest req,
        @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    String kbId = (String) body.get("kb_id");
    String question = (String) body.get("question");
    List<Map<String, String>> history = (List<Map<String, String>>) body.getOrDefault("history", List.of());
    Map<String, Object> response = wikiService.chat(tenant.getId(), kbId, question, history);
    return ResponseEntity.ok(response);
}

@PostMapping("/wiki/save-response")
public ResponseEntity<?> saveWikiResponse(HttpServletRequest req,
        @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    String kbId = (String) body.get("kb_id");
    String content = (String) body.get("content");
    wikiService.saveResponse(tenant.getId(), kbId, content);
    return ResponseEntity.ok(Map.of("status", "saved"));
}

@PostMapping("/wiki/ingest-url")
public ResponseEntity<?> ingestUrl(HttpServletRequest req,
        @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    String kbId = (String) body.get("kb_id");
    String url = (String) body.get("url");
    Map<String, Object> result = knowledgeService.ingestUrl(tenant.getId(), kbId, url);
    return ResponseEntity.ok(result);
}
```

Inject `WikiService` into the controller:

```java
private final WikiService wikiService;
```

- [ ] **Step 2: Add service methods to KnowledgeService**

Add methods:

```java
public List<DocumentEntity> listWikiPages(String tenantId, String kbId) {
    return documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, "wiki");
}

public String getWikiPageContent(String tenantId, String kbId, String docId) {
    return chunkService.getFulltext(tenantId, kbId, docId);
}
```

Add to DocumentRepository:

```java
List<DocumentEntity> findByTenantIdAndKbIdAndDocType(String tenantId, String kbId, String docType);
```

- [ ] **Step 3: Verify compilation**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java
git commit -m "feat(api): add wiki API endpoints (pages, graph, chat, save, url-ingest)"
```

---

## Task 5: Wiki Chat (Query Router Agent)

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`

- [ ] **Step 1: Add chat method to WikiService**

```java
/**
 * Router Agent: answer questions using wiki + optional raw document retrieval.
 */
public Map<String, Object> chat(String tenantId, String kbId,
                                 String question, List<Map<String, String>> history) {
    // 1. Read index.md
    String indexContent = readWikiPage(tenantId, kbId, "index.md");

    // 2. Ask LLM to route: which wiki pages are relevant? Is this a deep question?
    String routingPrompt = """
        你是一个知识库问答助手。用户提了一个问题，请根据 Wiki 索引判断：
        1. 哪些 wiki 页面与问题相关？列出页面标题
        2. 这是简单问题（wiki 页面足够回答）还是深度问题（需要查原始文档）？

        ## Wiki 索引
        %s

        ## 用户问题
        %s

        ## 输出格式（严格 JSON）
        ```json
        {
          "relevant_pages": ["页面标题1", "页面标题2"],
          "depth": "simple 或 deep"
        }
        ```
        只输出 JSON。
        """.formatted(indexContent, question);

    String routingResult = callDeepSeek(routingPrompt);
    JsonNode routing;
    try {
        String json = routingResult.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        routing = objectMapper.readTree(json);
    } catch (Exception e) {
        log.warn("Failed to parse routing result, falling back to simple mode: {}", e.getMessage());
        routing = objectMapper.createObjectNode();
    }

    // 3. Read relevant wiki pages
    StringBuilder context = new StringBuilder();
    JsonNode relevantPages = routing.path("relevant_pages");
    if (relevantPages.isArray()) {
        for (JsonNode pageName : relevantPages) {
            String title = pageName.asText();
            String pageContent = readWikiPage(tenantId, kbId, title + ".md");
            if (!pageContent.isBlank()) {
                context.append("## Wiki: ").append(title).append("\n")
                       .append(pageContent).append("\n\n");
            }
        }
    }

    // 4. For deep questions, also do vector search on raw chunks
    String depth = routing.path("depth").asText("simple");
    if ("deep".equals(depth)) {
        List<Map<String, Object>> chunks = knowledgeService.searchChunks(tenantId, kbId, question, 10);
        for (Map<String, Object> chunk : chunks) {
            context.append("## 原始文档片段\n")
                   .append(chunk.get("content")).append("\n\n");
        }
    }

    // 5. Generate answer
    StringBuilder historyText = new StringBuilder();
    for (Map<String, String> msg : history) {
        historyText.append(msg.getOrDefault("role", "user")).append(": ")
                   .append(msg.getOrDefault("content", "")).append("\n");
    }

    String answerPrompt = """
        你是一个知识库问答助手。基于以下知识内容回答用户的问题。
        - 引用信息时标注来源（wiki 页面名或文档名）
        - 如果知识库中没有相关信息，坦诚说明
        - 使用 [[页面名]] 引用相关 wiki 页面

        ## 知识内容
        %s

        ## 对话历史
        %s

        ## 用户问题
        %s

        请直接回答。
        """.formatted(context.toString(), historyText.toString(), question);

    String answer = callDeepSeek(answerPrompt);

    return Map.of(
        "answer", answer,
        "depth", depth,
        "sources", relevantPages != null && relevantPages.isArray() ? relevantPages : List.of()
    );
}
```

- [ ] **Step 2: Add searchChunks helper to KnowledgeService**

If not already available, add a method that performs vector search and returns chunk content:

```java
public List<Map<String, Object>> searchChunks(String tenantId, String kbId,
                                                String query, int topK) {
    // Use existing search logic but return raw chunk data
    // Adapt from the existing search endpoint implementation
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(api): add Query Router Agent for wiki chat"
```

---

## Task 6: URL Fetching (Orchestrator)

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/api/url_fetch.py`
- Modify: `lakeon-orchestrator/src/lakeon_orchestrator/main.py:64-74`

- [ ] **Step 1: Create url_fetch.py**

```python
"""URL fetching: download article HTML, extract text + images, return as Markdown."""

import re
import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter()


class UrlFetchRequest(BaseModel):
    url: str


class UrlFetchResponse(BaseModel):
    title: str
    content: str  # Markdown
    images: list[dict]  # [{url, filename, data_base64}]


@router.post("/fetch", response_model=UrlFetchResponse)
async def fetch_url(req: UrlFetchRequest):
    """Fetch a URL, extract article text as Markdown, download images."""
    try:
        import trafilatura

        # 1. Download HTML
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            resp = await client.get(req.url, headers={
                "User-Agent": "Mozilla/5.0 (compatible; DBay/1.0)"
            })
            resp.raise_for_status()
            html = resp.text

        # 2. Extract main content
        result = trafilatura.extract(html, include_images=True,
                                      include_links=True, output_format="txt")
        if not result:
            raise HTTPException(status_code=422, detail="Could not extract article content")

        # 3. Get title
        title = trafilatura.extract(html, output_format="xml")
        title_match = re.search(r"<title>(.*?)</title>", title or "", re.DOTALL)
        article_title = title_match.group(1).strip() if title_match else "Untitled"

        # 4. Extract and download images
        import base64
        images = []
        img_urls = re.findall(r'<img[^>]+src="([^"]+)"', html)
        async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
            for img_url in img_urls[:20]:  # Limit to 20 images
                try:
                    if img_url.startswith("//"):
                        img_url = "https:" + img_url
                    elif img_url.startswith("/"):
                        from urllib.parse import urlparse
                        parsed = urlparse(req.url)
                        img_url = f"{parsed.scheme}://{parsed.netloc}{img_url}"

                    img_resp = await client.get(img_url)
                    if img_resp.status_code == 200 and len(img_resp.content) < 5_000_000:
                        ext = img_url.rsplit(".", 1)[-1].split("?")[0][:4] or "png"
                        filename = f"img_{len(images):03d}.{ext}"
                        images.append({
                            "url": img_url,
                            "filename": filename,
                            "data_base64": base64.b64encode(img_resp.content).decode()
                        })
                except Exception:
                    continue  # Skip failed images

        return UrlFetchResponse(
            title=article_title,
            content=result,
            images=images
        )

    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail=f"Failed to fetch URL: {e}")
    except ImportError:
        raise HTTPException(status_code=500,
                            detail="trafilatura not installed. Run: pip install trafilatura")
```

- [ ] **Step 2: Register router in main.py**

In `main.py`, add after the existing router registration:

```python
from lakeon_orchestrator.api.url_fetch import router as url_fetch_router
_app.include_router(url_fetch_router, prefix="/url", tags=["url-fetch"])
```

- [ ] **Step 3: Add trafilatura dependency**

```bash
cd lakeon-orchestrator && pip install trafilatura
```

Also add to `requirements.txt` or `pyproject.toml`.

- [ ] **Step 4: Add ingestUrl to KnowledgeService (lakeon-api)**

The lakeon-api `ingestUrl` method calls the orchestrator's `/url/fetch` endpoint, then stores the result as a document:

```java
public Map<String, Object> ingestUrl(String tenantId, String kbId, String url) {
    // 1. Call orchestrator to fetch URL
    // 2. Store markdown content as a new raw document
    // 3. Upload images to OBS
    // 4. Trigger processing (same as file upload)
    // Return document_id
}
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/api/url_fetch.py \
        lakeon-orchestrator/src/lakeon_orchestrator/main.py
git commit -m "feat(orchestrator): add URL fetching with trafilatura"
```

---

## Task 7: Frontend — Markdown Renderer Component

**Files:**
- Create: `lakeon-console/src/components/MarkdownRenderer.vue`

- [ ] **Step 1: Create MarkdownRenderer component**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  content: string
  kbId: string
}>()

const emit = defineEmits<{
  (e: 'navigate', title: string): void
}>()

// Configure markdown-it with wikilink support
const md = new MarkdownIt({ html: true, linkify: true })

// Custom wikilink plugin: [[Page Name]] → clickable link
md.inline.ruler.push('wikilink', (state) => {
  const src = state.src.slice(state.pos)
  const match = src.match(/^\[\[([^\]]+)\]\]/)
  if (!match) return false

  if (!state.env.wikilinkTokens) state.env.wikilinkTokens = []

  const token = state.push('wikilink', '', 0)
  token.content = match[1]
  state.pos += match[0].length
  return true
})

md.renderer.rules.wikilink = (tokens, idx) => {
  const title = tokens[idx].content
  return `<a class="wikilink" data-title="${title}" href="javascript:void(0)">${title}</a>`
}

const rendered = computed(() => md.render(props.content || ''))

function handleClick(e: Event) {
  const target = e.target as HTMLElement
  if (target.classList.contains('wikilink')) {
    const title = target.getAttribute('data-title')
    if (title) emit('navigate', title)
  }
}
</script>

<template>
  <div class="markdown-body" @click="handleClick" v-html="rendered" />
</template>

<style scoped>
.markdown-body {
  font-size: 15px;
  line-height: 1.7;
  color: #333;
}
.markdown-body :deep(h1) { font-size: 1.6em; margin: 0.8em 0 0.4em; border-bottom: 1px solid #eee; padding-bottom: 0.3em; }
.markdown-body :deep(h2) { font-size: 1.3em; margin: 0.7em 0 0.3em; }
.markdown-body :deep(h3) { font-size: 1.1em; margin: 0.6em 0 0.2em; }
.markdown-body :deep(p) { margin: 0.5em 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 1.5em; }
.markdown-body :deep(code) { background: #f5f5f5; padding: 0.2em 0.4em; border-radius: 3px; font-size: 0.9em; }
.markdown-body :deep(pre) { background: #f5f5f5; padding: 12px; border-radius: 6px; overflow-x: auto; }
.markdown-body :deep(blockquote) { border-left: 3px solid #ddd; padding-left: 12px; color: #666; margin: 0.5em 0; }
.markdown-body :deep(a.wikilink) {
  color: #5b7bd5;
  text-decoration: none;
  border-bottom: 1px dashed #5b7bd5;
  cursor: pointer;
}
.markdown-body :deep(a.wikilink:hover) {
  color: #3a5bb5;
  border-bottom-style: solid;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/components/MarkdownRenderer.vue
git commit -m "feat(console): add MarkdownRenderer with wikilink support"
```

---

## Task 8: Frontend — Wiki Page View

**Files:**
- Create: `lakeon-console/src/views/knowledge/WikiPage.vue`
- Modify: `lakeon-console/src/api/knowledge.ts:349`

- [ ] **Step 1: Add wiki API functions**

In `knowledge.ts`, add at the end:

```typescript
// ── Wiki API ──

export interface WikiPageItem {
  id: string
  filename: string
  tags: string[]
  metadata: Record<string, string>
  created_at: string
  updated_at: string
}

export function listWikiPages(kbId: string) {
  return api.get<WikiPageItem[]>('/knowledge/wiki/pages', { params: { kb_id: kbId } })
}

export function getWikiPageContent(kbId: string, docId: string) {
  return api.get<{ content: string }>('/knowledge/wiki/pages/' + docId + '/content', {
    params: { kb_id: kbId }
  })
}

export interface WikiGraph {
  nodes: { id: string; label: string; document_id: string }[]
  edges: { source: string; target: string }[]
}

export function getWikiGraph(kbId: string) {
  return api.get<WikiGraph>('/knowledge/wiki/graph', { params: { kb_id: kbId } })
}

export function wikiChat(kbId: string, question: string, history: { role: string; content: string }[] = []) {
  return api.post<{ answer: string; depth: string; sources: string[] }>('/knowledge/wiki/chat', {
    kb_id: kbId, question, history
  })
}

export function saveWikiResponse(kbId: string, content: string) {
  return api.post('/knowledge/wiki/save-response', { kb_id: kbId, content })
}

export function ingestUrl(kbId: string, url: string) {
  return api.post<{ document_id: string; status: string }>('/knowledge/wiki/ingest-url', {
    kb_id: kbId, url
  })
}
```

- [ ] **Step 2: Create WikiPage.vue**

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { listWikiPages, getWikiPageContent, type WikiPageItem } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()

const pages = ref<WikiPageItem[]>([])
const selectedPage = ref<WikiPageItem | null>(null)
const content = ref('')
const loading = ref(false)

async function loadPages() {
  const resp = await listWikiPages(props.kbId)
  pages.value = resp.data
}

async function openPage(page: WikiPageItem) {
  selectedPage.value = page
  loading.value = true
  try {
    const resp = await getWikiPageContent(props.kbId, page.id)
    content.value = resp.data.content
  } finally {
    loading.value = false
  }
}

function navigateToTitle(title: string) {
  const page = pages.value.find(p => p.filename === title + '.md')
  if (page) openPage(page)
}

watch(() => props.kbId, loadPages, { immediate: true })
</script>

<template>
  <div style="display: flex; gap: 16px; height: 100%;">
    <!-- Page list sidebar -->
    <div style="width: 240px; flex-shrink: 0; border-right: 1px solid #eee; padding-right: 12px; overflow-y: auto;">
      <h4 style="margin: 0 0 12px; color: #666; font-size: 13px;">Wiki 页面 ({{ pages.length }})</h4>
      <div v-for="page in pages" :key="page.id"
           :class="['wiki-page-item', { active: selectedPage?.id === page.id }]"
           @click="openPage(page)">
        {{ page.filename.replace('.md', '') }}
      </div>
      <div v-if="pages.length === 0" style="color: #999; font-size: 13px;">
        暂无 wiki 页面，上传文章后自动生成
      </div>
    </div>

    <!-- Content area -->
    <div style="flex: 1; overflow-y: auto; padding: 0 12px;">
      <div v-if="loading" style="color: #999; padding: 20px;">加载中...</div>
      <div v-else-if="selectedPage">
        <div style="margin-bottom: 8px; font-size: 12px; color: #999;">
          版本 {{ selectedPage.metadata?.wiki_version || '1' }}
          · 更新于 {{ selectedPage.updated_at || selectedPage.created_at }}
        </div>
        <MarkdownRenderer :content="content" :kb-id="kbId" @navigate="navigateToTitle" />
      </div>
      <div v-else style="color: #999; padding: 40px; text-align: center;">
        选择左侧的 wiki 页面查看内容
      </div>
    </div>
  </div>
</template>

<style scoped>
.wiki-page-item {
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 2px;
}
.wiki-page-item:hover { background: #f5f5f5; }
.wiki-page-item.active { background: #e8f0fe; color: #1a73e8; }
</style>
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/WikiPage.vue
git commit -m "feat(console): add wiki page list and content viewer"
```

---

## Task 9: Frontend — Graph View

**Files:**
- Create: `lakeon-console/src/views/knowledge/WikiGraph.vue`

- [ ] **Step 1: Create WikiGraph.vue**

```vue
<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import * as d3 from 'd3'
import { getWikiGraph, type WikiGraph } from '@/api/knowledge'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

const svgRef = ref<SVGSVGElement>()
const graphData = ref<WikiGraph>({ nodes: [], edges: [] })

async function loadGraph() {
  const resp = await getWikiGraph(props.kbId)
  graphData.value = resp.data
  renderGraph()
}

function renderGraph() {
  if (!svgRef.value || graphData.value.nodes.length === 0) return

  const svg = d3.select(svgRef.value)
  svg.selectAll('*').remove()

  const width = svgRef.value.clientWidth
  const height = svgRef.value.clientHeight

  const g = svg.append('g')

  // Zoom
  const zoom = d3.zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.2, 4])
    .on('zoom', (event) => g.attr('transform', event.transform))
  svg.call(zoom)

  const nodes = graphData.value.nodes.map(n => ({ ...n }))
  const edges = graphData.value.edges.map(e => ({ ...e }))

  const simulation = d3.forceSimulation(nodes as any)
    .force('link', d3.forceLink(edges as any).id((d: any) => d.id).distance(120))
    .force('charge', d3.forceManyBody().strength(-300))
    .force('center', d3.forceCenter(width / 2, height / 2))
    .force('collision', d3.forceCollide().radius(40))

  // Edges
  const link = g.append('g')
    .selectAll('line')
    .data(edges)
    .join('line')
    .attr('stroke', '#ccc')
    .attr('stroke-width', 1.5)

  // Nodes
  const node = g.append('g')
    .selectAll('g')
    .data(nodes)
    .join('g')
    .attr('cursor', 'pointer')
    .call(d3.drag<any, any>()
      .on('start', (event, d: any) => {
        if (!event.active) simulation.alphaTarget(0.3).restart()
        d.fx = d.x; d.fy = d.y
      })
      .on('drag', (event, d: any) => { d.fx = event.x; d.fy = event.y })
      .on('end', (event, d: any) => {
        if (!event.active) simulation.alphaTarget(0)
        d.fx = null; d.fy = null
      })
    )
    .on('click', (_event, d: any) => emit('navigate', d.label))

  node.append('circle')
    .attr('r', (d: any) => d.document_id ? 8 : 5)
    .attr('fill', (d: any) => d.document_id ? '#5b7bd5' : '#ccc')
    .attr('stroke', '#fff')
    .attr('stroke-width', 2)

  node.append('text')
    .text((d: any) => d.label)
    .attr('dx', 12)
    .attr('dy', 4)
    .attr('font-size', '12px')
    .attr('fill', '#333')

  simulation.on('tick', () => {
    link
      .attr('x1', (d: any) => d.source.x)
      .attr('y1', (d: any) => d.source.y)
      .attr('x2', (d: any) => d.target.x)
      .attr('y2', (d: any) => d.target.y)
    node.attr('transform', (d: any) => `translate(${d.x},${d.y})`)
  })
}

watch(() => props.kbId, loadGraph)
onMounted(loadGraph)
</script>

<template>
  <div style="width: 100%; height: 500px; border: 1px solid #eee; border-radius: 8px; overflow: hidden;">
    <svg ref="svgRef" style="width: 100%; height: 100%;" />
    <div v-if="graphData.nodes.length === 0"
         style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: #999;">
      暂无图谱数据
    </div>
  </div>
</template>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/knowledge/WikiGraph.vue
git commit -m "feat(console): add interactive wiki graph view with D3"
```

---

## Task 10: Frontend — Wiki Chat Interface

**Files:**
- Create: `lakeon-console/src/views/knowledge/WikiChat.vue`

- [ ] **Step 1: Create WikiChat.vue**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { wikiChat, saveWikiResponse } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

interface Message {
  role: 'user' | 'assistant'
  content: string
  depth?: string
  sources?: string[]
  saved?: boolean
}

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)

async function send() {
  const question = input.value.trim()
  if (!question || loading.value) return

  messages.value.push({ role: 'user', content: question })
  input.value = ''
  loading.value = true

  try {
    const history = messages.value.slice(0, -1).map(m => ({
      role: m.role, content: m.content
    }))
    const resp = await wikiChat(props.kbId, question, history)
    messages.value.push({
      role: 'assistant',
      content: resp.data.answer,
      depth: resp.data.depth,
      sources: resp.data.sources,
      saved: false
    })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', content: '抱歉，出错了: ' + (e.message || e) })
  } finally {
    loading.value = false
  }
}

async function saveToWiki(msg: Message) {
  try {
    await saveWikiResponse(props.kbId, msg.content)
    msg.saved = true
  } catch (e: any) {
    alert('保存失败: ' + (e.message || e))
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%;">
    <!-- Messages -->
    <div style="flex: 1; overflow-y: auto; padding: 16px;">
      <div v-for="(msg, i) in messages" :key="i" :style="{ marginBottom: '16px' }">
        <div style="font-size: 12px; color: #999; margin-bottom: 4px;">
          {{ msg.role === 'user' ? '你' : 'Wiki 助手' }}
          <span v-if="msg.depth" style="margin-left: 8px; color: #5b7bd5;">
            ({{ msg.depth === 'deep' ? '深度分析' : '快速回答' }})
          </span>
        </div>
        <div v-if="msg.role === 'user'" style="background: #f5f7fa; padding: 10px 14px; border-radius: 8px;">
          {{ msg.content }}
        </div>
        <div v-else>
          <div style="background: #fff; border: 1px solid #eee; padding: 12px 16px; border-radius: 8px;">
            <MarkdownRenderer :content="msg.content" :kb-id="kbId" @navigate="(t) => emit('navigate', t)" />
          </div>
          <button v-if="!msg.saved"
                  style="margin-top: 6px; font-size: 12px; color: #5b7bd5; background: none; border: 1px solid #5b7bd5; border-radius: 4px; padding: 3px 10px; cursor: pointer;"
                  @click="saveToWiki(msg)">
            沉淀到知识库
          </button>
          <span v-else style="margin-top: 6px; font-size: 12px; color: #67c23a; display: inline-block;">
            已沉淀
          </span>
        </div>
      </div>
      <div v-if="loading" style="color: #999;">思考中...</div>
    </div>

    <!-- Input -->
    <div style="border-top: 1px solid #eee; padding: 12px 16px; display: flex; gap: 8px;">
      <textarea v-model="input"
                @keydown="handleKeydown"
                placeholder="基于知识库提问..."
                style="flex: 1; resize: none; height: 40px; border: 1px solid #ddd; border-radius: 6px; padding: 8px 12px; font-size: 14px;" />
      <button @click="send"
              :disabled="loading || !input.trim()"
              style="padding: 8px 20px; background: #5b7bd5; color: #fff; border: none; border-radius: 6px; cursor: pointer; white-space: nowrap;">
        发送
      </button>
    </div>
  </div>
</template>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/knowledge/WikiChat.vue
git commit -m "feat(console): add wiki chat interface with save-to-wiki"
```

---

## Task 11: Frontend — Integrate Wiki Tabs into KB Detail Page

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue:553-559`

- [ ] **Step 1: Add wiki tabs**

Update the tabs array (line 553-559):

```javascript
const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'documents', label: '文档' },
  { key: 'wiki', label: 'Wiki' },
  { key: 'graph', label: '图谱' },
  { key: 'chat', label: '对话' },
  { key: 'datasources', label: '数据源' },
  { key: 'search', label: '搜索' },
  { key: 'chunks', label: '切片' },
]
```

- [ ] **Step 2: Import and render wiki components**

Add imports:

```typescript
import WikiPage from './WikiPage.vue'
import WikiGraph from './WikiGraph.vue'
import WikiChat from './WikiChat.vue'
```

In the template, add tab content panels for wiki, graph, and chat. Find the existing tab content rendering pattern (v-if/v-show on activeTab) and add:

```html
<WikiPage v-if="activeTab === 'wiki'" :kb-id="kbId" />
<WikiGraph v-if="activeTab === 'graph'" :kb-id="kbId"
           @navigate="(title) => { /* switch to wiki tab and open page */ }" />
<WikiChat v-if="activeTab === 'chat'" :kb-id="kbId"
          @navigate="(title) => { /* switch to wiki tab and open page */ }" />
```

- [ ] **Step 3: Add URL ingest button to documents tab**

In the documents tab toolbar area, add a button next to the existing upload buttons:

```html
<button @click="showUrlIngest = true"
        style="background: #fff; border: 1px solid #5b7bd5; color: #5b7bd5; border-radius: 4px; padding: 4px 12px; cursor: pointer; font-size: 12px;">
  导入 URL
</button>
```

Add a simple dialog for URL input and call `ingestUrl()`.

- [ ] **Step 4: Add document type filter to documents tab**

Add filter buttons above the document table:

```html
<div style="margin-bottom: 8px; display: flex; gap: 8px;">
  <button :class="docTypeFilter === '' ? 'active' : ''" @click="docTypeFilter = ''">全部</button>
  <button :class="docTypeFilter === 'raw' ? 'active' : ''" @click="docTypeFilter = 'raw'">原始文档</button>
  <button :class="docTypeFilter === 'wiki' ? 'active' : ''" @click="docTypeFilter = 'wiki'">Wiki 页面</button>
</div>
```

Pass `docTypeFilter` to the document listing API call.

- [ ] **Step 5: Type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): integrate wiki, graph, chat tabs into KB detail page"
```

---

## Task 12: Deploy and E2E Verification

**Files:**
- Backend deployment
- E2E test

- [ ] **Step 1: Run database migration**

Execute the ALTER TABLE on the production metadata DB:

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS doc_type VARCHAR(16) NOT NULL DEFAULT 'raw';
```

- [ ] **Step 2: Configure DeepSeek API key**

Add to the Kubernetes secret or Helm values:

```yaml
api:
  wiki:
    apiKey: "sk-f61aebc253eb436eb5dc9997ecbe0f51"
    baseUrl: "https://api.deepseek.com/v1"
```

- [ ] **Step 3: Build and deploy API**

```bash
cd lakeon-api
SITE=hwstaff IMAGE_TAG=0.9.199 bash ../deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl set image deployment/lakeon-api -n lakeon \
  lakeon-api=swr.cn-north-4.myhuaweicloud.com/flex/lakeon-api:0.9.199
```

- [ ] **Step 4: Deploy console (git push triggers Railway)**

```bash
git push origin main
```

- [ ] **Step 5: E2E verification — Upload articles**

Upload 10 articles about Code Agent (from ~/code/kb-doc or manually).

- [ ] **Step 6: E2E verification — Check wiki pages generated**

```bash
curl -s 'https://api.dbay.cloud:8443/api/v1/knowledge/wiki/pages?kb_id=<KB_ID>' \
  -H 'Authorization: Bearer <token>' | python3 -m json.tool
```

Expected: multiple wiki pages (Code Agent, Devin, Cursor, etc.)

- [ ] **Step 7: E2E verification — Check graph**

```bash
curl -s 'https://api.dbay.cloud:8443/api/v1/knowledge/wiki/graph?kb_id=<KB_ID>' \
  -H 'Authorization: Bearer <token>' | python3 -m json.tool
```

Expected: nodes and edges from wikilinks

- [ ] **Step 8: E2E verification — Chat**

```bash
curl -s -X POST 'https://api.dbay.cloud:8443/api/v1/knowledge/wiki/chat' \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"kb_id":"<KB_ID>","question":"Code Agent 的发展趋势是什么？"}' | python3 -m json.tool
```

Expected: coherent answer referencing wiki pages

- [ ] **Step 9: E2E verification — Frontend**

Open console, navigate to KB detail, verify:
1. Wiki tab shows pages, click to view Markdown with wikilinks
2. Graph tab shows interactive node graph
3. Chat tab allows Q&A with save-to-wiki button

- [ ] **Step 10: Commit E2E test script**

```bash
git commit -m "test(e2e): verify KB wiki engine Phase 1"
```
