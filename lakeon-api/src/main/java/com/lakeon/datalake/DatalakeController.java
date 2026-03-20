package com.lakeon.datalake;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/datalake")
public class DatalakeController {

    private final DatalakeService service;
    private final DatalakeLogService logService;

    public DatalakeController(DatalakeService service, DatalakeLogService logService) {
        this.service = service;
        this.logService = logService;
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

    // ── Helpers ──────────────────────────────────────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }
}
