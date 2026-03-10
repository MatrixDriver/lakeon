package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.*;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.BranchStatus;
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
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.QuotaExceededException;
import com.lakeon.service.exception.ServiceException;
import com.lakeon.util.ScramUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Counter wakeupFailureCounter;

    public DatabaseService(DatabaseRepository databaseRepository,
                           BranchRepository branchRepository,
                           NeonApiClient neonApiClient,
                           ComputePodManager computePodManager,
                           LakeonProperties props,
                           OperationLogService operationLogService,
                           MeterRegistry meterRegistry,
                           TransactionTemplate txTemplate) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.neonApiClient = neonApiClient;
        this.computePodManager = computePodManager;
        this.props = props;
        this.operationLogService = operationLogService;
        this.meterRegistry = meterRegistry;
        this.txTemplate = txTemplate;
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
        record CreateResult(DatabaseEntity entity, NeonTenant neonTenant, NeonTimeline neonTimeline) {}
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
            return new CreateResult(saved, neonTenant, neonTimeline);
        });

        DatabaseEntity entity = created.entity();
        NeonTenant neonTenant = created.neonTenant();
        NeonTimeline neonTimeline = created.neonTimeline();

        // Outside transaction: K8s operations (may block 60s+)
        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.CREATE);
        try {
            computePodManager.createComputePod(entity);
            computePodManager.waitForPodReady(entity.getComputePodName(), 60_000);
        } catch (Exception e) {
            // Rollback in a new transaction
            txTemplate.executeWithoutResult(status -> databaseRepository.delete(entity));
            try { neonApiClient.deleteTenant(neonTenant.getId()); } catch (Exception rollbackEx) {
                log.warn("Failed to rollback Neon tenant {}: {}", neonTenant.getId(), rollbackEx.getMessage());
            }
            operationLogService.completeOperation(opLog, e.getMessage());
            throw new ServiceException("Failed to create compute Pod: " + e.getMessage(), e);
        }

        // Transaction 2: finalize entity status
        try {
            DatabaseResponse response = txTemplate.execute(status -> {
                DatabaseEntity e = databaseRepository.findById(entity.getId()).orElseThrow();
                e.setConnectionUri(buildConnectionUri(dbUser, request.name()));
                e.setStatus(DatabaseStatus.RUNNING);
                e.setLastActiveAt(Instant.now());
                e = databaseRepository.save(e);

                BranchEntity mainBranch = new BranchEntity();
                mainBranch.setName("main");
                mainBranch.setDatabaseId(e.getId());
                mainBranch.setNeonTimelineId(neonTimeline.getTimelineId());
                mainBranch.setIsDefault(true);
                mainBranch.setStatus(BranchStatus.ACTIVE);
                branchRepository.save(mainBranch);

                DatabaseResponse resp = toResponse(e, List.of(mainBranch));
                resp.setPassword(rawPassword);
                return resp;
            });
            operationLogService.completeOperation(opLog, null);
            return response;
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
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
        return databaseRepository.findAllByTenantId(tenant.getId()).stream()
            .map(entity -> {
                List<BranchEntity> branches = branchRepository.findAllByDatabaseId(entity.getId());
                return toResponse(entity, branches);
            })
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

    @Transactional
    public void delete(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.DELETE);
        try {
            // Delete all branches' compute pods and timelines (best-effort cleanup)
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

            // Delete main compute pod
            try {
                if (entity.getComputePodName() != null) {
                    computePodManager.deleteComputePod(entity.getComputePodName());
                }
            } catch (Exception e) {
                log.warn("Failed to delete main compute pod for database {}: {}", dbId, e.getMessage());
            }

            // Delete Neon tenant
            try {
                if (entity.getNeonTenantId() != null) {
                    neonApiClient.deleteTenant(entity.getNeonTenantId());
                }
            } catch (Exception e) {
                log.warn("Failed to delete Neon tenant for database {}: {}", dbId, e.getMessage());
            }

            databaseRepository.delete(entity);
            operationLogService.completeOperation(opLog, null);
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public void suspend(TenantEntity tenant, String dbId) {
        DatabaseEntity entity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        if (entity.getStatus() == DatabaseStatus.SUSPENDED) {
            return; // Already suspended
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

    public void resume(TenantEntity tenant, String dbId) {
        // Transaction 1: read entity
        DatabaseEntity entity = txTemplate.execute(status ->
            databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId)));

        if (entity.getStatus() == DatabaseStatus.RUNNING) {
            return; // Already running, idempotent
        }

        OperationLogEntity opLog = operationLogService.startOperation(
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Warm path: Pod still running from previous session
            boolean warmWake = entity.getComputePodName() != null
                    && computePodManager.isPodReady(entity.getComputePodName());

            if (!warmWake) {
                // Cold path: create new Pod (outside transaction, may block 60s)
                computePodManager.createComputePod(entity);
                computePodManager.waitForPodReady(entity.getComputePodName(), 60_000);
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
                return entity.getComputeHost() + ":" + entity.getComputePort();
            }
        }

        // Warm path: Pod retained after suspend, still running
        if (entity.getComputePodName() != null && entity.getComputeHost() != null
            && computePodManager.isPodReady(entity.getComputePodName())) {
            Timer.Sample warmSample = Timer.start(meterRegistry);
            log.info("Warm wake for database {} via proxy — Pod {} still running", entity.getId(), entity.getComputePodName());
            OperationLogEntity opLog = operationLogService.startOperation(
                    entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME);
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
                entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME);
        try {
            String address = computePodManager.createComputePod(entity);
            if (entity.getComputePodName() != null) {
                boolean ready = computePodManager.waitForPodReady(entity.getComputePodName(), 120_000L);
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
                .status(b.getStatus() != null ? b.getStatus().name().toLowerCase() : null)
                .computeStatus(b.getComputeStatus() != null ? b.getComputeStatus().name().toLowerCase() : null)
                .build())
            .toList();

        DatabaseResponse response = DatabaseResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .status(entity.getStatus())
            .connectionUri(entity.getConnectionUri())
            .computeSize(entity.getComputeSize())
            .suspendTimeout(entity.getSuspendTimeout())
            .storageLimitGb(entity.getStorageLimitGb())
            .storageUsedGb(fetchStorageUsedGb(entity))
            .branches(branchSummaries)
            .neonTimelineId(entity.getNeonTimelineId())
            .createdAt(entity.getCreatedAt())
            .build();

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
        return Map.of("password", rawPassword);
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
