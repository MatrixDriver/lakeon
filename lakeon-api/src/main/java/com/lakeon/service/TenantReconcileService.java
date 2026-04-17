package com.lakeon.service;

import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.exception.NeonApiException;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
public class TenantReconcileService {
    private static final Logger log = LoggerFactory.getLogger(TenantReconcileService.class);

    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final ReentrantLock lock = new ReentrantLock();

    // Expose last reconcile result for SRE API
    private final AtomicReference<ReconcileResult> lastResult = new AtomicReference<>();

    public record ReconcileResult(
        Instant timestamp,
        int expectedCount,
        int attachedCount,
        int missingCount,
        int reattachedCount,
        int failedCount,
        java.util.List<String> missingDatabases,
        java.util.List<String> reattachedDatabases,
        java.util.List<String> failedDatabases
    ) {}

    public TenantReconcileService(DatabaseRepository databaseRepository, NeonApiClient neonApiClient) {
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
    }

    @Scheduled(fixedDelayString = "${lakeon.tenant.reconcile-interval-ms:60000}", initialDelay = 30000)
    public void reconcile() {
        if (!lock.tryLock()) {
            log.debug("Tenant reconcile already running, skipping");
            return;
        }
        try {
            doReconcile(false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run reconcile — checks all active DBs against pageserver, re-attaches missing tenants.
     * Called by scheduled task and SRE manual trigger.
     */
    public ReconcileResult doReconcile(boolean manualTrigger) {
        var dbs = databaseRepository.findAllActiveWithNeonTenant();
        Set<String> expected = dbs.stream()
            .map(db -> db.getNeonTenantId())
            .collect(Collectors.toSet());

        Set<String> attached;
        try {
            var tenants = neonApiClient.listTenants();
            attached = tenants.stream()
                .map(t -> (String) t.get("id"))
                .collect(Collectors.toSet());
        } catch (NeonApiException e) {
            log.warn("Tenant reconcile: failed to list pageserver tenants: {}", e.getMessage());
            return null;
        }

        var missing = expected.stream()
            .filter(id -> !attached.contains(id))
            .collect(Collectors.toSet());

        var reattached = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<String>();

        if (!missing.isEmpty()) {
            log.warn("Tenant reconcile: {} missing from pageserver out of {} expected",
                missing.size(), expected.size());

            for (String tenantId : missing) {
                String dbName = dbs.stream()
                    .filter(db -> tenantId.equals(db.getNeonTenantId()))
                    .map(db -> db.getName())
                    .findFirst().orElse("?");
                try {
                    neonApiClient.createTenant(tenantId);
                    reattached.add(dbName + " (" + tenantId.substring(0, 8) + ")");
                    log.info("Tenant reconcile: re-attached {} ({})", dbName, tenantId);
                } catch (Exception e) {
                    failed.add(dbName + " (" + tenantId.substring(0, 8) + ")");
                    log.error("Tenant reconcile: failed to re-attach {} ({}): {}",
                        dbName, tenantId, e.getMessage());
                }
            }
        } else if (manualTrigger) {
            log.info("Tenant reconcile: all {} tenants attached", expected.size());
        }

        var missingNames = missing.stream().map(tid ->
            dbs.stream().filter(db -> tid.equals(db.getNeonTenantId()))
                .map(db -> db.getName()).findFirst().orElse(tid.substring(0, 8))
        ).collect(Collectors.toList());

        var result = new ReconcileResult(
            Instant.now(),
            expected.size(),
            attached.size(),
            missing.size(),
            reattached.size(),
            failed.size(),
            missingNames,
            reattached,
            failed
        );
        lastResult.set(result);
        return result;
    }

    public ReconcileResult getLastResult() {
        return lastResult.get();
    }
}
