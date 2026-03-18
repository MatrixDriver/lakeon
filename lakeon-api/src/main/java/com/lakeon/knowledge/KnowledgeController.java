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
        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(tenant, name, description);
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
        int topK = body.containsKey("top_k") ? ((Number) body.get("top_k")).intValue() : 5;
        List<String> documentIds = (List<String>) body.get("document_ids");
        List<String> tags = (List<String>) body.get("tags");

        if (kbId == null || kbId.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("kb_id is required");
        }
        if (query == null || query.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("query is required");
        }

        boolean rerank = body.containsKey("rerank") ? (Boolean) body.get("rerank") : false;

        List<Map<String, Object>> results = knowledgeService.search(tenant.getId(), kbId, query, topK, documentIds, tags, rerank);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("count", results.size());
        return response;
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
        map.put("database_id", kb.getDatabaseId());
        map.put("status", kb.getStatus() != null ? kb.getStatus().name() : null);
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
