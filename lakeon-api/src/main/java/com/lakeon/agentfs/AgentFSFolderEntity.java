package com.lakeon.agentfs;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agentfs_folders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agentfs_folders_tenant_name",
                columnNames = {"tenant_id", "display_name"}))
public class AgentFSFolderEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Column(name = "directory_kind", length = 32, nullable = false)
    private String directoryKind;

    @Column(name = "storage_policy", length = 32, nullable = false)
    private String storagePolicy;

    @Column(name = "processing_profile", length = 32, nullable = false)
    private String processingProfile;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "af_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDirectoryKind() { return directoryKind; }
    public void setDirectoryKind(String directoryKind) { this.directoryKind = directoryKind; }
    public String getStoragePolicy() { return storagePolicy; }
    public void setStoragePolicy(String storagePolicy) { this.storagePolicy = storagePolicy; }
    public String getProcessingProfile() { return processingProfile; }
    public void setProcessingProfile(String processingProfile) { this.processingProfile = processingProfile; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
