package com.lakeon.controller;

import com.lakeon.model.dto.OperationLogResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.OperationType;
import com.lakeon.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping("/databases/{dbId}/operations")
    public Page<OperationLogResponse> getDatabaseOperations(
            HttpServletRequest req,
            @PathVariable String dbId,
            @RequestParam(required = false) OperationType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return operationLogService.getByDatabase(dbId, tenant.getId(), type, page, size)
                .map(OperationLogResponse::from);
    }

    @GetMapping("/operations/recent")
    public List<OperationLogResponse> getRecentOperations(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return operationLogService.getRecent(tenant.getId()).stream()
                .map(OperationLogResponse::from)
                .toList();
    }
}
