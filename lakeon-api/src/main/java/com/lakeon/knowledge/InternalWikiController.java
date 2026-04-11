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

    public InternalWikiController(WikiToolService toolService,
                                  WikiRunLogRepository runLogRepository) {
        this.toolService = toolService;
        this.runLogRepository = runLogRepository;
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
