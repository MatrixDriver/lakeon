package com.lakeon.job;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    Optional<JobEntity> findByIdAndTenantId(String id, String tenantId);
    List<JobEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<JobEntity> findAllByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, JobType type);
    List<JobEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, JobStatus status);
    List<JobEntity> findAllByTenantIdAndTypeAndStatusOrderByCreatedAtDesc(String tenantId, JobType type, JobStatus status);
    List<JobEntity> findAllByStatusIn(List<JobStatus> statuses);
}
