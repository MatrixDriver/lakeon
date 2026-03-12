package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OperationLogService {

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

    public List<OperationLogEntity> getRecent(String tenantId) {
        return repository.findTop10ByTenantIdOrderByStartedAtDesc(tenantId);
    }
}
