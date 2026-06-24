package com.lakeon.controller;

import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.UpdateQuotaRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.pageserver.PageserverPlacementService;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public AdminController(TenantService tenantService,
                           TenantRepository tenantRepository,
                           DatabaseRepository databaseRepository,
                           DatabaseService databaseService,
                           AdminService adminService,
                           InviteCodeRepository inviteCodeRepository,
                           PageserverPlacementService pageserverPlacementService) {
        this.tenantService = tenantService;
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
        this.adminService = adminService;
        this.inviteCodeRepository = inviteCodeRepository;
        this.pageserverPlacementService = pageserverPlacementService;
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

    @GetMapping("/pageserver/topology")
    public Map<String, Object> getPageserverTopology() {
        return Map.of(
            "nodes", pageserverPlacementService.nodeStatuses().stream().map(status -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", status.node().id());
                out.put("http_url", status.node().httpUrl());
                out.put("pg_connstring", status.node().pgConnstring());
                out.put("healthy", status.healthy());
                out.put("load_score", status.loadScore());
                out.put("source", status.source());
                return out;
            }).toList(),
            "placements", pageserverPlacementService.placements().stream().map(this::placementToMap).toList()
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

    @PostMapping("/pageserver/nodes/{nodeId}/failover")
    public Map<String, Object> failoverPageserverNode(@PathVariable String nodeId) {
        var plan = pageserverPlacementService.failoverNode(nodeId);
        return Map.of(
            "dry_run", plan.dryRun(),
            "moves", plan.moves().stream().map(this::moveToMap).toList()
        );
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
        out.put("storage_limit_gb", db.getStorageLimitGb());
        return out;
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
}
