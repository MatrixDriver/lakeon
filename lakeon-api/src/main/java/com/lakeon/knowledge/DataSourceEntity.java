package com.lakeon.knowledge;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "datasources", indexes = {
    @Index(name = "idx_datasources_tenant_kb", columnList = "tenant_id, kb_id")
})
public class DataSourceEntity {
    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "kb_id", nullable = false, length = 32)
    private String kbId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "obs_prefix", nullable = false, length = 256)
    private String obsPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DataSourceStatus status = DataSourceStatus.ACTIVE;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_sync_stats", columnDefinition = "jsonb")
    private Map<String, Object> lastSyncStats;

    @Column(name = "file_count")
    private Integer fileCount = 0;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getObsPrefix() { return obsPrefix; }
    public void setObsPrefix(String obsPrefix) { this.obsPrefix = obsPrefix; }
    public DataSourceStatus getStatus() { return status; }
    public void setStatus(DataSourceStatus status) { this.status = status; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public Map<String, Object> getLastSyncStats() { return lastSyncStats; }
    public void setLastSyncStats(Map<String, Object> lastSyncStats) { this.lastSyncStats = lastSyncStats; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
