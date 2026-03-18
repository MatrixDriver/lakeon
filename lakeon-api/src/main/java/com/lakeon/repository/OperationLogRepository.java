package com.lakeon.repository;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLogEntity, String> {
    Page<OperationLogEntity> findByDatabaseIdAndTenantIdOrderByStartedAtDesc(
            String databaseId, String tenantId, Pageable pageable);
    Page<OperationLogEntity> findByDatabaseIdAndTenantIdAndOperationTypeOrderByStartedAtDesc(
            String databaseId, String tenantId, OperationType type, Pageable pageable);
    List<OperationLogEntity> findTop10ByTenantIdOrderByStartedAtDesc(String tenantId);
    List<OperationLogEntity> findByDatabaseIdAndStatus(String databaseId, OperationStatus status);

    // Admin: global operations with filters
    Page<OperationLogEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
    Page<OperationLogEntity> findByTenantIdOrderByStartedAtDesc(String tenantId, Pageable pageable);
    Page<OperationLogEntity> findByOperationTypeOrderByStartedAtDesc(OperationType type, Pageable pageable);
    Page<OperationLogEntity> findByStatusOrderByStartedAtDesc(OperationStatus status, Pageable pageable);
    Page<OperationLogEntity> findByTenantIdAndOperationTypeOrderByStartedAtDesc(
            String tenantId, OperationType type, Pageable pageable);

    // Usage metering: lifecycle events for compute time calculation
    List<OperationLogEntity> findByTenantIdAndOperationTypeInAndStatusAndStartedAtBetweenOrderByStartedAtAsc(
            String tenantId, List<OperationType> types, OperationStatus status, Instant from, Instant to);

    List<OperationLogEntity> findByDatabaseIdAndOperationTypeInAndStatusAndStartedAtBetweenOrderByStartedAtAsc(
            String databaseId, List<OperationType> types, OperationStatus status, Instant from, Instant to);

    // Admin: stats
    List<OperationLogEntity> findByStartedAtAfter(Instant after);
    List<OperationLogEntity> findByOperationTypeAndStatusAndStartedAtAfter(
            OperationType type, OperationStatus status, Instant after);
    List<OperationLogEntity> findByOperationTypeInAndStatusAndDurationMsNotNull(
            List<OperationType> types, OperationStatus status);
}
