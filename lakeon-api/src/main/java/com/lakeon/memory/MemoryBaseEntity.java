package com.lakeon.memory;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory_bases", indexes = {
    @Index(name = "idx_memory_bases_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_memory_bases_status", columnList = "status")
})
public class MemoryBaseEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16)
    private MemoryBaseType type = MemoryBaseType.BUILTIN;

    @Column(name = "database_id", length = 32)
    private String databaseId;

    @Column(name = "db_password", length = 256)
    private String dbPassword;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "CREATING";

    @Column(name = "memory_count")
    private Integer memoryCount = 0;

    @Column(name = "trait_count")
    private Integer traitCount = 0;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "one_llm_mode")
    private Boolean oneLlmMode = false;

    @Column(name = "scene", length = 32)
    private String scene = "CHAT_ASSISTANT";

    @Column(name = "encrypted")
    private Boolean encrypted = false;

    @Column(name = "encrypted_dek", columnDefinition = "TEXT")
    private String encryptedDek;

    @Column(name = "kdf_salt")
    private String kdfSalt;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public MemoryBaseType getType() { return type; }
    public void setType(MemoryBaseType type) { this.type = type; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getMemoryCount() { return memoryCount; }
    public void setMemoryCount(Integer memoryCount) { this.memoryCount = memoryCount; }

    public Integer getTraitCount() { return traitCount; }
    public void setTraitCount(Integer traitCount) { this.traitCount = traitCount; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Boolean getOneLlmMode() { return oneLlmMode; }
    public void setOneLlmMode(Boolean oneLlmMode) { this.oneLlmMode = oneLlmMode; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public Boolean getEncrypted() { return encrypted; }
    public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }

    public String getEncryptedDek() { return encryptedDek; }
    public void setEncryptedDek(String encryptedDek) { this.encryptedDek = encryptedDek; }

    public String getKdfSalt() { return kdfSalt; }
    public void setKdfSalt(String kdfSalt) { this.kdfSalt = kdfSalt; }

    public Integer getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
