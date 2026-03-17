package com.lakeon.controller;

import com.lakeon.model.dto.SchemaDiffResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.DiffService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/diff")
public class DiffController {

    private final DiffService diffService;

    @Autowired
    public DiffController(DiffService diffService) {
        this.diffService = diffService;
    }

    @GetMapping("/schema")
    public SchemaDiffResponse schemaDiff(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @RequestParam("source_type") String sourceType,
            @RequestParam("source_id") String sourceId,
            @RequestParam("target_type") String targetType,
            @RequestParam("target_id") String targetId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return diffService.schemaDiff(tenant, dbId, sourceType, sourceId, targetType, targetId);
    }
}
