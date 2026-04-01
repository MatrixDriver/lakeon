package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_versions", indexes = {
    @Index(name = "idx_pipev_pipeline", columnList = "pipeline_id")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"pipeline_id", "version"})
})
public class PipelineVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "pipeline_id", nullable = false, length = 64)
    private String pipelineId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "dag_yaml", nullable = false, columnDefinition = "text")
    private String dagYaml;

    @Column(length = 16)
    private String status = "DRAFT";

    @Column(columnDefinition = "text")
    private String changelog;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "pipev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDagYaml() {
        return dagYaml;
    }

    public void setDagYaml(String dagYaml) {
        this.dagYaml = dagYaml;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
