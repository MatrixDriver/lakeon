package com.lakeon.controller;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return tenantService.create(request);
    }

    @GetMapping("/me")
    public TenantResponse getCurrentTenant(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return tenantService.get(tenant.getId());
    }

    @GetMapping("/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @PostMapping("/{tenantId}/regenerate-key")
    public TenantResponse regenerateKey(HttpServletRequest req, @PathVariable String tenantId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (!tenant.getId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot regenerate key for another tenant");
        }
        return tenantService.regenerateApiKey(tenantId);
    }
}
