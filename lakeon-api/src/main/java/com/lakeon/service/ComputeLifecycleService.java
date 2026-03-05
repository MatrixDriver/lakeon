package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.WakeComputeTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ComputeLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ComputeLifecycleService.class);
    private static final long DEFAULT_WAKE_TIMEOUT_MS = 120_000L;
    private final ReentrantLock suspendLock = new ReentrantLock();

    private final DatabaseRepository databaseRepository;
    private final ComputePodManager computePodManager;

    public ComputeLifecycleService(DatabaseRepository databaseRepository,
                                   ComputePodManager computePodManager) {
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
    }

    /**
     * Wake compute for a database instance.
     * Creates a new Pod and waits for it to become ready.
     * Returns the compute address (host:port).
     */
    @Transactional
    public String wakeCompute(String dbId) {
        DatabaseEntity entity = databaseRepository.findById(dbId)
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // If already running, return existing address
        if (entity.getStatus() == DatabaseStatus.RUNNING
            && entity.getComputeHost() != null && entity.getComputePort() != null) {
            return entity.getComputeHost() + ":" + entity.getComputePort();
        }

        // Create compute pod
        String address = computePodManager.createComputePod(entity);

        // Wait for pod to be ready
        String podName = entity.getComputePodName();
        boolean ready = computePodManager.waitForPodReady(podName, DEFAULT_WAKE_TIMEOUT_MS);

        if (ready) {
            entity.setStatus(DatabaseStatus.RUNNING);
            databaseRepository.save(entity);
            return address;
        } else {
            entity.setStatus(DatabaseStatus.ERROR);
            databaseRepository.save(entity);
            throw new WakeComputeTimeoutException("Compute wake timeout for database: " + dbId);
        }
    }

    /**
     * Scheduled task to check for auto-suspend.
     * Runs every 30 seconds. Checks running instances whose last activity
     * exceeds the suspend_timeout, and suspends them.
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
        } finally {
            suspendLock.unlock();
        }
    }

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
                    entity.setLastActiveAt(java.time.Instant.now());
                    databaseRepository.save(entity);
                    continue;
                }
                log.info("Auto-suspending database {} (inactive for {}ms, timeout {}ms)",
                    entity.getId(), elapsed, timeout.toMillis());
                computePodManager.deleteComputePod(entity.getComputePodName());
                entity.setStatus(DatabaseStatus.SUSPENDED);
                entity.setComputePodName(null);
                entity.setComputeHost(null);
                entity.setComputePort(null);
                databaseRepository.save(entity);
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
