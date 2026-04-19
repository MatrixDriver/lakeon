package com.lakeon.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoints called ONLY by lakeon-wiki-agent.
 * Authenticated by ApiKeyFilter using lakeon.wiki.agent.internal-token.
 *
 * Each tool endpoint is a thin dispatch to {@link WikiToolService}. Two non-tool
 * endpoints accept the agent's run-result run log and a generic completion
 * callback.
 */
@RestController
@RequestMapping("/api/v1/internal/wiki")
public class InternalWikiController {
    private static final Logger log = LoggerFactory.getLogger(InternalWikiController.class);

    private final WikiToolService toolService;
    private final WikiRunLogRepository runLogRepository;
    private final DocumentRepository documentRepository;

    public InternalWikiController(WikiToolService toolService,
                                  WikiRunLogRepository runLogRepository,
                                  DocumentRepository documentRepository) {
        this.toolService = toolService;
        this.runLogRepository = runLogRepository;
        this.documentRepository = documentRepository;
    }

    // ── Read tools ─────────────────────────────────────────────

    @PostMapping("/tool/list_pages")
    public List<Map<String, Object>> listPages(@RequestBody Map<String, String> body) {
        return toolService.listPages(body.get("tenant_id"), body.get("kb_id"));
    }

    @PostMapping("/tool/read_page")
    public Map<String, Object> readPage(@RequestBody Map<String, String> body) {
        return toolService.readPage(body.get("tenant_id"), body.get("kb_id"), body.get("title"));
    }

    @PostMapping("/tool/search_pages")
    public List<Map<String, Object>> searchPages(@RequestBody Map<String, Object> body) {
        int topK = body.containsKey("top_k") ? ((Number) body.get("top_k")).intValue() : 5;
        return toolService.searchPages(
                (String) body.get("tenant_id"),
                (String) body.get("kb_id"),
                (String) body.get("query"),
                topK);
    }

    @PostMapping("/tool/read_source")
    public Map<String, Object> readSource(@RequestBody Map<String, String> body) {
        return toolService.readSource(
                body.get("tenant_id"), body.get("kb_id"), body.get("document_id"));
    }

    @PostMapping("/tool/get_schema")
    public Map<String, String> getSchema(@RequestBody Map<String, String> body) {
        String schema = toolService.getSchema(body.get("tenant_id"), body.get("kb_id"));
        return Map.of("schema", schema);
    }

    // ── Write tools ────────────────────────────────────────────

    @PostMapping("/tool/create_page")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPage(@RequestBody Map<String, Object> body) {
        return toolService.createPage(
                (String) body.get("tenant_id"),
                (String) body.get("kb_id"),
                (String) body.get("title"),
                (String) body.get("content"),
                (List<String>) body.getOrDefault("tags", List.of()));
    }

    @PostMapping("/tool/update_page")
    public Map<String, Object> updatePage(@RequestBody Map<String, String> body) {
        return toolService.updatePage(
                body.get("tenant_id"), body.get("kb_id"),
                body.get("title"), body.get("old_text"), body.get("new_text"));
    }

    @PostMapping("/tool/append_page")
    public Map<String, Object> appendPage(@RequestBody Map<String, String> body) {
        return toolService.appendPage(
                body.get("tenant_id"), body.get("kb_id"),
                body.get("title"), body.get("content"));
    }

    @PostMapping("/tool/delete_page")
    public Map<String, Object> deletePage(@RequestBody Map<String, String> body) {
        return toolService.deletePage(body.get("tenant_id"), body.get("kb_id"), body.get("title"));
    }

    @PostMapping("/tool/log_note")
    public Map<String, Object> logNote(@RequestBody Map<String, String> body) {
        return toolService.logNote(
                body.get("tenant_id"), body.get("kb_id"), body.get("message"));
    }

    // ── Run log & callback ─────────────────────────────────────

    @PostMapping("/runlog")
    public ResponseEntity<Void> writeRunLog(@RequestBody WikiRunLogRequest req) {
        try {
            WikiRunLogEntity e = new WikiRunLogEntity();
            // Match WikiService.writeRunLog id style: 32-char UUID hex.
            e.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
            e.setTenantId(req.tenantId);
            e.setKbId(req.kbId);
            e.setRunId(req.runId);
            e.setRunType(req.runType);
            e.setTriggerDoc(clampTriggerDoc(req.triggerDoc));
            e.setPagesCreated(req.pagesCreated);
            e.setPagesUpdated(req.pagesUpdated);
            e.setPagesDeleted(req.pagesDeleted);
            e.setDurationMs(req.durationMs);
            e.setStatus(req.status);
            e.setErrorMessage(clampErrorMessage(req.errorMessage));
            e.setToolCallsCount(req.toolCallsCount);
            e.setTokenCount(req.tokenCount);
            e.setSource(req.source);
            e.setCreatedAt(Instant.now());
            runLogRepository.save(e);
            log.info("Wiki agent run log recorded: run_id={} kb={} status={} created={} updated={} deleted={}",
                    req.runId, req.kbId, req.status,
                    req.pagesCreated, req.pagesUpdated, req.pagesDeleted);

            // Update trigger document's wiki_processed_at when the ingest actually
            // touched wiki pages — even if the agent hit max_rounds without calling
            // done(). Some LLMs (e.g. DeepSeek) do the work but skip the final done
            // tool call, which historically produced status=error despite real page
            // changes. Treat any run with positive page deltas as effectively done.
            boolean statusOk = "success".equals(req.status);
            boolean touchedPages = (req.pagesCreated + req.pagesUpdated + req.pagesDeleted) > 0;
            if ((statusOk || touchedPages) && "ingest".equals(req.runType)
                    && req.triggerDoc != null && !req.triggerDoc.isBlank()) {
                updateWikiProcessedAt(req.tenantId, req.triggerDoc);
                // Backfill other docs in same KB that have run logs but missing wiki_processed_at
                backfillWikiProcessedAt(req.tenantId, req.kbId);
            }
        } catch (Exception e) {
            log.warn("Failed to persist wiki run log for run_id={}: {}", req.runId, e.getMessage());
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/callback/{taskId}")
    public ResponseEntity<Void> callback(@PathVariable String taskId,
                                         @RequestBody(required = false) Map<String, Object> body) {
        // For Phase 1: just log. Later phases may poll task state via this signal.
        log.info("Wiki agent callback: task={} body={}", taskId, body);
        return ResponseEntity.accepted().build();
    }

    private static String clampErrorMessage(String message) {
        if (message == null) return null;
        return message.length() > 1024 ? message.substring(0, 1021) + "..." : message;
    }

    private static String clampTriggerDoc(String trigger) {
        if (trigger == null) return null;
        return trigger.length() > 256 ? trigger.substring(0, 253) + "..." : trigger;
    }

    /**
     * Mark the source document's wiki_processed_at metadata so the frontend shows "Wiki 已生成".
     */
    private void updateWikiProcessedAt(String tenantId, String documentId) {
        try {
            var optDoc = documentRepository.findByIdAndTenantId(documentId, tenantId);
            if (optDoc.isEmpty()) {
                log.debug("updateWikiProcessedAt: doc {} not found for tenant {}", documentId, tenantId);
                return;
            }
            var doc = optDoc.get();
            setWikiProcessedAtIfMissing(doc);
        } catch (Exception e) {
            log.warn("Failed to set wiki_processed_at for doc {}: {}", documentId, e.getMessage());
        }
    }

    /**
     * Backfill wiki_processed_at for documents in a KB that have successful run logs
     * but are missing the metadata field (from before the fix was deployed).
     */
    private void backfillWikiProcessedAt(String tenantId, String kbId) {
        try {
            var logs = runLogRepository.findByKbIdOrderByCreatedAtDesc(kbId,
                    org.springframework.data.domain.Pageable.unpaged());
            var processedDocIds = logs.stream()
                    .filter(l -> "success".equals(l.getStatus()) && "ingest".equals(l.getRunType())
                            && l.getTriggerDoc() != null && !l.getTriggerDoc().isBlank())
                    .map(WikiRunLogEntity::getTriggerDoc)
                    .collect(java.util.stream.Collectors.toSet());
            int count = 0;
            for (String docId : processedDocIds) {
                var optDoc = documentRepository.findByIdAndTenantId(docId, tenantId);
                if (optDoc.isPresent()) {
                    var doc = optDoc.get();
                    var meta = doc.getMetadata();
                    if (meta == null || !meta.containsKey("wiki_processed_at")) {
                        setWikiProcessedAtIfMissing(doc);
                        count++;
                    }
                }
            }
            if (count > 0) {
                log.info("Backfilled wiki_processed_at for {} docs in KB {}", count, kbId);
            }
        } catch (Exception e) {
            log.warn("Failed to backfill wiki_processed_at for KB {}: {}", kbId, e.getMessage());
        }
    }

    private void setWikiProcessedAtIfMissing(DocumentEntity doc) {
        var meta = doc.getMetadata();
        if (meta == null) {
            meta = new java.util.LinkedHashMap<>();
        }
        if (!meta.containsKey("wiki_processed_at")) {
            meta.put("wiki_processed_at", Instant.now().toString());
            doc.setMetadata(meta);
            documentRepository.save(doc);
            log.info("Set wiki_processed_at for doc {}", doc.getId());
        }
    }

    /**
     * Request DTO for /runlog. Uses public fields (Jackson-friendly) since this is
     * an internal service-to-service API, not a public one.
     */
    public static class WikiRunLogRequest {
        public String tenantId;
        public String kbId;
        public String runId;
        public String runType;
        public String triggerDoc;
        public int pagesCreated;
        public int pagesUpdated;
        public int pagesDeleted;
        public long durationMs;
        public String status;
        public String errorMessage;
        public int toolCallsCount;
        public long tokenCount;
        public String source;
    }
}
