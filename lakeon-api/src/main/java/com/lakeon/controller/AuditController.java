package com.lakeon.controller;

import com.lakeon.model.dto.AuditConfigResponse;
import com.lakeon.model.dto.UpdateAuditConfigRequest;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/config")
    public AuditConfigResponse getConfig(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return auditService.getConfig(tenant, dbId);
    }

    @PutMapping("/config")
    public AuditConfigResponse updateConfig(HttpServletRequest req,
                                            @PathVariable String dbId,
                                            @RequestBody UpdateAuditConfigRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return auditService.updateConfig(tenant, dbId, request);
    }

    @GetMapping("/logs")
    public Map<String, Object> getLogs(HttpServletRequest req,
                                       @PathVariable String dbId,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return auditService.getLogs(tenant, dbId, type, page, size);
    }
}
