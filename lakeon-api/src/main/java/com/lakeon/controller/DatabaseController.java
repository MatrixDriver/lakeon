package com.lakeon.controller;

import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseMetrics;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.dto.UpdateDatabaseRequest;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases")
public class DatabaseController {
    private final DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseResponse createDatabase(HttpServletRequest req,
                                           @Valid @RequestBody CreateDatabaseRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.create(tenant, request);
    }

    @GetMapping
    public List<DatabaseResponse> listDatabases(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.list(tenant);
    }

    @GetMapping("/{dbId}")
    public DatabaseResponse getDatabase(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.get(tenant, dbId);
    }

    @PatchMapping("/{dbId}")
    public DatabaseResponse updateDatabase(HttpServletRequest req, @PathVariable String dbId,
                                           @RequestBody UpdateDatabaseRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.update(tenant, dbId, request);
    }

    @DeleteMapping("/{dbId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDatabase(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        databaseService.delete(tenant, dbId);
    }

    @GetMapping("/{dbId}/metrics")
    public DatabaseMetrics getMetrics(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.getMetrics(tenant, dbId);
    }

    @GetMapping("/{dbId}/logs")
    public List<Map<String, String>> getDatabaseLogs(HttpServletRequest req, @PathVariable String dbId,
                                                     @RequestParam(defaultValue = "200") int tail) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.getDatabaseLogs(tenant, dbId, tail);
    }

    @PostMapping("/{dbId}/reset-password")
    public Map<String, String> resetPassword(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseService.resetPassword(tenant, dbId);
    }

    @PostMapping("/{dbId}/suspend")
    public void suspendDatabase(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        databaseService.suspend(tenant, dbId);
    }

    @PostMapping("/{dbId}/resume")
    public void resumeDatabase(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        databaseService.resume(tenant, dbId);
    }
}
