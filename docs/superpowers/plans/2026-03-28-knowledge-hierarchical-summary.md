# Knowledge Hierarchical Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-document L1 summaries and KB-level L2 summaries to the knowledge base, with async generation, 3-layer supervision, and hybrid search across L0+L1.

**Architecture:** After document parsing completes (level=0 chunks written), the API enqueues a lightweight DOCUMENT_SUMMARIZE task that reads fulltext from OBS, calls DeepSeek-V3.2 for summarization, embeds the summary via BGE-M3, and writes it as a level=1 chunk. When all documents have L1, a KB_SUMMARIZE task generates a level=2 global summary. Search is enhanced to query both L0 and L1 via RRF.

**Tech Stack:** Java/Spring Boot (API), PostgreSQL/pgvector (storage), SiliconFlow API (LLM + embedding), OBS (fulltext source)

**Spec:** `docs/superpowers/specs/2026-03-28-knowledge-hierarchical-summary-design.md`

---

### Task 1: Add DOCUMENT_SUMMARIZE and KB_SUMMARIZE task types

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskType.java`

- [ ] **Step 1: Add two new enum values**

```java
package com.lakeon.knowledge;

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
    KB_SUMMARIZE
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskType.java
git commit -m "feat(kb): add DOCUMENT_SUMMARIZE and KB_SUMMARIZE task types"
```

---

### Task 2: Create SummaryService — LLM call + embedding + chunk write

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/SummaryService.java`

This is the core service. It reads fulltext from OBS, calls LLM for summarization, computes embedding, and writes the summary chunk to the compute pod DB.

- [ ] **Step 1: Create SummaryService**

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;

@Service
public class SummaryService {
    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final String SUMMARY_MODEL = "deepseek-ai/DeepSeek-V3.2";
    private static final int MAX_FULLTEXT_CHARS = 28_000;
    private static final String SUMMARY_PROMPT = """
            你是一个文档摘要助手。请为以下文档生成一份结构化摘要。

            要求：
            1. 用中文输出（除非原文是纯英文）
            2. 先用一句���概括文档主题
            3. 再列出3-7个关键要点
            4. 总长度控制在300-500字
            5. 保留专业术语原文

            文档内容：
            """;

    private static final String KB_SUMMARY_PROMPT = """
            你是一个知识库摘要助手。以下是���个知识���中所有文档的摘要。
            请生成一份知识库全局概览。

            要求：
            1. 用中文输出（除非所有摘要都是纯英文）
            2. 先用一句话概括知识库的主题和范围
            3. 列出知识库涵盖的主要主题（3-10个）
            4. 总长度控制在300-500字

            文档摘要列表：
            """;

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChunkService chunkService;

    public SummaryService(LakeonProperties props, ObjectMapper objectMapper,
                          ChunkService chunkService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.chunkService = chunkService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generate and store a document summary (level=1 chunk).
     * Called by KbWriteQueue for DOCUMENT_SUMMARIZE tasks.
     */
    public void summarizeDocument(String tenantId, String kbId, String documentId,
                                  String connstr) {
        // 1. Read fulltext from OBS (with chunk fallback)
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null || fulltext.isBlank()) {
            log.warn("No fulltext found for doc {}, skipping summarization", documentId);
            return;
        }

        // 2. Truncate to max chars
        if (fulltext.length() > MAX_FULLTEXT_CHARS) {
            fulltext = fulltext.substring(0, MAX_FULLTEXT_CHARS);
        }

        // 3. Call LLM for summary
        String summary = callLlm(SUMMARY_PROMPT + fulltext);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM returned empty summary for doc " + documentId);
        }

        // 4. Compute embedding
        String embModel = props.getKnowledge().getEmbeddingModel();
        float[] embedding = chunkService.getEmbeddingPublic(summary, embModel);
        String vectorStr = chunkService.floatArrayToVectorLiteralPublic(embedding);

        // 5. Get source chunk IDs
        int[] sourceChunkIds = getSourceChunkIds(connstr, documentId);

        // 6. Write level=1 chunk (delete old one first)
        writeSummaryChunk(connstr, documentId, 1, summary, vectorStr,
                sourceChunkIds, Map.of("type", "document_summary"));

        log.info("Document summary generated for doc {} ({} chars)", documentId, summary.length());
    }

    /**
     * Generate and store a KB-level summary (level=2 chunk).
     * Called by KbWriteQueue for KB_SUMMARIZE tasks.
     */
    public void summarizeKb(String tenantId, String kbId, String connstr) {
        // 1. Read all L1 summaries
        List<Map<String, Object>> l1Chunks = readChunksByLevel(connstr, 1);
        if (l1Chunks.isEmpty()) {
            log.warn("No L1 summaries found for KB {}, skipping KB summarization", kbId);
            return;
        }

        // 2. Build input from L1 summaries
        StringBuilder sb = new StringBuilder();
        List<Integer> sourceIds = new ArrayList<>();
        for (Map<String, Object> chunk : l1Chunks) {
            sb.append("---\n").append(chunk.get("content")).append("\n");
            sourceIds.add((Integer) chunk.get("id"));
        }

        String input = sb.toString();
        if (input.length() > MAX_FULLTEXT_CHARS) {
            input = input.substring(0, MAX_FULLTEXT_CHARS);
        }

        // 3. Call LLM
        String summary = callLlm(KB_SUMMARY_PROMPT + input);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM returned empty KB summary for KB " + kbId);
        }

        // 4. Compute embedding
        String embModel = props.getKnowledge().getEmbeddingModel();
        float[] embedding = chunkService.getEmbeddingPublic(summary, embModel);
        String vectorStr = chunkService.floatArrayToVectorLiteralPublic(embedding);

        // 5. Write level=2 chunk
        int[] sourceArr = sourceIds.stream().mapToInt(Integer::intValue).toArray();
        writeSummaryChunk(connstr, "__kb_summary__", 2, summary, vectorStr,
                sourceArr, Map.of("type", "kb_summary"));

        log.info("KB summary generated for KB {} from {} document summaries", kbId, l1Chunks.size());
    }

    /**
     * Check if all documents in a KB have L1 summaries.
     */
    public boolean allDocumentsHaveSummary(String connstr, List<String> documentIds) {
        if (documentIds.isEmpty()) return false;
        String jdbcUrl = buildJdbcUrl(connstr);
        String[] creds = extractCreds(connstr);
        String placeholders = String.join(",", Collections.nCopies(documentIds.size(), "?"));
        String sql = "SELECT COUNT(DISTINCT document_id) FROM knowledge_chunks " +
                "WHERE level = 1 AND document_id IN (" + placeholders + ")";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, creds[0], creds[1]);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < documentIds.size(); i++) {
                ps.setString(i + 1, documentIds.get(i));
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) == documentIds.size();
        } catch (Exception e) {
            log.warn("Failed to check L1 completeness: {}", e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private String callLlm(String prompt) {
        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("AI API key not configured, cannot generate summary");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", SUMMARY_MODEL);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 1024);

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new RuntimeException("LLM API returned " + response.statusCode()
                        + ": " + response.body());
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("LLM API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content")
                    .asText("").trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private void writeSummaryChunk(String connstr, String documentId, int level,
                                   String content, String vectorStr, int[] sourceChunkIds,
                                   Map<String, String> metadata) {
        String jdbcUrl = buildJdbcUrl(connstr);
        String[] creds = extractCreds(connstr);
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            metaJson = "{}";
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, creds[0], creds[1])) {
            conn.setAutoCommit(false);
            // Delete existing summary for this document at this level
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM knowledge_chunks WHERE document_id = ? AND level = ?")) {
                del.setString(1, documentId);
                del.setInt(2, level);
                del.executeUpdate();
            }
            // Insert new summary chunk
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO knowledge_chunks " +
                    "(document_id, chunk_index, content, embedding, metadata, " +
                    "level, source_chunks, char_count, created_at) " +
                    "VALUES (?, 0, ?, ?::vector, ?::jsonb, ?, ?, ?, now())")) {
                ins.setString(1, documentId);
                ins.setString(2, content);
                ins.setString(3, vectorStr);
                ins.setString(4, metaJson);
                ins.setInt(5, level);
                if (sourceChunkIds != null && sourceChunkIds.length > 0) {
                    Integer[] boxed = Arrays.stream(sourceChunkIds).boxed().toArray(Integer[]::new);
                    ins.setArray(6, conn.createArrayOf("integer", boxed));
                } else {
                    ins.setNull(6, java.sql.Types.ARRAY);
                }
                ins.setInt(7, content.length());
                ins.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("Failed to write summary chunk: " + e.getMessage(), e);
        }
    }

    private int[] getSourceChunkIds(String connstr, String documentId) {
        String jdbcUrl = buildJdbcUrl(connstr);
        String[] creds = extractCreds(connstr);
        String sql = "SELECT id FROM knowledge_chunks WHERE document_id = ? AND level = 0 ORDER BY chunk_index";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, creds[0], creds[1]);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            ResultSet rs = ps.executeQuery();
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt("id"));
            return ids.stream().mapToInt(Integer::intValue).toArray();
        } catch (Exception e) {
            log.warn("Failed to get source chunk IDs for doc {}: {}", documentId, e.getMessage());
            return new int[0];
        }
    }

    private List<Map<String, Object>> readChunksByLevel(String connstr, int level) {
        String jdbcUrl = buildJdbcUrl(connstr);
        String[] creds = extractCreds(connstr);
        String sql = "SELECT id, document_id, content FROM knowledge_chunks WHERE level = ? ORDER BY document_id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, creds[0], creds[1]);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> chunks = new ArrayList<>();
            while (rs.next()) {
                chunks.add(Map.of(
                        "id", rs.getInt("id"),
                        "document_id", rs.getString("document_id"),
                        "content", rs.getString("content")
                ));
            }
            return chunks;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read level-" + level + " chunks: " + e.getMessage(), e);
        }
    }

    private String buildJdbcUrl(String connstr) {
        // connstr format: postgresql://user:pass@host:port/db?options=...
        return connstr.replace("postgresql://", "jdbc:postgresql://");
    }

    private String[] extractCreds(String connstr) {
        // Extract user:pass from postgresql://user:pass@host...
        String afterProto = connstr.substring("postgresql://".length());
        String userPass = afterProto.substring(0, afterProto.indexOf('@'));
        String[] parts = userPass.split(":", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : ""};
    }
}
```

- [ ] **Step 2: Expose getEmbedding and floatArrayToVectorLiteral from ChunkService**

ChunkService already has `getEmbedding()` and `floatArrayToVectorLiteral()` as private methods. Add public wrappers. In `ChunkService.java`, after the existing `floatArrayToVectorLiteral` method (around line 755):

```java
    // ── Public wrappers for SummaryService ──────────────────────────

    public float[] getEmbeddingPublic(String text, String model) {
        return getEmbedding(text, model);
    }

    public String floatArrayToVectorLiteralPublic(float[] vec) {
        return floatArrayToVectorLiteral(vec);
    }
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/SummaryService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java
git commit -m "feat(kb): add SummaryService for document and KB summarization"
```

---

### Task 3: Wire DOCUMENT_SUMMARIZE into KbWriteQueue

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java`

- [ ] **Step 1: Add SummaryService dependency**

In `KbWriteQueue.java`, add field and constructor parameter. Around line 48 (existing fields):

```java
    private final SummaryService summaryService;
```

In the constructor (around line 71), add parameter and assignment:

```java
    public KbWriteQueue(KbWriteTaskRepository taskRepository,
                         ...
                         @Lazy JobService jobService,
                         SummaryService summaryService,
                         ...) {
        ...
        this.summaryService = summaryService;
    }
```

- [ ] **Step 2: Add DOCUMENT_SUMMARIZE and KB_SUMMARIZE to LIGHTWEIGHT_TYPES**

At line 57-63, add the new types:

```java
    private static final Set<KbWriteTaskType> LIGHTWEIGHT_TYPES = Set.of(
        KbWriteTaskType.EDIT_CHUNK,
        KbWriteTaskType.DELETE_CHUNK,
        KbWriteTaskType.CREATE_CHUNK,
        KbWriteTaskType.RECHUNK_ROLLBACK,
        KbWriteTaskType.DELETE_DOCUMENT_CHUNKS,
        KbWriteTaskType.DOCUMENT_SUMMARIZE,
        KbWriteTaskType.KB_SUMMARIZE
    );
```

- [ ] **Step 3: Add execution cases in executeLightweight switch**

In the switch statement (around line 526-533), add:

```java
    case DOCUMENT_SUMMARIZE -> executeDocumentSummarize(conn, params);
    case KB_SUMMARIZE -> executeKbSummarize(conn, params);
```

Note: these don't actually use the `conn` parameter (they use their own connection via connstr). The `conn` is passed for API consistency with other lightweight tasks.

- [ ] **Step 4: Add executeDocumentSummarize method**

After the existing `executeDeleteDocumentChunks` method, add:

```java
    @SuppressWarnings("unchecked")
    private void executeDocumentSummarize(Connection conn, Map<String, Object> params) {
        String tenantId = (String) params.get("tenant_id");
        String kbId = (String) params.get("kb_id");
        String documentId = (String) params.get("document_id");
        String connstr = (String) params.get("connstr");
        summaryService.summarizeDocument(tenantId, kbId, documentId, connstr);

        // Check if all documents now have L1 → enqueue KB_SUMMARIZE
        List<String> docIds = (List<String>) params.get("all_document_ids");
        if (docIds != null && summaryService.allDocumentsHaveSummary(connstr, docIds)) {
            Map<String, Object> kbParams = new LinkedHashMap<>();
            kbParams.put("tenant_id", tenantId);
            kbParams.put("kb_id", kbId);
            kbParams.put("connstr", connstr);
            kbParams.put("database_id", params.get("database_id"));
            enqueueTask((String) params.get("database_id"), KbWriteTaskType.KB_SUMMARIZE, kbParams);
            log.info("All documents have L1 summaries, enqueued KB_SUMMARIZE for KB {}", kbId);
        }
    }

    private void executeKbSummarize(Connection conn, Map<String, Object> params) {
        String tenantId = (String) params.get("tenant_id");
        String kbId = (String) params.get("kb_id");
        String connstr = (String) params.get("connstr");
        summaryService.summarizeKb(tenantId, kbId, connstr);
    }
```

- [ ] **Step 5: Enqueue DOCUMENT_SUMMARIZE after successful DOCUMENT_PARSE**

In `onJobCompleted()` (around line 224), after the existing `syncDocumentFromTask(task, true, result, null)`, add summarize enqueue logic:

```java
                if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                    syncDocumentFromTask(task, true, result, null);
                    enqueueSummarizeAfterParse(task);
                } else if (task.getType() == KbWriteTaskType.BATCH_DOCUMENT_PARSE) {
                    syncBatchDocumentsFromTask(task, true, result, null);
                    enqueueSummarizeAfterBatchParse(task);
                }
```

Add the helper methods:

```java
    @SuppressWarnings("unchecked")
    private void enqueueSummarizeAfterParse(KbWriteTaskEntity task) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            String tenantId = (String) params.get("tenant_id");
            String kbId = (String) params.get("kb_id");
            String documentId = (String) params.get("document_id");
            String connstr = (String) params.get("connstr");
            List<String> allDocIds = getAllDocumentIds(tenantId, kbId);

            Map<String, Object> sumParams = new LinkedHashMap<>();
            sumParams.put("tenant_id", tenantId);
            sumParams.put("kb_id", kbId);
            sumParams.put("document_id", documentId);
            sumParams.put("connstr", connstr);
            sumParams.put("database_id", task.getDatabaseId());
            sumParams.put("all_document_ids", allDocIds);
            enqueueTask(task.getDatabaseId(), KbWriteTaskType.DOCUMENT_SUMMARIZE, sumParams);
            log.info("Enqueued DOCUMENT_SUMMARIZE for doc {} in KB {}", documentId, kbId);
        } catch (Exception e) {
            log.warn("Failed to enqueue DOCUMENT_SUMMARIZE after parse: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueueSummarizeAfterBatchParse(KbWriteTaskEntity task) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            String tenantId = (String) params.get("tenant_id");
            String kbId = (String) params.get("kb_id");
            String connstr = (String) params.get("connstr");
            List<Map<String, Object>> docs = (List<Map<String, Object>>) params.get("documents");
            List<String> allDocIds = getAllDocumentIds(tenantId, kbId);

            for (Map<String, Object> doc : docs) {
                String docId = (String) doc.get("document_id");
                Map<String, Object> sumParams = new LinkedHashMap<>();
                sumParams.put("tenant_id", tenantId);
                sumParams.put("kb_id", kbId);
                sumParams.put("document_id", docId);
                sumParams.put("connstr", connstr);
                sumParams.put("database_id", task.getDatabaseId());
                sumParams.put("all_document_ids", allDocIds);
                enqueueTask(task.getDatabaseId(), KbWriteTaskType.DOCUMENT_SUMMARIZE, sumParams);
            }
            log.info("Enqueued {} DOCUMENT_SUMMARIZE tasks for batch in KB {}", docs.size(), kbId);
        } catch (Exception e) {
            log.warn("Failed to enqueue DOCUMENT_SUMMARIZE after batch parse: {}", e.getMessage());
        }
    }

    private List<String> getAllDocumentIds(String tenantId, String kbId) {
        return documentRepository.findAllByKbId(kbId)
                .stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY)
                .map(DocumentEntity::getId)
                .toList();
    }
```

- [ ] **Step 6: Add enqueueTask helper if not already present**

Check if a generic `enqueueTask(databaseId, type, params)` method exists. If not, add:

```java
    private void enqueueTask(String databaseId, KbWriteTaskType type, Map<String, Object> params) {
        KbWriteTaskEntity task = new KbWriteTaskEntity();
        task.setDatabaseId(databaseId);
        task.setType(type);
        task.setStatus(KbWriteTaskStatus.QUEUED);
        task.setMaxRetries(3);
        try {
            task.setParams(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize task params", e);
        }
        taskRepository.save(task);
        executor.submit(() -> drain(databaseId));
    }
```

- [ ] **Step 7: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "feat(kb): wire DOCUMENT_SUMMARIZE and KB_SUMMARIZE into task queue"
```

---

### Task 4: Adjust stuck task detection for summarize tasks

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java`

The existing stuck detection has a 30-minute timeout (for heavyweight job pods). Summarize tasks are lightweight (LLM call takes 5-30s), so they should use a shorter timeout.

- [ ] **Step 1: Add a shorter timeout for summarize tasks**

In `detectStuckTasks()` method (around line 110), add differentiated timeout:

```java
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void detectStuckTasks() {
        try {
            Instant heavyCutoff = Instant.now().minusSeconds(STUCK_TASK_TIMEOUT_MINUTES * 60);
            Instant lightCutoff = Instant.now().minusSeconds(5 * 60); // 5 minutes for lightweight
            List<KbWriteTaskEntity> stuck = taskRepository.findStuckRunningBefore(heavyCutoff);

            // Also find stuck lightweight summarize tasks with shorter timeout
            List<KbWriteTaskEntity> stuckLight = taskRepository.findStuckSummarizeBefore(lightCutoff);
            stuck.addAll(stuckLight);

            for (KbWriteTaskEntity task : stuck) {
                // ... existing retry/fail logic
            }
        } catch (Exception e) {
            log.warn("detectStuckTasks error: {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Add repository query for stuck summarize tasks**

In the task repository interface, add:

```java
    @Query("SELECT t FROM KbWriteTaskEntity t WHERE t.status = 'RUNNING' " +
           "AND t.type IN ('DOCUMENT_SUMMARIZE', 'KB_SUMMARIZE') " +
           "AND t.startedAt < ?1")
    List<KbWriteTaskEntity> findStuckSummarizeBefore(Instant cutoff);
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskRepository.java
git commit -m "feat(kb): add 5-minute stuck detection for summarize tasks"
```

---

### Task 5: Enhance search to include L1 summaries

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

- [ ] **Step 1: Add level filter to search SQL**

In the search method (around line 739), add `AND level IN (0, 1)` to both the semantic and fts CTEs. Also select `level` column:

```java
        String sql = "WITH semantic AS (" +
                "  SELECT id, content, metadata, level," +
                "         1 - (embedding <=> ?::vector) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE level IN (0, 1)" + docFilter +
                "  ORDER BY embedding <=> ?::vector" +
                "  LIMIT 20" +
                "), fts AS (" +
                "  SELECT id, content, metadata, level," +
                "         ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS score," +
                "         ROW_NUMBER() OVER (ORDER BY ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) DESC) AS rank" +
                "  FROM knowledge_chunks" +
                "  WHERE level IN (0, 1) AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)" + docFilter +
                "  LIMIT 20" +
                ") " +
                "SELECT COALESCE(s.id, f.id) AS id," +
                "       COALESCE(s.content, f.content) AS content," +
                "       COALESCE(s.metadata, f.metadata)::text AS metadata," +
                "       COALESCE(s.level, f.level) AS level," +
                "       COALESCE(1.0/(60+s.rank), 0) + COALESCE(1.0/(60+f.rank), 0) AS rrf_score" +
                " FROM semantic s FULL OUTER JOIN fts f ON s.id = f.id" +
                " ORDER BY rrf_score DESC" +
                " LIMIT ?";
```

- [ ] **Step 2: Include level in search results**

In the result mapping loop (after line 761 in the existing code), add level to the result map:

```java
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("content", rs.getString("content"));
                row.put("metadata", rs.getString("metadata"));
                row.put("level", rs.getInt("level"));
                row.put("score", rs.getDouble("rrf_score"));
                results.add(row);
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(kb): enhance search to include L1 document summaries via RRF"
```

---

### Task 6: Handle incremental updates — delete/re-parse triggers

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java`

When a document is deleted or re-parsed, its L1 summary must be cleaned up and the L2 KB summary regenerated.

- [ ] **Step 1: Delete L1 chunk when document is deleted**

In `KnowledgeService.deleteDocument()` (around line 620), the existing code deletes chunks via `DELETE_DOCUMENT_CHUNKS` task. The existing `executeDeleteDocumentChunks` in KbWriteQueue (around line 713) already deletes all chunks for the document:

```sql
DELETE FROM knowledge_chunks WHERE document_id = ? AND level = 0
```

Change this to delete ALL levels for the document:

```sql
DELETE FROM knowledge_chunks WHERE document_id = ?
```

This removes both L0 and L1 chunks in one shot.

- [ ] **Step 2: Regenerate L2 after document deletion**

After deleting a document's chunks, enqueue a KB_SUMMARIZE task to regenerate the L2 global summary. In `executeDeleteDocumentChunks`, after the delete:

```java
    private void executeDeleteDocumentChunks(Connection conn, Map<String, Object> params) {
        String docId = (String) params.get("document_id");
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM knowledge_chunks WHERE document_id = ?")) {
            ps.setString(1, docId);
            int deleted = ps.executeUpdate();
            log.info("Deleted {} chunks for document {}", deleted, docId);
        }
        // Regenerate KB summary after document removal
        if (params.containsKey("tenant_id") && params.containsKey("kb_id")) {
            Map<String, Object> kbParams = new LinkedHashMap<>();
            kbParams.put("tenant_id", params.get("tenant_id"));
            kbParams.put("kb_id", params.get("kb_id"));
            kbParams.put("connstr", params.get("connstr"));
            kbParams.put("database_id", params.get("database_id"));
            enqueueTask((String) params.get("database_id"), KbWriteTaskType.KB_SUMMARIZE, kbParams);
        }
    }
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "feat(kb): handle L1/L2 cleanup on document delete and re-parse"
```

---

### Task 7: Add admin API for manual resummarize and summary status

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` (or AdminController if KB admin endpoints are there)

- [ ] **Step 1: Add resummarize endpoint**

```java
    @PostMapping("/admin/knowledge/{kbId}/documents/{docId}/resummarize")
    public ResponseEntity<?> resummarize(@PathVariable String kbId, @PathVariable String docId) {
        Tenant tenant = authService.getCurrentTenant();
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);
        DocumentEntity doc = documentRepository.findByIdAndKbId(docId, kbId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String connstr = knowledgeService.getComputeConnstr(tenant.getId(), kb);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", kbId);
        params.put("document_id", docId);
        params.put("connstr", connstr);
        params.put("database_id", kb.getDatabaseId());
        params.put("all_document_ids", knowledgeService.getAllReadyDocumentIds(tenant.getId(), kbId));
        kbWriteQueue.enqueueTask(kb.getDatabaseId(), KbWriteTaskType.DOCUMENT_SUMMARIZE, params);

        return ResponseEntity.ok(Map.of("status", "enqueued", "document_id", docId));
    }
```

- [ ] **Step 2: Make enqueueTask public in KbWriteQueue**

Change `enqueueTask` from `private` to `public`.

- [ ] **Step 3: Add getAllReadyDocumentIds helper to KnowledgeService**

```java
    public List<String> getAllReadyDocumentIds(String tenantId, String kbId) {
        return documentRepository.findAllByKbId(kbId)
                .stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY)
                .map(DocumentEntity::getId)
                .toList();
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "feat(kb): add admin resummarize API endpoint"
```

---

### Task 8: Expose KB summary in knowledge_list and KB detail API

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`
- Modify: `dbay-mcp/src/dbay_mcp/server.py`

- [ ] **Step 1: Add summary field to KB detail response**

In the KB detail endpoint (where KB metadata is returned), fetch the L2 summary chunk and include it. In `KnowledgeController.java`, in the KB detail method, add:

```java
        // Fetch KB summary (level=2) if available
        String connstr = knowledgeService.getComputeConnstr(tenant.getId(), kb);
        String kbSummary = knowledgeService.getKbSummary(connstr);
        map.put("summary", kbSummary);
```

Add `getKbSummary` to `KnowledgeService`:

```java
    public String getKbSummary(String connstr) {
        String jdbcUrl = dbHelper.connstrToJdbc(connstr);
        String pgUser = dbHelper.extractUser(connstr);
        String pgPass = dbHelper.extractPassword(connstr);
        String sql = "SELECT content FROM knowledge_chunks " +
                "WHERE document_id = '__kb_summary__' AND level = 2 LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("content");
            return null;
        } catch (Exception e) {
            log.warn("Failed to get KB summary: {}", e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 2: Update MCP knowledge_list to show summary**

In `dbay-mcp/src/dbay_mcp/server.py`, update the `knowledge_list` function (around line 148) to include the summary field if present:

```python
@mcp.tool(description=_desc("knowledge_list"))
def knowledge_list() -> str:
    """List all knowledge bases with their id, name, type, status, and document count."""
    bases = _api("GET", "/knowledge/bases")
    lines = []
    for kb in bases:
        line = (
            f"- {kb['name']} (id={kb['id']}, type={kb.get('type','DOCUMENT')}, "
            f"model={kb.get('embedding_model','?')}, "
            f"status={kb.get('status','?')}, docs={kb.get('document_count',0)})"
        )
        summary = kb.get('summary')
        if summary:
            line += f"\n  Summary: {summary[:200]}"
        lines.append(line)
    return "\n".join(lines) if lines else "No knowledge bases found."
```

- [ ] **Step 3: Update MCP knowledge_search to show level in results**

In the `knowledge_search` function (around line 162), include the level indicator:

```python
    for i, r in enumerate(results, 1):
        meta = r.get("metadata", {})
        level = r.get("level", 0)
        level_tag = " [document summary]" if level == 1 else ""
        # ... existing metadata parsing ...
        parts.append(f"### Result {i} (score={score:.3f}, doc={doc_id}{level_tag})\n{content}")
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        dbay-mcp/src/dbay_mcp/server.py
git commit -m "feat(kb): expose KB summary in list/detail API and MCP tools"
```

---

### Task 9: Console — search results show L1 "文档摘要" tag

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts:44-54`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue:226-232`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeSearch.vue:35-44`

- [ ] **Step 1: Add `level` to SearchResult interface**

In `lakeon-console/src/api/knowledge.ts`, add `level` to the SearchResult interface:

```typescript
export interface SearchResult {
  content: string
  score: number
  level?: number
  metadata: {
    filename?: string
    section?: string
    document_id?: string
    kb_id?: string
    kb_name?: string
  }
}
```

- [ ] **Step 2: Show "文档摘要" tag in KnowledgeBaseDetail search results**

In `KnowledgeBaseDetail.vue`, in the chat-style search result card (around line 228), add a level tag before the existing metadata:

```html
                <div style="margin-top: 8px; font-size: 12px; color: #999; display: flex; gap: 12px; flex-wrap: wrap;">
                  <span v-if="r.level === 1" style="background: #eff6ff; color: #2563eb; padding: 1px 8px; border-radius: 3px; font-weight: 500;">文档摘要</span>
                  <span>来源: {{ r.metadata?.filename }}</span>
                  <span v-if="r.metadata?.section">章节: {{ r.metadata.section }}</span>
                  <span>得分: {{ r.score?.toFixed(3) }}</span>
                </div>
```

- [ ] **Step 3: Show "文档摘要" tag in KnowledgeSearch page results**

In `KnowledgeSearch.vue`, in the result card (around line 38), add the same level tag:

```html
        <div style="margin-top: 10px; font-size: 12px; color: #999; display: flex; gap: 16px; flex-wrap: wrap;">
          <span v-if="r.level === 1" style="background: #eff6ff; color: #2563eb; padding: 1px 8px; border-radius: 3px; font-weight: 500;">文档摘要</span>
          <span v-if="r.metadata?.kb_name" style="color: #1890ff;">{{ r.metadata.kb_name }}</span>
          <span>来源: {{ r.metadata?.filename }}</span>
          <span v-if="r.metadata?.section">章节: {{ r.metadata.section }}</span>
          <span>得分: {{ r.score?.toFixed(3) }}</span>
        </div>
```

- [ ] **Step 4: Verify TypeScript**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue \
        lakeon-console/src/views/knowledge/KnowledgeSearch.vue
git commit -m "feat(console): show document summary tag in search results"
```

---

### Task 10: Console — KB detail overview shows L2 global summary

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue:31-49`

The KB detail API will now return a `summary` field. Display it in the overview tab.

- [ ] **Step 1: Add summary card to overview tab**

In `KnowledgeBaseDetail.vue`, after the existing overview section-card (around line 49, after the `</div>` that closes the overview grid), add:

```html
      <!-- KB Summary (L2) -->
      <div v-if="kb?.summary" class="section-card" style="max-width: 600px; margin-top: 16px;">
        <div class="section-header">知识库概览</div>
        <div style="padding: 16px; font-size: 14px; line-height: 1.8; color: #333; white-space: pre-wrap;">{{ kb.summary }}</div>
      </div>
```

- [ ] **Step 2: Add `summary` to KB type if needed**

Check if the `KnowledgeBase` interface in `api/knowledge.ts` needs updating. Add `summary?` field:

```typescript
export interface KnowledgeBase {
  id: string
  name: string
  description?: string
  type?: string
  status?: string
  document_count?: number
  embedding_model?: string
  database_id?: string
  created_at?: string
  summary?: string
}
```

- [ ] **Step 3: Verify TypeScript**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue \
        lakeon-console/src/api/knowledge.ts
git commit -m "feat(console): show KB global summary in overview tab"
```

---

### Task 11: Console — document detail shows L1 summary in dedicated tab

**Files:**
- Modify: `lakeon-console/src/views/knowledge/DocumentDetail.vue`
- Modify: `lakeon-console/src/api/knowledge.ts`

Add a "摘要" tab to the document detail page that displays the L1 summary for this document.

- [ ] **Step 1: Add API function to get document summary**

In `lakeon-console/src/api/knowledge.ts`, add:

```typescript
export function getDocumentSummary(kbId: string, docId: string) {
  return api.get<{ content: string | null }>(`/knowledge/${kbId}/documents/${docId}/summary`)
}
```

- [ ] **Step 2: Add summary endpoint in API (backend)**

In `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`, add:

```java
    @GetMapping("/{kbId}/documents/{docId}/summary")
    public ResponseEntity<?> getDocumentSummary(@PathVariable String kbId, @PathVariable String docId) {
        Tenant tenant = authService.getCurrentTenant();
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);
        String connstr = knowledgeService.getComputeConnstr(tenant.getId(), kb);
        String summary = knowledgeService.getDocumentSummary(connstr, docId);
        return ResponseEntity.ok(Map.of("content", summary != null ? summary : ""));
    }
```

Add `getDocumentSummary` to `KnowledgeService`:

```java
    public String getDocumentSummary(String connstr, String docId) {
        String jdbcUrl = dbHelper.connstrToJdbc(connstr);
        String pgUser = dbHelper.extractUser(connstr);
        String pgPass = dbHelper.extractPassword(connstr);
        String sql = "SELECT content FROM knowledge_chunks " +
                "WHERE document_id = ? AND level = 1 LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("content");
            return null;
        } catch (Exception e) {
            log.warn("Failed to get document summary for doc {}: {}", docId, e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 3: Add "摘要" tab to DocumentDetail.vue**

Read `DocumentDetail.vue` to find the tab structure, then add a "摘要" tab that loads and displays the L1 summary. The tab should show:
- Summary text if available
- "摘要生成中..." if not yet generated
- A "重新生成" button that calls the resummarize admin API

The exact code depends on DocumentDetail.vue's tab structure — read the file and follow the existing pattern.

- [ ] **Step 4: Verify TypeScript**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 5: Verify Java compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/DocumentDetail.vue \
        lakeon-console/src/api/knowledge.ts \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(kb): add document summary tab in document detail page"
```

---

### Task 12: E2E test — upload document and verify L1/L2 generation

**Files:**
- Create: `tests/test_kb_summary_e2e.py`

- [ ] **Step 1: Write E2E test**

```python
"""
E2E test: upload a document to a KB, wait for L1 summary generation,
verify search returns both L0 chunks and L1 summary.

Prerequisites:
- lakeon-api running locally on port 8080
- A knowledge base already created (set KB_ID env var)
- AI API key configured in API

Usage:
  KB_ID=<your-kb-id> python tests/test_kb_summary_e2e.py
"""
import os
import sys
import time
import requests

API = os.getenv("API_BASE", "http://localhost:8080")
KB_ID = os.getenv("KB_ID")
TOKEN = os.getenv("API_TOKEN", "")

if not KB_ID:
    print("ERROR: Set KB_ID env var")
    sys.exit(1)

headers = {"Authorization": f"Bearer {TOKEN}"} if TOKEN else {}


def test_summary_generation():
    # 1. Upload a small test document
    print("1. Uploading test document...")
    upload_resp = requests.post(
        f"{API}/api/v1/knowledge/{KB_ID}/documents/upload",
        headers=headers,
        json={"filename": "test-summary.md", "tags": ["test"]},
    )
    assert upload_resp.status_code == 200, f"Upload failed: {upload_resp.text}"
    doc_id = upload_resp.json()["document_id"]
    presigned_url = upload_resp.json()["upload_url"]

    # Upload content via presigned URL
    test_content = """# 人工智能简介

人工智能（AI）是计算机科学的一个分支。它致力��创建能够执行通常需要人类智能的任务的系统。

## 机器学习
机器学习是AI的核心方法，通过数据训练模型来做出预测。

## 深度学习
深度学习使用多层神经网络处理复杂模式，在图像识别和自然语言处理中表现突出。

## 应用领域
AI广泛应用于医疗诊断、自动驾驶、推荐系统等领域。
"""
    requests.put(presigned_url, data=test_content.encode("utf-8"))

    # Trigger parse
    requests.post(
        f"{API}/api/v1/knowledge/{KB_ID}/documents/{doc_id}/parse",
        headers=headers,
    )

    # 2. Wait for processing + summarization (parse ~10s + summarize ~5s)
    print("2. Waiting for document processing and summarization...")
    for i in range(30):
        time.sleep(5)
        doc_resp = requests.get(
            f"{API}/api/v1/knowledge/{KB_ID}/documents/{doc_id}",
            headers=headers,
        )
        status = doc_resp.json().get("status")
        print(f"   [{i*5}s] status={status}")
        if status == "READY":
            break
    else:
        print("FAIL: Document did not reach READY status in 150s")
        sys.exit(1)

    # 3. Wait extra time for async summarization
    print("3. Waiting for async summarization...")
    time.sleep(15)

    # 4. Search for summary-level content
    print("4. Searching for document summary...")
    search_resp = requests.post(
        f"{API}/api/v1/knowledge/search",
        headers=headers,
        json={"kb_id": KB_ID, "query": "这篇文档讲了什么", "top_k": 10},
    )
    assert search_resp.status_code == 200, f"Search failed: {search_resp.text}"
    results = search_resp.json().get("results", [])

    # Check that at least one result is level=1
    levels = [r.get("level", 0) for r in results]
    print(f"   Result levels: {levels}")
    assert 1 in levels, "No L1 summary found in search results"

    l1_result = next(r for r in results if r.get("level") == 1)
    print(f"   L1 summary content: {l1_result['content'][:200]}...")

    # 5. Check KB summary (level=2)
    print("5. Checking KB-level summary...")
    kb_resp = requests.get(
        f"{API}/api/v1/knowledge/{KB_ID}",
        headers=headers,
    )
    kb_summary = kb_resp.json().get("summary")
    if kb_summary:
        print(f"   KB summary: {kb_summary[:200]}...")
    else:
        print("   KB summary not yet generated (may need more documents)")

    print("\nPASS: Document summary generated and searchable")


if __name__ == "__main__":
    test_summary_generation()
```

- [ ] **Step 2: Commit**

```bash
git add tests/test_kb_summary_e2e.py
git commit -m "test(kb): add E2E test for document summary generation and search"
```

---

### Task 13: Final integration test and cleanup

- [ ] **Step 1: Run full compilation**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run existing tests**

Run: `cd lakeon-api && ./mvnw test -q`
Expected: All existing tests pass (no regressions)

- [ ] **Step 3: Build Docker image for deployment**

Run: `cd lakeon-api && docker build -t lakeon-api:summary-test .`
Expected: Image builds successfully

- [ ] **Step 4: Commit any final fixes**

```bash
git add -A
git commit -m "feat(kb): finalize hierarchical summary implementation"
```
