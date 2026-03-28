package com.lakeon.knowledge;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_write_tasks", indexes = {
    @Index(name = "idx_kwt_database_id_status", columnList = "database_id, status"),
    @Index(name = "idx_kwt_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_kwt_job_id", columnList = "job_id")
})
public class KbWriteTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private KbWriteTaskType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KbWriteTaskStatus status;

    @Column(name = "params", columnDefinition = "TEXT")
    private String params;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "max_retries")
    private int maxRetries = 3;

    @Column(name = "error_category")
    private String errorCategory;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "kwt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public KbWriteTaskType getType() { return type; }
    public void setType(KbWriteTaskType type) { this.type = type; }
    public KbWriteTaskStatus getStatus() { return status; }
    public void setStatus(KbWriteTaskStatus status) { this.status = status; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String errorCategory) { this.errorCategory = errorCategory; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
}
