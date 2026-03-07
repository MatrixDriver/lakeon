package com.lakeon.model.entity;

import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_tasks", indexes = {
    @Index(name = "idx_import_tasks_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_import_tasks_database_id", columnList = "database_id")
})
public class ImportTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "source_host", nullable = false)
    private String sourceHost;

    @Column(name = "source_port", nullable = false)
    private Integer sourcePort;

    @Column(name = "source_dbname", nullable = false)
    private String sourceDbname;

    @Column(name = "source_user", nullable = false)
    private String sourceUser;

    @Column(name = "source_password", length = 256)
    private String sourcePassword;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private ImportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_strategy", nullable = false)
    private ConflictStrategy conflictStrategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportTaskStatus status;

    @Column(name = "total_tables")
    private Integer totalTables;

    @Column(name = "completed_tables")
    private Integer completedTables = 0;

    @Column(name = "job_pod_name", length = 128)
    private String jobPodName;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "original_suspend_timeout", length = 16)
    private String originalSuspendTimeout;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "imp_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public String getSourceHost() {
        return sourceHost;
    }

    public void setSourceHost(String sourceHost) {
        this.sourceHost = sourceHost;
    }

    public Integer getSourcePort() {
        return sourcePort;
    }

    public void setSourcePort(Integer sourcePort) {
        this.sourcePort = sourcePort;
    }

    public String getSourceDbname() {
        return sourceDbname;
    }

    public void setSourceDbname(String sourceDbname) {
        this.sourceDbname = sourceDbname;
    }

    public String getSourceUser() {
        return sourceUser;
    }

    public void setSourceUser(String sourceUser) {
        this.sourceUser = sourceUser;
    }

    public String getSourcePassword() {
        return sourcePassword;
    }

    public void setSourcePassword(String sourcePassword) {
        this.sourcePassword = sourcePassword;
    }

    public ImportMode getMode() {
        return mode;
    }

    public void setMode(ImportMode mode) {
        this.mode = mode;
    }

    public ConflictStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    public void setConflictStrategy(ConflictStrategy conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    public ImportTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ImportTaskStatus status) {
        this.status = status;
    }

    public Integer getTotalTables() {
        return totalTables;
    }

    public void setTotalTables(Integer totalTables) {
        this.totalTables = totalTables;
    }

    public Integer getCompletedTables() {
        return completedTables;
    }

    public void setCompletedTables(Integer completedTables) {
        this.completedTables = completedTables;
    }

    public String getJobPodName() {
        return jobPodName;
    }

    public void setJobPodName(String jobPodName) {
        this.jobPodName = jobPodName;
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

    public String getOriginalSuspendTimeout() {
        return originalSuspendTimeout;
    }

    public void setOriginalSuspendTimeout(String originalSuspendTimeout) {
        this.originalSuspendTimeout = originalSuspendTimeout;
    }
}
