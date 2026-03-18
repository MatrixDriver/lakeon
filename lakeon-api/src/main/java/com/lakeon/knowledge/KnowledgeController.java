package com.lakeon.knowledge;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/upload-url")
    public Map<String, Object> getUploadUrl(HttpServletRequest req,
                                            @RequestParam("filename") String filename,
                                            @RequestParam("database_id") String databaseId) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.generateUploadUrl(tenant, databaseId, filename);
    }

    @PostMapping("/documents/{id}/process")
    public Map<String, Object> processDocument(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        DocumentEntity doc = knowledgeService.processDocument(tenant, id);
        return toDocumentResponse(doc);
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments(HttpServletRequest req,
                                                   @RequestParam(value = "database_id", required = false) String databaseId) {
        TenantEntity tenant = getTenant(req);
        return knowledgeService.listDocuments(tenant.getId(), databaseId).stream()
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

    @PostMapping("/search")
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String databaseId = (String) body.get("database_id");
        String query = (String) body.get("query");
        int topK = body.containsKey("top_k") ? ((Number) body.get("top_k")).intValue() : 5;
        List<String> documentIds = (List<String>) body.get("document_ids");

        if (databaseId == null || databaseId.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("database_id is required");
        }
        if (query == null || query.isBlank()) {
            throw new com.lakeon.service.exception.BadRequestException("query is required");
        }

        List<Map<String, Object>> results = knowledgeService.search(tenant.getId(), databaseId, query, topK, documentIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("count", results.size());
        return response;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toDocumentResponse(DocumentEntity doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.getId());
        map.put("tenant_id", doc.getTenantId());
        map.put("database_id", doc.getDatabaseId());
        map.put("filename", doc.getFilename());
        map.put("format", doc.getFormat());
        map.put("status", doc.getStatus() != null ? doc.getStatus().name() : null);
        map.put("obs_key", doc.getObsKey());
        map.put("size_bytes", doc.getSizeBytes());
        map.put("chunks_count", doc.getChunksCount());
        map.put("job_id", doc.getJobId());
        map.put("error", doc.getError());
        map.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        map.put("updated_at", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
        return map;
    }
}
