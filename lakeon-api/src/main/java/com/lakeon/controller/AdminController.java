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
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.AlertService;
import com.lakeon.service.TenantReconcileService;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.service.AuditService;
import com.lakeon.service.LogQueryService;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.knowledge.*;
import com.lakeon.memory.MemoryBaseEntity;
import com.lakeon.memory.MemoryBaseRepository;
import com.lakeon.memory.MemoryService;
import com.lakeon.datalake.*;
import com.lakeon.dataset.*;
import com.lakeon.pipeline.*;
import com.lakeon.notebook.NotebookSessionEntity;
import com.lakeon.notebook.NotebookSessionRepository;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.SystemConfigEntity;
import com.lakeon.repository.SystemConfigRepository;
import com.lakeon.service.CbcBillingService;
import com.lakeon.service.DatabaseService;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;

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
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final KbWriteTaskRepository kbWriteTaskRepository;
    private final KbWriteQueue kbWriteQueue;
    private final KnowledgeService knowledgeService;
    private final MemoryBaseRepository memoryBaseRepository;
    private final MemoryService memoryService;
    private final DatalakeJobRepository datalakeJobRepository;
    private final DatalakeLogService datalakeLogService;
    private final DatalakeService datalakeService;
    private final DatasetRepository datasetRepository;
    private final DatasetService datasetService;
    private final NotebookSessionRepository notebookSessionRepository;
    private final LakeonProperties props;
    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;
    private final LogQueryService logQueryService;
    private final PipelineRepository pipelineRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineStepRunRepository pipelineStepRunRepository;
    private final PipelineComponentRepository pipelineComponentRepository;
    private final com.lakeon.knowledge.WikiService wikiService;
    private final TenantReconcileService tenantReconcileService;
    private final NeonApiClient neonApiClient;
    private final DataConsistencyCheckService dataConsistencyCheckService;
    private final StuckTaskQueryService stuckTaskQueryService;

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
                           KnowledgeBaseRepository knowledgeBaseRepository,
                           DocumentRepository documentRepository,
                           KbWriteTaskRepository kbWriteTaskRepository,
                           KbWriteQueue kbWriteQueue,
                           KnowledgeService knowledgeService,
                           MemoryBaseRepository memoryBaseRepository,
                           MemoryService memoryService,
                           DatalakeJobRepository datalakeJobRepository,
                           DatalakeLogService datalakeLogService,
                           DatalakeService datalakeService,
                           DatasetRepository datasetRepository,
                           DatasetService datasetService,
                           NotebookSessionRepository notebookSessionRepository,
                           LakeonProperties props,
                           SystemConfigRepository systemConfigRepository,
                           ObjectMapper objectMapper,
                           LogQueryService logQueryService,
                           PipelineRepository pipelineRepository,
                           PipelineRunRepository pipelineRunRepository,
                           PipelineStepRunRepository pipelineStepRunRepository,
                           PipelineComponentRepository pipelineComponentRepository,
                           com.lakeon.knowledge.WikiService wikiService,
                           TenantReconcileService tenantReconcileService,
                           NeonApiClient neonApiClient,
                           DataConsistencyCheckService dataConsistencyCheckService,
                           StuckTaskQueryService stuckTaskQueryService) {
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
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.kbWriteTaskRepository = kbWriteTaskRepository;
        this.kbWriteQueue = kbWriteQueue;
        this.knowledgeService = knowledgeService;
        this.memoryBaseRepository = memoryBaseRepository;
        this.memoryService = memoryService;
        this.datalakeJobRepository = datalakeJobRepository;
        this.datalakeLogService = datalakeLogService;
        this.datalakeService = datalakeService;
        this.datasetRepository = datasetRepository;
        this.datasetService = datasetService;
        this.notebookSessionRepository = notebookSessionRepository;
        this.props = props;
        this.systemConfigRepository = systemConfigRepository;
        this.objectMapper = objectMapper;
        this.logQueryService = logQueryService;
        this.pipelineRepository = pipelineRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineStepRunRepository = pipelineStepRunRepository;
        this.pipelineComponentRepository = pipelineComponentRepository;
        this.wikiService = wikiService;
        this.tenantReconcileService = tenantReconcileService;
        this.neonApiClient = neonApiClient;
        this.dataConsistencyCheckService = dataConsistencyCheckService;
        this.stuckTaskQueryService = stuckTaskQueryService;
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

                // 1. Cancel running datalake jobs + delete job records
                List<DatalakeJobEntity> jobs = datalakeJobRepository.findByTenantIdOrderByCreatedAtDesc(id);
                for (DatalakeJobEntity job : jobs) {
                    try {
                        if (!DatalakeJobStatus.SUCCEEDED.equals(job.getStatus())
                                && !DatalakeJobStatus.FAILED.equals(job.getStatus())
                                && !DatalakeJobStatus.CANCELLED.equals(job.getStatus())) {
                            datalakeService.cancelJob(id, job.getId());
                        }
                        datalakeJobRepository.delete(job);
                    } catch (Exception e) {
                        log.warn("Failed to delete datalake job {} for tenant {}: {}", job.getId(), id, e.getMessage());
                    }
                }

                // 2. Delete datasets (includes OBS file cleanup)
                List<DatasetEntity> datasets = datasetRepository.findAllByTenantIdOrderByCreatedAtDesc(id);
                for (DatasetEntity ds : datasets) {
                    try {
                        datasetService.deleteDataset(id, ds.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete dataset {} for tenant {}: {}", ds.getId(), id, e.getMessage());
                    }
                }

                // 3. Delete knowledge bases (includes documents, write tasks, backing DBs)
                List<KnowledgeBaseEntity> kbs = knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(id);
                for (KnowledgeBaseEntity kb : kbs) {
                    try {
                        knowledgeService.deleteKnowledgeBase(id, kb.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete knowledge base {} for tenant {}: {}", kb.getId(), id, e.getMessage());
                    }
                }

                // 4. Delete memory bases
                List<MemoryBaseEntity> memBases = memoryBaseRepository.findByTenantIdOrderByCreatedAtDesc(id);
                for (MemoryBaseEntity mem : memBases) {
                    try {
                        memoryService.deleteBase(id, mem.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete memory base {} for tenant {}: {}", mem.getId(), id, e.getMessage());
                    }
                }

                // 5. Delete databases (includes compute pods, Neon tenants/timelines)
                List<DatabaseEntity> dbs = databaseRepository.findAllByTenantId(id);
                for (DatabaseEntity db : dbs) {
                    try {
                        databaseService.delete(tenant, db.getId());
                    } catch (Exception e) {
                        log.warn("Failed to delete database {} for tenant {}: {}", db.getName(), id, e.getMessage());
                    }
                }

                // 6. Delete tenant entity
                tenantRepository.delete(tenant);
                deleted.add(id);
                log.info("Deleted tenant {} with {} databases, {} KBs, {} memory bases, {} datalake jobs, {} datasets",
                        id, dbs.size(), kbs.size(), memBases.size(), jobs.size(), datasets.size());
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

    // ── Knowledge Base Admin ──────────────────────────────────────

    @GetMapping("/knowledge/stats")
    public Map<String, Object> getKnowledgeStats() {
        long kbCount = knowledgeBaseRepository.count();
        long docCount = documentRepository.count();
        long processingCount = documentRepository.countByStatus(DocumentStatus.PROCESSING);
        long failedCount = documentRepository.countByStatus(DocumentStatus.FAILED);
        long readyCount = documentRepository.countByStatus(DocumentStatus.READY);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_count", kbCount);
        result.put("document_count", docCount);
        result.put("processing_count", processingCount);
        result.put("failed_count", failedCount);
        result.put("ready_count", readyCount);
        return result;
    }

    @GetMapping("/knowledge/bases")
    public List<Map<String, Object>> listAllKnowledgeBases(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        List<KnowledgeBaseEntity> kbs;
        if (tenantId != null) {
            kbs = knowledgeBaseRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            kbs = knowledgeBaseRepository.findAll();
        }
        return kbs.stream()
                .filter(kb -> status == null || (kb.getStatus() != null && kb.getStatus().name().equalsIgnoreCase(status)))
                .filter(kb -> type == null || (kb.getType() != null && kb.getType().name().equalsIgnoreCase(type)))
                .map(this::kbToMap)
                .toList();
    }

    @GetMapping("/knowledge/bases/{id}")
    public Map<String, Object> getKnowledgeBaseAdmin(@PathVariable String id) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Knowledge base not found: " + id));
        Map<String, Object> result = kbToMap(kb);
        List<DocumentEntity> docs = documentRepository.findAllByKbId(id);
        result.put("documents", docs.stream().map(this::docToMap).toList());
        return result;
    }

    @DeleteMapping("/knowledge/bases/{id}")
    public Map<String, Object> deleteKnowledgeBaseAdmin(@PathVariable String id) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Knowledge base not found: " + id));
        TenantEntity tenant = tenantRepository.findById(kb.getTenantId()).orElse(null);
        if (tenant != null) {
            knowledgeService.deleteKnowledgeBase(tenant.getId(), id);
        }
        return Map.of("deleted", id);
    }

    @GetMapping("/knowledge/documents")
    public List<Map<String, Object>> listAllDocuments(
            @RequestParam(required = false, name = "kb_id") String kbId,
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status) {
        List<DocumentEntity> docs;
        if (kbId != null) {
            docs = documentRepository.findAllByKbId(kbId);
        } else if (tenantId != null) {
            docs = documentRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            docs = documentRepository.findAll();
        }
        return docs.stream()
                .filter(d -> status == null || (d.getStatus() != null && d.getStatus().name().equalsIgnoreCase(status)))
                .map(this::docToMap)
                .toList();
    }

    @DeleteMapping("/knowledge/documents/{id}")
    public Map<String, Object> deleteDocumentAdmin(@PathVariable String id) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Document not found: " + id));
        TenantEntity tenant = tenantRepository.findById(doc.getTenantId()).orElse(null);
        if (tenant != null) {
            knowledgeService.deleteDocument(tenant.getId(), id);
        }
        return Map.of("deleted", id);
    }

    @PostMapping("/knowledge/documents/{id}/reprocess")
    public Map<String, Object> reprocessDocument(@PathVariable String id) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Document not found: " + id));
        // Reset to PENDING so it can be reprocessed
        doc.setStatus(DocumentStatus.PENDING);
        doc.setError(null);
        documentRepository.save(doc);
        TenantEntity tenant = tenantRepository.findById(doc.getTenantId()).orElse(null);
        if (tenant != null) {
            knowledgeService.processDocument(tenant, id);
        }
        return Map.of("document_id", id, "status", "PROCESSING");
    }

    @GetMapping("/knowledge/write-tasks")
    public List<Map<String, Object>> listWriteTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        List<KbWriteTaskEntity> tasks;
        if (status != null) {
            tasks = kbWriteTaskRepository.findAll().stream()
                    .filter(t -> t.getStatus().name().equalsIgnoreCase(status))
                    .toList();
        } else {
            tasks = kbWriteTaskRepository.findAll();
        }
        return tasks.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(limit)
                .map(this::taskToMap)
                .toList();
    }

    @PostMapping("/knowledge/write-tasks/{id}/fail")
    public Map<String, Object> failWriteTask(@PathVariable String id) {
        KbWriteTaskEntity task = kbWriteTaskRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Task not found: " + id));
        task.setStatus(KbWriteTaskStatus.FAILED);
        task.setError("Manually failed by admin");
        task.setCompletedAt(java.time.Instant.now());
        kbWriteTaskRepository.save(task);
        // Trigger drain for next task
        kbWriteQueue.onJobCompleted(task.getJobId() != null ? task.getJobId() : "manual-" + id, false, null, "Admin cancelled");
        return taskToMap(task);
    }

    // ── Pipeline Monitor ──────────────────────────────────────

    @GetMapping("/knowledge/pipeline/tasks")
    public Map<String, Object> getPipelineTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kbId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Specification<KbWriteTaskEntity> spec = Specification.where(null);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), KbWriteTaskStatus.valueOf(status)));
        }
        if (kbId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("kbId"), kbId));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var taskPage = kbWriteTaskRepository.findAll(spec, pageable);
        var paged = taskPage.getContent().stream()
                .map(this::pipelineTaskToMap)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", paged);
        result.put("total", taskPage.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @GetMapping("/knowledge/pipeline/stats")
    public Map<String, Object> getPipelineStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(7));
        Instant end = to != null ? to : Instant.now();
        var tasks = kbWriteTaskRepository.findByCreatedAtBetween(start, end);
        long total = tasks.size();
        long succeeded = tasks.stream().filter(t -> t.getStatus() == KbWriteTaskStatus.SUCCEEDED).count();
        long failed = tasks.stream().filter(t -> t.getStatus() == KbWriteTaskStatus.FAILED).count();
        long retried = tasks.stream().filter(t -> t.getRetryCount() > 0).count();
        double successRate = total > 0 ? (double) succeeded / total : 0.0;
        double retryRate = total > 0 ? (double) retried / total : 0.0;

        // Parse stage durations from result JSON of succeeded tasks
        Map<String, List<Long>> stageDurations = new LinkedHashMap<>();
        for (var task : tasks) {
            if (task.getStatus() != KbWriteTaskStatus.SUCCEEDED || task.getResult() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                var resultMap = objectMapper.readValue(task.getResult(), Map.class);
                Object stagesObj = resultMap.get("stages");
                if (stagesObj instanceof Map<?, ?> stagesMap) {
                    stagesMap.forEach((key, val) -> {
                        if (val instanceof Map<?, ?> stage) {
                            Object durationMs = stage.get("duration_ms");
                            if (durationMs instanceof Number num) {
                                stageDurations.computeIfAbsent(String.valueOf(key), k -> new ArrayList<>()).add(num.longValue());
                            }
                        }
                    });
                }
            } catch (Exception e) {
                log.debug("Failed to parse result JSON for task {}: {}", task.getId(), e.getMessage());
            }
        }
        Map<String, Long> avgStageDurations = new LinkedHashMap<>();
        stageDurations.forEach((name, durations) -> {
            long avg = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
            avgStageDurations.put(name, avg);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        result.put("retried", retried);
        result.put("success_rate", Math.round(successRate * 10000.0) / 10000.0);
        result.put("retry_rate", Math.round(retryRate * 10000.0) / 10000.0);
        result.put("avg_stage_durations_ms", avgStageDurations);
        result.put("period_start", start.toString());
        result.put("period_end", end.toString());
        return result;
    }

    // ── Wiki Agent Admin ──────────────────────────────────

    @GetMapping("/wiki/config")
    public Map<String, Object> getWikiConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("model", wikiService.getModel());
        config.put("base_url", props.getWiki() != null ? props.getWiki().getBaseUrl() : "");
        return config;
    }

    @PutMapping("/wiki/config")
    public Map<String, String> updateWikiConfig(@RequestBody Map<String, String> body) {
        if (body.containsKey("model")) {
            props.getWiki().setModel(body.get("model"));
        }
        if (body.containsKey("base_url")) {
            props.getWiki().setBaseUrl(body.get("base_url"));
        }
        return Map.of("status", "ok");
    }

    @GetMapping("/wiki/pages")
    public List<Map<String, Object>> adminListWikiPages(
            @RequestParam("kb_id") String kbId,
            @RequestParam(value = "doc_type", required = false, defaultValue = "wiki") String docType) {
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) return List.of();
        return documentRepository.findByTenantIdAndKbIdAndDocType(kb.getTenantId(), kbId, docType)
                .stream().map(this::toDocResponse).toList();
    }

    @GetMapping("/wiki/pages/{docId}/content")
    public Map<String, String> adminGetWikiPageContent(
            @PathVariable String docId, @RequestParam("kb_id") String kbId) {
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) return Map.of("content", "");
        String content = knowledgeService.getWikiPageContent(kb.getTenantId(), kbId, docId);
        return Map.of("content", content != null ? content : "");
    }

    private Map<String, Object> toDocResponse(com.lakeon.knowledge.DocumentEntity doc) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("filename", doc.getFilename());
        m.put("format", doc.getFormat());
        m.put("doc_type", doc.getDocType());
        m.put("status", doc.getStatus() != null ? doc.getStatus().name() : null);
        m.put("size_bytes", doc.getSizeBytes());
        m.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        return m;
    }

    // ── Memory Admin ──────────────────────────────────────

    @GetMapping("/memory/stats")
    public Map<String, Object> getMemoryStats() {
        List<MemoryBaseEntity> all = memoryBaseRepository.findAll();
        Map<String, Long> byStatus = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(MemoryBaseEntity::getStatus, java.util.stream.Collectors.counting()));
        long totalMemories = all.stream().mapToInt(m -> m.getMemoryCount() != null ? m.getMemoryCount() : 0).sum();
        long totalTraits = all.stream().mapToInt(m -> m.getTraitCount() != null ? m.getTraitCount() : 0).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_count", all.size());
        result.put("total_memories", totalMemories);
        result.put("total_traits", totalTraits);
        result.put("by_status", byStatus);
        return result;
    }

    @GetMapping("/memory/bases")
    public List<Map<String, Object>> listAllMemoryBases(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status) {
        List<MemoryBaseEntity> bases;
        if (tenantId != null) {
            bases = memoryBaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            bases = memoryBaseRepository.findAll();
            bases.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        }
        if (status != null) {
            bases = bases.stream().filter(b -> status.equalsIgnoreCase(b.getStatus())).toList();
        }
        return bases.stream().map(this::memBaseToMap).toList();
    }

    @GetMapping("/memory/bases/{id}")
    public Map<String, Object> getMemoryBaseAdmin(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        Map<String, Object> result = memBaseToMap(mem);
        // Try to fetch recent memories via proxy
        try {
            Object memories = memoryService.proxyGet(mem.getTenantId(), id, "/memories",
                    Map.of("limit", "10"));
            result.put("recent_memories", memories);
        } catch (Exception e) {
            result.put("recent_memories", List.of());
            result.put("recent_memories_error", e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/memory/bases/{id}")
    public Map<String, Object> deleteMemoryBaseAdmin(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        memoryBaseRepository.delete(mem);
        return Map.of("deleted", id);
    }

    @DeleteMapping("/memory/bases/batch")
    public Map<String, Object> batchDeleteMemoryBases(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.getOrDefault("ids", List.of());
        int count = 0;
        for (String id : ids) {
            memoryBaseRepository.findById(id).ifPresent(mem -> memoryBaseRepository.delete(mem));
            count++;
        }
        return Map.of("deleted", count);
    }

    @PostMapping("/memory/bases/{id}/digest")
    public Object triggerMemoryDigest(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        return memoryService.proxyPost(mem.getTenantId(), id, "/digest", null);
    }

    // ── MCP Tool Descriptions ──────────────────────────────────

    private static final String MCP_DESCRIPTIONS_KEY = "mcp_tool_descriptions";

    @SuppressWarnings("unchecked")
    @GetMapping("/mcp/descriptions")
    public Map<String, Object> getMcpDescriptions() {
        var entity = systemConfigRepository.findById(MCP_DESCRIPTIONS_KEY).orElse(null);
        if (entity == null || entity.getValue() == null || entity.getValue().isBlank()) {
            return Map.of("server_instructions", "", "tools", List.of(), "updated_at", "");
        }
        try {
            var yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> parsed = yaml.load(entity.getValue());
            // Extract server instructions
            String instructions = "";
            var server = (Map<String, Object>) parsed.getOrDefault("server", Map.of());
            if (server.get("instructions") != null) instructions = server.get("instructions").toString().trim();
            // Extract tools as list
            var toolsMap = (Map<String, Object>) parsed.getOrDefault("tools", Map.of());
            var toolsList = new ArrayList<Map<String, Object>>();
            toolsMap.forEach((name, val) -> {
                var toolDef = (Map<String, Object>) val;
                var item = new LinkedHashMap<String, Object>();
                item.put("name", name);
                item.put("description", toolDef.getOrDefault("description", "").toString().trim());
                toolsList.add(item);
            });
            return Map.of(
                "server_instructions", instructions,
                "tools", toolsList,
                "updated_at", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : ""
            );
        } catch (Exception e) {
            return Map.of("server_instructions", "", "tools", List.of(), "updated_at",
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : "", "error", e.getMessage());
        }
    }

    @PutMapping("/mcp/descriptions")
    public Map<String, Object> updateMcpDescriptions(@RequestBody Map<String, Object> body) {
        // Accept structured JSON, serialize to YAML for storage
        String serverInstructions = (String) body.getOrDefault("server_instructions", "");
        @SuppressWarnings("unchecked")
        var tools = (List<Map<String, String>>) body.getOrDefault("tools", List.of());

        // Build YAML structure
        var yamlMap = new LinkedHashMap<String, Object>();
        var serverMap = new LinkedHashMap<String, Object>();
        serverMap.put("instructions", serverInstructions);
        yamlMap.put("server", serverMap);

        var toolsMap = new LinkedHashMap<String, Object>();
        for (var tool : tools) {
            var toolDef = new LinkedHashMap<String, Object>();
            toolDef.put("description", tool.getOrDefault("description", ""));
            toolsMap.put(tool.get("name"), toolDef);
        }
        yamlMap.put("tools", toolsMap);

        // Preserve agents section from existing YAML if present
        var existing = systemConfigRepository.findById(MCP_DESCRIPTIONS_KEY).orElse(null);
        if (existing != null && existing.getValue() != null) {
            try {
                var yaml = new org.yaml.snakeyaml.Yaml();
                @SuppressWarnings("unchecked")
                Map<String, Object> oldParsed = yaml.load(existing.getValue());
                if (oldParsed.containsKey("agents")) {
                    yamlMap.put("agents", oldParsed.get("agents"));
                }
            } catch (Exception ignored) {}
        }

        var dumperOptions = new org.yaml.snakeyaml.DumperOptions();
        dumperOptions.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.LITERAL);
        dumperOptions.setPrettyFlow(true);
        var yaml = new org.yaml.snakeyaml.Yaml(dumperOptions);
        String yamlStr = yaml.dump(yamlMap);

        SystemConfigEntity entity = systemConfigRepository.findById(MCP_DESCRIPTIONS_KEY)
                .orElseGet(() -> { var e = new SystemConfigEntity(); e.setKey(MCP_DESCRIPTIONS_KEY); return e; });
        entity.setValue(yamlStr);
        systemConfigRepository.save(entity);
        return Map.of("status", "saved");
    }

    private Map<String, Object> memBaseToMap(MemoryBaseEntity m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("tenant_id", m.getTenantId());
        map.put("name", m.getName());
        map.put("description", m.getDescription());
        map.put("type", m.getType() != null ? m.getType().name() : null);
        map.put("status", m.getStatus());
        map.put("one_llm_mode", Boolean.TRUE.equals(m.getOneLlmMode()));
        map.put("database_id", m.getDatabaseId());
        map.put("memory_count", m.getMemoryCount());
        map.put("trait_count", m.getTraitCount());
        map.put("embedding_model", m.getEmbeddingModel());
        map.put("error", m.getError());
        map.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> kbToMap(KnowledgeBaseEntity kb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.getId());
        m.put("tenant_id", kb.getTenantId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("type", kb.getType() != null ? kb.getType().name() : "DOCUMENT");
        m.put("status", kb.getStatus() != null ? kb.getStatus().name() : null);
        m.put("database_id", kb.getDatabaseId());
        m.put("embedding_model", kb.getEmbeddingModel());
        m.put("document_count", kb.getDocumentCount());
        m.put("error", kb.getError());
        m.put("created_at", kb.getCreatedAt() != null ? kb.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> docToMap(DocumentEntity doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("tenant_id", doc.getTenantId());
        m.put("kb_id", doc.getKbId());
        m.put("filename", doc.getFilename());
        m.put("format", doc.getFormat());
        m.put("status", doc.getStatus() != null ? doc.getStatus().name() : null);
        m.put("size_bytes", doc.getSizeBytes());
        m.put("chunks_count", doc.getChunksCount());
        m.put("error", doc.getError());
        m.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> taskToMap(KbWriteTaskEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("tenant_id", t.getTenantId());
        m.put("kb_id", t.getKbId());
        m.put("database_id", t.getDatabaseId());
        m.put("type", t.getType().name());
        m.put("status", t.getStatus().name());
        m.put("job_id", t.getJobId());
        m.put("error", t.getError());
        m.put("created_at", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("started_at", t.getStartedAt() != null ? t.getStartedAt().toString() : null);
        m.put("completed_at", t.getCompletedAt() != null ? t.getCompletedAt().toString() : null);
        return m;
    }

    private Map<String, Object> pipelineTaskToMap(KbWriteTaskEntity t) {
        Map<String, Object> m = taskToMap(t);
        m.put("retry_count", t.getRetryCount());
        m.put("max_retries", t.getMaxRetries());
        m.put("error_category", t.getErrorCategory());
        m.put("next_retry_at", t.getNextRetryAt() != null ? t.getNextRetryAt().toString() : null);
        // Include result JSON (stages/metrics) as parsed object if possible
        if (t.getResult() != null) {
            try {
                m.put("result", objectMapper.readValue(t.getResult(), Map.class));
            } catch (Exception e) {
                m.put("result", t.getResult());
            }
        }
        if (t.getParams() != null) {
            try {
                m.put("params", objectMapper.readValue(t.getParams(), Map.class));
            } catch (Exception e) {
                m.put("params", t.getParams());
            }
        }
        return m;
    }

    // ── Datalake Admin ──────────────────────────────────────

    @GetMapping("/datalake/stats")
    public Map<String, Object> getDatalakeStats() {
        List<DatalakeJobEntity> all = datalakeJobRepository.findAll();
        Map<String, Long> byStatus = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        j -> j.getStatus().name(), java.util.stream.Collectors.counting()));
        Map<String, Long> byType = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        j -> j.getType().name(), java.util.stream.Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_count", all.size());
        result.put("by_status", byStatus);
        result.put("by_type", byType);
        result.put("running_count", byStatus.getOrDefault("RUNNING", 0L) + byStatus.getOrDefault("STARTING", 0L));
        result.put("failed_count", byStatus.getOrDefault("FAILED", 0L));
        return result;
    }

    @GetMapping("/datalake/jobs")
    public List<Map<String, Object>> listAllDatalakeJobs(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        List<DatalakeJobEntity> jobs;
        if (tenantId != null) {
            jobs = datalakeJobRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else if (status != null) {
            jobs = datalakeJobRepository.findByStatusOrderByCreatedAtDesc(
                    DatalakeJobStatus.valueOf(status.toUpperCase()));
        } else {
            jobs = datalakeJobRepository.findAllByOrderByCreatedAtDesc();
        }
        if (type != null) {
            DatalakeJobType t = DatalakeJobType.valueOf(type.toUpperCase());
            jobs = jobs.stream().filter(j -> j.getType() == t).toList();
        }
        return jobs.stream().map(this::datalakeJobToMap).toList();
    }

    @GetMapping("/datalake/jobs/{id}")
    public Map<String, Object> getDatalakeJobAdmin(@PathVariable String id) {
        DatalakeJobEntity job = datalakeJobRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
        Map<String, Object> result = datalakeJobToMap(job);
        result.put("spec", job.getSpec());
        return result;
    }

    @DeleteMapping("/datalake/jobs/{id}")
    public Map<String, Object> cancelDatalakeJobAdmin(@PathVariable String id) {
        DatalakeJobEntity job = datalakeJobRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
        if (job.getStatus() == DatalakeJobStatus.SUCCEEDED
                || job.getStatus() == DatalakeJobStatus.FAILED
                || job.getStatus() == DatalakeJobStatus.CANCELLED) {
            return Map.of("id", id, "status", job.getStatus().name(), "message", "Job already in terminal state");
        }
        job.setStatus(DatalakeJobStatus.CANCELLED);
        job.setFinishedAt(Instant.now());
        datalakeJobRepository.save(job);
        return Map.of("id", id, "status", "CANCELLED");
    }

    @GetMapping(value = "/datalake/jobs/{id}/logs", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamDatalakeJobLogsAdmin(@PathVariable String id) {
        DatalakeJobEntity job = datalakeJobRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
        return datalakeLogService.streamLogs(job.getTenantId(), id);
    }

    // ── Dataset Admin ──────────────────────────────────────

    @GetMapping("/datalake/datasets")
    public List<Map<String, Object>> listAllDatasets(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status) {
        List<DatasetEntity> datasets;
        if (tenantId != null) {
            datasets = datasetRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        } else if (status != null) {
            datasets = datasetRepository.findByStatusOrderByCreatedAtDesc(
                    DatasetStatus.valueOf(status.toUpperCase()));
        } else {
            datasets = datasetRepository.findAllByOrderByCreatedAtDesc();
        }
        return datasets.stream().map(this::datasetToMap).toList();
    }

    @GetMapping("/datalake/datasets/{id}")
    public Map<String, Object> getDatasetAdmin(@PathVariable String id) {
        DatasetEntity ds = datasetRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Dataset not found: " + id));
        Map<String, Object> result = datasetToMap(ds);
        result.put("schema_json", ds.getSchemaJson());
        return result;
    }

    @DeleteMapping("/datalake/datasets/{id}")
    public Map<String, Object> deleteDatasetAdmin(@PathVariable String id) {
        DatasetEntity ds = datasetRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Dataset not found: " + id));
        datasetRepository.delete(ds);
        return Map.of("deleted", id);
    }

    // ── Datalake Images ─────────────────────────────────────

    @GetMapping("/datalake/images")
    public Map<String, Object> getDatalakeImages() {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        Map<String, String> presetImages = dl.getPresetImages();

        // Get all sessions to compute startup stats per image
        List<NotebookSessionEntity> allSessions = notebookSessionRepository.findAll();

        // Build per-image stats: avg startup time, session count
        // Startup time ≈ updatedAt - createdAt for sessions that reached RUNNING
        Map<String, List<Long>> startupTimesPerImage = new LinkedHashMap<>();
        for (NotebookSessionEntity s : allSessions) {
            if (s.getImage() == null || s.getCreatedAt() == null || s.getUpdatedAt() == null) continue;
            long startupMs = Duration.between(s.getCreatedAt(), s.getUpdatedAt()).toMillis();
            if (startupMs <= 0 || startupMs > 300_000) continue; // skip invalid (>5min)
            startupTimesPerImage.computeIfAbsent(s.getImage(), k -> new ArrayList<>()).add(startupMs);
        }

        // Node pool info
        String nodePool = dl.getVkNodeSelectorKey() + "=" + dl.getVkNodeSelectorValue();

        // Get image sizes from K8s node status.images
        Map<String, Long> imageSizes = adminService.getNodeImageSizes();

        // Build response
        List<Map<String, Object>> images = new ArrayList<>();
        for (var entry : presetImages.entrySet()) {
            String key = entry.getKey();
            String fullImage = entry.getValue();
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("key", key);
            img.put("image", fullImage);
            img.put("node_pool", nodePool);
            img.put("size_bytes", imageSizes.get(fullImage));

            // Startup stats
            List<Long> times = startupTimesPerImage.getOrDefault(fullImage, List.of());
            img.put("session_count", times.size());
            if (!times.isEmpty()) {
                long avg = times.stream().mapToLong(Long::longValue).sum() / times.size();
                long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
                long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
                img.put("avg_startup_ms", avg);
                img.put("min_startup_ms", min);
                img.put("max_startup_ms", max);
            } else {
                img.put("avg_startup_ms", null);
                img.put("min_startup_ms", null);
                img.put("max_startup_ms", null);
            }
            images.add(img);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("images", images);
        result.put("node_selector", Map.of("key", dl.getVkNodeSelectorKey(), "value", dl.getVkNodeSelectorValue()));
        return result;
    }

    // ── Warm Pool ────────────────────────────────────────────────

    @GetMapping("/datalake/warm-pool")
    public Map<String, Object> getWarmPoolStatus() {
        return adminService.getWarmPoolStatus();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private Map<String, Object> datalakeJobToMap(DatalakeJobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("tenant_id", j.getTenantId());
        m.put("name", j.getName());
        m.put("type", j.getType().name());
        m.put("status", j.getStatus().name());
        m.put("base_image", j.getBaseImage());
        m.put("cci_namespace", j.getCciNamespace());
        m.put("k8s_job_name", j.getK8sJobName());
        m.put("ray_job_name", j.getRayJobName());
        m.put("log_obs_path", j.getLogObsPath());
        m.put("core_hours", j.getCoreHours());
        m.put("gpu_hours", j.getGpuHours());
        m.put("error_message", j.getErrorMessage());
        m.put("started_at", j.getStartedAt() != null ? j.getStartedAt().toString() : null);
        m.put("finished_at", j.getFinishedAt() != null ? j.getFinishedAt().toString() : null);
        m.put("created_at", j.getCreatedAt() != null ? j.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> datasetToMap(DatasetEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("tenant_id", ds.getTenantId());
        m.put("name", ds.getName());
        m.put("description", ds.getDescription());
        m.put("source_type", ds.getSourceType() != null ? ds.getSourceType().name() : null);
        m.put("database_id", ds.getDatabaseId());
        m.put("obs_path", ds.getObsPath());
        m.put("row_count", ds.getRowCount());
        m.put("file_size", ds.getFileSize());
        m.put("status", ds.getStatus() != null ? ds.getStatus().name() : null);
        m.put("job_id", ds.getJobId());
        m.put("error", ds.getError());
        m.put("created_at", ds.getCreatedAt() != null ? ds.getCreatedAt().toString() : null);
        return m;
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

    // ── Pipeline Admin ──────────────────────────────────────

    @GetMapping("/pipelines/stats")
    public Map<String, Object> getPipelineAdminStats() {
        List<PipelineEntity> allPipelines = pipelineRepository.findAll();
        List<PipelineRunEntity> allRuns = pipelineRunRepository.findAll();
        List<PipelineComponentEntity> allComponents = pipelineComponentRepository.findAll();

        long pipelineCount = allPipelines.stream().filter(p -> !Boolean.TRUE.equals(p.getIsTemplate())).count();
        long templateCount = allPipelines.stream().filter(p -> Boolean.TRUE.equals(p.getIsTemplate())).count();
        long componentCount = allComponents.size();

        Map<String, Long> runsByStatus = allRuns.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.getStatus().name(), java.util.stream.Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pipeline_count", pipelineCount);
        result.put("template_count", templateCount);
        result.put("component_count", componentCount);
        result.put("run_count", allRuns.size());
        result.put("running_count", runsByStatus.getOrDefault("RUNNING", 0L) + runsByStatus.getOrDefault("PENDING", 0L));
        result.put("succeeded_count", runsByStatus.getOrDefault("SUCCEEDED", 0L));
        result.put("failed_count", runsByStatus.getOrDefault("FAILED", 0L));
        result.put("cancelled_count", runsByStatus.getOrDefault("CANCELLED", 0L));
        return result;
    }

    @GetMapping("/pipelines")
    public List<Map<String, Object>> listAllPipelines(
            @RequestParam(required = false, name = "tenant_id") String tenantId) {
        List<PipelineEntity> pipelines;
        if (tenantId != null) {
            pipelines = pipelineRepository.findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc(tenantId);
        } else {
            pipelines = pipelineRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        return pipelines.stream().map(this::pipelineToMap).toList();
    }

    @GetMapping("/pipelines/runs")
    public List<Map<String, Object>> listAllPipelineRuns(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "pipeline_id") String pipelineId) {
        List<PipelineRunEntity> runs;
        if (tenantId != null) {
            runs = pipelineRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else if (status != null) {
            runs = pipelineRunRepository.findByStatus(PipelineRunStatus.valueOf(status));
        } else if (pipelineId != null) {
            runs = pipelineRunRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
        } else {
            runs = pipelineRunRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        return runs.stream().map(this::pipelineRunToMap).toList();
    }

    @GetMapping("/pipelines/runs/{id}")
    public Map<String, Object> getPipelineRunAdmin(@PathVariable String id) {
        PipelineRunEntity run = pipelineRunRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Pipeline run not found: " + id));
        Map<String, Object> result = pipelineRunToMap(run);
        // Include step runs
        List<PipelineStepRunEntity> steps = pipelineStepRunRepository.findByRunIdOrderByCreatedAtAsc(id);
        result.put("steps", steps.stream().map(this::stepRunToMap).toList());
        return result;
    }

    @GetMapping("/pipelines/components")
    public List<Map<String, Object>> listAllPipelineComponents() {
        return pipelineComponentRepository.findAll().stream().map(this::componentToMap).toList();
    }

    private Map<String, Object> pipelineToMap(PipelineEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("tenant_id", p.getTenantId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("data_type", p.getDataType());
        m.put("is_template", p.getIsTemplate());
        m.put("latest_version", p.getLatestVersion());
        m.put("created_at", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("updated_at", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> pipelineRunToMap(PipelineRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("pipeline_id", r.getPipelineId());
        m.put("pipeline_version", r.getPipelineVersion());
        m.put("tenant_id", r.getTenantId());
        m.put("input_dataset_id", r.getInputDatasetId());
        m.put("input_dataset_version", r.getInputDatasetVersion());
        m.put("output_dataset_version_id", r.getOutputDatasetVersionId());
        m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        m.put("started_at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
        m.put("finished_at", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
        m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> stepRunToMap(PipelineStepRunEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("run_id", s.getRunId());
        m.put("step_id", s.getStepId());
        m.put("component_id", s.getComponentId());
        m.put("component_version", s.getComponentVersion());
        m.put("status", s.getStatus() != null ? s.getStatus().name() : null);
        m.put("input_ref", s.getInputRef());
        m.put("output_ref", s.getOutputRef());
        m.put("metrics", s.getMetrics());
        m.put("error", s.getError());
        m.put("started_at", s.getStartedAt() != null ? s.getStartedAt().toString() : null);
        m.put("finished_at", s.getFinishedAt() != null ? s.getFinishedAt().toString() : null);
        m.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> componentToMap(PipelineComponentEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tenant_id", c.getTenantId());
        m.put("name", c.getName());
        m.put("display_name", c.getDisplayName());
        m.put("category", c.getCategory());
        m.put("data_type", c.getDataType());
        m.put("description", c.getDescription());
        m.put("latest_version", c.getLatestVersion());
        m.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        return m;
    }
}
