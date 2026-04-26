package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps database_instances.compute_host in sync with actual K8s pod IPs.
 *
 * Runs every 60s. For each RUNNING database:
 *   - if pod IP differs from stored host -> update the row
 *   - if pod is missing -> mark status=SUSPENDED so next ensureRunning re-provisions cold
 *
 * Complements the on-call L1 reconcile (in DatabaseService warm paths) and
 * the reactive L2 retry (AgentFSDatabaseManager.openAdmin). L3 gives SRE
 * visibility into drift rate and catches slow corner cases.
 */
@Component
@ConditionalOnProperty(name = "lakeon.compute-pod.reconcile.enabled",
                       havingValue = "true", matchIfMissing = true)
public class ComputePodReconcileService {
    private static final Logger log = LoggerFactory.getLogger(ComputePodReconcileService.class);

    private final DatabaseRepository databaseRepository;
    private final ComputePodManager computePodManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<ReconcileResult> lastResult = new AtomicReference<>();

    public record ReconcileResult(
        Instant timestamp,
        int scanned,
        int drifted,
        int markedSuspended,
        List<String> driftedIds,
        List<String> suspendedIds
    ) {}

    public ComputePodReconcileService(DatabaseRepository databaseRepository,
                                       ComputePodManager computePodManager) {
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
    }

    @Scheduled(fixedDelayString = "${lakeon.compute-pod.reconcile.interval-ms:60000}",
               initialDelay = 45000)
    public void scheduledReconcile() {
        if (!lock.tryLock()) {
            log.debug("ComputePod reconcile already running, skipping");
            return;
        }
        try {
            reconcile(false);
        } catch (Exception e) {
            log.error("ComputePod reconcile crashed: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Skip rows updated within this window so an in-flight cold start isn't disturbed. */
    private static final long YOUNG_ROW_GRACE_SECONDS = 420;

    public ReconcileResult reconcile(boolean manualTrigger) {
        List<DatabaseEntity> running = databaseRepository.findAllByStatus(DatabaseStatus.RUNNING);
        List<String> drifted = new ArrayList<>();
        List<String> suspended = new ArrayList<>();

        for (DatabaseEntity db : running) {
            if (db.getComputePodName() == null) {
                // Orphan: status=RUNNING but no pod recorded. Causes seen in
                // production: cold-start aborted after status flipped to RUNNING
                // but before pod_name was persisted; or an external path cleared
                // pod_name without updating status. L3 used to skip these rows
                // entirely (`continue`), so they sat as RUNNING+null forever
                // (e.g. mem_97baf5eb went 3+ days unhealed).
                //
                // Skip very young rows in case wakeCompute is mid-flight; older
                // rows are real orphans — flip to SUSPENDED so the next access
                // does a clean cold re-provision.
                if (db.getUpdatedAt() != null
                    && Instant.now().minusSeconds(YOUNG_ROW_GRACE_SECONDS).isBefore(db.getUpdatedAt())) {
                    log.debug("reconcile: skipping young orphan db {} (updated_at within grace window)",
                              db.getId());
                    continue;
                }
                log.warn("reconcile: orphan db {} ({}) RUNNING with no pod_name; marking SUSPENDED",
                         db.getId(), db.getName());
                db.setStatus(DatabaseStatus.SUSPENDED);
                db.setComputeHost(null);
                db.setComputePort(null);
                databaseRepository.save(db);
                suspended.add(db.getId());
                continue;
            }
            String actual = computePodManager.getPodIp(db.getComputePodName());
            if (actual == null) {
                // Young pod: IP not yet assigned, or still being scheduled. Don't
                // touch the row — wakeCompute owns it and will set the IP when
                // waitForPodReady returns. Without this guard we race against the
                // wake path: clearing computePodName makes doCleanupAndRecoverPods
                // treat the fresh pod as orphan and delete it.
                if (computePodManager.getPodAgeSeconds(db.getComputePodName()) < 420) {
                    log.debug("reconcile: skipping young pod {} for db {} (age < 420s)",
                              db.getComputePodName(), db.getId());
                    continue;
                }
                // Pod gone - mark stale so next ensureRunning does cold re-provision
                log.warn("reconcile: pod {} missing for db {} ({}); marking SUSPENDED",
                         db.getComputePodName(), db.getId(), db.getName());
                db.setStatus(DatabaseStatus.SUSPENDED);
                db.setComputeHost(null);
                db.setComputePort(null);
                db.setComputePodName(null);
                databaseRepository.save(db);
                suspended.add(db.getId());
            } else if (!actual.equals(db.getComputeHost())) {
                log.info("reconcile: db {} pod {} IP drifted ({} -> {})",
                         db.getId(), db.getComputePodName(), db.getComputeHost(), actual);
                db.setComputeHost(actual);
                databaseRepository.save(db);
                drifted.add(db.getId());
            }
        }

        ReconcileResult r = new ReconcileResult(
            Instant.now(), running.size(), drifted.size(), suspended.size(),
            drifted, suspended
        );
        lastResult.set(r);
        if (manualTrigger || !drifted.isEmpty() || !suspended.isEmpty()) {
            log.info("reconcile: scanned={} drifted={} suspended={}",
                     running.size(), drifted.size(), suspended.size());
        }
        return r;
    }

    public ReconcileResult getLastResult() {
        return lastResult.get();
    }
}
