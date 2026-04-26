package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.*;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ComputeSize;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.NeonTenant;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.QuotaExceededException;
import com.lakeon.service.exception.ServiceException;
import com.lakeon.util.ScramUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DatabaseService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;
    private final LakeonProperties props;
    private final OperationLogService operationLogService;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;
    private final DatabaseProvisioningService provisioningService;
    private final Counter wakeupFailureCounter;

    public DatabaseService(DatabaseRepository databaseRepository,
                           BranchRepository branchRepository,
                           NeonApiClient neonApiClient,
                           ComputePodManager computePodManager,
                           LakeonProperties props,
                           OperationLogService operationLogService,
                           MeterRegistry meterRegistry,
                           TransactionTemplate txTemplate,
                           @org.springframework.context.annotation.Lazy DatabaseProvisioningService provisioningService) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.neonApiClient = neonApiClient;
        this.computePodManager = computePodManager;
        this.props = props;
        this.operationLogService = operationLogService;
        this.meterRegistry = meterRegistry;
        this.txTemplate = txTemplate;
        this.provisioningService = provisioningService;
        this.wakeupFailureCounter = Counter.builder("lakeon_compute_wakeup_failures_total")
            .description("Total number of compute wakeup failures")
            .register(meterRegistry);
    }

    public DatabaseResponse create(TenantEntity tenant, CreateDatabaseRequest request) {
        // Generate credentials
        String dbUser = "user_" + UUID.randomUUID().toString().substring(0, 8);
        String rawPassword = generatePassword();
        String scramHash = ScramUtils.generateScramHash(rawPassword);

        // Transaction 1: validate + create Neon resources + save entity as CREATING
        record CreateResult(DatabaseEntity entity, NeonTimeline neonTimeline) {}
        CreateResult created = txTemplate.execute(status -> {
            // Check name uniqueness
            databaseRepository.findByTenantIdAndName(tenant.getId(), request.name()).ifPresent(existing -> {
                throw new ConflictException("Database '" + request.name() + "' already exists for this tenant");
            });

            // Check quota: database count
            int currentDbCount = databaseRepository.findAllByTenantId(tenant.getId()).size();
            if (tenant.getMaxDatabases() != null && currentDbCount >= tenant.getMaxDatabases()) {
                throw new QuotaExceededException(
                    "Database quota exceeded: limit is " + tenant.getMaxDatabases() + ", current count is " + currentDbCount);
            }

            // Apply defaults
            String computeSize = request.computeSize() != null ? request.computeSize() : props.getDefaults().getComputeSize();
            String suspendTimeout = request.suspendTimeout() != null ? request.suspendTimeout() : props.getDefaults().getSuspendTimeout();
            int storageLimitGb = request.storageLimitGb() != null ? request.storageLimitGb() : props.getDefaults().getStorageLimitGb();

            // Check quota: compute size
            int requestedCu = parseComputeUnits(computeSize);
            if (tenant.getMaxComputeCu() != null && requestedCu > tenant.getMaxComputeCu()) {
                throw new QuotaExceededException(
                    "Compute quota exceeded: requested " + computeSize + " but max allowed is " + tenant.getMaxComputeCu() + "cu");
            }

            // Create Neon tenant
            NeonTenant neonTenant;
            try {
                neonTenant = neonApiClient.createTenant(generateHexId());
            } catch (Exception e) {
                throw new ServiceException("Failed to create Neon tenant: " + e.getMessage(), e);
            }

            // Wait for tenant to become Active before creating timeline
            try {
                neonApiClient.waitForTenantActive(neonTenant.getId(), 30);
            } catch (Exception e) {
                try { neonApiClient.deleteTenant(neonTenant.getId()); } catch (Exception rollbackEx) {
                    log.warn("Failed to rollback Neon tenant {}: {}", neonTenant.getId(), rollbackEx.getMessage());
                }
                throw new ServiceException("Tenant did not become active: " + e.getMessage(), e);
            }

            // Create Neon timeline
            NeonTimeline neonTimeline;
            try {
                neonTimeline = neonApiClient.createTimeline(neonTenant.getId(),
                    CreateTimelineRequest.forNewTimeline(generateHexId(), 17));
            } catch (Exception e) {
                try { neonApiClient.deleteTenant(neonTenant.getId()); } catch (Exception rollbackEx) {
                    log.warn("Failed to rollback Neon tenant {}: {}", neonTenant.getId(), rollbackEx.getMessage());
                }
                throw new ServiceException("Failed to create Neon timeline: " + e.getMessage(), e);
            }

            // Build entity
            DatabaseEntity entity = new DatabaseEntity();
            entity.setName(request.name());
            entity.setTenantId(tenant.getId());
            entity.setNeonTenantId(neonTenant.getId());
            entity.setNeonTimelineId(neonTimeline.getTimelineId());
            entity.setStatus(DatabaseStatus.CREATING);
            entity.setStatusMessage("正在准备存储资源...");
            entity.setComputeSize(computeSize);
            entity.setSuspendTimeout(suspendTimeout);
            entity.setStorageLimitGb(storageLimitGb);
            entity.setDbUser(dbUser);
            entity.setDbPassword(scramHash);

            String proxyHost = props.getProxy().getExternalHost();
            if (proxyHost != null && !proxyHost.isBlank()) {
                entity.setComputeHost(proxyHost);
            } else {
                entity.setComputeHost("proxy.lakeon.svc.cluster.local");
            }
            entity.setComputePort(props.getProxy().getExternalPort());

            DatabaseEntity saved = databaseRepository.save(entity);
            return new CreateResult(saved, neonTimeline);
        });

        DatabaseEntity entity = created.entity();
        NeonTimeline neonTimeline = created.neonTimeline();

        // Start operation log
        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.CREATE);

        // Launch async provisioning (via separate service to ensure @Async proxy works)
        provisioningService.provisionAsync(entity.getId(), neonTimeline.getTimelineId(), dbUser, opLog.getId());

        // Return immediately with CREATING status
        DatabaseResponse response = toResponse(entity, List.of());
        response.setPassword(rawPassword);
        return response;
    }

    public DatabaseResponse get(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        List<BranchEntity> branches = branchRepository.findAllByDatabaseId(dbId);
        return toResponse(entity, branches);
    }

    public DatabaseMetrics getMetrics(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        DatabaseMetrics m = new DatabaseMetrics();
        m.setStatus(entity.getStatus().name());
        m.setStorageUsedGb(fetchStorageUsedGb(entity));
        m.setStorageLimitGb(entity.getStorageLimitGb());

        ComputeSize spec = ComputeSize.fromLabel(entity.getComputeSize());
        m.setCpuLimit(Double.parseDouble(spec.getCpu()));
        String memStr = spec.getMemory().replace("Gi", "");
        m.setMemoryLimitMb(Double.parseDouble(memStr) * 1024);

        String podName = entity.getComputePodName();
        if (podName != null) {
            m.setActiveConnections(computePodManager.getActiveConnectionCount(podName));
            m.setSlowQueries(computePodManager.getSlowQueryCount(podName));

            double[] resources = computePodManager.getComputePodResourceUsage(podName);
            if (resources != null) {
                m.setCpuUsage(Math.round(resources[0] * 1000) / 1000.0);
                m.setMemoryUsageMb(Math.round(resources[1] / (1024 * 1024) * 10) / 10.0);
            }
        }
        return m;
    }

    public List<Map<String, String>> getDatabaseLogs(TenantEntity tenant, String dbId, int tailLines) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        String podName = entity.getComputePodName();
        if (podName == null) return List.of();

        String rawLogs = computePodManager.getComputePodLogs(podName, Math.min(tailLines, 500));
        if (rawLogs == null || rawLogs.isBlank()) return List.of();

        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (String line : rawLogs.split("\n")) {
            if (line.isBlank()) continue;
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            // Try to parse PG log format: "2026-03-09 12:00:00.123 UTC [pid] LOG:  message"
            String level = "INFO";
            String message = line;
            String timestamp = "";
            if (line.length() > 24 && line.charAt(4) == '-') {
                int bracketEnd = line.indexOf(']');
                if (bracketEnd > 0 && bracketEnd + 2 < line.length()) {
                    timestamp = line.substring(0, Math.min(23, line.length()));
                    String rest = line.substring(bracketEnd + 1).trim();
                    int colonIdx = rest.indexOf(':');
                    if (colonIdx > 0 && colonIdx < 12) {
                        level = rest.substring(0, colonIdx).trim();
                        message = rest.substring(colonIdx + 1).trim();
                    } else {
                        message = rest;
                    }
                }
            }
            entry.put("timestamp", timestamp);
            entry.put("level", level);
            entry.put("message", message);
            result.add(entry);
        }
        return result;
    }

    public List<DatabaseResponse> list(TenantEntity tenant) {
        return listInternal(databaseRepository.findAllByTenantIdAndStatusNot(tenant.getId(), DatabaseStatus.DELETED));
    }

    public List<DatabaseResponse> listDeleted(TenantEntity tenant) {
        return listInternal(databaseRepository.findAllByTenantIdAndStatus(tenant.getId(), DatabaseStatus.DELETED));
    }

    private List<DatabaseResponse> listInternal(List<DatabaseEntity> dbs) {
        if (dbs.isEmpty()) return List.of();
        List<String> dbIds = dbs.stream().map(DatabaseEntity::getId).toList();
        Map<String, List<BranchEntity>> branchesByDb = branchRepository
            .findAllByDatabaseIdIn(dbIds).stream()
            .collect(java.util.stream.Collectors.groupingBy(BranchEntity::getDatabaseId));
        return dbs.stream()
            .map(entity -> toResponse(entity, branchesByDb.getOrDefault(entity.getId(), List.of())))
            .toList();
    }

    @Transactional
    public DatabaseResponse update(TenantEntity tenant, String dbId, UpdateDatabaseRequest request) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.UPDATE);
        try {
            boolean needsRestart = false;

            if (request.computeSize() != null && !request.computeSize().equals(entity.getComputeSize())) {
                entity.setComputeSize(request.computeSize());
                needsRestart = true;
            }
            if (request.suspendTimeout() != null) {
                entity.setSuspendTimeout(request.suspendTimeout());
            }
            if (request.storageLimitGb() != null) {
                entity.setStorageLimitGb(request.storageLimitGb());
            }

            if (needsRestart && entity.getStatus() == DatabaseStatus.RUNNING) {
                // Restart compute with new config — wait for old pod to fully delete
                if (entity.getComputePodName() != null) {
                    computePodManager.deleteComputePod(entity.getComputePodName(), true);
                }
                computePodManager.createComputePod(entity);
            }

            entity = databaseRepository.save(entity);
            List<BranchEntity> branches = branchRepository.findAllByDatabaseId(dbId);
            operationLogService.completeOperation(opLog, null);
            return toResponse(entity, branches);
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    /**
     * Soft-delete: move database to recycle bin. Compute pod is released but Neon data is preserved.
     */
    @Transactional
    public void delete(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.DELETE);
        try {
            // Release compute pods (save resources) but keep Neon tenant/timeline
            List<BranchEntity> branches = branchRepository.findAllByDatabaseId(dbId);
            for (BranchEntity branch : branches) {
                try {
                    if (branch.getComputePodName() != null) {
                        computePodManager.deleteComputePod(branch.getComputePodName());
                        branch.setComputePodName(null);
                        branch.setComputeHost(null);
                        branch.setComputePort(null);
                        branchRepository.save(branch);
                    }
                } catch (Exception e) {
                    log.warn("Failed to delete compute pod for branch {}: {}", branch.getId(), e.getMessage());
                }
            }
            try {
                if (entity.getComputePodName() != null) {
                    computePodManager.deleteComputePod(entity.getComputePodName());
                }
            } catch (Exception e) {
                log.warn("Failed to delete compute pod for database {}: {}", dbId, e.getMessage());
            }

            // Soft delete: mark as DELETED, preserve Neon data for recovery
            entity.setComputePodName(null);
            entity.setComputeHost(null);
            entity.setComputePort(null);
            entity.setStatus(DatabaseStatus.DELETED);
            entity.setDeletedAt(Instant.now());
            databaseRepository.save(entity);
            operationLogService.completeOperation(opLog, null);
            log.info("Soft-deleted database {} ({}), will be purged after 7 days", entity.getName(), dbId);
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    /**
     * Restore a soft-deleted database from the recycle bin.
     */
    @Transactional
    public DatabaseResponse restore(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        if (entity.getStatus() != DatabaseStatus.DELETED) {
            throw new BadRequestException("Database is not in recycle bin");
        }
        entity.setStatus(DatabaseStatus.SUSPENDED);
        entity.setDeletedAt(null);
        databaseRepository.save(entity);
        log.info("Restored database {} ({}) from recycle bin", entity.getName(), dbId);
        List<BranchEntity> branches = branchRepository.findAllByDatabaseId(dbId);
        return toResponse(entity, branches);
    }

    /**
     * Permanently delete a database: remove Neon tenant/timeline and metadata record.
     */
    @Transactional
    public void purge(String dbId) {
        DatabaseEntity entity = databaseRepository.findById(dbId)
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        hardDelete(entity);
    }

    private void hardDelete(DatabaseEntity entity) {
        String dbId = entity.getId();
        List<BranchEntity> branches = branchRepository.findAllByDatabaseId(dbId);
        for (BranchEntity branch : branches) {
            try {
                if (branch.getComputePodName() != null) {
                    computePodManager.deleteComputePod(branch.getComputePodName());
                }
            } catch (Exception e) {
                log.warn("Failed to delete compute pod for branch {}: {}", branch.getId(), e.getMessage());
            }
            try {
                if (branch.getNeonTimelineId() != null) {
                    neonApiClient.deleteTimeline(entity.getNeonTenantId(), branch.getNeonTimelineId());
                }
            } catch (Exception e) {
                log.warn("Failed to delete timeline for branch {}: {}", branch.getId(), e.getMessage());
            }
            branchRepository.delete(branch);
        }
        try {
            if (entity.getComputePodName() != null) {
                computePodManager.deleteComputePod(entity.getComputePodName());
            }
        } catch (Exception e) {
            log.warn("Failed to delete compute pod for database {}: {}", dbId, e.getMessage());
        }
        try {
            if (entity.getNeonTenantId() != null) {
                neonApiClient.deleteTenant(entity.getNeonTenantId());
            }
        } catch (Exception e) {
            log.warn("Failed to delete Neon tenant for database {}: {}", dbId, e.getMessage());
        }
        databaseRepository.delete(entity);
        log.info("Permanently deleted database {} ({})", entity.getName(), dbId);
    }

    /**
     * Scheduled cleanup: permanently delete databases that have been in recycle bin for over 7 days.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredDeleted() {
        Instant cutoff = Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        List<DatabaseEntity> expired = databaseRepository.findAllByStatusAndDeletedAtBefore(
                DatabaseStatus.DELETED, cutoff);
        if (expired.isEmpty()) return;
        log.info("Cleaning up {} expired deleted databases", expired.size());
        for (DatabaseEntity entity : expired) {
            try {
                hardDelete(entity);
            } catch (Exception e) {
                log.error("Failed to purge expired database {}: {}", entity.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void suspend(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        if (entity.getStatus() == DatabaseStatus.SUSPENDED) {
            return; // Already suspended
        }

        // Flush WAL before suspending to prevent data loss on cold resume
        if (entity.getComputePodName() != null && computePodManager.isPodReady(entity.getComputePodName())) {
            computePodManager.executeCheckpoint(entity.getComputePodName());
        }

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.SUSPEND);
        try {
            // Pod retained for warm wake — do NOT delete
            entity.setStatus(DatabaseStatus.SUSPENDED);
            entity.setSuspendedAt(Instant.now());
            databaseRepository.save(entity);
            operationLogService.completeOperation(opLog, null);
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    /**
     * Ensure the database compute is actually running. If status=RUNNING but pod is gone,
     * force-suspend then resume to recreate the pod.
     */
    public void ensureRunning(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = txTemplate.execute(status ->
            databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId)));

        boolean podReady = entity.getComputePodName() != null
                && computePodManager.isPodReady(entity.getComputePodName());
        if (entity.getStatus() == DatabaseStatus.RUNNING && podReady) {
            // Pod IP may have drifted since last write — sync before returning.
            if (computePodManager.reconcileComputeHost(entity)) {
                return;
            }
            log.warn("Database {} pod {} reported ready but pod-IP missing; forcing SUSPENDED",
                     dbId, entity.getComputePodName());
            // fall through to status-fix branch below
        }
        if (entity.getStatus() == DatabaseStatus.RUNNING && !podReady) {
            // Status says RUNNING but pod is gone — fix the status first
            log.warn("Database {} status=RUNNING but pod gone, forcing SUSPENDED for re-resume", dbId);
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(dbId).orElseThrow();
                e.setStatus(DatabaseStatus.SUSPENDED);
                e.setComputeHost(null);
                e.setComputePort(null);
                e.setComputePodName(null);
                databaseRepository.save(e);
            });
        }
        try {
            resume(tenant, dbId);
        } catch (Exception e) {
            // May fail if pod already exists from a concurrent operation — check if it's ready now
            log.warn("ensureRunning resume failed ({}), checking if compute is ready anyway", e.getMessage());
            DatabaseEntity recheck = databaseRepository.findById(dbId).orElseThrow();
            if (recheck.getComputePodName() != null && computePodManager.isPodReady(recheck.getComputePodName())) {
                log.info("Compute pod {} is ready despite resume error", recheck.getComputePodName());
                return;
            }
            throw e;
        }
    }

    public void resume(TenantEntity tenant, String dbId) {
        // Transaction 1: read entity
        DatabaseEntity entity = txTemplate.execute(status ->
            databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId)));

        if (entity.getStatus() == DatabaseStatus.RUNNING) {
            return; // Already running, idempotent
        }

        // Determine warm/cold before logging
        boolean warmWake = entity.getComputePodName() != null
                && computePodManager.isPodReady(entity.getComputePodName());

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME,
                warmWake ? "WARM" : "COLD");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (!warmWake) {
                // Pre-write computePodName + clear suspendedAt so the three cleanup
                // schedulers don't race-delete the fresh pod:
                //   - orphan-cleanup matches by computePodName -> sees pod as owned
                //   - cleanup-expired skips rows with suspendedAt==null
                //   - reconcile only scans RUNNING rows, so SUSPENDED stays untouched
                // Status stays SUSPENDED until the pod is actually ready, so the UI
                // doesn't lie about "运行中" while the pod is still ContainerCreating.
                String podName = "compute-" + entity.getId().replace("_", "-");
                txTemplate.executeWithoutResult(status -> {
                    DatabaseEntity e = databaseRepository.findById(entity.getId()).orElseThrow();
                    e.setSuspendedAt(null);
                    e.setComputePodName(podName);
                    databaseRepository.save(e);
                });
                computePodManager.createComputePod(entity);
                computePodManager.waitForPodReady(entity.getComputePodName(), 360_000);
            } else {
                // Warm path: verify pod IP still matches what we stored.
                if (!computePodManager.reconcileComputeHost(entity)) {
                    log.warn("Warm wake for {}: pod disappeared, falling back to cold",
                             entity.getId());
                    String podName = "compute-" + entity.getId().replace("_", "-");
                    txTemplate.executeWithoutResult(status -> {
                        DatabaseEntity e = databaseRepository.findById(entity.getId()).orElseThrow();
                        e.setSuspendedAt(null);
                        e.setComputePodName(podName);
                        databaseRepository.save(e);
                    });
                    computePodManager.createComputePod(entity);
                    computePodManager.waitForPodReady(entity.getComputePodName(), 360_000);
                }
            }
            sample.stop(Timer.builder("lakeon_compute_wakeup_seconds")
                .description("Compute wakeup duration")
                .tag("path", warmWake ? "warm" : "cold")
                .register(meterRegistry));

            // Transaction 2: update status
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(entity.getId()).orElseThrow();
                e.setStatus(DatabaseStatus.RUNNING);
                e.setSuspendedAt(null);
                e.setLastActiveAt(Instant.now());
                e.setConnectionUri(buildConnectionUri(e.getDbUser(), e.getName()));
                databaseRepository.save(e);
            });
            operationLogService.completeOperation(opLog, null);
            log.info("Resumed database {} via {} path", entity.getId(), warmWake ? "warm" : "cold");
        } catch (Exception e) {
            wakeupFailureCounter.increment();
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    /**
     * Wake compute for Proxy adapter. Returns "host:port" or throws if not found.
     * Logs a RESUME operation when resuming a suspended database.
     */
    public String wakeCompute(DatabaseEntity entity) {
        if (entity.getComputeHost() != null && entity.getComputePort() != null
            && entity.getStatus() == DatabaseStatus.RUNNING) {
            // 验证 compute pod 是否仍在运行，防止 pod 被清理后状态不一致
            if (entity.getComputePodName() != null && !computePodManager.isPodReady(entity.getComputePodName())) {
                log.warn("Database {} status is RUNNING but compute pod {} is gone, re-creating", entity.getId(), entity.getComputePodName());
                entity.setStatus(DatabaseStatus.SUSPENDED);
                entity.setComputeHost(null);
                entity.setComputePort(null);
                entity.setComputePodName(null);
                databaseRepository.save(entity);
                // Fall through to cold path below
            } else {
                // Reconcile before returning — pod may have restarted with a different IP.
                if (!computePodManager.reconcileComputeHost(entity)) {
                    // Pod gone; fall through to cold path by marking SUSPENDED
                    log.warn("Database {} pod {} reconcile returned missing; falling to cold path",
                             entity.getId(), entity.getComputePodName());
                    entity.setStatus(DatabaseStatus.SUSPENDED);
                    entity.setComputeHost(null);
                    entity.setComputePort(null);
                    entity.setComputePodName(null);
                    databaseRepository.save(entity);
                    // fall through to the warm-or-cold logic below
                } else {
                    return entity.getComputeHost() + ":" + entity.getComputePort();
                }
            }
        }

        // Warm path: Pod retained after suspend, still running
        if (entity.getComputePodName() != null && entity.getComputeHost() != null
            && computePodManager.isPodReady(entity.getComputePodName())) {
            Timer.Sample warmSample = Timer.start(meterRegistry);
            log.info("Warm wake for database {} via proxy — Pod {} still running", entity.getId(), entity.getComputePodName());
            OperationLogEntity opLog = operationLogService.startOperation(
                    entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME, "WARM");
            entity.setStatus(DatabaseStatus.RUNNING);
            entity.setSuspendedAt(null);
            entity.setLastActiveAt(Instant.now());
            databaseRepository.save(entity);
            operationLogService.completeOperation(opLog, null);
            warmSample.stop(Timer.builder("lakeon_compute_wakeup_seconds")
                .description("Compute wakeup duration")
                .tag("path", "warm")
                .register(meterRegistry));
            return entity.getComputeHost() + ":" + entity.getComputePort();
        }

        // Cold path: create new Pod
        Timer.Sample coldSample = Timer.start(meterRegistry);
        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME, "COLD");
        try {
            // Pre-write computePodName + clear suspendedAt so cleanup schedulers
            // don't race-delete the fresh pod. Status stays SUSPENDED until ready
            // so the UI doesn't show 运行中 while the pod is still ContainerCreating.
            String prePodName = "compute-" + entity.getId().replace("_", "-");
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(entity.getId()).orElseThrow();
                e.setSuspendedAt(null);
                e.setComputePodName(prePodName);
                databaseRepository.save(e);
            });
            String address = computePodManager.createComputePod(entity);
            if (entity.getComputePodName() != null) {
                boolean ready = computePodManager.waitForPodReady(entity.getComputePodName(), 360_000L);
                if (!ready) {
                    throw new RuntimeException("Compute pod not ready for database: " + entity.getName());
                }
                String podIp = computePodManager.getPodIp(entity.getComputePodName());
                if (podIp != null) {
                    entity.setComputeHost(podIp);
                }
            }
            entity.setStatus(DatabaseStatus.RUNNING);
            entity.setSuspendedAt(null);
            entity.setLastActiveAt(Instant.now());
            entity.setConnectionUri(buildConnectionUri(entity.getDbUser(), entity.getName()));
            databaseRepository.save(entity);
            operationLogService.completeOperation(opLog, null);
            coldSample.stop(Timer.builder("lakeon_compute_wakeup_seconds")
                .description("Compute wakeup duration")
                .tag("path", "cold")
                .register(meterRegistry));
            return address;
        } catch (Exception e) {
            wakeupFailureCounter.increment();
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    private DatabaseResponse toResponse(DatabaseEntity entity, List<BranchEntity> branches) {
        List<DatabaseResponse.BranchSummary> branchSummaries = branches.stream()
            .map(b -> DatabaseResponse.BranchSummary.builder()
                .id(b.getId())
                .name(b.getName())
                .isDefault(b.getIsDefault())
                .status(b.getStatus() != null ? b.getStatus().name() : null)
                .computeStatus(b.getComputeStatus() != null ? b.getComputeStatus().name() : null)
                .build())
            .toList();

        DatabaseResponse response = DatabaseResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .status(entity.getStatus())
            .statusMessage(entity.getStatusMessage())
            .connectionUri(entity.getConnectionUri())
            .computeSize(entity.getComputeSize())
            .suspendTimeout(entity.getSuspendTimeout())
            .storageLimitGb(entity.getStorageLimitGb())
            .storageUsedGb(fetchStorageUsedGb(entity))
            .branches(branchSummaries)
            .neonTimelineId(entity.getNeonTimelineId())
            .kbId(entity.getKbId())
            .createdAt(entity.getCreatedAt())
            .build();

        if (entity.getDeletedAt() != null) {
            response.setDeletedAt(entity.getDeletedAt());
        }

        // Query active connections if Pod exists (even when SUSPENDED, Pod may still be retained)
        if (entity.getComputePodName() != null) {
            try {
                int count = computePodManager.getActiveConnectionCount(entity.getComputePodName());
                response.setActiveConnections(count);
            } catch (Exception e) {
                response.setActiveConnections(0);
            }
        } else {
            response.setActiveConnections(0);
        }

        return response;
    }

    private double fetchStorageUsedGb(DatabaseEntity entity) {
        if (entity.getNeonTenantId() == null || entity.getNeonTimelineId() == null) {
            return 0.0;
        }
        try {
            NeonTimeline timeline = neonApiClient.getTimeline(
                entity.getNeonTenantId(), entity.getNeonTimelineId());
            Long sizeBytes = timeline.getCurrentLogicalSize();
            if (sizeBytes != null && sizeBytes > 0) {
                return Math.round(sizeBytes / (1024.0 * 1024 * 1024) * 100.0) / 100.0;
            }
        } catch (Exception e) {
            log.debug("Failed to fetch storage size for database {}: {}", entity.getId(), e.getMessage());
        }
        return 0.0;
    }

    private String generateHexId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private int parseComputeUnits(String computeSize) {
        if (computeSize == null) return 1;
        String num = computeSize.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Transactional
    public Map<String, String> resetPassword(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        String rawPassword = generatePassword();
        String scramHash = ScramUtils.generateScramHash(rawPassword);
        entity.setDbPassword(scramHash);
        databaseRepository.save(entity);

        // Sync password to running compute pod
        if (entity.getComputePodName() != null) {
            computePodManager.syncPassword(entity.getComputePodName(), entity.getDbUser(), scramHash);
        }

        return Map.of("password", rawPassword);
    }

    // ── IP Allowlist ─────────────────────────────────────────────

    public Map<String, Object> getAllowedIps(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        return Map.of(
            "enabled", entity.getAllowedIps() != null && !entity.getAllowedIps().isBlank(),
            "ips", parseIpList(entity.getAllowedIps())
        );
    }

    @Transactional
    public Map<String, Object> setAllowedIps(TenantEntity tenant, String dbId, java.util.List<String> ips) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        if (ips == null || ips.isEmpty()) {
            entity.setAllowedIps(null);
        } else {
            // Validate IP/CIDR format
            for (String ip : ips) {
                if (!ip.matches("^[0-9a-fA-F.:]+(/\\d{1,3})?$")) {
                    throw new com.lakeon.service.exception.BadRequestException("Invalid IP/CIDR: " + ip);
                }
            }
            entity.setAllowedIps(String.join(",", ips));
        }
        databaseRepository.save(entity);
        return getAllowedIps(tenant, dbId);
    }

    @Transactional
    public void clearAllowedIps(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        entity.setAllowedIps(null);
        databaseRepository.save(entity);
    }

    private java.util.List<String> parseIpList(String allowedIps) {
        if (allowedIps == null || allowedIps.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(allowedIps.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static final List<String> DEFAULT_EXTENSIONS = List.of("vector", "pg_search");

    void enableDefaultExtensions(DatabaseEntity entity) {
        // Connect directly to compute pod (not proxy) — no options=endpoint needed
        String host = entity.getComputeHost() != null ? entity.getComputeHost() : "proxy.lakeon.svc.cluster.local";
        int port = entity.getComputePort() != 0 ? entity.getComputePort() : 55433;
        boolean directPod = entity.getComputeHost() != null;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + entity.getName()
                + (directPod ? "" : "?options=endpoint%3D" + entity.getName());
        for (int attempt = 0; attempt < 5; attempt++) {
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
                 java.sql.Statement st = conn.createStatement()) {
                for (String ext : DEFAULT_EXTENSIONS) {
                    st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\" CASCADE");
                }
                log.info("Enabled default extensions {} for database {}", DEFAULT_EXTENSIONS, entity.getName());
                return;
            } catch (java.sql.SQLException e) {
                if (attempt == 4) {
                    log.warn("Failed to enable default extensions for {}: {}", entity.getName(), e.getMessage());
                } else {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }

    public String buildConnectionUri(String dbUser, String dbName) {
        String host = props.getProxy().getExternalHost();
        int port = props.getProxy().getExternalPort();
        String base;
        if (host != null && !host.isBlank()) {
            base = "postgres://" + dbUser + "@" + host + ":" + port + "/" + dbName;
        } else {
            base = "postgres://" + dbUser + "@proxy.lakeon.svc.cluster.local:" + port + "/" + dbName;
        }
        // Append endpoint option for Neon proxy routing (required when connecting via IP without SNI)
        base += "?options=endpoint%3D" + dbName;
        return base;
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
