package com.lakeon.controller;

import com.lakeon.model.dto.BranchResponse;
import com.lakeon.model.dto.BranchTreeResponse;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.dto.RestoreBranchRequest;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.BranchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/branches")
public class BranchController {
    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse createBranch(HttpServletRequest req,
                                       @PathVariable String dbId,
                                       @Valid @RequestBody CreateBranchRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return branchService.create(tenant, dbId, request);
    }

    @GetMapping
    public List<BranchResponse> listBranches(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return branchService.list(tenant, dbId);
    }

    @GetMapping("/{branchId}")
    public BranchResponse getBranch(HttpServletRequest req,
                                    @PathVariable String dbId,
                                    @PathVariable String branchId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return branchService.get(tenant, dbId, branchId);
    }

    @GetMapping("/tree")
    public BranchTreeResponse getBranchTree(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return branchService.getTree(tenant, dbId);
    }

    @PostMapping("/{branchId}/promote")
    @ResponseStatus(HttpStatus.OK)
    public BranchResponse promote(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return branchService.promote(tenant, dbId, branchId);
    }

    @PostMapping("/{branchId}/restore")
    public BranchResponse restore(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @Valid @RequestBody RestoreBranchRequest request) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return branchService.restore(tenant, dbId, branchId, request);
    }

    @DeleteMapping("/{branchId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBranch(HttpServletRequest req,
                             @PathVariable String dbId,
                             @PathVariable String branchId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        branchService.delete(tenant, dbId, branchId);
    }
}
