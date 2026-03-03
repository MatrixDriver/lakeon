package com.lakeon.controller;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }
}
