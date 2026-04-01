package com.lakeon.pipeline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_components", indexes = {
    @Index(name = "idx_comp_tenant", columnList = "tenant_id"),
    @Index(name = "idx_comp_category", columnList = "category"),
    @Index(name = "idx_comp_data_type", columnList = "data_type")
})
public class PipelineComponentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "latest_version")
    private Integer latestVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "comp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(Integer latestVersion) {
        this.latestVersion = latestVersion;
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
