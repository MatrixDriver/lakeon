package com.lakeon.controller;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Proxy Adapter API.
 *
 * These endpoints are called by Neon Proxy. Proxy startup args:
 *   --auth-backend control-plane
 *   --auth-endpoint http://lakeon-api:8080/proxy
 *   --control-plane-token <internal_jwt>
 *
 * Proxy calls:
 *   GET /proxy/wake_compute?endpointish=<db_name>&session_id=...
 *   GET /proxy/get_endpoint_access_control?endpointish=<db_name>&role=<role>&session_id=...
 */
@RestController
@RequestMapping("/proxy")
public class ProxyAdapterController {
    private static final Logger log = LoggerFactory.getLogger(ProxyAdapterController.class);
    private final DatabaseService databaseService;
    private final DatabaseRepository dbRepo;
    private final BranchRepository branchRepo;
    private final ComputePodManager computePodManager;

    public ProxyAdapterController(DatabaseService databaseService,
                                  DatabaseRepository dbRepo,
                                  BranchRepository branchRepo,
                                  ComputePodManager computePodManager) {
        this.databaseService = databaseService;
        this.dbRepo = dbRepo;
        this.branchRepo = branchRepo;
        this.computePodManager = computePodManager;
    }

    /**
     * wake_compute - Called by Proxy when a connection arrives to wake compute.
     *
     * endpointish parameter: database instance name or "db_name--branch_name" format.
     */
    @GetMapping("/wake_compute")
    public Map<String, Object> wakeCompute(
            @RequestParam("endpointish") String endpointish,
            @RequestParam(value = "session_id", required = false) String sessionId,
            @RequestParam(value = "application_name", required = false) String applicationName) {

        EndpointRoute route = parseEndpointRoute(endpointish);

        // endpointish format: "db_name" or "db_name--branch_name"
        String dbName = route.normalized().contains("--")
            ? route.normalized().split("--", 2)[0]
            : route.normalized();

        DatabaseEntity db = dbRepo.findByName(dbName)
            .orElseThrow(() -> new RuntimeException("Database not found: " + dbName));

        BranchEntity branch = resolveBranch(db, route.normalized());

        String address;
        String coldStartInfo;

        // Hot path: branch compute is RUNNING and host/port cached
        if (branch.getComputeStatus() == ComputeStatus.RUNNING
                && branch.getComputeHost() != null && branch.getComputePort() != null
                && branch.getComputePodName() != null
                && computePodManager.isPodReady(branch.getComputePodName())) {
            address = branch.getComputeHost() + ":" + branch.getComputePort();
            branch.setLastActiveAt(Instant.now());
            branchRepo.save(branch);
            coldStartInfo = "warm";
        }
        // Warm path: pod exists and is ready but host/port not cached
        else if (branch.getComputePodName() != null
                && computePodManager.isPodReady(branch.getComputePodName())) {
            String podIp = computePodManager.getPodIp(branch.getComputePodName());
            if (podIp != null) {
                address = podIp + ":55433";
                branch.setComputeHost(podIp);
                branch.setComputePort(55433);
                branch.setComputeStatus(ComputeStatus.RUNNING);
                branch.setSuspendedAt(null);
                branch.setLastActiveAt(Instant.now());
                branchRepo.save(branch);
                coldStartInfo = "warm";
            } else {
                // Pod ready but no IP — fall through to cold path
                address = coldStartBranch(db, branch);
                coldStartInfo = "pool_miss";
            }
        }
        // Cold path: create new compute pod for branch
        else {
            address = coldStartBranch(db, branch);
            coldStartInfo = "pool_miss";
        }

        // Backward compatibility: if branch is default, sync database status to RUNNING
        if (branch.getIsDefault() && db.getStatus() != DatabaseStatus.RUNNING) {
            db.setStatus(DatabaseStatus.RUNNING);
            db.setComputeHost(branch.getComputeHost());
            db.setComputePort(branch.getComputePort());
            db.setComputePodName(branch.getComputePodName());
            db.setSuspendedAt(null);
            db.setLastActiveAt(Instant.now());
            dbRepo.save(db);
        }

        Map<String, Object> aux = new HashMap<>();
        aux.put("endpoint_id", db.getId());
        aux.put("project_id", db.getId());
        aux.put("branch_id", branch.getId());
        aux.put("compute_id", branch.getComputePodName() != null ? branch.getComputePodName() : "");
        aux.put("cold_start_info", coldStartInfo);
        aux.put("pooler_mode", route.pooled() ? "PROXY_POOLED" : "DIRECT");

        Map<String, Object> result = new HashMap<>();
        result.put("address", address);
        result.put("aux", aux);
        return result;
    }

    /**
     * get_endpoint_access_control - Called by Proxy to authenticate connections.
     *
     * Returns the role's password hash for Proxy to perform SCRAM authentication.
     */
    @GetMapping("/get_endpoint_access_control")
    public Map<String, Object> getEndpointAccessControl(
            @RequestParam("endpointish") String endpointish,
            @RequestParam("role") String role,
            @RequestParam(value = "session_id", required = false) String sessionId) {

        EndpointRoute route = parseEndpointRoute(endpointish);
        String dbName = route.normalized().contains("--")
            ? route.normalized().split("--", 2)[0]
            : route.normalized();

        DatabaseEntity db = dbRepo.findByName(dbName)
            .orElseThrow(() -> new RuntimeException("Database not found: " + dbName));

        // Resolve branch to validate it exists (but password comes from database)
        resolveBranch(db, route.normalized());

        Map<String, Object> result = new HashMap<>();
        result.put("role_secret", db.getDbPassword());
        result.put("project_id", db.getId());
        String ips = db.getAllowedIps();
        if (ips != null && !ips.isBlank()) {
            result.put("allowed_ips", java.util.Arrays.stream(ips.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new));
        } else {
            result.put("allowed_ips", new Object[0]);
        }
        return result;
    }

    private record EndpointRoute(String raw, String normalized, boolean pooled) {}

    private EndpointRoute parseEndpointRoute(String endpointish) {
        String suffix = "-pooler";
        if (endpointish.endsWith(suffix)) {
            return new EndpointRoute(
                endpointish,
                endpointish.substring(0, endpointish.length() - suffix.length()),
                true
            );
        }
        return new EndpointRoute(endpointish, endpointish, false);
    }

    /**
     * Resolve branch from endpointish. If "db_name--branch_name", find by name;
     * otherwise find the default branch.
     */
    private BranchEntity resolveBranch(DatabaseEntity db, String endpointish) {
        if (endpointish.contains("--")) {
            String branchName = endpointish.split("--", 2)[1];
            return branchRepo.findByDatabaseIdAndName(db.getId(), branchName)
                .orElseThrow(() -> new RuntimeException("Branch not found: " + branchName));
        }
        return branchRepo.findByDatabaseIdAndIsDefaultTrue(db.getId())
            .orElseThrow(() -> new IllegalStateException("No default branch for database " + db.getName()));
    }

    /**
     * Cold-start a compute pod for a branch: create pod, wait for ready, update branch fields.
     */
    private String coldStartBranch(DatabaseEntity db, BranchEntity branch) {
        long coldStartBegin = System.currentTimeMillis();
        log.info("Cold-starting compute for database {} branch {} ({})",
                db.getName(), branch.getName(), branch.getId());

        String address = computePodManager.createComputePodForBranch(db, branch);
        computePodManager.waitForPodReady(branch.getComputePodName(), 360_000);

        // Refresh pod IP after pod is ready
        String podIp = computePodManager.getPodIp(branch.getComputePodName());
        if (podIp != null) {
            branch.setComputeHost(podIp);
            branch.setComputePort(55433);
            address = podIp + ":55433";
        }

        branch.setComputeStatus(ComputeStatus.RUNNING);
        branch.setSuspendedAt(null);
        branch.setLastActiveAt(Instant.now());
        branchRepo.save(branch);

        long coldStartMs = System.currentTimeMillis() - coldStartBegin;
        log.info("compute started in {}ms for tenant={} db={}",
                coldStartMs, db.getTenantId(), db.getId());

        return address;
    }
}
