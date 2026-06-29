package com.lakeon.pageserver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pageserver_rebalance_events")
public class PageserverRebalanceEventEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "trigger_type", nullable = false, length = 64)
    private String triggerType;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "target_node_id", length = 64)
    private String targetNodeId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "move_count", nullable = false)
    private int moveCount;

    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "moves_json", columnDefinition = "TEXT")
    private String movesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "pre_" + UUID.randomUUID().toString().substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getMoveCount() { return moveCount; }
    public void setMoveCount(int moveCount) { this.moveCount = moveCount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getMovesJson() { return movesJson; }
    public void setMovesJson(String movesJson) { this.movesJson = movesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
