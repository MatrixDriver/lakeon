package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private final KnowledgeService knowledgeService;
    private final DocumentRepository documentRepository;
    private final KbWriteQueue kbWriteQueue;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final WikiService wikiService;
    private final WikiRunLogRepository wikiRunLogRepository;
    private final LakeonProperties lakeonProperties;
    private final ObjectMapper objectMapper;
    private final KbAccessService kbAccessService;
    private final KbShareRepository kbShareRepository;
    private final TenantRepository tenantRepository;
    private final ChunkService chunkService;
    private final com.lakeon.repository.DatabaseRepository databaseRepository;

    public KnowledgeController(KnowledgeService knowledgeService,
                               DocumentRepository documentRepository,
                               KbWriteQueue kbWriteQueue,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               WikiService wikiService,
                               WikiRunLogRepository wikiRunLogRepository,
                               LakeonProperties lakeonProperties,
                               ObjectMapper objectMapper,
                               KbAccessService kbAccessService,
                               KbShareRepository kbShareRepository,
                               TenantRepository tenantRepository,
                               ChunkService chunkService,
                               com.lakeon.repository.DatabaseRepository databaseRepository) {
        this.knowledgeService = knowledgeService;
        this.documentRepository = documentRepository;
        this.kbWriteQueue = kbWriteQueue;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.wikiService = wikiService;
        this.wikiRunLogRepository = wikiRunLogRepository;
        this.lakeonProperties = lakeonProperties;
        this.objectMapper = objectMapper;
        this.kbAccessService = kbAccessService;
        this.kbShareRepository = kbShareRepository;
        this.tenantRepository = tenantRepository;
        this.chunkService = chunkService;
        this.databaseRepository = databaseRepository;
    }

    // ── Knowledge Base endpoints ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping("/bases")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createKnowledgeBase(HttpServletRequest req,
                                                   @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        if (name == null || name.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("name is required");
        }

        // Parse type (default DOCUMENT)
        KnowledgeBaseType type = KnowledgeBaseType.DOCUMENT;
        String typeStr = (String) body.get("type");
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                type = KnowledgeBaseType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new com.lakeon.service.exception.BadRequestException("Invalid type: " + typeStr + ". Must be DOCUMENT or TABLE");
            }
        }

        String sourceDatabaseId = (String) body.get("source_database_id");
        List<String> tableNames = (List<String>) body.get("table_names");
        String embeddingModel = (String) body.get("embedding_model");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
                tenant, name, description, type, sourceDatabaseId, tableNames, embeddingModel);
        return toKbResponse(kb);
    }

    @GetMapping("/bases")
    public List<Map<String, Object>> listKnowledgeBases(HttpServletRequest req) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.listKnowledgeBases(tenant.getId()).stream()
                .map(kb -> {
                    Map<String, Object> m = toKbResponse(kb);
                    m.put("is_shared", !kb.getTenantId().equals(tenant.getId()));
                    if (!kb.getTenantId().equals(tenant.getId())) {
                        tenantRepository.findById(kb.getTenantId()).ifPresent(t -> m.put("owner_name", t.getName()));
                    }
                    return m;
                })
                .toList();
    }

    @GetMapping("/bases/{id}")
    public Map<String, Object> getKnowledgeBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), id);
        Map<String, Object> response = toKbResponse(kb);
        response.put("total_size_bytes", documentRepository.sumSizeBytesByKbId(id));
        // Include KB-level summary if available
        if (kb.getStatus() == KnowledgeBaseStatus.READY && kb.getType() == KnowledgeBaseType.DOCUMENT) {
            try {
                String connstr = knowledgeService.getComputeConnstr(tenant.getId(), id);
                String summary = knowledgeService.getKbSummary(connstr);
                response.put("summary", summary);
            } catch (Exception e) {
                log.debug("Could not fetch KB summary for {}: {}", id, e.getMessage());
            }
        }
        return response;
    }

    @DeleteMapping("/bases/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> deleteKnowledgeBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.deleteKnowledgeBase(tenant.getId(), id);
        return toKbResponse(kb);
    }

    // ── Share management endpoints ───────────────────────────────────

    @GetMapping("/bases/{kbId}/shares")
    public List<Map<String, Object>> listShares(HttpServletRequest req, @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbAdminOnly(kbId, tenant.getId());
        List<KbShareEntity> shares = kbShareRepository.findAllByKbId(kbId);
        return shares.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("kb_id", s.getKbId());
            m.put("tenant_id", s.getTenantId());
            m.put("role", s.getRole().name().toLowerCase());
            m.put("invited_by", s.getInvitedBy());
            m.put("created_at", s.getCreatedAt().toString());
            tenantRepository.findById(s.getTenantId()).ifPresent(t -> m.put("username", t.getUsername()));
            return m;
        }).toList();
    }

    @PostMapping("/bases/{kbId}/shares")
    public Map<String, Object> createShare(HttpServletRequest req, @PathVariable String kbId,
                                            @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbAdminOnly(kbId, tenant.getId());
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            throw new BadRequestException("username is required");
        }
        TenantEntity target = tenantRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
        if (target.getId().equals(tenant.getId())) {
            throw new BadRequestException("Cannot share with yourself");
        }
        if (kbShareRepository.findByKbIdAndTenantId(kbId, target.getId()).isPresent()) {
            throw new BadRequestException("Already shared with this user");
        }
        KbShareEntity share = new KbShareEntity();
        share.setKbId(kbId);
        share.setTenantId(target.getId());
        share.setRole(KbRole.MEMBER);
        share.setInvitedBy(tenant.getId());
        kbShareRepository.save(share);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", share.getId());
        result.put("kb_id", share.getKbId());
        result.put("tenant_id", share.getTenantId());
        result.put("username", target.getUsername());
        result.put("role", share.getRole().name().toLowerCase());
        result.put("created_at", share.getCreatedAt().toString());
        return result;
    }

    @DeleteMapping("/bases/{kbId}/shares/{shareId}")
    public ResponseEntity<?> deleteShare(HttpServletRequest req, @PathVariable String kbId,
                                          @PathVariable String shareId) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbAdminOnly(kbId, tenant.getId());
        KbShareEntity share = kbShareRepository.findById(shareId)
                .orElseThrow(() -> new NotFoundException("Share not found: " + shareId));
        if (!share.getKbId().equals(kbId)) {
            throw new NotFoundException("Share not found in this KB");
        }
        kbShareRepository.delete(share);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ── Document endpoints ───────────────────────────────────────────

    @GetMapping("/upload-url")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUploadUrl(HttpServletRequest req,
                                            @RequestParam("filename") String filename,
                                            @RequestParam("kb_id") String kbId,
                                            @RequestParam(value = "tags", required = false) List<String> tags) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.generateUploadUrl(tenant, kbId, filename, tags);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/batch-upload-urls")
    public Map<String, Object> batchUploadUrls(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        if (kbId == null || kbId.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("kb_id is required");
        }
        List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        Map<String, String> batchMetadata = (Map<String, String>) body.get("metadata");
        List<Map<String, Object>> documents = knowledgeService.batchGenerateUploadUrls(tenant, kbId, files, batchMetadata);
        return Map.of("documents", documents);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/batch-process")
    public Map<String, Object> batchProcess(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        List<String> documentIds = (List<String>) body.get("document_ids");
        if (documentIds == null || documentIds.isEmpty()) {
            throw new com.lakeon.service.exception.BadRequestException("document_ids is required");
        }
        return knowledgeService.batchProcessDocuments(tenant, documentIds);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/ingest")
    public Map<String, Object> ingest(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        List<String> documentIds = (List<String>) body.get("document_ids");
        Map<String, String> metadata = (Map<String, String>) body.get("metadata");
        return knowledgeService.ingestDocuments(tenant, documentIds, metadata);
    }

    @PostMapping("/documents/{id}/process")
    public Map<String, Object> processDocument(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = knowledgeService.processDocument(tenant, id);
        return toDocumentResponse(doc);
    }

    @GetMapping("/documents")
    public Map<String, Object> listDocuments(HttpServletRequest req,
                                             @RequestParam(value = "kb_id", required = false) String kbId,
                                             @RequestParam(value = "database_id", required = false) String databaseId,
                                             @RequestParam(value = "page", defaultValue = "1") int page,
                                             @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
                                             @RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "folder", required = false) String folder,
                                             @RequestParam(value = "sort_by", defaultValue = "upload_time") String sortBy,
                                             @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
        TenantEntity tenant = getTenant(req);
        KnowledgeService.DocumentPage result = knowledgeService.listDocumentsPaged(
            tenant.getId(), kbId, status, folder, sortBy, sortOrder, page, pageSize);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documents", result.documents().stream().map(this::toDocumentResponse).toList());
        response.put("total", result.total());
        response.put("page", result.page());
        response.put("page_size", result.pageSize());
        return response;
    }

    @GetMapping("/folders")
    public List<Map<String, Object>> listFolders(HttpServletRequest req,
                                                  @RequestParam("kb_id") String kbId,
                                                  @RequestParam(value = "parent", defaultValue = "") String parent) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.listFolders(tenant.getId(), kbId, parent).stream()
            .map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("path", f.path());
                m.put("document_count", f.documentCount());
                m.put("total_size", f.totalSize());
                return m;
            }).toList();
    }

    @GetMapping("/documents/stats")
    public Map<String, Object> documentStats(HttpServletRequest req,
                                             @RequestParam("kb_id") String kbId) {
        TenantEntity tenant = getTenant(req);
        List<Object[]> rows = documentRepository.countByStatusGrouped(tenant.getId(), kbId);
        long total = 0, processing = 0, ready = 0, failed = 0, pending = 0, wikiPending = 0;
        for (Object[] row : rows) {
            String s = (String) row[0];
            long count = ((Number) row[1]).longValue();
            total += count;
            switch (s) {
                case "PROCESSING" -> processing = count;
                case "READY" -> ready = count;
                case "FAILED" -> failed = count;
                case "PENDING" -> pending = count;
                case "WIKI_PENDING" -> wikiPending = count;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("processing", processing);
        stats.put("ready", ready);
        stats.put("failed", failed);
        stats.put("pending", pending);
        stats.put("wiki_pending", wikiPending);
        return stats;
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> getDocument(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = knowledgeService.getDocument(tenant.getId(), id);
        return toDocumentResponse(doc);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> deleteDocument(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = knowledgeService.deleteDocument(tenant.getId(), id);
        return toDocumentResponse(doc);
    }

    @DeleteMapping("/bases/{kbId}/documents")
    public Map<String, Object> clearAllDocuments(HttpServletRequest req, @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        int deleted = knowledgeService.clearAllDocuments(tenant.getId(), kbId);
        return Map.of("deleted", deleted);
    }

    @PutMapping("/documents/{id}/tags")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> setTags(HttpServletRequest req,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        List<String> tags = (List<String>) body.get("tags");
        if (tags == null) {
            throw new com.lakeon.service.exception.BadRequestException("tags is required");
        }
        if (tags.size() > 20) {
            throw new com.lakeon.service.exception.BadRequestException("Maximum 20 tags allowed");
        }
        for (String tag : tags) {
            if (tag == null || tag.length() > 50) {
                throw new com.lakeon.service.exception.BadRequestException("Each tag must be at most 50 characters");
            }
        }
        DocumentEntity doc = documentRepository.findByIdAndTenantId(id, tenant.getId())
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Document not found: " + id));
        doc.setTags(tags);
        documentRepository.save(doc);
        return ResponseEntity.ok(Map.of("tags", tags));
    }

    @SuppressWarnings("unchecked")
    @PatchMapping("/documents/{id}/metadata")
    public Map<String, Object> updateDocumentMetadata(HttpServletRequest req,
                                                       @PathVariable String id,
                                                       @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        Map<String, String> metadata = (Map<String, String>) body.get("metadata");
        if (metadata == null || metadata.isEmpty()) {
            throw new com.lakeon.service.exception.BadRequestException("metadata is required");
        }
        DocumentEntity doc = knowledgeService.updateDocumentMetadata(tenant.getId(), id, metadata);
        return toDocumentResponse(doc);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/documents/bulk-metadata")
    public Map<String, Object> bulkUpdateMetadata(HttpServletRequest req,
                                                   @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        List<String> documentIds = (List<String>) body.get("document_ids");
        Map<String, String> metadata = (Map<String, String>) body.get("metadata");
        if (documentIds == null || documentIds.isEmpty()) {
            throw new com.lakeon.service.exception.BadRequestException("document_ids is required");
        }
        if (metadata == null || metadata.isEmpty()) {
            throw new com.lakeon.service.exception.BadRequestException("metadata is required");
        }
        int count = knowledgeService.bulkUpdateDocumentMetadata(tenant.getId(), documentIds, metadata);
        return Map.of("updated", count);
    }

    @PostMapping("/search")
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String query = (String) body.get("query");

        if (query == null || query.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("query is required");
        }

        int topK = body.containsKey("top_k") ? ((Number) body.get("top_k")).intValue() : 5;
        boolean rerank = body.containsKey("rerank") ? (Boolean) body.get("rerank") : false;
        List<String> tags = (List<String>) body.get("tags");
        List<Map<String, String>> conversationHistory = body.containsKey("conversation_history")
                ? (List<Map<String, String>>) body.get("conversation_history") : null;
        Map<String, String> metadataFilter = (Map<String, String>) body.get("metadata");
        String folder = (String) body.get("folder");

        // Cross-KB search: when kb_id is null or empty, search all DOCUMENT KBs
        if (kbId == null || kbId.isBlank()) {
            List<KnowledgeBaseEntity> allKbs = knowledgeService.listKnowledgeBases(tenant.getId());
            List<Map<String, Object>> allResults = new java.util.ArrayList<>();

            for (KnowledgeBaseEntity kb : allKbs) {
                if (kb.getType() != KnowledgeBaseType.DOCUMENT || kb.getStatus() != KnowledgeBaseStatus.READY) continue;
                try {
                    Map<String, Object> sr = knowledgeService.search(
                            tenant.getId(), kb.getId(), query, topK, null, tags, metadataFilter, folder, rerank, conversationHistory);
                    List<Map<String, Object>> results = (List<Map<String, Object>>) sr.get("results");
                    // Tag each result with kb info
                    for (Map<String, Object> r : results) {
                        Map<String, Object> meta = (Map<String, Object>) r.getOrDefault("metadata", new LinkedHashMap<>());
                        meta.put("kb_id", kb.getId());
                        meta.put("kb_name", kb.getName());
                        r.put("metadata", meta);
                    }
                    allResults.addAll(results);
                } catch (Exception e) {
                    log.warn("Cross-KB search failed for kb {} ({}): {}", kb.getId(), kb.getName(), e.getMessage());
                }
            }

            // Sort by score descending, limit to topK
            allResults.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0)).doubleValue()));
            if (allResults.size() > topK) allResults = allResults.subList(0, topK);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", allResults);
            response.put("count", allResults.size());
            return response;
        }

        // Single KB search
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);
        if (kb.getType() == KnowledgeBaseType.TABLE) {
            String modelId = (String) body.getOrDefault("model", null);
            return knowledgeService.searchTable(tenant.getId(), kbId, query, modelId);
        }

        List<String> documentIds = (List<String>) body.get("document_ids");

        Map<String, Object> searchResult = knowledgeService.search(
                tenant.getId(), kbId, query, topK, documentIds, tags, metadataFilter, folder, rerank, conversationHistory);

        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResult.get("results");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("count", results.size());
        if (searchResult.containsKey("rewritten_query")) {
            response.put("rewritten_query", searchResult.get("rewritten_query"));
        }
        return response;
    }

    // ── Summary endpoints ────────────────────────────────────────────

    @GetMapping("/{kbId}/documents/{docId}/summary")
    public ResponseEntity<?> getDocumentSummary(HttpServletRequest req,
                                                @PathVariable String kbId,
                                                @PathVariable String docId) {
        TenantEntity tenant = getTenant(req);
        knowledgeService.getKnowledgeBase(tenant.getId(), kbId); // validate access
        String connstr = knowledgeService.getComputeConnstr(tenant.getId(), kbId);
        String summary = knowledgeService.getDocumentSummary(connstr, docId);
        return ResponseEntity.ok(Map.of("content", summary != null ? summary : ""));
    }

    // ── Admin endpoints ──────────────────────────────────────────────

    @PostMapping("/admin/bases/{kbId}/documents/{docId}/resummarize")
    public ResponseEntity<?> adminResumarize(HttpServletRequest req,
                                             @PathVariable String kbId,
                                             @PathVariable String docId) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenant.getId())
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Document not found: " + docId));

        String databaseId = kb.getDatabaseId();
        if (databaseId == null) {
            throw new com.lakeon.service.exception.BadRequestException("Knowledge base has no backing database");
        }

        List<String> allDocumentIds = knowledgeService.getAllReadyDocumentIds(tenant.getId(), kbId);
        String connstr = knowledgeService.getComputeConnstr(tenant.getId(), kbId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", kbId);
        params.put("document_id", docId);
        params.put("database_id", databaseId);
        params.put("connstr", connstr);
        params.put("all_document_ids", allDocumentIds);

        kbWriteQueue.enqueueTask(databaseId, KbWriteTaskType.DOCUMENT_SUMMARIZE, params);

        return ResponseEntity.ok(Map.of("status", "enqueued", "document_id", docId));
    }

    @GetMapping("/admin/wiki/config")
    public ResponseEntity<?> getWikiConfig(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        validateAdminToken(adminToken);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("ingest_prompt", wikiService.getIngestPrompt());
        config.put("curate_prompt", wikiService.getCuratePrompt());
        config.put("chat_routing_prompt", wikiService.getChatRoutingPrompt());
        config.put("chat_answer_prompt", wikiService.getChatAnswerPrompt());
        config.put("model", wikiService.getModel());
        config.put("base_url", lakeonProperties.getWiki() != null ? lakeonProperties.getWiki().getBaseUrl() : "");
        return ResponseEntity.ok(config);
    }

    @PutMapping("/admin/wiki/config")
    public ResponseEntity<?> updateWikiConfig(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestBody Map<String, String> body) {
        validateAdminToken(adminToken);
        if (body.containsKey("ingest_prompt")) {
            lakeonProperties.getWiki().setIngestPrompt(body.get("ingest_prompt"));
        }
        if (body.containsKey("curate_prompt")) {
            lakeonProperties.getWiki().setCuratePrompt(body.get("curate_prompt"));
        }
        if (body.containsKey("model")) {
            lakeonProperties.getWiki().setModel(body.get("model"));
        }
        if (body.containsKey("chat_routing_prompt")) {
            lakeonProperties.getWiki().setChatRoutingPrompt(body.get("chat_routing_prompt"));
        }
        if (body.containsKey("chat_answer_prompt")) {
            lakeonProperties.getWiki().setChatAnswerPrompt(body.get("chat_answer_prompt"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/admin/wiki/pages")
    public ResponseEntity<?> adminListWikiPages(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam("kb_id") String kbId,
            @RequestParam(value = "doc_type", required = false) String docType) {
        validateAdminToken(adminToken);
        // Find KB to get tenant_id
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) return ResponseEntity.notFound().build();
        String tenantId = kb.getTenantId();
        String type = docType != null ? docType : "wiki";
        List<DocumentEntity> pages = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, type);
        return ResponseEntity.ok(pages.stream().map(this::toDocumentResponse).toList());
    }

    @GetMapping("/admin/wiki/pages/{docId}/content")
    public ResponseEntity<?> adminGetWikiPageContent(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @PathVariable String docId,
            @RequestParam("kb_id") String kbId) {
        validateAdminToken(adminToken);
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) return ResponseEntity.notFound().build();
        String content = knowledgeService.getWikiPageContent(kb.getTenantId(), kbId, docId);
        return ResponseEntity.ok(Map.of("content", content != null ? content : ""));
    }

    @DeleteMapping("/admin/wiki/pages/{docId}")
    public ResponseEntity<?> adminDeleteWikiPage(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @PathVariable String docId,
            @RequestParam("kb_id") String kbId) {
        validateAdminToken(adminToken);
        documentRepository.deleteById(docId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

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

    @PostMapping("/admin/wiki/curate")
    public ResponseEntity<?> adminCurateWiki(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam("kb_id") String kbId) {
        validateAdminToken(adminToken);
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) return ResponseEntity.notFound().build();
        new Thread(() -> wikiService.runCurate(kb.getTenantId(), kbId)).start();
        return ResponseEntity.ok(Map.of("status", "curating"));
    }

    @PostMapping("/admin/wiki/test-connection")
    public ResponseEntity<?> adminTestConnection(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        validateAdminToken(adminToken);
        long start = System.currentTimeMillis();
        try {
            String response = wikiService.testConnection();
            long latency = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "latency_ms", latency,
                    "model", wikiService.getModel(),
                    "response", response
            ));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "latency_ms", latency,
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @GetMapping("/admin/wiki/run-logs")
    public ResponseEntity<?> getWikiRunLogs(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam(required = false) String kb_id,
            @RequestParam(defaultValue = "50") int limit) {
        validateAdminToken(adminToken);
        org.springframework.data.domain.Pageable pageable = PageRequest.of(0, Math.min(limit, 200),
                Sort.by("createdAt").descending());
        List<WikiRunLogEntity> logs;
        if (kb_id != null && !kb_id.isBlank()) {
            logs = wikiRunLogRepository.findByKbIdOrderByCreatedAtDesc(kb_id, pageable);
        } else {
            logs = wikiRunLogRepository.findAllRecent(pageable);
        }
        return ResponseEntity.ok(logs);
    }

    // ── TABLE KB endpoints ─────────────────────────────────────────

    @GetMapping("/bases/{id}/tables")
    public ResponseEntity<?> getTableInfo(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), id);
        if (kb.getType() != KnowledgeBaseType.TABLE) {
            throw new com.lakeon.service.exception.BadRequestException("Knowledge base is not TABLE type");
        }
        java.util.List<Map<String, Object>> schemas = knowledgeService.getTableSchema(tenant.getId(), id);
        return ResponseEntity.ok(schemas);
    }

    // ── Wiki endpoints ───────────────────────────────────────────────

    @GetMapping("/wiki/stats")
    public ResponseEntity<?> getWikiStats(HttpServletRequest req,
                                           @RequestParam("kb_id") String kbId) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);

        // Count wiki pages
        List<DocumentEntity> wikiDocs = knowledgeService.listWikiPages(tenant.getId(), kbId);
        int wikiPageCount = (int) wikiDocs.stream()
            .filter(d -> !"index.md".equals(d.getFilename()) && !"log.md".equals(d.getFilename()))
            .count();

        // Get graph stats
        Map<String, Object> graph = wikiService.getGraph(tenant.getId(), kbId);
        @SuppressWarnings("unchecked")
        int graphNodes = ((List<?>) graph.get("nodes")).size();
        @SuppressWarnings("unchecked")
        int graphEdges = ((List<?>) graph.get("edges")).size();

        // Count source docs (raw type)
        List<DocumentEntity> rawDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenant.getId(), kbId, "raw");
        int sourceDocCount = rawDocs.size();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("document_count", kb.getDocumentCount() != null ? kb.getDocumentCount() : 0);
        stats.put("source_doc_count", sourceDocCount);
        stats.put("wiki_page_count", wikiPageCount);
        stats.put("graph_nodes", graphNodes);
        stats.put("graph_edges", graphEdges);
        stats.put("chat_count", kb.getChatCount() != null ? kb.getChatCount() : 0);
        stats.put("settlement_count", kb.getSettlementCount() != null ? kb.getSettlementCount() : 0);
        stats.put("llm_tokens_used", kb.getLlmTokensUsed() != null ? kb.getLlmTokensUsed() : 0);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/wiki/pages")
    public ResponseEntity<?> listWikiPages(HttpServletRequest req,
                                           @RequestParam("kb_id") String kbId) {
        TenantEntity tenant = getTenant(req);
        List<DocumentEntity> pages = knowledgeService.listWikiPages(tenant.getId(), kbId);
        return ResponseEntity.ok(pages.stream().map(this::toDocumentResponse).toList());
    }

    @GetMapping("/wiki/pages/{docId}/content")
    public ResponseEntity<?> getWikiPageContent(HttpServletRequest req,
                                                @PathVariable String docId,
                                                @RequestParam("kb_id") String kbId) {
        TenantEntity tenant = getTenant(req);
        String content = knowledgeService.getWikiPageContent(tenant.getId(), kbId, docId);
        return ResponseEntity.ok(Map.of("content", content != null ? content : ""));
    }

    @GetMapping("/wiki/graph")
    public ResponseEntity<?> getWikiGraph(HttpServletRequest req,
                                          @RequestParam("kb_id") String kbId) {
        TenantEntity tenant = getTenant(req);
        Map<String, Object> graph = wikiService.getGraph(tenant.getId(), kbId);
        return ResponseEntity.ok(graph);
    }

    @PostMapping("/wiki/chat")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> wikiChat(HttpServletRequest req,
                                      @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("question is required");
        }
        List<Map<String, String>> history = (List<Map<String, String>>) body.get("history");
        Map<String, Object> result = wikiService.chat(tenant.getId(), kbId, question, history);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/wiki/chat/stream")
    @SuppressWarnings("unchecked")
    public SseEmitter wikiChatStream(HttpServletRequest req,
                                     @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("question is required");
        }
        List<Map<String, String>> history = (List<Map<String, String>>) body.getOrDefault("history", List.of());

        var emitter = new SseEmitter(120_000L);

        new Thread(() -> {
            try {
                wikiService.chatStream(tenant.getId(), kbId, question, history, event -> {
                    try {
                        emitter.send(SseEmitter.event().data(event));
                    } catch (Exception e) {
                        // client disconnected
                    }
                });
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(
                            "{\"type\":\"error\",\"message\":\"" +
                            e.getMessage().replace("\"", "\\\"") + "\"}"));
                } catch (Exception ignored) {}
                emitter.complete();
            }

            // Increment chat count
            try {
                KnowledgeBaseEntity kbEntity = knowledgeBaseRepository.findById(kbId).orElse(null);
                if (kbEntity != null) {
                    kbEntity.setChatCount((kbEntity.getChatCount() != null ? kbEntity.getChatCount() : 0) + 1);
                    knowledgeBaseRepository.save(kbEntity);
                }
            } catch (Exception ignored) {}
        }).start();

        return emitter;
    }

    @PostMapping("/wiki/save-response")
    public ResponseEntity<?> saveWikiResponse(HttpServletRequest req,
                                              @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        if (title == null || title.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("title is required");
        }
        if (content == null || content.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("content is required");
        }
        wikiService.saveResponse(tenant.getId(), kbId, title, content);
        return ResponseEntity.ok(Map.of("status", "saved", "title", title));
    }

    @PostMapping("/wiki/ingest-url")
    public ResponseEntity<?> ingestWikiUrl(HttpServletRequest req,
                                           @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String url = (String) body.get("url");
        String prefetchedTitle = (String) body.get("title");
        String prefetchedContent = (String) body.get("content");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        try {
            Map<String, Object> result = wikiService.ingestUrl(tenant.getId(), kbId, url,
                    prefetchedTitle, prefetchedContent);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            int status = 500;
            if (msg.startsWith("Failed to fetch URL") || msg.startsWith("HTTP ")) {
                status = 422;
            } else if (msg.contains("content too short")) {
                status = 422;
                msg = "页面内容过少，可能需要 JavaScript 渲染或登录访问";
            }
            return ResponseEntity.status(status).body(Map.of("error", Map.of("message", msg)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", Map.of("message", e.getMessage())));
        }
    }

    /**
     * Get wiki preview for a WIKI_PENDING document: summary + key points extracted from fulltext.
     */
    @GetMapping("/documents/{docId}/wiki-preview")
    public ResponseEntity<?> getWikiPreview(HttpServletRequest req, @PathVariable String docId) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_id", docId);
        result.put("filename", doc.getFilename());
        result.put("status", doc.getStatus().name());

        // Get fulltext from OBS
        String fulltext = chunkService.getFulltext(tenant.getId(), doc.getKbId(), docId);
        if (fulltext != null && !fulltext.isBlank()) {
            // Show a preview (first 500 chars)
            result.put("preview", fulltext.length() > 500 ? fulltext.substring(0, 500) + "..." : fulltext);
            List<String> keyPoints = extractKeyPoints(fulltext);
            result.put("key_points", keyPoints);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Confirm wiki generation for a WIKI_PENDING document, with optional user-edited key points.
     */
    @PostMapping("/documents/{docId}/wiki-confirm")
    public ResponseEntity<?> confirmWikiGeneration(HttpServletRequest req,
                                                    @PathVariable String docId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        if (doc.getStatus() != DocumentStatus.WIKI_PENDING) {
            return ResponseEntity.badRequest().body(Map.of("error", "Document is not pending wiki review"));
        }

        @SuppressWarnings("unchecked")
        List<String> keyPoints = body != null ? (List<String>) body.get("key_points") : null;

        // Mark as READY first
        doc.setStatus(DocumentStatus.READY);
        documentRepository.save(doc);

        // Trigger wiki ingest with key points
        try {
            String fulltext = chunkService.getFulltext(tenant.getId(), doc.getKbId(), docId);
            if (fulltext != null && !fulltext.isBlank()) {
                Map<String, Object> result = wikiService.ingestText(
                        tenant.getId(), doc.getKbId(), fulltext, keyPoints, doc.getFilename());
                // Mark wiki_processed_at (consistent with processIngest)
                doc.getMetadata().put("wiki_processed_at", java.time.Instant.now().toString());
                documentRepository.save(doc);
                return ResponseEntity.ok(result);
            }
            return ResponseEntity.ok(Map.of("status", "ok", "message", "No fulltext found, skipped wiki generation"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", Map.of("message", e.getMessage())));
        }
    }

    /**
     * Skip wiki generation for a WIKI_PENDING document.
     */
    @PostMapping("/documents/{docId}/wiki-skip")
    public ResponseEntity<?> skipWikiGeneration(HttpServletRequest req, @PathVariable String docId) {
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        doc.setStatus(DocumentStatus.READY);
        documentRepository.save(doc);
        return ResponseEntity.ok(Map.of("status", "skipped"));
    }

    private List<String> extractKeyPoints(String summary) {
        // Split summary into key points by lines/sentences
        List<String> points = new java.util.ArrayList<>();
        for (String line : summary.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // Skip markdown headers
            if (line.startsWith("#")) {
                continue;
            }
            // Remove bullet prefixes
            line = line.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+\\.\\s*", "");
            if (line.length() > 10) {
                points.add(line);
            }
        }
        // Limit to top 8 points
        if (points.size() > 8) {
            points = points.subList(0, 8);
        }
        return points;
    }

    @PostMapping("/wiki/ingest-text")
    public ResponseEntity<?> ingestWikiText(HttpServletRequest req,
                                            @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        String content = (String) body.get("content");
        @SuppressWarnings("unchecked")
        List<String> keyPoints = (List<String>) body.get("key_points");
        String source = (String) body.get("source");
        if (kbId == null || kbId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "kb_id is required"));
        }
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        try {
            Map<String, Object> result = wikiService.ingestText(tenant.getId(), kbId, content, keyPoints, source);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", Map.of("message", e.getMessage())));
        }
    }

    @PostMapping("/wiki/lint")
    public ResponseEntity<?> runWikiLint(HttpServletRequest req,
                                         @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        if (kbId == null || kbId.isBlank()) {
            throw new BadRequestException("kb_id is required");
        }
        Map<String, Object> result = wikiService.runLint(tenant.getId(), kbId);
        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/wiki/lint/fix")
    public ResponseEntity<?> fixWikiLint(HttpServletRequest req,
                                          @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String kbId = (String) body.get("kb_id");
        if (kbId == null || kbId.isBlank()) {
            throw new BadRequestException("kb_id is required");
        }
        List<String> categories = (List<String>) body.get("categories");
        List<Map<String, Object>> issues = (List<Map<String, Object>>) body.get("issues");
        if (issues == null || issues.isEmpty()) {
            throw new BadRequestException("issues is required");
        }
        Map<String, Object> result = wikiService.fixLintIssues(
                tenant.getId(), kbId, categories, issues);
        return ResponseEntity.ok(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void validateAdminToken(String token) {
        String expected = lakeonProperties.getAdmin() != null ? lakeonProperties.getAdmin().getToken() : null;
        if (token == null || !token.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toKbResponse(KnowledgeBaseEntity kb) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", kb.getId());
        map.put("tenant_id", kb.getTenantId());
        map.put("name", kb.getName());
        map.put("description", kb.getDescription());
        map.put("type", kb.getType() != null ? kb.getType().name() : "DOCUMENT");
        map.put("database_id", kb.getDatabaseId());
        map.put("source_database_id", kb.getSourceDatabaseId());
        map.put("table_names", kb.getTableNames());
        map.put("status", kb.getStatus() != null ? kb.getStatus().name() : null);
        map.put("embedding_model", kb.getEmbeddingModel());
        map.put("document_count", kb.getDocumentCount());
        map.put("chat_count", kb.getChatCount());
        map.put("settlement_count", kb.getSettlementCount());
        map.put("llm_tokens_used", kb.getLlmTokensUsed());
        map.put("error", kb.getError());
        map.put("created_at", kb.getCreatedAt() != null ? kb.getCreatedAt().toString() : null);
        map.put("updated_at", kb.getUpdatedAt() != null ? kb.getUpdatedAt().toString() : null);
        // Include associated database status (RUNNING/SUSPENDED) for UI display
        if (kb.getDatabaseId() != null) {
            databaseRepository.findById(kb.getDatabaseId()).ifPresent(db ->
                    map.put("database_status", db.getStatus().name()));
        }
        return map;
    }

    private Map<String, Object> toDocumentResponse(DocumentEntity doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.getId());
        map.put("tenant_id", doc.getTenantId());
        map.put("kb_id", doc.getKbId());
        map.put("database_id", doc.getDatabaseId());
        map.put("filename", doc.getFilename());
        map.put("format", doc.getFormat());
        map.put("type", doc.getDocType());
        map.put("status", doc.getStatus() != null ? doc.getStatus().name() : null);
        map.put("obs_key", doc.getObsKey());
        map.put("size_bytes", doc.getSizeBytes());
        map.put("chunks_count", doc.getChunksCount());
        map.put("job_id", doc.getJobId());
        map.put("tags", doc.getTags());
        map.put("folder", doc.getFolder());
        map.put("metadata", doc.getMetadata());
        map.put("error", doc.getError());
        map.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        map.put("updated_at", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
        // Include progress info for PROCESSING documents
        Map<String, Object> progress = knowledgeService.getDocumentProgress(doc);
        if (progress != null) {
            map.put("progress", progress.get("progress"));
            map.put("progress_message", progress.get("message"));
        }
        return map;
    }
}
