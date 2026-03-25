package com.lakeon.datalake;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datalake")
public class DatalakeController {

    private final DatalakeService service;
    private final DatalakeLogService logService;
    private final AiScriptService aiScriptService;

    public DatalakeController(DatalakeService service, DatalakeLogService logService, AiScriptService aiScriptService) {
        this.service = service;
        this.logService = logService;
        this.aiScriptService = aiScriptService;
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public DatalakeJobResponse submitJob(HttpServletRequest req,
                                        @RequestBody DatalakeJobRequest body) {
        TenantEntity tenant = getTenant(req);
        return service.submitJob(tenant.getId(), body);
    }

    @GetMapping("/jobs")
    public List<DatalakeJobResponse> listJobs(HttpServletRequest req,
                                              @RequestParam(value = "status", required = false) String statusParam) {
        TenantEntity tenant = getTenant(req);
        DatalakeJobStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = DatalakeJobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new com.lakeon.service.exception.BadRequestException("Invalid status: " + statusParam);
            }
        }
        return service.listJobs(tenant.getId(), status);
    }

    @GetMapping("/jobs/{id}")
    public DatalakeJobResponse getJob(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return service.getJob(tenant.getId(), id);
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelJob(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        service.cancelJob(tenant.getId(), id);
    }

    @GetMapping(value = "/jobs/{id}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return logService.streamLogs(tenant.getId(), id);
    }

    @PostMapping("/ai-script/generate")
    public Map<String, Object> generateScript(HttpServletRequest req,
                                              @RequestBody Map<String, String> body) {
        String tenantId = getTenant(req).getId();
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return Map.of("error", "prompt is required");
        }
        String model = body.get("model");
        return aiScriptService.generateScript(tenantId, prompt, model);
    }

    @GetMapping("/ai-script/models")
    public List<Map<String, Object>> getScriptModels() {
        return AiScriptService.AVAILABLE_MODELS;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }
}
