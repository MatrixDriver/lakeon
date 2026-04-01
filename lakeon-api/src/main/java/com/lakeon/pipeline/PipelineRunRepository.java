package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, String> {
    List<PipelineRunEntity> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);
    List<PipelineRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<PipelineRunEntity> findByStatus(PipelineRunStatus status);
    Optional<PipelineRunEntity> findByIdAndTenantId(String id, String tenantId);
}
