package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineRepository extends JpaRepository<PipelineEntity, String> {
    List<PipelineEntity> findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc(String tenantId);
    List<PipelineEntity> findByIsTemplateTrue();
    Optional<PipelineEntity> findByIdAndTenantId(String id, String tenantId);
}
