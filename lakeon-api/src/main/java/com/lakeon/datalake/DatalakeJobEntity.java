package com.lakeon.datalake;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datalake_jobs")
public class DatalakeJobEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DatalakeJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DatalakeJobStatus status;

    @Column(columnDefinition = "jsonb", nullable = false)
    private String spec;

    @Column(name = "cci_namespace")
    private String cciNamespace;

    @Column(name = "ray_job_name")
    private String rayJobName;

    @Column(name = "k8s_job_name")
    private String k8sJobName;

    @Column(name = "base_image")
    private String baseImage;

    @Column(name = "log_obs_path")
    private String logObsPath;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "core_hours", precision = 10, scale = 4)
    private BigDecimal coreHours;

    @Column(name = "gpu_hours", precision = 10, scale = 4)
    private BigDecimal gpuHours;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "dlj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DatalakeJobType getType() {
        return type;
    }

    public void setType(DatalakeJobType type) {
        this.type = type;
    }

    public DatalakeJobStatus getStatus() {
        return status;
    }

    public void setStatus(DatalakeJobStatus status) {
        this.status = status;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getCciNamespace() {
        return cciNamespace;
    }

    public void setCciNamespace(String cciNamespace) {
        this.cciNamespace = cciNamespace;
    }

    public String getRayJobName() {
        return rayJobName;
    }

    public void setRayJobName(String rayJobName) {
        this.rayJobName = rayJobName;
    }

    public String getK8sJobName() {
        return k8sJobName;
    }

    public void setK8sJobName(String k8sJobName) {
        this.k8sJobName = k8sJobName;
    }

    public String getBaseImage() {
        return baseImage;
    }

    public void setBaseImage(String baseImage) {
        this.baseImage = baseImage;
    }

    public String getLogObsPath() {
        return logObsPath;
    }

    public void setLogObsPath(String logObsPath) {
        this.logObsPath = logObsPath;
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

    public BigDecimal getCoreHours() {
        return coreHours;
    }

    public void setCoreHours(BigDecimal coreHours) {
        this.coreHours = coreHours;
    }

    public BigDecimal getGpuHours() {
        return gpuHours;
    }

    public void setGpuHours(BigDecimal gpuHours) {
        this.gpuHours = gpuHours;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
