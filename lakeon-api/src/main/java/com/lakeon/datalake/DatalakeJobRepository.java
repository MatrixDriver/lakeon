package com.lakeon.datalake;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DatalakeJobRepository extends JpaRepository<DatalakeJobEntity, String> {
    List<DatalakeJobEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DatalakeJobEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, DatalakeJobStatus status);
    List<DatalakeJobEntity> findByStatusIn(List<DatalakeJobStatus> statuses);
}
