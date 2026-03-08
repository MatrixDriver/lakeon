package com.lakeon.model.entity;

import com.lakeon.model.enums.DatabaseRole;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_users",
       uniqueConstraints = @UniqueConstraint(columnNames = {"database_id", "username"}),
       indexes = {
           @Index(name = "idx_database_users_database_id", columnList = "database_id"),
           @Index(name = "idx_database_users_tenant_id", columnList = "tenant_id")
       })
public class DatabaseUserEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password", length = 512)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private DatabaseRole role;

    @Column(name = "is_owner", nullable = false)
    private Boolean isOwner = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "du_" + UUID.randomUUID().toString().substring(0, 8);
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

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public DatabaseRole getRole() { return role; }
    public void setRole(DatabaseRole role) { this.role = role; }

    public Boolean getIsOwner() { return isOwner; }
    public void setIsOwner(Boolean isOwner) { this.isOwner = isOwner; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
