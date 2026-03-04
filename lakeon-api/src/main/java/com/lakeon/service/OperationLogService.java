package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OperationLogService {

    private final OperationLogRepository repository;

    public OperationLogService(OperationLogRepository repository) {
        this.repository = repository;
    }

    public OperationLogEntity startOperation(String databaseId, String tenantId,
                                              String databaseName, OperationType type) {
        OperationLogEntity log = new OperationLogEntity();
        log.setDatabaseId(databaseId);
        log.setTenantId(tenantId);
        log.setDatabaseName(databaseName);
        log.setOperationType(type);
        log.setStatus(OperationStatus.IN_PROGRESS);
        log.setStartedAt(Instant.now());
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
        repository.save(log);
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
