package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.ImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ImportController {
    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import/test-connection")
    public Map<String, Object> testConnection(HttpServletRequest req,
                                              @Valid @RequestBody TestConnectionRequest request) {
        return importService.testConnection(request);
    }

    @PostMapping("/import/source-tables")
    public List<SourceTableInfo> listSourceTables(HttpServletRequest req,
                                                  @Valid @RequestBody TestConnectionRequest request) {
        return importService.listSourceTables(request);
    }

    @PostMapping("/databases/{dbId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportTaskResponse createImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @RequestBody CreateImportRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.createImport(tenant, dbId, request);
    }

    @GetMapping("/databases/{dbId}/import")
    public List<ImportTaskResponse> listImports(HttpServletRequest req,
                                                @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.listImports(tenant, dbId);
    }

    @GetMapping("/databases/{dbId}/import/{taskId}")
    public ImportTaskResponse getImport(HttpServletRequest req,
                                        @PathVariable String dbId,
                                        @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.getImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/pause")
    public ImportTaskResponse pauseImport(HttpServletRequest req,
                                          @PathVariable String dbId,
                                          @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.pauseImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/resume")
    public ImportTaskResponse resumeImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.resumeImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/cancel")
    public ImportTaskResponse cancelImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.cancelImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/retry")
    public ImportTaskResponse retryImport(HttpServletRequest req,
                                          @PathVariable String dbId,
                                          @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.retryImport(tenant, dbId, taskId);
    }

    // Internal callback endpoint (no auth required — handled by ApiKeyFilter exclusion)
    @PutMapping("/import/callback/{taskId}")
    public void handleCallback(@PathVariable String taskId,
                               @RequestBody ImportCallbackRequest request) {
        importService.handleCallback(taskId, request);
    }
}
