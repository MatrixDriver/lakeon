package com.lakeon.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PipelineStepRunRepository extends JpaRepository<PipelineStepRunEntity, String> {
    List<PipelineStepRunEntity> findByRunIdOrderByCreatedAtAsc(String runId);
    List<PipelineStepRunEntity> findByRunIdAndStatus(String runId, PipelineStepRunStatus status);
}
