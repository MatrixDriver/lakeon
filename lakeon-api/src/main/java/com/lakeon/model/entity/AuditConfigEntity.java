package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_configs", indexes = {
    @Index(name = "idx_audit_configs_database_id", columnList = "database_id"),
    @Index(name = "idx_audit_configs_tenant_id", columnList = "tenant_id")
})
public class AuditConfigEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "database_id", nullable = false, length = 64, unique = true)
    private String databaseId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "log_ddl", nullable = false)
    private boolean logDdl = true;

    @Column(name = "log_dml", nullable = false)
    private boolean logDml = false;

    @Column(name = "log_select", nullable = false)
    private boolean logSelect = false;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays = 30;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ak_" + UUID.randomUUID().toString().substring(0, 8);
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isLogDdl() { return logDdl; }
    public void setLogDdl(boolean logDdl) { this.logDdl = logDdl; }

    public boolean isLogDml() { return logDml; }
    public void setLogDml(boolean logDml) { this.logDml = logDml; }

    public boolean isLogSelect() { return logSelect; }
    public void setLogSelect(boolean logSelect) { this.logSelect = logSelect; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
