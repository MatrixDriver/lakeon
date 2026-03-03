package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "api_key", nullable = false, unique = true, length = 64)
    private String apiKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "tn_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (apiKey == null) {
            // Use SecureRandom for cryptographically strong API key
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("lk_");
            for (byte b : bytes) sb.append(String.format("%02x", b));
            apiKey = sb.toString();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
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
