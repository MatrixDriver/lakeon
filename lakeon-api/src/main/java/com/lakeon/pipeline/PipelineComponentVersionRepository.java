package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineComponentVersionRepository extends JpaRepository<PipelineComponentVersionEntity, String> {
    List<PipelineComponentVersionEntity> findByComponentIdOrderByVersionDesc(String componentId);
    Optional<PipelineComponentVersionEntity> findByComponentIdAndVersion(String componentId, Integer version);
}
