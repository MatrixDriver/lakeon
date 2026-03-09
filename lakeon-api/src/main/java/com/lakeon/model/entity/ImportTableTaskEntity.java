package com.lakeon.model.entity;

import com.lakeon.model.enums.ImportTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_table_tasks", indexes = {
    @Index(name = "idx_import_table_tasks_import_task_id", columnList = "import_task_id")
})
public class ImportTableTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "import_task_id", nullable = false, length = 64)
    private String importTaskId;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportTaskStatus status;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sync_state", length = 16)
    private String syncState;

    @Column(name = "synced_rows")
    private Long syncedRows;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "itb_" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getImportTaskId() {
        return importTaskId;
    }

    public void setImportTaskId(String importTaskId) {
        this.importTaskId = importTaskId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public ImportTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ImportTaskStatus status) {
        this.status = status;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    public String getSyncState() { return syncState; }
    public void setSyncState(String syncState) { this.syncState = syncState; }

    public Long getSyncedRows() { return syncedRows; }
    public void setSyncedRows(Long syncedRows) { this.syncedRows = syncedRows; }
}
