package com.lakeon.notebook;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datalake/notebook")
public class NotebookController {

    private final NotebookService notebookService;

    public NotebookController(NotebookService notebookService) {
        this.notebookService = notebookService;
    }

    /**
     * POST /api/v1/datalake/notebook/sessions
     * Body: { "image": "python-data", "dataset_ids": ["ds_xxx", "ds_yyy"] }
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSession(HttpServletRequest req,
                                             @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        String image = (String) body.get("image");

        @SuppressWarnings("unchecked")
        List<String> datasetIds = (List<String>) body.get("dataset_ids");

        Integer workerCount = body.get("worker_count") != null
                ? ((Number) body.get("worker_count")).intValue() : 0;

        NotebookSessionEntity session = notebookService.getOrCreateSession(tenant.getId(), image, datasetIds, workerCount);
        return sessionToMap(session);
    }

    /**
     * GET /api/v1/datalake/notebook/sessions/current
     */
    @GetMapping("/sessions/current")
    public ResponseEntity<Map<String, Object>> getCurrentSession(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return notebookService.getSession(tenant.getId())
                .map(s -> ResponseEntity.ok(sessionToMap(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/v1/datalake/notebook/sessions/{id}
     */
    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopSession(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        notebookService.stopSession(tenant.getId(), id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> sessionToMap(NotebookSessionEntity s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("status", s.getStatus() != null ? s.getStatus().name() : null);
        map.put("pod_name", s.getPodName());
        map.put("image", s.getImage());
        map.put("dataset_ids", s.getDatasetIds());
        map.put("worker_count", s.getWorkerCount());
        map.put("last_active_at", s.getLastActiveAt() != null ? s.getLastActiveAt().toString() : null);
        map.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return map;
    }
}
