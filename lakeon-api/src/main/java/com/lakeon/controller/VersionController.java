package com.lakeon.controller;

import com.lakeon.model.dto.CreateVersionRequest;
import com.lakeon.model.dto.SquashVersionsRequest;
import com.lakeon.model.dto.VersionResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.VersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/branches/{branchId}/versions")
public class VersionController {

    private final VersionService versionService;

    @Autowired
    public VersionController(VersionService versionService) {
        this.versionService = versionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @Valid @RequestBody CreateVersionRequest request) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.create(tenant, dbId, branchId, request);
    }

    @GetMapping
    public List<VersionResponse> list(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.list(tenant, dbId, branchId);
    }

    @GetMapping("/{versionId}")
    public VersionResponse get(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @PathVariable String versionId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.get(tenant, dbId, branchId, versionId);
    }

    @DeleteMapping("/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @PathVariable String versionId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        versionService.delete(tenant, dbId, branchId, versionId);
    }

    @PostMapping("/squash")
    public List<VersionResponse> squash(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @Valid @RequestBody SquashVersionsRequest request) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.squash(tenant, dbId, branchId, request);
    }
}
