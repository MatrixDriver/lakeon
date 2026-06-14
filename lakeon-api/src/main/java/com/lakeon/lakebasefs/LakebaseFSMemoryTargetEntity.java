package com.lakeon.lakebasefs;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lbfs_memory_targets")
public class LakebaseFSMemoryTargetEntity {

    @Id
    @Column(name = "tenant_id", length = 32, nullable = false)
    private String tenantId;

    @Column(name = "memory_base_id", length = 32, nullable = false)
    private String memoryBaseId;

    @Column(name = "auto_created", nullable = false)
    private Boolean autoCreated = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public LakebaseFSMemoryTargetEntity() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getMemoryBaseId() { return memoryBaseId; }
    public void setMemoryBaseId(String v) { this.memoryBaseId = v; }
    public Boolean getAutoCreated() { return autoCreated; }
    public void setAutoCreated(Boolean v) { this.autoCreated = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
