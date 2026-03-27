package com.lakeon.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatasetRepository extends JpaRepository<DatasetEntity, String> {
    List<DatasetEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DatasetEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, DatasetStatus status);
    Optional<DatasetEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<DatasetEntity> findByJobId(String jobId);
    List<DatasetEntity> findAllByOrderByCreatedAtDesc();
    List<DatasetEntity> findByStatusOrderByCreatedAtDesc(DatasetStatus status);
}
