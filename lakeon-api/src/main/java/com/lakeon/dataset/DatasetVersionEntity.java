package com.lakeon.dataset;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dataset_versions", indexes = {
    @Index(name = "idx_dsv_dataset", columnList = "dataset_id"),
    @Index(name = "idx_dsv_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"dataset_id", "version"})
})
public class DatasetVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "dataset_id", nullable = false, length = 64)
    private String datasetId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 16)
    private String format = "PARQUET";

    @Column(name = "obs_path", length = 512)
    private String obsPath;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "schema_json", columnDefinition = "text")
    private String schemaJson;

    @Column(nullable = false, length = 16)
    private String status = "CREATING";

    @Column(name = "source_pipeline_run_id", length = 64)
    private String sourcePipelineRunId;

    @Column(name = "source_job_id", length = 64)
    private String sourceJobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "dsv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getObsPath() {
        return obsPath;
    }

    public void setObsPath(String obsPath) {
        this.obsPath = obsPath;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getSchemaJson() {
        return schemaJson;
    }

    public void setSchemaJson(String schemaJson) {
        this.schemaJson = schemaJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourcePipelineRunId() {
        return sourcePipelineRunId;
    }

    public void setSourcePipelineRunId(String sourcePipelineRunId) {
        this.sourcePipelineRunId = sourcePipelineRunId;
    }

    public String getSourceJobId() {
        return sourceJobId;
    }

    public void setSourceJobId(String sourceJobId) {
        this.sourceJobId = sourceJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
