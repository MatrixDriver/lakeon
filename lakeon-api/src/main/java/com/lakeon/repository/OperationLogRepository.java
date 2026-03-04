package com.lakeon.repository;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLogEntity, String> {
    Page<OperationLogEntity> findByDatabaseIdAndTenantIdOrderByStartedAtDesc(
            String databaseId, String tenantId, Pageable pageable);
    Page<OperationLogEntity> findByDatabaseIdAndTenantIdAndOperationTypeOrderByStartedAtDesc(
            String databaseId, String tenantId, OperationType type, Pageable pageable);
    List<OperationLogEntity> findTop10ByTenantIdOrderByStartedAtDesc(String tenantId);
}
