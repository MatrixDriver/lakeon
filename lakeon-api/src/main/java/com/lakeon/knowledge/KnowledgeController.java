package com.lakeon.knowledge;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public KnowledgeController(KnowledgeService knowledgeService,
                               DocumentRepository documentRepository,
                               KbWriteQueue kbWriteQueue,
                               KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeService = knowledgeService;
        this.documentRepository = documentRepository;
        this.kbWriteQueue = kbWriteQueue;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
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
                .map(this::toKbResponse)
                .toList();
    }

    @GetMapping("/bases/{id}")
    public Map<String, Object> getKnowledgeBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), id);
        Map<String, Object> response = toKbResponse(kb);
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
        long total = 0, processing = 0, ready = 0, failed = 0, pending = 0;
        for (Object[] row : rows) {
            String s = (String) row[0];
            long count = ((Number) row[1]).longValue();
            total += count;
            switch (s) {
                case "PROCESSING" -> processing = count;
                case "READY" -> ready = count;
                case "FAILED" -> failed = count;
                case "PENDING" -> pending = count;
            }
        }
        return Map.of("total", total, "processing", processing, "ready", ready, "failed", failed, "pending", pending);
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

        // Cross-KB search: when kb_id is null or empty, search all DOCUMENT KBs
        if (kbId == null || kbId.isBlank()) {
            List<KnowledgeBaseEntity> allKbs = knowledgeService.listKnowledgeBases(tenant.getId());
            List<Map<String, Object>> allResults = new java.util.ArrayList<>();

            for (KnowledgeBaseEntity kb : allKbs) {
                if (kb.getType() != KnowledgeBaseType.DOCUMENT || kb.getStatus() != KnowledgeBaseStatus.READY) continue;
                try {
                    Map<String, Object> sr = knowledgeService.search(
                            tenant.getId(), kb.getId(), query, topK, null, tags, rerank, conversationHistory);
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
                tenant.getId(), kbId, query, topK, documentIds, tags, rerank, conversationHistory);

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

    // ── Helpers ──────────────────────────────────────────────────────

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
        map.put("error", kb.getError());
        map.put("created_at", kb.getCreatedAt() != null ? kb.getCreatedAt().toString() : null);
        map.put("updated_at", kb.getUpdatedAt() != null ? kb.getUpdatedAt().toString() : null);
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
