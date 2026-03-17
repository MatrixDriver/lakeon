package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "name"}))
public class VersionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "lsn", nullable = false)
    private long lsn;  // Stored as BIGINT, parsed from Neon hex LSN via LsnUtil

    @Column(name = "lsn_hex", nullable = false, length = 32)
    private String lsnHex;  // Original hex string for display (e.g., "0/1A2B3C0")

    @Column(name = "snapshot_timeline_id", length = 64)
    private String snapshotTimelineId;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ver_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
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

    public String getBranchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getLsn() {
        return lsn;
    }

    public void setLsn(long lsn) {
        this.lsn = lsn;
    }

    public String getLsnHex() {
        return lsnHex;
    }

    public void setLsnHex(String lsnHex) {
        this.lsnHex = lsnHex;
    }

    public String getSnapshotTimelineId() {
        return snapshotTimelineId;
    }

    public void setSnapshotTimelineId(String snapshotTimelineId) {
        this.snapshotTimelineId = snapshotTimelineId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
