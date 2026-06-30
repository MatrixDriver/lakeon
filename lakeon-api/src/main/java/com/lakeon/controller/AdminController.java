package com.lakeon.controller;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.pageserver.PageserverPlacementService;
import com.lakeon.pageserver.PageserverRebalanceEventEntity;
import com.lakeon.pageserver.PageserverRebalanceEventService;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final TenantService tenantService;
    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;
    private final AdminService adminService;
    private final InviteCodeRepository inviteCodeRepository;
    private final PageserverPlacementService pageserverPlacementService;
    private final PageserverRebalanceEventService pageserverRebalanceEventService;
    private final OperationLogRepository operationLogRepository;
    private final LakeonProperties lakeonProperties;

    public AdminController(TenantService tenantService,
                           TenantRepository tenantRepository,
                           DatabaseRepository databaseRepository,
                           DatabaseService databaseService,
                           AdminService adminService,
                           InviteCodeRepository inviteCodeRepository,
                           PageserverPlacementService pageserverPlacementService,
                           PageserverRebalanceEventService pageserverRebalanceEventService,
                           OperationLogRepository operationLogRepository,
                           LakeonProperties lakeonProperties) {
        this.tenantService = tenantService;
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
        this.adminService = adminService;
        this.inviteCodeRepository = inviteCodeRepository;
        this.pageserverPlacementService = pageserverPlacementService;
        this.pageserverRebalanceEventService = pageserverRebalanceEventService;
        this.operationLogRepository = operationLogRepository;
        this.lakeonProperties = lakeonProperties;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard() {
        return adminService.getDashboard();
    }

    @GetMapping("/system/health")
    public Map<String, Object> systemHealth() {
        return adminService.checkAllComponents();
    }

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
        return tenantService.updateQuota(
                tenantId,
                request.maxDatabases(),
                request.maxStorageGb(),
                request.maxComputeCu());
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
                TenantEntity tenant = tenantRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Tenant not found: " + id));
                for (DatabaseEntity db : databaseRepository.findAllByTenantId(id)) {
                    try {
                        databaseService.purge(db.getId());
                    } catch (Exception e) {
                        errors.add(Map.of("id", id, "error", "database " + db.getId() + ": " + e.getMessage()));
                    }
                }
                tenantRepository.delete(tenant);
                deleted.add(id);
            } catch (Exception e) {
                errors.add(Map.of("id", id, "error", e.getMessage()));
            }
        }
        return Map.of("deleted", deleted, "errors", errors);
    }

    @GetMapping("/databases")
    public List<Map<String, Object>> listDatabases() {
        return databaseRepository.findAll().stream().map(this::databaseToMap).toList();
    }

    @GetMapping("/databases/{databaseId}")
    public Map<String, Object> getDatabase(@PathVariable String databaseId) {
        return databaseToMap(databaseRepository.findById(databaseId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId)));
    }

    @GetMapping("/compute/cold-start")
    public Map<String, Object> getColdStartAnalysis(@RequestParam(defaultValue = "7") int days,
                                                    @RequestParam(required = false) Instant now) {
        int windowDays = Math.max(1, Math.min(days, 90));
        Instant end = now != null ? now : Instant.now();
        Instant since = end.minusSeconds(windowDays * 24L * 60L * 60L);
        List<OperationLogEntity> logs = operationLogRepository.findByOperationTypeAndStatusAndStartedAtAfter(
                OperationType.RESUME, OperationStatus.SUCCESS, since).stream()
            .filter(op -> op.getDurationMs() != null)
            .toList();
        List<OperationLogEntity> cold = logs.stream()
            .filter(op -> "COLD".equalsIgnoreCase(op.getResumeType())
                || "POOL_MISS".equalsIgnoreCase(op.getResumeType()))
            .sorted(Comparator.comparing(OperationLogEntity::getStartedAt).reversed())
            .toList();
        List<OperationLogEntity> warm = logs.stream()
            .filter(op -> "WARM".equalsIgnoreCase(op.getResumeType()))
            .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", windowDays);
        out.put("since", since);
        out.put("cold", latencySummary(cold));
        out.put("warm", latencySummary(warm));
        out.put("trend", coldStartTrend(cold));
        out.put("by_database", coldStartByDatabase(cold));
        out.put("recent", cold.stream().limit(20).map(this::coldStartRecentToMap).toList());
        return out;
    }

    @GetMapping("/pageserver/topology")
    public Map<String, Object> getPageserverTopology() {
        Map<String, Map<String, Double>> loadBreakdown = pageserverPlacementService.loadBreakdown();
        return Map.of(
            "nodes", pageserverPlacementService.nodeStatuses().stream().map(status -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", status.node().id());
                out.put("http_url", status.node().httpUrl());
                out.put("pg_connstring", status.node().pgConnstring());
                out.put("healthy", status.healthy());
                out.put("load_score", status.loadScore());
                out.put("load_breakdown", loadBreakdown.getOrDefault(status.node().id(), Map.of()));
                out.put("source", status.source());
                out.put("instance_id", status.instanceId());
                out.put("failover_cooling_down", status.failoverCoolingDown());
                out.put("failover_cooldown_until", status.failoverCooldownUntil());
                return out;
            }).toList(),
            "placements", pageserverPlacementService.placements().stream().map(this::placementToMap).toList(),
            "decision_engine", dicerDecisionEngineToMap()
        );
    }

    @GetMapping("/pageserver/placements/{tenantId}")
    public List<Map<String, Object>> getTenantPageserverPlacements(@PathVariable String tenantId) {
        return pageserverPlacementService.placementsForTenant(tenantId).stream()
            .map(this::placementToMap)
            .toList();
    }

    @PostMapping("/pageserver/placements/{tenantId}/resolve")
    public Map<String, Object> resolvePageserverPlacement(@PathVariable String tenantId,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        int shardId = 0;
        if (body != null && body.get("shard_id") instanceof Number number) {
            shardId = number.intValue();
        }
        return placementToMap(pageserverPlacementService.resolve(tenantId, shardId));
    }

    @PostMapping("/pageserver/rebalance/dry-run")
    public Map<String, Object> dryRunPageserverRebalance() {
        var plan = pageserverPlacementService.rebalanceDryRun();
        return Map.of(
            "dry_run", plan.dryRun(),
            "moves", plan.moves().stream().map(this::moveToMap).toList()
        );
    }

    @PostMapping("/pageserver/rebalance/apply")
    public Map<String, Object> applyPageserverRebalance() {
        var plan = pageserverPlacementService.rebalanceApply();
        return Map.of(
            "dry_run", plan.dryRun(),
            "moves", plan.moves().stream().map(this::moveToMap).toList()
        );
    }

    @PostMapping("/pageserver/nodes/{nodeId}/failover")
    public Map<String, Object> failoverPageserverNode(@PathVariable String nodeId) {
        var plan = pageserverPlacementService.failoverNode(nodeId);
        return Map.of(
            "dry_run", plan.dryRun(),
            "moves", plan.moves().stream().map(this::moveToMap).toList()
        );
    }

    @GetMapping("/pageserver/rebalance/events")
    public List<Map<String, Object>> listPageserverRebalanceEvents(
            @RequestParam(defaultValue = "20") int limit) {
        return pageserverRebalanceEventService.recent(limit).stream()
            .map(this::rebalanceEventToMap)
            .toList();
    }

    @DeleteMapping("/databases/{databaseId}/purge")
    public Map<String, Object> purgeDatabase(@PathVariable String databaseId) {
        databaseService.purge(databaseId);
        return Map.of("deleted", true, "id", databaseId);
    }

    @PostMapping("/invite-codes")
    public Map<String, Object> createInviteCode(@RequestBody(required = false) Map<String, Object> body) {
        InviteCodeEntity entity = new InviteCodeEntity();
        if (body != null && body.get("max_uses") instanceof Number n) {
            entity.setMaxUses(n.intValue());
        }
        entity.setCreatedBy("admin");
        entity = inviteCodeRepository.save(entity);
        return inviteCodeToMap(entity);
    }

    @GetMapping("/invite-codes")
    public List<Map<String, Object>> listInviteCodes() {
        return inviteCodeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::inviteCodeToMap)
                .toList();
    }

    @DeleteMapping("/invite-codes/{code}")
    public void deleteInviteCode(@PathVariable String code) {
        inviteCodeRepository.deleteById(code.toUpperCase());
    }

    private Map<String, Object> databaseToMap(DatabaseEntity db) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", db.getId());
        out.put("tenant_id", db.getTenantId());
        out.put("name", db.getName());
        out.put("status", db.getStatus());
        out.put("created_at", db.getCreatedAt());
        out.put("updated_at", db.getUpdatedAt());
        out.put("compute_pod_name", db.getComputePodName());
        out.put("neon_tenant_id", db.getNeonTenantId());
        out.put("neon_timeline_id", db.getNeonTimelineId());
        out.put("pageserver_placement", pageserverPlacementToMap(db.getNeonTenantId()));
        out.put("connection_uri", db.getConnectionUri());
        out.put("pooled_connection_uri", databaseService.buildPooledConnectionUri(db.getConnectionUri()));
        out.put("storage_limit_gb", db.getStorageLimitGb());
        return out;
    }

    private Map<String, Object> pageserverPlacementToMap(String neonTenantId) {
        if (neonTenantId == null || neonTenantId.isBlank()) {
            return null;
        }
        return pageserverPlacementService.placementsForTenant(neonTenantId).stream()
            .filter(placement -> placement.shardId() == 0)
            .findFirst()
            .map(this::placementToMap)
            .orElse(null);
    }

    private Map<String, Object> inviteCodeToMap(InviteCodeEntity e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", e.getCode());
        out.put("max_uses", e.getMaxUses());
        out.put("used_count", e.getUsedCount());
        out.put("created_at", e.getCreatedAt());
        out.put("expires_at", e.getExpiresAt());
        out.put("valid", e.isValid());
        out.put("server_time", Instant.now());
        return out;
    }

    private Map<String, Object> latencySummary(List<OperationLogEntity> logs) {
        List<Long> durations = logs.stream()
            .map(OperationLogEntity::getDurationMs)
            .filter(v -> v != null && v >= 0)
            .sorted()
            .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", durations.size());
        out.put("avg_ms", durations.isEmpty() ? null :
            durations.stream().mapToLong(Long::longValue).average().orElse(0));
        out.put("p50_ms", percentile(durations, 50));
        out.put("p90_ms", percentile(durations, 90));
        out.put("p99_ms", percentile(durations, 99));
        out.put("max_ms", durations.isEmpty() ? null : durations.get(durations.size() - 1));
        return out;
    }

    private Long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private List<Map<String, Object>> coldStartTrend(List<OperationLogEntity> cold) {
        return cold.stream()
            .collect(Collectors.groupingBy(
                op -> op.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                java.util.TreeMap::new,
                Collectors.toList()))
            .entrySet().stream()
            .map(entry -> {
                Map<String, Object> out = latencySummary(entry.getValue());
                out.put("date", entry.getKey().toString());
                return out;
            })
            .toList();
    }

    private List<Map<String, Object>> coldStartByDatabase(List<OperationLogEntity> cold) {
        return cold.stream()
            .collect(Collectors.groupingBy(OperationLogEntity::getDatabaseId))
            .values().stream()
            .map(group -> {
                Map<String, Object> out = latencySummary(group);
                OperationLogEntity first = group.get(0);
                out.put("database_id", first.getDatabaseId());
                out.put("database", first.getDatabaseName());
                return out;
            })
            .sorted(Comparator.comparingInt(row -> -((Number) row.get("count")).intValue()))
            .limit(20)
            .toList();
    }

    private Map<String, Object> coldStartRecentToMap(OperationLogEntity op) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", op.getId());
        out.put("database_id", op.getDatabaseId());
        out.put("database_name", op.getDatabaseName());
        out.put("tenant_id", op.getTenantId());
        out.put("started_at", op.getStartedAt());
        out.put("duration_ms", op.getDurationMs());
        out.put("resume_type", op.getResumeType());
        return out;
    }

    private Map<String, Object> placementToMap(com.lakeon.pageserver.PageserverPlacement placement) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", placement.tenantId());
        out.put("shard_id", placement.shardId());
        out.put("node_id", placement.node().id());
        out.put("http_url", placement.node().httpUrl());
        out.put("pg_connstring", placement.node().pgConnstring());
        out.put("epoch", placement.epoch());
        out.put("source", placement.source());
        return out;
    }

    private Map<String, Object> dicerDecisionEngineToMap() {
        LakeonProperties.DicerConfig dicer = lakeonProperties.getDicer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", dicer.isEnabled() ? "dicer-assisted-placement" : "static-placement");
        out.put("endpoint", dicer.getEndpoint());
        out.put("transport", "grpc");
        out.put("live_load_enabled", dicer.isLiveLoadEnabled());
        out.put("auto_failover_enabled", dicer.isAutoFailoverEnabled());
        out.put("failover_node_cooldown_ms", dicer.getFailoverNodeCooldownMs());
        out.put("auto_rebalance_enabled", dicer.isAutoRebalanceEnabled());
        out.put("auto_rebalance_min_moves", dicer.getAutoRebalanceMinMoves());
        out.put("clerk_slicelet_integrated", false);
        return out;
    }

    private Map<String, Object> moveToMap(com.lakeon.pageserver.PageserverRebalancePlan.Move move) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", move.tenantId());
        out.put("shard_id", move.shardId());
        out.put("from_node_id", move.fromNodeId());
        out.put("to_node_id", move.toNodeId());
        out.put("next_epoch", move.nextEpoch());
        out.put("reason", move.reason());
        return out;
    }

    private Map<String, Object> rebalanceEventToMap(PageserverRebalanceEventEntity event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.getId());
        out.put("created_at", event.getCreatedAt());
        out.put("action", event.getAction());
        out.put("trigger_type", event.getTriggerType());
        out.put("actor", event.getActor());
        out.put("target_node_id", event.getTargetNodeId());
        out.put("dry_run", event.isDryRun());
        out.put("status", event.getStatus());
        out.put("move_count", event.getMoveCount());
        out.put("reason", event.getReason());
        out.put("moves", pageserverRebalanceEventService.parseMoves(event));
        return out;
    }

}
