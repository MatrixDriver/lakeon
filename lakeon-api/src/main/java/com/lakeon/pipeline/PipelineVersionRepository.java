package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersionEntity, String> {
    List<PipelineVersionEntity> findByPipelineIdOrderByVersionDesc(String pipelineId);
    Optional<PipelineVersionEntity> findByPipelineIdAndVersion(String pipelineId, Integer version);
}
