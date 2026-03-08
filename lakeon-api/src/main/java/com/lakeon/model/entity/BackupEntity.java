package com.lakeon.model.entity;

import com.lakeon.model.enums.BackupStatus;
import com.lakeon.model.enums.BackupType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "backups", indexes = {
    @Index(name = "idx_backups_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_backups_database_id", columnList = "database_id")
})
public class BackupEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BackupStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private BackupType type;

    @Column(name = "neon_tenant_id", length = 64)
    private String neonTenantId;

    @Column(name = "neon_timeline_id", length = 64)
    private String neonTimelineId;

    @Column(name = "source_tenant_id", length = 64)
    private String sourceTenantId;

    @Column(name = "source_timeline_id", length = 64)
    private String sourceTimelineId;

    @Column(name = "lsn", length = 32)
    private String lsn;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "bk_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BackupStatus getStatus() { return status; }
    public void setStatus(BackupStatus status) { this.status = status; }

    public BackupType getType() { return type; }
    public void setType(BackupType type) { this.type = type; }

    public String getNeonTenantId() { return neonTenantId; }
    public void setNeonTenantId(String neonTenantId) { this.neonTenantId = neonTenantId; }

    public String getNeonTimelineId() { return neonTimelineId; }
    public void setNeonTimelineId(String neonTimelineId) { this.neonTimelineId = neonTimelineId; }

    public String getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(String sourceTenantId) { this.sourceTenantId = sourceTenantId; }

    public String getSourceTimelineId() { return sourceTimelineId; }
    public void setSourceTimelineId(String sourceTimelineId) { this.sourceTimelineId = sourceTimelineId; }

    public String getLsn() { return lsn; }
    public void setLsn(String lsn) { this.lsn = lsn; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
