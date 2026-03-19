package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.k8s.KbWritePodManager;
import com.lakeon.knowledge.KbWriteTaskRepository;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.WakeComputeTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ComputeLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ComputeLifecycleService.class);
    private static final long DEFAULT_WAKE_TIMEOUT_MS = 120_000L;
    private final ReentrantLock suspendLock = new ReentrantLock();

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final ComputePodManager computePodManager;
    private final KbWritePodManager kbWritePodManager;
    private final KbWriteTaskRepository kbWriteTaskRepository;
    private final OperationLogService operationLogService;
    private final LakeonProperties props;
    private final TransactionTemplate txTemplate;

    public ComputeLifecycleService(DatabaseRepository databaseRepository,
                                   BranchRepository branchRepository,
                                   ComputePodManager computePodManager,
                                   KbWritePodManager kbWritePodManager,
                                   KbWriteTaskRepository kbWriteTaskRepository,
                                   OperationLogService operationLogService,
                                   LakeonProperties props,
                                   TransactionTemplate txTemplate) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.computePodManager = computePodManager;
        this.kbWritePodManager = kbWritePodManager;
        this.kbWriteTaskRepository = kbWriteTaskRepository;
        this.operationLogService = operationLogService;
        this.props = props;
        this.txTemplate = txTemplate;
    }

    /**
     * Wake compute for a database instance.
     * Warm path: if Pod still exists and is ready, returns immediately (~100ms).
     * Cold path: creates a new Pod and waits for it to become ready (~10s).
     */
    public String wakeCompute(String dbId) {
        // Transaction 1: read entity
        DatabaseEntity entity = txTemplate.execute(status ->
            databaseRepository.findById(dbId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId)));

        // If already running, return existing address
        if (entity.getStatus() == DatabaseStatus.RUNNING
            && entity.getComputeHost() != null && entity.getComputePort() != null) {
            return entity.getComputeHost() + ":" + entity.getComputePort();
        }

        // Warm path: Pod was retained after suspend, check if it's still alive
        if (entity.getComputePodName() != null && computePodManager.isPodReady(entity.getComputePodName())) {
            log.info("Warm wake for database {} — Pod {} still running", dbId, entity.getComputePodName());
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(dbId).orElseThrow();
                e.setStatus(DatabaseStatus.RUNNING);
                e.setSuspendedAt(null);
                e.setLastActiveAt(Instant.now());
                databaseRepository.save(e);
            });
            return entity.getComputeHost() + ":" + entity.getComputePort();
        }

        // Cold path: create new Pod (outside transaction, may block 120s)
        String address = computePodManager.createComputePod(entity);
        String podName = entity.getComputePodName();
        boolean ready = computePodManager.waitForPodReady(podName, DEFAULT_WAKE_TIMEOUT_MS);

        // Transaction 2: update status
        if (ready) {
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(dbId).orElseThrow();
                e.setStatus(DatabaseStatus.RUNNING);
                e.setSuspendedAt(null);
                e.setLastActiveAt(Instant.now());
                databaseRepository.save(e);
            });
            return address;
        } else {
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(dbId).orElseThrow();
                e.setStatus(DatabaseStatus.ERROR);
                databaseRepository.save(e);
            });
            throw new WakeComputeTimeoutException("Compute wake timeout for database: " + dbId);
        }
    }

    /**
     * Auto-suspend check. Runs every 30 seconds.
     * Marks inactive databases as SUSPENDED but retains the Pod for fast wake.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void checkAutoSuspend() {
        if (!suspendLock.tryLock()) {
            log.debug("Auto-suspend check already in progress, skipping");
            return;
        }
        try {
            doCheckAutoSuspend();
            doCleanupExpiredPods();
            doCleanupAndRecoverPods();
            doCleanupIdleKbWritePods();
        } finally {
            suspendLock.unlock();
        }
    }

    /**
     * Suspend: mark as SUSPENDED but KEEP the Pod running for warm wake.
     * Pod will be cleaned up later by doCleanupExpiredPods().
     */
    private void doCheckAutoSuspend() {
        List<DatabaseEntity> runningInstances = databaseRepository.findAllByStatus(DatabaseStatus.RUNNING);
        for (DatabaseEntity entity : runningInstances) {
            if (entity.getComputePodName() == null) continue;

            Duration timeout = parseDuration(entity.getSuspendTimeout());

            // Skip if entity was recently activated (grace period for job processing)
            if (entity.getLastActiveAt() != null
                    && entity.getLastActiveAt().isAfter(Instant.now().minus(timeout))) {
                continue;
            }

            long lastActivity = computePodManager.getLastActivityTime(entity.getComputePodName());
            long elapsed = System.currentTimeMillis() - lastActivity;

            if (elapsed > timeout.toMillis()) {
                // Before suspending, check if pod has active client connections
                if (computePodManager.hasActiveConnections(entity.getComputePodName())) {
                    log.debug("Skipping auto-suspend for {} — has active connections", entity.getId());
                    entity.setLastActiveAt(Instant.now());
                    databaseRepository.save(entity);
                    continue;
                }
                log.info("Auto-suspending database {} (inactive for {}ms, timeout {}ms) — Pod retained for warm wake",
                    entity.getId(), elapsed, timeout.toMillis());
                OperationLogEntity opLog = operationLogService.startOperation(
                        entity.getId(), entity.getTenantId(), entity.getName(), OperationType.SUSPEND);
                try {
                    // Key change: do NOT delete the Pod, just mark as SUSPENDED
                    entity.setStatus(DatabaseStatus.SUSPENDED);
                    entity.setSuspendedAt(Instant.now());
                    // Keep computePodName, computeHost, computePort for warm wake
                    databaseRepository.save(entity);
                    operationLogService.completeOperation(opLog, null);
                } catch (Exception e) {
                    operationLogService.completeOperation(opLog, e.getMessage());
                    log.error("Failed to auto-suspend database {}: {}", entity.getId(), e.getMessage());
                }
            }
        }

        // Branch auto-suspend
        List<BranchEntity> runningBranches = branchRepository.findByComputeStatus(ComputeStatus.RUNNING);
        for (BranchEntity branch : runningBranches) {
            if (branch.getComputePodName() == null) continue;

            String timeout = branch.getSuspendTimeout();
            if (timeout == null) {
                DatabaseEntity db = databaseRepository.findById(branch.getDatabaseId()).orElse(null);
                if (db != null) timeout = db.getSuspendTimeout();
            }
            if (timeout == null) timeout = "5m";
            Duration suspendAfter = parseDuration(timeout);
            Instant lastActive = branch.getLastActiveAt() != null ? branch.getLastActiveAt() : branch.getCreatedAt();
            if (lastActive != null && Instant.now().isAfter(lastActive.plus(suspendAfter))) {
                if (!computePodManager.hasActiveConnections(branch.getComputePodName())) {
                    log.info("Auto-suspending branch {} compute (idle since {})", branch.getName(), lastActive);
                    branch.setComputeStatus(ComputeStatus.SUSPENDED);
                    branch.setSuspendedAt(Instant.now());
                    branchRepository.save(branch);
                } else {
                    branch.setLastActiveAt(Instant.now());
                    branchRepository.save(branch);
                }
            }
        }
    }

    /**
     * Tiered cleanup: delete Pods that have been SUSPENDED longer than podRetainMinutes.
     * This releases compute resources for databases that haven't been accessed in a while.
     */
    private void doCleanupExpiredPods() {
        int retainMinutes = props.getSuspend().getPodRetainMinutes();
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(retainMinutes));

        List<DatabaseEntity> suspendedInstances = databaseRepository.findAllByStatus(DatabaseStatus.SUSPENDED);
        for (DatabaseEntity entity : suspendedInstances) {
            if (entity.getComputePodName() == null) continue;
            if (entity.getSuspendedAt() == null || entity.getSuspendedAt().isAfter(cutoff)) continue;

            log.info("Cleaning up expired Pod for database {} (suspended since {}, retain={}min)",
                entity.getId(), entity.getSuspendedAt(), retainMinutes);
            try {
                computePodManager.deleteComputePod(entity.getComputePodName());
                entity.setComputePodName(null);
                entity.setComputeHost(null);
                entity.setComputePort(null);
                databaseRepository.save(entity);
            } catch (Exception e) {
                log.error("Failed to cleanup Pod for database {}: {}", entity.getId(), e.getMessage());
            }
        }

        // Branch pod cleanup
        List<BranchEntity> suspendedBranches = branchRepository.findByComputeStatus(ComputeStatus.SUSPENDED);
        for (BranchEntity branch : suspendedBranches) {
            if (branch.getComputePodName() == null) continue;
            if (branch.getSuspendedAt() != null
                    && branch.getSuspendedAt().plus(Duration.ofMinutes(retainMinutes)).isBefore(Instant.now())) {
                log.info("Cleaning up expired branch Pod: {}", branch.getComputePodName());
                try {
                    computePodManager.deleteComputePod(branch.getComputePodName());
                    branch.setComputePodName(null);
                    branch.setComputeHost(null);
                    branch.setComputePort(null);
                    branchRepository.save(branch);
                } catch (Exception e) {
                    log.error("Failed to cleanup Pod for branch {}: {}", branch.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Single-pass pod cleanup: handles both orphaned pods and CrashLoopBackOff recovery.
     * One K8s list call + one DB scan, instead of two separate passes.
     */
    private void doCleanupAndRecoverPods() {
        List<ComputePodManager.PodInfo> allPods;
        try {
            allPods = computePodManager.listAllPods();
        } catch (Exception e) {
            log.debug("Failed to list compute pods: {}", e.getMessage());
            return;
        }
        if (allPods.isEmpty()) return;

        // Build pod-name → entity lookup from DB (single scan)
        java.util.Map<String, DatabaseEntity> dbByPod = new java.util.HashMap<>();
        for (DatabaseEntity db : databaseRepository.findAll()) {
            if (db.getComputePodName() != null) dbByPod.put(db.getComputePodName(), db);
        }
        java.util.Map<String, BranchEntity> brByPod = new java.util.HashMap<>();
        for (BranchEntity br : branchRepository.findAll()) {
            if (br.getComputePodName() != null) brByPod.put(br.getComputePodName(), br);
        }

        for (ComputePodManager.PodInfo pod : allPods) {
            boolean owned = dbByPod.containsKey(pod.name()) || brByPod.containsKey(pod.name());

            // CrashLoopBackOff recovery: reset owner to SUSPENDED and delete pod
            if (pod.crashLoop() && owned && computePodManager.getPodAgeSeconds(pod.name()) >= 60) {
                log.warn("Recovering CrashLoopBackOff pod: {}", pod.name());
                DatabaseEntity db = dbByPod.get(pod.name());
                if (db != null) {
                    db.setStatus(DatabaseStatus.SUSPENDED);
                    db.setComputePodName(null);
                    db.setComputeHost(null);
                    db.setComputePort(null);
                    db.setSuspendedAt(Instant.now());
                    databaseRepository.save(db);
                    log.info("Reset database {} to SUSPENDED after CrashLoopBackOff", db.getId());
                }
                BranchEntity br = brByPod.get(pod.name());
                if (br != null) {
                    br.setComputeStatus(ComputeStatus.SUSPENDED);
                    br.setComputePodName(null);
                    br.setComputeHost(null);
                    br.setComputePort(null);
                    br.setSuspendedAt(Instant.now());
                    branchRepository.save(br);
                    log.info("Reset branch {} to SUSPENDED after CrashLoopBackOff", br.getId());
                }
                try {
                    computePodManager.deleteComputePod(pod.name());
                } catch (Exception e) {
                    log.error("Failed to delete crash-loop pod {}: {}", pod.name(), e.getMessage());
                }
                continue;
            }

            // Orphan cleanup: pod has no owning entity
            if (!owned) {
                if (computePodManager.getPodAgeSeconds(pod.name()) < 600) {
                    log.debug("Skipping orphan check for young pod: {} (age < 600s)", pod.name());
                    continue;
                }
                log.warn("Cleaning up orphaned compute pod: {}", pod.name());
                try {
                    computePodManager.deleteComputePod(pod.name());
                } catch (Exception e) {
                    log.error("Failed to delete orphaned pod {}: {}", pod.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * Clean up kb-write pods that have no active tasks and have been idle
     * longer than the configured timeout.
     */
    private void doCleanupIdleKbWritePods() {
        int idleMinutes = props.getKbWrite().getIdleTimeoutMinutes();
        List<String> kbWriteDbIds = kbWritePodManager.listKbWritePodDatabaseIds();
        Set<String> activeDbIds = new java.util.HashSet<>(kbWriteTaskRepository.findDatabaseIdsWithActiveTasks());

        for (String dbId : kbWriteDbIds) {
            if (activeDbIds.contains(dbId)) continue;

            // Check pod age as proxy for idle time
            String podName = "kb-write-" + dbId.replace("_", "-");
            long ageSeconds = computePodManager.getPodAgeSeconds(podName);
            if (ageSeconds < idleMinutes * 60L) continue;

            log.info("Cleaning up idle kb-write pod for database {} (age={}s, threshold={}min)",
                    dbId, ageSeconds, idleMinutes);
            try {
                kbWritePodManager.deleteKbWritePod(dbId);
            } catch (Exception e) {
                log.error("Failed to delete idle kb-write pod for db {}: {}", dbId, e.getMessage());
            }
        }
    }

    private Duration parseDuration(String timeout) {
        if (timeout == null || timeout.isBlank()) return Duration.ofMinutes(5);
        try {
            int value = Integer.parseInt(timeout.replaceAll("[^0-9]", ""));
            if (value <= 0) return Duration.ofMinutes(5);
            if (timeout.endsWith("m")) return Duration.ofMinutes(value);
            if (timeout.endsWith("h")) return Duration.ofHours(value);
            return Duration.ofMinutes(5);
        } catch (NumberFormatException e) {
            log.warn("Invalid suspend timeout value '{}', using default 5m", timeout);
            return Duration.ofMinutes(5);
        }
    }
}
