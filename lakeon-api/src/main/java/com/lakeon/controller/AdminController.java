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
import com.lakeon.knowledge.*;
import com.lakeon.memory.MemoryBaseEntity;
import com.lakeon.memory.MemoryBaseRepository;
import com.lakeon.memory.MemoryService;
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
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final KbWriteTaskRepository kbWriteTaskRepository;
    private final KnowledgeService knowledgeService;
    private final MemoryBaseRepository memoryBaseRepository;
    private final MemoryService memoryService;

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
                           KnowledgeService knowledgeService,
                           MemoryBaseRepository memoryBaseRepository,
                           MemoryService memoryService) {
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
        this.knowledgeService = knowledgeService;
        this.memoryBaseRepository = memoryBaseRepository;
        this.memoryService = memoryService;
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

    @GetMapping("/infra/compute-summary")
    public Map<String, Object> getComputeSummary() {
        return adminService.getComputePodSummary();
    }

    @PostMapping("/infra/cleanup-idle-pods")
    public Map<String, Object> cleanupIdlePods() {
        return adminService.cleanupIdleComputePods();
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
