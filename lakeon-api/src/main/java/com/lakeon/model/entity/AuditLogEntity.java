package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_logs_database_id", columnList = "database_id"),
    @Index(name = "idx_audit_logs_timestamp", columnList = "timestamp")
})
public class AuditLogEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "user_name", length = 128)
    private String userName;

    @Column(name = "statement", columnDefinition = "TEXT")
    private String statement;

    @Column(name = "statement_type", length = 32)
    private String statementType;

    @Column(name = "object_name", length = 256)
    private String objectName;

    @Column(name = "client_addr", length = 64)
    private String clientAddr;

    @Column(name = "duration")
    private Long duration;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "al_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }

    public String getStatementType() { return statementType; }
    public void setStatementType(String statementType) { this.statementType = statementType; }

    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }

    public String getClientAddr() { return clientAddr; }
    public void setClientAddr(String clientAddr) { this.clientAddr = clientAddr; }

    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
}
