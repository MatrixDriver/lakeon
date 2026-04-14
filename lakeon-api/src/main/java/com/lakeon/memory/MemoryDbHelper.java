package com.lakeon.memory;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Resolves a memory base ID to a PostgreSQL connection string by waking the
 * compute pod and connecting directly via internal pod IP.
 */
@Component
public class MemoryDbHelper {
    private static final Logger log = LoggerFactory.getLogger(MemoryDbHelper.class);

    private final MemoryBaseRepository memoryBaseRepository;
    private final DatabaseRepository databaseRepository;
    private final ComputeLifecycleService computeLifecycleService;
    private final ComputePodManager computePodManager;

    public MemoryDbHelper(MemoryBaseRepository memoryBaseRepository,
                           DatabaseRepository databaseRepository,
                           @Lazy ComputeLifecycleService computeLifecycleService,
                           ComputePodManager computePodManager) {
        this.memoryBaseRepository = memoryBaseRepository;
        this.databaseRepository = databaseRepository;
        this.computeLifecycleService = computeLifecycleService;
        this.computePodManager = computePodManager;
    }

    /**
     * Try to sync PROVISIONING -> READY if backing database is ACTIVE.
     * Called from getBase() so polling clients see the status change.
     */
    public void trySyncStatus(MemoryBaseEntity mem) {
        if (!"PROVISIONING".equals(mem.getStatus()) || mem.getDatabaseId() == null) return;
        log.info("trySyncStatus: checking mem={} db={}", mem.getId(), mem.getDatabaseId());
        var optDb = databaseRepository.findById(mem.getDatabaseId());
        if (optDb.isEmpty()) {
            log.warn("trySyncStatus: backing database {} not found", mem.getDatabaseId());
            return;
        }
        var db = optDb.get();
        String dbStatus = db.getStatus().name();
        log.info("trySyncStatus: db={} status={}", mem.getDatabaseId(), dbStatus);
        if ("RUNNING".equals(dbStatus) || "SUSPENDED".equals(dbStatus)) {
            mem.setStatus("READY");
            memoryBaseRepository.save(mem);
            log.info("Memory base {} status synced to READY (db={}, dbStatus={})", mem.getId(), mem.getDatabaseId(), dbStatus);
        }
    }

    /**
     * Resolve memory base -> databaseId -> compute pod -> connection string.
     */
    public String resolveConnstr(String tenantId, String memId) {
        MemoryBaseEntity mem = memoryBaseRepository.findByIdAndTenantId(memId, tenantId)
                .orElseThrow(() -> new NotFoundException("Memory base not found: " + memId));
        if (!"READY".equals(mem.getStatus())) {
            if (mem.getDatabaseId() != null) {
                DatabaseEntity db = databaseRepository.findByIdAndTenantId(mem.getDatabaseId(), tenantId)
                        .orElse(null);
                String dbSt = db != null ? db.getStatus().name() : "";
                if ("RUNNING".equals(dbSt) || "SUSPENDED".equals(dbSt)) {
                    mem.setStatus("READY");
                    memoryBaseRepository.save(mem);
                    log.info("Memory base {} status synced to READY (db={})", memId, mem.getDatabaseId());
                } else {
                    throw new BadRequestException("Memory base is not ready. Current status: " + mem.getStatus());
                }
            } else {
                throw new BadRequestException("Memory base has no backing database");
            }
        }
        String databaseId = mem.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Memory base has no backing database");
        }

        DatabaseEntity db = databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));

        // Wake compute pod (no-op if already running)
        computeLifecycleService.wakeCompute(databaseId);

        // Get direct pod IP
        String podName = "compute-" + databaseId.replace("_", "-");
        if (!computePodManager.waitForPodReady(podName, 360_000)) {
            throw new RuntimeException("Compute pod not ready: " + podName);
        }
        String podIp = computePodManager.getPodIp(podName);
        if (podIp == null) {
            throw new RuntimeException("Compute pod IP not available for: " + podName);
        }

        log.info("resolveConnstr: mem={} db={} pod={} ip={}", memId, databaseId, podName, podIp);
        return "postgresql://cloud_admin:cloud-admin-internal@" + podIp + ":55433/" + db.getName()
                + "?sslmode=disable";
    }
}
