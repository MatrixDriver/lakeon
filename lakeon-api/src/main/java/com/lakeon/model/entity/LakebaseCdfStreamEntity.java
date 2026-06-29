package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lakebase_cdf_streams",
       indexes = {
           @Index(name = "idx_lakebase_cdf_streams_tenant_db", columnList = "tenant_id, database_id")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_lakebase_cdf_stream_target",
                             columnNames = {"tenant_id", "database_id", "branch_id", "target_namespace", "target_table"})
       })
public class LakebaseCdfStreamEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "branch_id", nullable = false, length = 128)
    private String branchId;

    @Column(name = "source_schema", nullable = false, length = 128)
    private String sourceSchema;

    @Column(name = "source_table", nullable = false, length = 128)
    private String sourceTable;

    @Column(name = "target_namespace", nullable = false, length = 128)
    private String targetNamespace;

    @Column(name = "target_table", nullable = false, length = 128)
    private String targetTable;

    @Column(name = "mode", nullable = false, length = 32)
    private String mode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "slot_name", nullable = false, length = 128)
    private String slotName;

    @Column(name = "publication_name", nullable = false, length = 128)
    private String publicationName;

    @Column(name = "export_status", nullable = false, length = 32)
    private String exportStatus;

    @Column(name = "backfill_status", nullable = false, length = 32)
    private String backfillStatus;

    @Column(name = "backfill_lsn", length = 128)
    private String backfillLsn;

    @Column(name = "last_commit_lsn", length = 128)
    private String lastCommitLsn;

    @Column(name = "last_snapshot_id")
    private Long lastSnapshotId;

    @Column(name = "observed_lag_ms")
    private Long observedLagMs;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "cdf_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (mode == null) {
            mode = "APPEND_CHANGELOG";
        }
        if (status == null) {
            status = "PAUSED";
        }
        if (exportStatus == null) {
            exportStatus = "NOT_MATERIALIZED";
        }
        if (backfillStatus == null) {
            backfillStatus = "PENDING";
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

    public String getBranchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getTargetNamespace() {
        return targetNamespace;
    }

    public void setTargetNamespace(String targetNamespace) {
        this.targetNamespace = targetNamespace;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSlotName() {
        return slotName;
    }

    public void setSlotName(String slotName) {
        this.slotName = slotName;
    }

    public String getPublicationName() {
        return publicationName;
    }

    public void setPublicationName(String publicationName) {
        this.publicationName = publicationName;
    }

    public String getExportStatus() {
        return exportStatus;
    }

    public void setExportStatus(String exportStatus) {
        this.exportStatus = exportStatus;
    }

    public String getBackfillStatus() {
        return backfillStatus;
    }

    public void setBackfillStatus(String backfillStatus) {
        this.backfillStatus = backfillStatus;
    }

    public String getBackfillLsn() {
        return backfillLsn;
    }

    public void setBackfillLsn(String backfillLsn) {
        this.backfillLsn = backfillLsn;
    }

    public String getLastCommitLsn() {
        return lastCommitLsn;
    }

    public void setLastCommitLsn(String lastCommitLsn) {
        this.lastCommitLsn = lastCommitLsn;
    }

    public Long getLastSnapshotId() {
        return lastSnapshotId;
    }

    public void setLastSnapshotId(Long lastSnapshotId) {
        this.lastSnapshotId = lastSnapshotId;
    }

    public Long getObservedLagMs() {
        return observedLagMs;
    }

    public void setObservedLagMs(Long observedLagMs) {
        this.observedLagMs = observedLagMs;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
