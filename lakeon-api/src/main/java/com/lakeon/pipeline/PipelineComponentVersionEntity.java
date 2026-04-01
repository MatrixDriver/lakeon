package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_component_versions", indexes = {
    @Index(name = "idx_compv_component", columnList = "component_id"),
    @Index(name = "idx_compv_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"component_id", "version"})
})
public class PipelineComponentVersionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "component_id", nullable = false, length = 64)
    private String componentId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 256)
    private String entrypoint;

    @Column(name = "params_schema", columnDefinition = "text")
    private String paramsSchema;

    @Column(name = "input_schema", columnDefinition = "text")
    private String inputSchema;

    @Column(name = "output_schema", columnDefinition = "text")
    private String outputSchema;

    @Column(name = "output_branches", columnDefinition = "text")
    private String outputBranches;

    @Column(name = "requires_gpu")
    private Boolean requiresGpu = false;

    @Column(name = "requires_model", length = 128)
    private String requiresModel;

    @Column(name = "execution_mode", length = 20)
    private String executionMode = "FUNCTION";

    @Column(length = 16)
    private String status = "DRAFT";

    @Column(columnDefinition = "text")
    private String changelog;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "compv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    public String getParamsSchema() {
        return paramsSchema;
    }

    public void setParamsSchema(String paramsSchema) {
        this.paramsSchema = paramsSchema;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    public String getOutputBranches() {
        return outputBranches;
    }

    public void setOutputBranches(String outputBranches) {
        this.outputBranches = outputBranches;
    }

    public Boolean getRequiresGpu() {
        return requiresGpu;
    }

    public void setRequiresGpu(Boolean requiresGpu) {
        this.requiresGpu = requiresGpu;
    }

    public String getRequiresModel() {
        return requiresModel;
    }

    public void setRequiresModel(String requiresModel) {
        this.requiresModel = requiresModel;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
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
