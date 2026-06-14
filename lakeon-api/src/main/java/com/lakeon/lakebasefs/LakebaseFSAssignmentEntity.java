package com.lakeon.lakebasefs;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lbfs_assignments")
public class LakebaseFSAssignmentEntity {

    @Id
    @Column(name = "tenant_id", length = 32, nullable = false)
    private String tenantId;

    @Column(name = "database_id", length = 32, nullable = false)
    private String databaseId;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "PROVISIONING";

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ready_at")
    private Instant readyAt;

    public LakebaseFSAssignmentEntity() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String v) { this.databaseId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant v) { this.readyAt = v; }
}
