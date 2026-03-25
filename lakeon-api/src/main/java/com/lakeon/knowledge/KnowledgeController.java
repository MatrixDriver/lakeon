package com.lakeon.knowledge;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final DocumentRepository documentRepository;

    public KnowledgeController(KnowledgeService knowledgeService, DocumentRepository documentRepository) {
        this.knowledgeService = knowledgeService;
        this.documentRepository = documentRepository;
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
        return toKbResponse(kb);
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
        List<Map<String, Object>> documents = knowledgeService.batchGenerateUploadUrls(tenant, kbId, files);
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

    @PostMapping("/documents/{id}/process")
    public Map<String, Object> processDocument(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = knowledgeService.processDocument(tenant, id);
        return toDocumentResponse(doc);
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments(HttpServletRequest req,
                                                   @RequestParam(value = "kb_id", required = false) String kbId,
                                                   @RequestParam(value = "database_id", required = false) String databaseId) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.listDocuments(tenant.getId(), kbId, databaseId).stream()
                .map(this::toDocumentResponse)
                .toList();
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

        if (kbId == null || kbId.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("kb_id is required");
        }
        if (query == null || query.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("query is required");
        }

        // Check KB type and route accordingly
        KnowledgeBaseEntity kb = knowledgeService.getKnowledgeBase(tenant.getId(), kbId);
        if (kb.getType() == KnowledgeBaseType.TABLE) {
            String modelId = (String) body.getOrDefault("model", null);
            return knowledgeService.searchTable(tenant.getId(), kbId, query, modelId);
        }

        // DOCUMENT type: existing search flow
        int topK = body.containsKey("top_k") ? ((Number) body.get("top_k")).intValue() : 5;
        List<String> documentIds = (List<String>) body.get("document_ids");
        List<String> tags = (List<String>) body.get("tags");
        List<Map<String, String>> conversationHistory = body.containsKey("conversation_history")
                ? (List<Map<String, String>>) body.get("conversation_history") : null;

        boolean rerank = body.containsKey("rerank") ? (Boolean) body.get("rerank") : false;

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
        map.put("error", doc.getError());
        map.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        map.put("updated_at", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
        return map;
    }
}
