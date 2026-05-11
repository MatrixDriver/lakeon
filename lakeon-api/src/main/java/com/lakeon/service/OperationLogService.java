package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OperationLogService {
    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    // Any IN_PROGRESS row older than this is treated as orphaned (API restart, missed
    // completeOperation, etc.) and forced to FAILED. Picked conservatively above the
    // longest legitimate operation (IMPORT/BACKUP may take 10+ minutes).
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);

    private final OperationLogRepository repository;
    private final MeterRegistry meterRegistry;

    public OperationLogService(OperationLogRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public OperationLogEntity startOperation(String databaseId, String tenantId,
                                              String databaseName, OperationType type) {
        return startOperation(databaseId, tenantId, databaseName, type, null);
    }

    public OperationLogEntity startOperation(String databaseId, String tenantId,
                                              String databaseName, OperationType type,
                                              String resumeType) {
        OperationLogEntity log = new OperationLogEntity();
        log.setDatabaseId(databaseId);
        log.setTenantId(tenantId);
        log.setDatabaseName(databaseName);
        log.setOperationType(type);
        log.setStatus(OperationStatus.IN_PROGRESS);
        log.setStartedAt(Instant.now());
        log.setResumeType(resumeType);
        return repository.save(log);
    }

    public void completeOperation(OperationLogEntity log, String errorMessage) {
        log.setCompletedAt(Instant.now());
        log.setDurationMs(log.getCompletedAt().toEpochMilli() - log.getStartedAt().toEpochMilli());
        if (errorMessage != null) {
            log.setStatus(OperationStatus.FAILED);
            log.setErrorMessage(errorMessage);
        } else {
            log.setStatus(OperationStatus.SUCCESS);
        }
        Counter.builder("lakeon_api_operations_total")
            .tag("type", log.getOperationType().name().toLowerCase())
            .tag("status", log.getStatus().name().toLowerCase())
            .description("Total API operations")
            .register(meterRegistry)
            .increment();
        repository.save(log);
    }

    public Optional<OperationLogEntity> findById(String id) {
        return repository.findById(id);
    }

    public Page<OperationLogEntity> getByDatabase(String databaseId, String tenantId,
                                                    OperationType type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        if (type != null) {
            return repository.findByDatabaseIdAndTenantIdAndOperationTypeOrderByStartedAtDesc(
                    databaseId, tenantId, type, pageable);
        }
        return repository.findByDatabaseIdAndTenantIdOrderByStartedAtDesc(
                databaseId, tenantId, pageable);
    }

    public List<OperationLogEntity> getRecent(String tenantId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        return repository.findByTenantIdOrderByStartedAtDesc(
                tenantId, PageRequest.of(0, capped)).getContent();
    }

    public void failInProgressOperations(String databaseId, String errorMessage) {
        List<OperationLogEntity> inProgress = repository.findByDatabaseIdAndStatus(
            databaseId, OperationStatus.IN_PROGRESS);
        for (OperationLogEntity op : inProgress) {
            completeOperation(op, errorMessage);
        }
    }

    /**
     * Mark any IN_PROGRESS operation older than {@code threshold} as FAILED. Used to
     * clean up rows orphaned by API restarts or missed completeOperation calls — without
     * this they linger forever and the UI keeps showing "进行中".
     */
    public int failStaleInProgressOperations(Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        List<OperationLogEntity> stale = repository.findByStatusAndStartedAtBefore(
            OperationStatus.IN_PROGRESS, cutoff);
        if (stale.isEmpty()) return 0;
        log.warn("Sweeping {} stale IN_PROGRESS operation(s) older than {} — marking FAILED",
            stale.size(), threshold);
        String msg = "Operation timeout (>" + threshold.toMinutes()
            + "m without completion — likely API restart or stuck cold start)";
        for (OperationLogEntity op : stale) {
            completeOperation(op, msg);
        }
        return stale.size();
    }

    // Sweeps both at startup (initialDelay) and periodically. initialDelay handles the
    // common case where an api pod restart left rows orphaned; the recurring sweep
    // covers any later loss of completeOperation (exception escape, kill -9, etc.).
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5M")
    public void scheduledStaleSweep() {
        try {
            failStaleInProgressOperations(STALE_THRESHOLD);
        } catch (Exception e) {
            log.error("Stale IN_PROGRESS sweep failed: {}", e.getMessage(), e);
        }
    }
}
