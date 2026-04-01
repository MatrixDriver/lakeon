package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_step_runs", indexes = {
    @Index(name = "idx_sr_run", columnList = "run_id"),
    @Index(name = "idx_sr_status", columnList = "status")
})
public class PipelineStepRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;

    @Column(name = "component_id", length = 64)
    private String componentId;

    @Column(name = "component_version")
    private Integer componentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipelineStepRunStatus status = PipelineStepRunStatus.PENDING;

    @Column(name = "input_ref", columnDefinition = "text")
    private String inputRef;

    @Column(name = "output_ref", columnDefinition = "text")
    private String outputRef;

    @Column(name = "checkpoint_path", length = 512)
    private String checkpointPath;

    @Column(columnDefinition = "text")
    private String metrics;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "sr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public Integer getComponentVersion() {
        return componentVersion;
    }

    public void setComponentVersion(Integer componentVersion) {
        this.componentVersion = componentVersion;
    }

    public PipelineStepRunStatus getStatus() {
        return status;
    }

    public void setStatus(PipelineStepRunStatus status) {
        this.status = status;
    }

    public String getInputRef() {
        return inputRef;
    }

    public void setInputRef(String inputRef) {
        this.inputRef = inputRef;
    }

    public String getOutputRef() {
        return outputRef;
    }

    public void setOutputRef(String outputRef) {
        this.outputRef = outputRef;
    }

    public String getCheckpointPath() {
        return checkpointPath;
    }

    public void setCheckpointPath(String checkpointPath) {
        this.checkpointPath = checkpointPath;
    }

    public String getMetrics() {
        return metrics;
    }

    public void setMetrics(String metrics) {
        this.metrics = metrics;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
