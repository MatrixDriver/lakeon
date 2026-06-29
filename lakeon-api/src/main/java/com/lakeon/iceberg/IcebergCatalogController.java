package com.lakeon.iceberg;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/iceberg/catalog/{databaseId}/{branchId}/v1")
public class IcebergCatalogController {

    private final IcebergCatalogService service;

    public IcebergCatalogController(IcebergCatalogService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public Map<String, Object> config(HttpServletRequest request,
                                      @PathVariable String databaseId,
                                      @PathVariable String branchId) {
        return service.config(getTenant(request), databaseId, branchId);
    }

    @GetMapping("/namespaces/{namespace}/tables/{table}")
    public Map<String, Object> loadTable(HttpServletRequest request,
                                         @PathVariable String databaseId,
                                         @PathVariable String branchId,
                                         @PathVariable String namespace,
                                         @PathVariable String table) {
        return service.loadTable(getTenant(request), databaseId, branchId, namespace, table);
    }

    @PostMapping("/namespaces/{namespace}/tables/{table}/plan")
    public IcebergPlanningService.PlanTableScanResponse planTableScan(
            HttpServletRequest request,
            @PathVariable String databaseId,
            @PathVariable String branchId,
            @PathVariable String namespace,
            @PathVariable String table,
            @RequestBody(required = false) IcebergPlanningService.PlanTableScanRequest body
    ) {
        return service.planTableScan(getTenant(request), databaseId, branchId, namespace, table, body);
    }

    @GetMapping("/namespaces/{namespace}/tables/{table}/plan/{planId}")
    public IcebergPlanningService.PlanTableScanResponse getPlan(
            HttpServletRequest request,
            @PathVariable String databaseId,
            @PathVariable String branchId,
            @PathVariable String namespace,
            @PathVariable String table,
            @PathVariable String planId
    ) {
        return service.getPlan(getTenant(request), databaseId, branchId, namespace, table, planId);
    }

    @PostMapping("/namespaces/{namespace}/tables/{table}/tasks")
    public IcebergPlanningService.PlanTableScanResponse planTableScanTasks(
            HttpServletRequest request,
            @PathVariable String databaseId,
            @PathVariable String branchId,
            @PathVariable String namespace,
            @PathVariable String table,
            @RequestBody(required = false) IcebergCatalogService.FetchPlanTasksRequest body
    ) {
        return service.planTableScanTasks(getTenant(request), databaseId, branchId, namespace, table, body);
    }

    @PostMapping("/namespaces/{namespace}/tables/{table}")
    public Map<String, Object> commitTable(HttpServletRequest request,
                                           @PathVariable String databaseId,
                                           @PathVariable String branchId,
                                           @PathVariable String namespace,
                                           @PathVariable String table) {
        throw new BadRequestException(
                "Lakeon-managed Iceberg tables are read-only for external Iceberg clients; write through Lakebase CDF");
    }

    private static TenantEntity getTenant(HttpServletRequest request) {
        TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
        if (tenant == null) {
            throw new BadRequestException("no authenticated tenant");
        }
        return tenant;
    }
}
