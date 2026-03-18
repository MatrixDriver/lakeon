package com.lakeon.knowledge;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "knowledge_bases", indexes = {
    @Index(name = "idx_knowledge_bases_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_knowledge_bases_status", columnList = "status")
})
public class KnowledgeBaseEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "database_id", length = 32)
    private String databaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16)
    private KnowledgeBaseType type = KnowledgeBaseType.DOCUMENT;

    @Column(name = "source_database_id", length = 32)
    private String sourceDatabaseId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_names", columnDefinition = "jsonb")
    private List<String> tableNames = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KnowledgeBaseStatus status;

    @Column(name = "document_count")
    private Integer documentCount = 0;

    @Column(name = "db_password", length = 256)
    private String dbPassword;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public KnowledgeBaseType getType() { return type; }
    public void setType(KnowledgeBaseType type) { this.type = type; }

    public String getSourceDatabaseId() { return sourceDatabaseId; }
    public void setSourceDatabaseId(String sourceDatabaseId) { this.sourceDatabaseId = sourceDatabaseId; }

    public List<String> getTableNames() { return tableNames; }
    public void setTableNames(List<String> tableNames) { this.tableNames = tableNames; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public KnowledgeBaseStatus getStatus() { return status; }
    public void setStatus(KnowledgeBaseStatus status) { this.status = status; }

    public Integer getDocumentCount() { return documentCount; }
    public void setDocumentCount(Integer documentCount) { this.documentCount = documentCount; }

    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
