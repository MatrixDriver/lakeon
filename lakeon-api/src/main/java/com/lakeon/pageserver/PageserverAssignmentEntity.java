package com.lakeon.pageserver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pageserver_assignments")
public class PageserverAssignmentEntity {

    @Id
    @Column(name = "id", length = 96)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "shard_id", nullable = false)
    private int shardId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "epoch", nullable = false)
    private long epoch;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "updated_reason", length = 256)
    private String updatedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static String id(String tenantId, int shardId) {
        return tenantId + ":" + shardId;
    }

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = id(tenantId, shardId);
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
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getShardId() { return shardId; }
    public void setShardId(int shardId) { this.shardId = shardId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUpdatedReason() { return updatedReason; }
    public void setUpdatedReason(String updatedReason) { this.updatedReason = updatedReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
