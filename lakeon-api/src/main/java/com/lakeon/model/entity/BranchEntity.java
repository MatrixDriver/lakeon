package com.lakeon.model.entity;

import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.BranchType;
import com.lakeon.model.enums.ComputeStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "branches",
       uniqueConstraints = @UniqueConstraint(columnNames = {"database_id", "name"}))
public class BranchEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "neon_timeline_id", length = 64)
    private String neonTimelineId;

    @Column(name = "parent_branch_id", length = 64)
    private String parentBranchId;

    @Column(name = "parent_branch_name", length = 255)
    private String parentBranchName;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_type", nullable = false)
    private BranchType branchType = BranchType.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BranchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "compute_status")
    private ComputeStatus computeStatus;

    @Column(name = "compute_pod_name", length = 128)
    private String computePodName;

    @Column(name = "compute_host", length = 256)
    private String computeHost;

    @Column(name = "compute_port")
    private Integer computePort;

    @Column(name = "connection_uri", length = 512)
    private String connectionUri;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "br_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (branchType == null) {
            branchType = BranchType.USER;
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

    public String getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(String databaseId) {
        this.databaseId = databaseId;
    }

    public String getNeonTimelineId() {
        return neonTimelineId;
    }

    public void setNeonTimelineId(String neonTimelineId) {
        this.neonTimelineId = neonTimelineId;
    }

    public String getParentBranchId() {
        return parentBranchId;
    }

    public void setParentBranchId(String parentBranchId) {
        this.parentBranchId = parentBranchId;
    }

    public String getParentBranchName() {
        return parentBranchName;
    }

    public void setParentBranchName(String parentBranchName) {
        this.parentBranchName = parentBranchName;
    }

    public boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public BranchType getBranchType() {
        return branchType;
    }

    public void setBranchType(BranchType branchType) {
        this.branchType = branchType;
    }

    public BranchStatus getStatus() {
        return status;
    }

    public void setStatus(BranchStatus status) {
        this.status = status;
    }

    public ComputeStatus getComputeStatus() {
        return computeStatus;
    }

    public void setComputeStatus(ComputeStatus computeStatus) {
        this.computeStatus = computeStatus;
    }

    public String getComputePodName() {
        return computePodName;
    }

    public void setComputePodName(String computePodName) {
        this.computePodName = computePodName;
    }

    public String getComputeHost() {
        return computeHost;
    }

    public void setComputeHost(String computeHost) {
        this.computeHost = computeHost;
    }

    public Integer getComputePort() {
        return computePort;
    }

    public void setComputePort(Integer computePort) {
        this.computePort = computePort;
    }

    public String getConnectionUri() {
        return connectionUri;
    }

    public void setConnectionUri(String connectionUri) {
        this.connectionUri = connectionUri;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
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
