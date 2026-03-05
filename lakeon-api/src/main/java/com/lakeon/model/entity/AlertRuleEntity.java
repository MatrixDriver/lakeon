package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
public class AlertRuleEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "check_type", nullable = false)
    private String checkType;

    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "webhook_url", length = 512)
    private String webhookUrl;

    @Column(name = "cooldown_minutes", nullable = false)
    private Integer cooldownMinutes = 10;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ar_" + UUID.randomUUID().toString().substring(0, 8);
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

    public String getCheckType() {
        return checkType;
    }

    public void setCheckType(String checkType) {
        this.checkType = checkType;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public Integer getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(Integer cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public Instant getLastFiredAt() {
        return lastFiredAt;
    }

    public void setLastFiredAt(Instant lastFiredAt) {
        this.lastFiredAt = lastFiredAt;
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
