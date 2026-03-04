package com.lakeon.controller;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

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

        // endpointish format: "db_name" or "db_name--branch_name"
        // Branch-specific wake is not yet supported; always wakes main compute
        String dbName = endpointish.contains("--") ? endpointish.split("--", 2)[0] : endpointish;

        DatabaseEntity instance = dbRepo.findByName(dbName)
            .orElseThrow(() -> new RuntimeException("Database not found: " + dbName));

        String address;
        String coldStartInfo;
        if (instance.getStatus() == DatabaseStatus.RUNNING
            && instance.getComputeHost() != null && instance.getComputePort() != null) {
            address = instance.getComputeHost() + ":" + instance.getComputePort();
            coldStartInfo = "warm";
        } else if (instance.getStatus() == DatabaseStatus.RUNNING
            && instance.getComputePodName() != null) {
            // Pod is running but host/port not cached — look up pod IP directly
            String podIp = computePodManager.getPodIp(instance.getComputePodName());
            if (podIp != null) {
                address = podIp + ":55433";
                // Cache for next time
                instance.setComputeHost(podIp);
                instance.setComputePort(55433);
                dbRepo.save(instance);
                coldStartInfo = "warm";
            } else {
                address = databaseService.wakeCompute(instance);
                coldStartInfo = "pool_miss";
            }
        } else {
            address = databaseService.wakeCompute(instance);
            coldStartInfo = "pool_miss";
        }

        Map<String, Object> aux = new HashMap<>();
        aux.put("endpoint_id", instance.getId());
        aux.put("project_id", instance.getId());
        aux.put("branch_id", instance.getId());
        aux.put("compute_id", instance.getComputePodName() != null ? instance.getComputePodName() : "");
        aux.put("cold_start_info", coldStartInfo);

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

        String dbName = endpointish.contains("--") ? endpointish.split("--")[0] : endpointish;

        DatabaseEntity instance = dbRepo.findByName(dbName)
            .orElseThrow(() -> new RuntimeException("Database not found: " + dbName));

        Map<String, Object> result = new HashMap<>();
        result.put("role_secret", instance.getDbPassword());
        result.put("project_id", instance.getId());
        result.put("allowed_ips", new Object[0]);
        return result;
    }
}
