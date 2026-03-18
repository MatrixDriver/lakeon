package com.lakeon.controller;

import com.lakeon.model.dto.DatabaseUsageSummary;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.TenantUsageSummary;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.AlertService;
import com.lakeon.service.AuditService;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.service.CbcBillingService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import com.lakeon.service.UsageMeteringService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final TenantService tenantService;
    private final AdminService adminService;
    private final DatabaseService databaseService;
    private final DatabaseRepository databaseRepository;
    private final TenantRepository tenantRepository;
    private final OperationLogRepository operationLogRepository;
    private final UsageMeteringService usageMeteringService;
    private final CbcBillingService cbcBillingService;
    private final AlertService alertService;
    private final AuditService auditService;
    private final InviteCodeRepository inviteCodeRepository;

    public AdminController(TenantService tenantService,
                           AdminService adminService,
                           DatabaseService databaseService,
                           DatabaseRepository databaseRepository,
                           TenantRepository tenantRepository,
                           OperationLogRepository operationLogRepository,
                           UsageMeteringService usageMeteringService,
                           CbcBillingService cbcBillingService,
                           AlertService alertService,
                           AuditService auditService,
                           InviteCodeRepository inviteCodeRepository) {
        this.tenantService = tenantService;
        this.adminService = adminService;
        this.databaseService = databaseService;
        this.databaseRepository = databaseRepository;
        this.tenantRepository = tenantRepository;
        this.operationLogRepository = operationLogRepository;
        this.usageMeteringService = usageMeteringService;
        this.cbcBillingService = cbcBillingService;
        this.alertService = alertService;
        this.auditService = auditService;
        this.inviteCodeRepository = inviteCodeRepository;
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

    @PostMapping("/tenants/{tenantId}/disable")
    public TenantResponse disableTenant(@PathVariable String tenantId) {
        return tenantService.disableTenant(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/enable")
    public TenantResponse enableTenant(@PathVariable String tenantId) {
        return tenantService.enableTenant(tenantId);
    }

    @DeleteMapping("/tenants/batch")
    public Map<String, Object> batchDeleteTenants(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.getOrDefault("ids", List.of());
        List<String> deleted = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();
        for (String id : ids) {
            try {
                // Delete all databases under this tenant first
                TenantEntity tenant = tenantRepository.findById(id).orElse(null);
                if (tenant == null) {
                    errors.add(Map.of("id", id, "error", "Tenant not found"));
                    continue;
                }
                List<DatabaseEntity> dbs = databaseRepository.findAllByTenantId(id);
                for (DatabaseEntity db : dbs) {
                    try {
                        databaseService.delete(tenant, db.getId());
                    } catch (Exception e) {
                        errors.add(Map.of("id", id, "error", "Failed to delete database " + db.getName() + ": " + e.getMessage()));
                    }
                }
                tenantRepository.delete(tenant);
                deleted.add(id);
            } catch (Exception e) {
                errors.add(Map.of("id", id, "error", e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted.size());
        result.put("errors", errors);
        return result;
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

    @GetMapping("/databases/{databaseId}")
    public Map<String, Object> getDatabase(@PathVariable String databaseId) {
        DatabaseEntity db = databaseRepository.findById(databaseId)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Database not found: " + databaseId));
        return dbToMap(db);
    }

    @DeleteMapping("/databases/batch")
    public Map<String, Object> batchDeleteDatabases(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.getOrDefault("ids", List.of());
        List<String> deleted = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();
        for (String id : ids) {
            try {
                DatabaseEntity db = databaseRepository.findById(id).orElse(null);
                if (db == null) {
                    errors.add(Map.of("id", id, "error", "Database not found"));
                    continue;
                }
                TenantEntity tenant = tenantRepository.findById(db.getTenantId()).orElse(null);
                if (tenant == null) {
                    errors.add(Map.of("id", id, "error", "Tenant not found for database"));
                    continue;
                }
                databaseService.delete(tenant, id);
                deleted.add(id);
            } catch (Exception e) {
                errors.add(Map.of("id", id, "error", e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted.size());
        result.put("errors", errors);
        return result;
    }

    // ── Cloud Resources ─────────────────────────────────────────────

    @GetMapping("/cloud/resources")
    public Map<String, Object> getCloudResources() {
        return adminService.getCloudResources();
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

    @GetMapping("/system/health/obs")
    public Map<String, Object> getObsHealth() {
        return adminService.checkObs();
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

    @GetMapping("/cost/trend")
    public List<Map<String, Object>> getCostTrend(@RequestParam(defaultValue = "30") int days) {
        return adminService.getCostTrend(days);
    }

    @GetMapping("/cost/tenants")
    public Map<String, Object> getCostByTenant() {
        return adminService.getCostByTenant();
    }

    @GetMapping(value = "/cost/cbc", produces = "application/json")
    @org.springframework.web.bind.annotation.ResponseBody
    public String getCbcBilling(@RequestParam(required = false, name = "bill_cycle") String billCycle) {
        String result;
        if (billCycle == null || billCycle.isBlank()) {
            result = cbcBillingService.getCurrentMonthBilling();
        } else {
            result = cbcBillingService.fetchMonthlyBillSummary(billCycle);
        }
        return result != null ? result : "{\"error\":\"Failed to fetch CBC billing\"}";
    }

    // ── Usage Metering ────────────────────────────────────────────

    @GetMapping("/usage/tenants")
    public List<TenantUsageSummary> getAllTenantsUsage(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant[] range = parseTimeRange(from, to);
        return usageMeteringService.getAllTenantsUsage(range[0], range[1]);
    }

    @GetMapping("/usage/tenants/{tenantId}")
    public TenantUsageSummary getTenantUsage(
            @PathVariable String tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant[] range = parseTimeRange(from, to);
        return usageMeteringService.getTenantUsage(tenantId, range[0], range[1]);
    }

    @GetMapping("/usage/databases/{databaseId}")
    public DatabaseUsageSummary getDatabaseUsage(
            @PathVariable String databaseId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant[] range = parseTimeRange(from, to);
        return usageMeteringService.getDatabaseUsage(databaseId, range[0], range[1]);
    }

    private Instant[] parseTimeRange(String from, String to) {
        Instant now = Instant.now();
        Instant start = from != null
                ? Instant.parse(from)
                : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = to != null ? Instant.parse(to) : now;
        return new Instant[]{start, end};
    }

    // ── Logs ─────────────────────────────────────────────────────

    @GetMapping(value = "/logs/{component}", produces = "text/plain")
    public String getComponentLogs(@PathVariable String component,
                                    @RequestParam(defaultValue = "200") int tail) {
        return adminService.getComponentLogs(component, tail);
    }

    // ── Metrics ─────────────────────────────────────────────────

    @GetMapping("/metrics/summary")
    public Map<String, Object> getMetricsSummary() {
        return adminService.getMetricsSummary();
    }

    // ── Alerts ──────────────────────────────────────────────────

    @GetMapping("/alerts")
    public List<?> getAlerts() {
        return alertService.getAlerts();
    }

    @GetMapping("/alerts/rules")
    public List<?> getAlertRules() {
        return alertService.getRules();
    }

    @PutMapping("/alerts/rules/{id}")
    public Object updateAlertRule(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        return alertService.updateRule(id, updates);
    }

    @PostMapping("/alerts/test-webhook")
    public Map<String, Object> testWebhook(@RequestBody Map<String, String> body) {
        return alertService.testWebhook(body.getOrDefault("webhook_url", ""));
    }

    // ── Pageserver Metrics ─────────────────────────────────────

    @GetMapping("/pageserver/metrics")
    public Map<String, Object> getPageserverMetrics() {
        return adminService.getPageserverMetrics();
    }

    // ── Infrastructure ──────────────────────────────────────────

    @GetMapping("/infra/nodes")
    public Map<String, Object> getInfraNodes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", adminService.getInfraNodes());
        result.put("pods", adminService.getInfraPods());
        return result;
    }

    @GetMapping("/infra/node-pool")
    public Map<String, Object> getNodePoolStatus() {
        return adminService.getNodePoolStatus();
    }

    @GetMapping("/infra/autoscaling-events")
    public Map<String, Object> getAutoscalingEvents() {
        return adminService.getAutoscalingEvents();
    }

    @GetMapping("/infra/events")
    public Map<String, Object> getPodEvents(
            @RequestParam(defaultValue = "lakeon-compute") String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", adminService.getPodEvents(namespace));
        return result;
    }

    // ── Audit Logs ──────────────────────────────────────────────────

    @GetMapping("/audit/logs")
    public Map<String, Object> getAuditLogs(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false, name = "db_id") String dbId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.getLogsForAdmin(tenantId, dbId, type, page, size);
    }

    // ── Helpers ────────────────────────────────────────────────────

    private Map<String, Object> dbToMap(DatabaseEntity db) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", db.getId());
        m.put("name", db.getName());
        m.put("tenant_id", db.getTenantId());
        m.put("status", db.getStatus().name());
        m.put("status_message", db.getStatusMessage());
        m.put("compute_size", db.getComputeSize());
        m.put("storage_limit_gb", db.getStorageLimitGb());
        m.put("compute_pod_name", db.getComputePodName());
        m.put("connection_uri", db.getConnectionUri());
        m.put("last_active_at", db.getLastActiveAt());
        m.put("created_at", db.getCreatedAt());
        return m;
    }

    // ── Invite Codes ─────────────────────────────────────────────────

    @PostMapping("/invite-codes")
    public Map<String, Object> createInviteCode(@RequestBody(required = false) Map<String, Object> body) {
        InviteCodeEntity entity = new InviteCodeEntity();
        if (body != null) {
            if (body.containsKey("max_uses")) {
                entity.setMaxUses(((Number) body.get("max_uses")).intValue());
            }
            if (body.containsKey("expires_at") && body.get("expires_at") != null) {
                entity.setExpiresAt(Instant.parse((String) body.get("expires_at")));
            }
        }
        entity.setCreatedBy("admin");
        entity = inviteCodeRepository.save(entity);
        return inviteCodeToMap(entity);
    }

    @GetMapping("/invite-codes")
    public List<Map<String, Object>> listInviteCodes() {
        return inviteCodeRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(this::inviteCodeToMap).toList();
    }

    @DeleteMapping("/invite-codes/{code}")
    public void deleteInviteCode(@PathVariable String code) {
        inviteCodeRepository.deleteById(code.toUpperCase());
    }

    private Map<String, Object> inviteCodeToMap(InviteCodeEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.getCode());
        m.put("max_uses", e.getMaxUses());
        m.put("used_count", e.getUsedCount());
        m.put("valid", e.isValid());
        m.put("expires_at", e.getExpiresAt());
        m.put("created_at", e.getCreatedAt());
        return m;
    }
}
