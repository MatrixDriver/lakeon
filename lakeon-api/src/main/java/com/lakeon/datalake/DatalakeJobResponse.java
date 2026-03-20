package com.lakeon.datalake;

import java.math.BigDecimal;
import java.time.Instant;

public class DatalakeJobResponse {

    private String id;
    private String tenantId;
    private String name;
    private DatalakeJobType type;
    private DatalakeJobStatus status;
    private String spec;
    private String cciNamespace;
    private String rayJobName;
    private String k8sJobName;
    private String baseImage;
    private String logObsPath;
    private Instant startedAt;
    private Instant finishedAt;
    private BigDecimal coreHours;
    private BigDecimal gpuHours;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static DatalakeJobResponse from(DatalakeJobEntity entity) {
        DatalakeJobResponse r = new DatalakeJobResponse();
        r.id = entity.getId();
        r.tenantId = entity.getTenantId();
        r.name = entity.getName();
        r.type = entity.getType();
        r.status = entity.getStatus();
        r.spec = entity.getSpec();
        r.cciNamespace = entity.getCciNamespace();
        r.rayJobName = entity.getRayJobName();
        r.k8sJobName = entity.getK8sJobName();
        r.baseImage = entity.getBaseImage();
        r.logObsPath = entity.getLogObsPath();
        r.startedAt = entity.getStartedAt();
        r.finishedAt = entity.getFinishedAt();
        r.coreHours = entity.getCoreHours();
        r.gpuHours = entity.getGpuHours();
        r.errorMessage = entity.getErrorMessage();
        r.createdAt = entity.getCreatedAt();
        r.updatedAt = entity.getUpdatedAt();
        return r;
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
