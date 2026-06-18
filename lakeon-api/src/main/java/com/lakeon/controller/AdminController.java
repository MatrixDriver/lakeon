package com.lakeon.controller;

import com.lakeon.service.admin.DataConsistencyCheckService;
import com.lakeon.service.admin.StuckTaskQueryService;
import com.lakeon.model.dto.DatabaseUsageSummary;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.TenantUsageSummary;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.AlertService;
import com.lakeon.service.AuditService;
import com.lakeon.service.LogQueryService;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.service.CbcBillingService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantReconcileService;
import com.lakeon.service.TenantService;
import com.lakeon.service.UsageMeteringService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
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
    private final LogQueryService logQueryService;
    private final DataConsistencyCheckService dataConsistencyCheckService;
    private final StuckTaskQueryService stuckTaskQueryService;
    private final NeonApiClient neonApiClient;
    private final TenantReconcileService tenantReconcileService;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminController.class);

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
                           InviteCodeRepository inviteCodeRepository,
                           LogQueryService logQueryService,
                           DataConsistencyCheckService dataConsistencyCheckService,
                           StuckTaskQueryService stuckTaskQueryService,
                           NeonApiClient neonApiClient,
                           TenantReconcileService tenantReconcileService) {
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
        this.logQueryService = logQueryService;
        this.dataConsistencyCheckService = dataConsistencyCheckService;
        this.stuckTaskQueryService = stuckTaskQueryService;
        this.neonApiClient = neonApiClient;
        this.tenantReconcileService = tenantReconcileService;
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
                TenantEntity tenant = tenantRepository.findById(id).orElse(null);
                if (tenant == null) {
                    errors.add(Map.of("id", id, "error", "Tenant not found"));
                    continue;
                }

                // 1. Delete databases (includes compute pods, Neon tenants/timelines)
                List<DatabaseEntity> dbs = databaseRepository.findAllByTenantId(id);
                for (DatabaseEntity db : dbs) {
                    try {
                        databaseService.delete(tenant, db.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete database {} for tenant {}: {}", db.getName(), id, e.getMessage());
                    }
                }

                // 2. Delete tenant entity
                tenantRepository.delete(tenant);
                deleted.add(id);
                log.info("Deleted tenant {} with {} databases", id, dbs.size());
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

    @DeleteMapping("/databases/{dbId}/purge")
    public Map<String, Object> purgeDatabase(@PathVariable String dbId) {
        databaseService.purge(dbId);
        return Map.of("success", true, "message", "Database permanently deleted");
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

    // ── Cold Start Analysis ─────────────────────────────────────────

    @GetMapping("/compute/cold-start")
    public Map<String, Object> getColdStartAnalysis(
            @RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(days));
        List<OperationLogEntity> ops = operationLogRepository
            .findByOperationTypeAndStatusAndStartedAtAfter(OperationType.RESUME, OperationStatus.SUCCESS, since);

        // Separate cold and warm
        List<Long> coldMs = new ArrayList<>();
        List<Long> warmMs = new ArrayList<>();
        List<Map<String, Object>> coldOps = new ArrayList<>();
        Map<String, List<Long>> byDatabase = new LinkedHashMap<>();
        // Hourly distribution
        Map<String, long[]> hourly = new LinkedHashMap<>(); // hour -> [count, totalMs]

        for (OperationLogEntity op : ops) {
            boolean isCold = !"WARM".equals(op.getResumeType());
            long dur = op.getDurationMs() != null ? op.getDurationMs() : 0;
            if (isCold) {
                coldMs.add(dur);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", op.getId());
                entry.put("database_id", op.getDatabaseId());
                entry.put("database_name", op.getDatabaseName());
                entry.put("tenant_id", op.getTenantId());
                entry.put("duration_ms", dur);
                entry.put("started_at", op.getStartedAt());
                coldOps.add(entry);

                String dbKey = op.getDatabaseName() != null ? op.getDatabaseName() : op.getDatabaseId();
                byDatabase.computeIfAbsent(dbKey, k -> new ArrayList<>()).add(dur);

                // Group by date for trend
                String dateKey = op.getStartedAt().toString().substring(0, 10);
                hourly.computeIfAbsent(dateKey, k -> new long[2]);
                hourly.get(dateKey)[0]++;
                hourly.get(dateKey)[1] += dur;
            } else {
                warmMs.add(dur);
            }
        }

        // Percentiles for cold
        java.util.Collections.sort(coldMs);
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> cold = new LinkedHashMap<>();
        cold.put("count", coldMs.size());
        cold.put("avg_ms", coldMs.isEmpty() ? 0 : coldMs.stream().mapToLong(Long::longValue).average().orElse(0));
        cold.put("p50_ms", percentile(coldMs, 50));
        cold.put("p90_ms", percentile(coldMs, 90));
        cold.put("p99_ms", percentile(coldMs, 99));
        cold.put("min_ms", coldMs.isEmpty() ? 0 : coldMs.get(0));
        cold.put("max_ms", coldMs.isEmpty() ? 0 : coldMs.get(coldMs.size() - 1));
        result.put("cold", cold);

        java.util.Collections.sort(warmMs);
        Map<String, Object> warm = new LinkedHashMap<>();
        warm.put("count", warmMs.size());
        warm.put("avg_ms", warmMs.isEmpty() ? 0 : warmMs.stream().mapToLong(Long::longValue).average().orElse(0));
        warm.put("p50_ms", percentile(warmMs, 50));
        result.put("warm", warm);

        // Daily trend
        List<Map<String, Object>> trend = new ArrayList<>();
        hourly.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("date", e.getKey());
            t.put("count", e.getValue()[0]);
            t.put("avg_ms", e.getValue()[0] > 0 ? e.getValue()[1] / e.getValue()[0] : 0);
            trend.add(t);
        });
        result.put("trend", trend);

        // Per-database breakdown
        List<Map<String, Object>> dbBreakdown = new ArrayList<>();
        byDatabase.forEach((db, durations) -> {
            java.util.Collections.sort(durations);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("database", db);
            d.put("count", durations.size());
            d.put("avg_ms", durations.stream().mapToLong(Long::longValue).average().orElse(0));
            d.put("p50_ms", percentile(durations, 50));
            d.put("max_ms", durations.get(durations.size() - 1));
            dbBreakdown.add(d);
        });
        dbBreakdown.sort((a, b) -> Long.compare(
            ((Number) b.get("avg_ms")).longValue(), ((Number) a.get("avg_ms")).longValue()));
        result.put("by_database", dbBreakdown);

        // Recent cold starts (latest 20)
        coldOps.sort((a, b) -> ((Instant) b.get("started_at")).compareTo((Instant) a.get("started_at")));
        result.put("recent", coldOps.subList(0, Math.min(20, coldOps.size())));

        result.put("days", days);
        return result;
    }

    private long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
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

    // ── SRE: Data consistency invariants ───────────────────────────

    @GetMapping("/data-consistency/{rule}")
    public Map<String, Object> dataConsistencyCheck(
            @PathVariable String rule,
            @RequestParam(name = "threshold_minutes", defaultValue = "10") int thresholdMinutes) {
        return dataConsistencyCheckService.run(rule, thresholdMinutes);
    }

    // ── SRE: Stuck async tasks ─────────────────────────────────────

    @GetMapping("/stuck-tasks")
    public Map<String, Object> stuckTaskQuery(
            @RequestParam(name = "threshold_minutes", defaultValue = "10") int thresholdMinutes,
            @RequestParam(required = false, defaultValue = "") String type) {
        return stuckTaskQueryService.run(thresholdMinutes, type);
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

    @GetMapping("/pageserver/tenant-health")
    public Map<String, Object> getTenantHealth() {
        var dbs = databaseRepository.findAllActiveWithNeonTenant();
        var expected = dbs.stream()
            .map(db -> db.getNeonTenantId())
            .collect(java.util.stream.Collectors.toSet());

        java.util.Set<String> attached = java.util.Set.of();
        boolean pageserverReachable = true;
        try {
            var tenants = neonApiClient.listTenants();
            attached = tenants.stream()
                .map(t -> (String) t.get("id"))
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            pageserverReachable = false;
        }

        int missingCount = 0;
        var missingList = new java.util.ArrayList<Map<String, String>>();
        if (pageserverReachable) {
            for (var db : dbs) {
                if (!attached.contains(db.getNeonTenantId())) {
                    missingCount++;
                    missingList.add(Map.of(
                        "db_name", db.getName(),
                        "db_id", db.getId(),
                        "neon_tenant_id", db.getNeonTenantId(),
                        "status", db.getStatus().name()
                    ));
                }
            }
        }

        String health = !pageserverReachable ? "UNREACHABLE"
            : missingCount == 0 ? "HEALTHY"
            : "DEGRADED";

        var response = new LinkedHashMap<String, Object>();
        response.put("health", health);
        response.put("expected_tenants", expected.size());
        response.put("attached_tenants", attached.size());
        response.put("missing_count", missingCount);
        response.put("missing", missingList);
        response.put("pageserver_reachable", pageserverReachable);

        var lastResult = tenantReconcileService.getLastResult();
        if (lastResult != null) {
            response.put("last_reconcile", Map.of(
                "timestamp", lastResult.timestamp().toString(),
                "reattached", lastResult.reattachedCount(),
                "failed", lastResult.failedCount()
            ));
        }
        return response;
    }

    @PostMapping("/pageserver/tenant-reconcile")
    public Map<String, Object> triggerReconcile() {
        var result = tenantReconcileService.doReconcile(true);
        if (result == null) {
            return Map.of("success", false, "error", "Pageserver unreachable");
        }
        return Map.of(
            "success", true,
            "expected", result.expectedCount(),
            "attached", result.attachedCount(),
            "missing", result.missingCount(),
            "reattached", result.reattachedCount(),
            "failed", result.failedCount(),
            "reattached_databases", result.reattachedDatabases(),
            "failed_databases", result.failedDatabases()
        );
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

    @GetMapping("/infra/compute-summary")
    public Map<String, Object> getComputeSummary() {
        return adminService.getComputePodSummary();
    }

    @PostMapping("/infra/cleanup-idle-pods")
    public Map<String, Object> cleanupIdlePods() {
        return adminService.cleanupIdleComputePods();
    }

    @PostMapping("/infra/restart-pod/{podName}")
    public Map<String, Object> restartPod(@PathVariable String podName) {
        return adminService.restartComputePod(podName);
    }

    // ── Storage Management ──────────────────────────────────────────

    @GetMapping("/storage/summary")
    public Map<String, Object> getStorageSummary() {
        return adminService.getStorageSummary();
    }

    @PostMapping("/storage/scan")
    public Map<String, Object> scanOrphanStorage() {
        return adminService.scanOrphanStorage();
    }

    @PostMapping("/storage/cleanup")
    public Map<String, Object> cleanupOrphanStorage(@RequestBody Map<String, Object> body) {
        String tenantId = (String) body.get("tenant_id");
        boolean dryRun = body.get("dry_run") != null ? (Boolean) body.get("dry_run") : true;
        return adminService.cleanupOrphanStorage(tenantId, dryRun);
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

    // ── Structured Logs ───────────────────────────────────────────────

    @GetMapping("/structured-logs/search")
    public org.springframework.http.ResponseEntity<?> logSearch(
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tenant_id,
            @RequestParam(defaultValue = "1h") String since,
            @RequestParam(defaultValue = "100") int limit) {
        return org.springframework.http.ResponseEntity.ok(logQueryService.search(component, level, keyword, tenant_id, since, limit));
    }

    @GetMapping("/structured-logs/trace/{requestId}")
    public org.springframework.http.ResponseEntity<?> logTrace(@PathVariable String requestId) {
        return org.springframework.http.ResponseEntity.ok(logQueryService.trace(requestId));
    }

    @GetMapping("/structured-logs/errors")
    public org.springframework.http.ResponseEntity<?> logErrors(
            @RequestParam(defaultValue = "1h") String since,
            @RequestParam(required = false) String component) {
        return org.springframework.http.ResponseEntity.ok(logQueryService.errors(since, component));
    }

    @GetMapping("/structured-logs/stats")
    public org.springframework.http.ResponseEntity<?> logStats(@RequestParam(defaultValue = "24h") String since) {
        return org.springframework.http.ResponseEntity.ok(logQueryService.stats(since));
    }

}
