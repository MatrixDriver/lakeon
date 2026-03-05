package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerts",
       indexes = {
           @Index(name = "idx_alerts_status", columnList = "status"),
           @Index(name = "idx_alerts_fired_at", columnList = "fired_at")
       })
public class AlertEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "message", length = 1024)
    private String message;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "al_" + UUID.randomUUID().toString().substring(0, 8);
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

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public void setFiredAt(Instant firedAt) {
        this.firedAt = firedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
