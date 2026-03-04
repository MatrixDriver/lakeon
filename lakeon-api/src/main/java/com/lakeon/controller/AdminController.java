package com.lakeon.controller;

import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.TenantService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final TenantService tenantService;
    private final AdminService adminService;
    private final DatabaseRepository databaseRepository;
    private final OperationLogRepository operationLogRepository;

    public AdminController(TenantService tenantService,
                           AdminService adminService,
                           DatabaseRepository databaseRepository,
                           OperationLogRepository operationLogRepository) {
        this.tenantService = tenantService;
        this.adminService = adminService;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;
    }

    // ── Dashboard ──────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard() {
        return adminService.getDashboard();
    }

    // ── Tenants ────────────────────────────────────────────────────

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

    // ── Databases (global) ─────────────────────────────────────────

    @GetMapping("/databases")
    public List<Map<String, Object>> listAllDatabases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "tenant_id") String tenantId) {
        List<DatabaseEntity> dbs;
        if (tenantId != null) {
            dbs = databaseRepository.findAllByTenantId(tenantId);
        } else if (status != null) {
            dbs = databaseRepository.findAllByStatus(
                    com.lakeon.model.enums.DatabaseStatus.valueOf(status.toUpperCase()));
        } else {
            dbs = databaseRepository.findAll();
        }
        return dbs.stream().map(this::dbToMap).toList();
    }

    // ── Compute Stats ──────────────────────────────────────────────

    @GetMapping("/compute/stats")
    public Map<String, Object> getComputeStats() {
        return adminService.getComputeStats();
    }

    // ── System Health ──────────────────────────────────────────────

    @GetMapping("/system/health")
    public Map<String, Object> getSystemHealth() {
        return adminService.checkAllComponents();
    }

    @GetMapping("/system/health/{component}")
    public Map<String, Object> getComponentHealth(@PathVariable String component) {
        return switch (component) {
            case "pageserver" -> adminService.checkPageserver();
            case "safekeeper" -> adminService.checkSafekeeper();
            case "proxy" -> adminService.checkProxy();
            case "rds" -> adminService.checkRds();
            default -> Map.of("error", "Unknown component: " + component);
        };
    }

    // ── Operations (global audit) ──────────────────────────────────

    @GetMapping("/operations")
    public Map<String, Object> listOperations(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<OperationLogEntity> result;

        if (tenantId != null && type != null) {
            result = operationLogRepository.findByTenantIdAndOperationTypeOrderByStartedAtDesc(
                    tenantId, OperationType.valueOf(type.toUpperCase()), pageable);
        } else if (tenantId != null) {
            result = operationLogRepository.findByTenantIdOrderByStartedAtDesc(tenantId, pageable);
        } else if (type != null) {
            result = operationLogRepository.findByOperationTypeOrderByStartedAtDesc(
                    OperationType.valueOf(type.toUpperCase()), pageable);
        } else if (status != null) {
            result = operationLogRepository.findByStatusOrderByStartedAtDesc(
                    OperationStatus.valueOf(status.toUpperCase()), pageable);
        } else {
            result = operationLogRepository.findAllByOrderByStartedAtDesc(pageable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result.getContent());
        response.put("total", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("total_pages", result.getTotalPages());
        return response;
    }

    // ── Cost ───────────────────────────────────────────────────────

    @GetMapping("/cost/summary")
    public Map<String, Object> getCostSummary() {
        return adminService.estimateMonthlyCost();
    }

    @GetMapping("/cost/tenants")
    public Map<String, Object> getCostByTenant() {
        return adminService.getCostByTenant();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private Map<String, Object> dbToMap(DatabaseEntity db) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", db.getId());
        m.put("name", db.getName());
        m.put("tenant_id", db.getTenantId());
        m.put("status", db.getStatus().name());
        m.put("compute_size", db.getComputeSize());
        m.put("storage_limit_gb", db.getStorageLimitGb());
        m.put("compute_pod_name", db.getComputePodName());
        m.put("connection_uri", db.getConnectionUri());
        m.put("last_active_at", db.getLastActiveAt());
        m.put("created_at", db.getCreatedAt());
        return m;
    }
}
