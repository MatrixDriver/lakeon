package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.OperationType;
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
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ComputeLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ComputeLifecycleService.class);
    private static final long DEFAULT_WAKE_TIMEOUT_MS = 120_000L;
    private final ReentrantLock suspendLock = new ReentrantLock();

    private final DatabaseRepository databaseRepository;
    private final ComputePodManager computePodManager;
    private final OperationLogService operationLogService;
    private final LakeonProperties props;
    private final TransactionTemplate txTemplate;

    public ComputeLifecycleService(DatabaseRepository databaseRepository,
                                   ComputePodManager computePodManager,
                                   OperationLogService operationLogService,
                                   LakeonProperties props,
                                   TransactionTemplate txTemplate) {
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
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
