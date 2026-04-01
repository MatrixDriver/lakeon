package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_runs", indexes = {
    @Index(name = "idx_run_pipeline", columnList = "pipeline_id"),
    @Index(name = "idx_run_tenant", columnList = "tenant_id"),
    @Index(name = "idx_run_status", columnList = "status")
})
public class PipelineRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "pipeline_id", nullable = false, length = 64)
    private String pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    private Integer pipelineVersion;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "input_dataset_id", length = 64)
    private String inputDatasetId;

    @Column(name = "input_dataset_version")
    private Integer inputDatasetVersion;

    @Column(name = "output_dataset_version_id", length = 64)
    private String outputDatasetVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipelineRunStatus status = PipelineRunStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public Integer getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(Integer pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getInputDatasetId() {
        return inputDatasetId;
    }

    public void setInputDatasetId(String inputDatasetId) {
        this.inputDatasetId = inputDatasetId;
    }

    public Integer getInputDatasetVersion() {
        return inputDatasetVersion;
    }

    public void setInputDatasetVersion(Integer inputDatasetVersion) {
        this.inputDatasetVersion = inputDatasetVersion;
    }

    public String getOutputDatasetVersionId() {
        return outputDatasetVersionId;
    }

    public void setOutputDatasetVersionId(String outputDatasetVersionId) {
        this.outputDatasetVersionId = outputDatasetVersionId;
    }

    public PipelineRunStatus getStatus() {
        return status;
    }

    public void setStatus(PipelineRunStatus status) {
        this.status = status;
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
