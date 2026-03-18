package com.lakeon.knowledge;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge/bases/{kbId}")
public class ChunkController {

    private final ChunkService chunkService;

    public ChunkController(ChunkService chunkService) {
        this.chunkService = chunkService;
    }

    @GetMapping("/documents/{docId}/chunks")
    public ResponseEntity<?> listChunks(HttpServletRequest request,
                                        @PathVariable String kbId,
                                        @PathVariable String docId,
                                        @RequestParam(defaultValue = "0") int level,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "50") int limit) {
        TenantEntity tenant = getTenant(request);
        limit = Math.min(limit, 200);
        try {
            Map<String, Object> result = chunkService.listChunks(tenant.getId(), kbId, docId, level, offset, limit);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/chunks")
    public ResponseEntity<?> listKbChunks(HttpServletRequest request,
                                          @PathVariable String kbId,
                                          @RequestParam(value = "doc_id", required = false) String docId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "50") int limit) {
        TenantEntity tenant = getTenant(request);
        limit = Math.min(limit, 200);
        try {
            Map<String, Object> result = chunkService.listKbChunks(tenant.getId(), kbId, docId, status, offset, limit);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/documents/{docId}/chunks/{chunkIndex}")
    public ResponseEntity<?> getChunk(HttpServletRequest request,
                                      @PathVariable String kbId,
                                      @PathVariable String docId,
                                      @PathVariable int chunkIndex) {
        TenantEntity tenant = getTenant(request);
        try {
            Map<String, Object> result = chunkService.getChunk(tenant.getId(), kbId, docId, chunkIndex);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/documents/{docId}/chunks/{chunkIndex}/context")
    public ResponseEntity<?> getChunkContext(HttpServletRequest request,
                                             @PathVariable String kbId,
                                             @PathVariable String docId,
                                             @PathVariable int chunkIndex) {
        TenantEntity tenant = getTenant(request);
        try {
            Map<String, Object> result = chunkService.getChunkContext(tenant.getId(), kbId, docId, chunkIndex);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/documents/{docId}/fulltext")
    public ResponseEntity<?> getFulltext(HttpServletRequest request,
                                         @PathVariable String kbId,
                                         @PathVariable String docId) {
        TenantEntity tenant = getTenant(request);
        try {
            String fulltext = chunkService.getFulltext(tenant.getId(), kbId, docId);
            return ResponseEntity.ok(Map.of("fulltext", fulltext));
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/documents/{docId}/chunk-stats")
    public ResponseEntity<?> getChunkStats(HttpServletRequest request,
                                            @PathVariable String kbId,
                                            @PathVariable String docId) {
        TenantEntity tenant = getTenant(request);
        try {
            Map<String, Object> result = chunkService.getChunkStats(tenant.getId(), kbId, docId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @PutMapping("/documents/{docId}/chunks/{chunkIndex}")
    public ResponseEntity<?> editChunk(HttpServletRequest request,
                                       @PathVariable String kbId,
                                       @PathVariable String docId,
                                       @PathVariable int chunkIndex,
                                       @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(request);
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "content is required"));
        }
        try {
            Map<String, Object> result = chunkService.editChunk(tenant.getId(), kbId, docId, chunkIndex, content);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @DeleteMapping("/documents/{docId}/chunks/{chunkIndex}")
    public ResponseEntity<?> deleteChunk(HttpServletRequest request,
                                         @PathVariable String kbId,
                                         @PathVariable String docId,
                                         @PathVariable int chunkIndex) {
        TenantEntity tenant = getTenant(request);
        try {
            chunkService.deleteChunk(tenant.getId(), kbId, docId, chunkIndex);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @PostMapping("/documents/{docId}/chunks")
    public ResponseEntity<?> createChunk(HttpServletRequest request,
                                         @PathVariable String kbId,
                                         @PathVariable String docId,
                                         @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(request);
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "content is required"));
        }
        Integer insertAfterIndex = body.get("insert_after_index") != null
                ? ((Number) body.get("insert_after_index")).intValue()
                : -1;  // default: insert at beginning (index 0)
        try {
            Map<String, Object> result = chunkService.createChunk(tenant.getId(), kbId, docId, content, insertAfterIndex);
            return ResponseEntity.status(201).body(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    // ── Rechunk operations ────────────────────────────────────────

    @PostMapping("/documents/{docId}/rechunk")
    public ResponseEntity<?> rechunk(HttpServletRequest request,
                                     @PathVariable String kbId,
                                     @PathVariable String docId,
                                     @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(request);
        int maxTokens = body.get("max_tokens") != null
                ? ((Number) body.get("max_tokens")).intValue() : 400;
        double overlapRatio = body.get("overlap_ratio") != null
                ? ((Number) body.get("overlap_ratio")).doubleValue() : 0.15;
        String customSeparator = (String) body.get("custom_separator");
        try {
            Map<String, Object> result = chunkService.rechunk(tenant, kbId, docId,
                    maxTokens, overlapRatio, customSeparator);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @PostMapping("/documents/{docId}/rechunk/rollback")
    public ResponseEntity<?> rechunkRollback(HttpServletRequest request,
                                              @PathVariable String kbId,
                                              @PathVariable String docId,
                                              @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(request);
        String branchId = (String) body.get("branch_id");
        if (branchId == null || branchId.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "branch_id is required"));
        }
        try {
            chunkService.rechunkRollback(tenant.getId(), kbId, docId, branchId);
            return ResponseEntity.ok(Map.of("rolled_back", true));
        } catch (Exception e) {
            return handleError(e);
        }
    }

    @GetMapping("/documents/{docId}/rechunk/branches")
    public ResponseEntity<?> listRechunkBranches(HttpServletRequest request,
                                                  @PathVariable String kbId,
                                                  @PathVariable String docId) {
        TenantEntity tenant = getTenant(request);
        try {
            var branches = chunkService.listRechunkBranches(tenant.getId(), kbId, docId);
            return ResponseEntity.ok(Map.of("branches", branches));
        } catch (Exception e) {
            return handleError(e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private ResponseEntity<?> handleError(Exception e) {
        if (e instanceof com.lakeon.service.exception.NotFoundException) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
        if (e instanceof com.lakeon.service.exception.BadRequestException) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        if (e instanceof com.lakeon.service.exception.ConflictException) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
    }
}
