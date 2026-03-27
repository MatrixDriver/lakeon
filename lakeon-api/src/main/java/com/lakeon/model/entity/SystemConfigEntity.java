package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "system_config")
public class SystemConfigEntity {

    @Id
    @Column(length = 128)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() { updatedAt = Instant.now(); }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
