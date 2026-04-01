package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineComponentRepository extends JpaRepository<PipelineComponentEntity, String> {
    List<PipelineComponentEntity> findByTenantIdIsNull();
    List<PipelineComponentEntity> findByTenantIdIsNullOrTenantId(String tenantId);
    Optional<PipelineComponentEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<PipelineComponentEntity> findByNameAndTenantId(String name, String tenantId);
    Optional<PipelineComponentEntity> findByNameAndTenantIdIsNull(String name);
}
