package com.lakeon.controller;

import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.service.TenantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final TenantService tenantService;

    public AdminController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/tenants")
    public List<TenantResponse> listTenants() {
        return tenantService.listAll();
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @PutMapping("/tenants/{tenantId}/quota")
    public TenantResponse updateQuota(@PathVariable String tenantId, @RequestBody UpdateQuotaRequest request) {
        return tenantService.updateQuota(tenantId, request.maxDatabases(), request.maxStorageGb(), request.maxComputeCu());
    }
}
