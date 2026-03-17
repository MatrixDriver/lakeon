package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class BranchResponse {
    private String id;
    private String name;
    @JsonProperty("parent_branch")
    private String parentBranch;
    @JsonProperty("is_default")
    private boolean isDefault;
    private String status;
    @JsonProperty("compute_status")
    private String computeStatus;
    @JsonProperty("connection_uri")
    private String connectionUri;
    @JsonProperty("parent_branch_id")
    private String parentBranchId;
    @JsonProperty("neon_timeline_id")
    private String neonTimelineId;
    @JsonProperty("ancestor_lsn")
    private String ancestorLsn;
    @JsonProperty("last_record_lsn")
    private String lastRecordLsn;
    @JsonProperty("current_logical_size_bytes")
    private Long currentLogicalSizeBytes;
    @JsonProperty("created_at")
    private Instant createdAt;
    @JsonProperty("branch_type")
    private String branchType;

    public BranchResponse() {}

    public BranchResponse(String id, String name, String parentBranch, boolean isDefault,
                          String status, String computeStatus, String connectionUri,
                          String parentBranchId, String neonTimelineId, String ancestorLsn,
                          String lastRecordLsn, Long currentLogicalSizeBytes, Instant createdAt,
                          String branchType) {
        this.id = id;
        this.name = name;
        this.parentBranch = parentBranch;
        this.isDefault = isDefault;
        this.status = status;
        this.computeStatus = computeStatus;
        this.connectionUri = connectionUri;
        this.parentBranchId = parentBranchId;
        this.neonTimelineId = neonTimelineId;
        this.ancestorLsn = ancestorLsn;
        this.lastRecordLsn = lastRecordLsn;
        this.currentLogicalSizeBytes = currentLogicalSizeBytes;
        this.createdAt = createdAt;
        this.branchType = branchType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParentBranch() { return parentBranch; }
    public void setParentBranch(String parentBranch) { this.parentBranch = parentBranch; }
    public boolean isIsDefault() { return isDefault; }
    public void setIsDefault(boolean isDefault) { this.isDefault = isDefault; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getComputeStatus() { return computeStatus; }
    public void setComputeStatus(String computeStatus) { this.computeStatus = computeStatus; }
    public String getConnectionUri() { return connectionUri; }
    public void setConnectionUri(String connectionUri) { this.connectionUri = connectionUri; }
    public String getParentBranchId() { return parentBranchId; }
    public void setParentBranchId(String parentBranchId) { this.parentBranchId = parentBranchId; }
    public String getNeonTimelineId() { return neonTimelineId; }
    public void setNeonTimelineId(String neonTimelineId) { this.neonTimelineId = neonTimelineId; }
    public String getAncestorLsn() { return ancestorLsn; }
    public void setAncestorLsn(String ancestorLsn) { this.ancestorLsn = ancestorLsn; }
    public String getLastRecordLsn() { return lastRecordLsn; }
    public void setLastRecordLsn(String lastRecordLsn) { this.lastRecordLsn = lastRecordLsn; }
    public Long getCurrentLogicalSizeBytes() { return currentLogicalSizeBytes; }
    public void setCurrentLogicalSizeBytes(Long currentLogicalSizeBytes) { this.currentLogicalSizeBytes = currentLogicalSizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getBranchType() { return branchType; }
    public void setBranchType(String branchType) { this.branchType = branchType; }

    public static class Builder {
        private String id;
        private String name;
        private String parentBranch;
        private boolean isDefault;
        private String status;
        private String computeStatus;
        private String connectionUri;
        private String parentBranchId;
        private String neonTimelineId;
        private String ancestorLsn;
        private String lastRecordLsn;
        private Long currentLogicalSizeBytes;
        private Instant createdAt;
        private String branchType;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder parentBranch(String parentBranch) { this.parentBranch = parentBranch; return this; }
        public Builder isDefault(boolean isDefault) { this.isDefault = isDefault; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder computeStatus(String computeStatus) { this.computeStatus = computeStatus; return this; }
        public Builder connectionUri(String connectionUri) { this.connectionUri = connectionUri; return this; }
        public Builder parentBranchId(String parentBranchId) { this.parentBranchId = parentBranchId; return this; }
        public Builder neonTimelineId(String neonTimelineId) { this.neonTimelineId = neonTimelineId; return this; }
        public Builder ancestorLsn(String ancestorLsn) { this.ancestorLsn = ancestorLsn; return this; }
        public Builder lastRecordLsn(String lastRecordLsn) { this.lastRecordLsn = lastRecordLsn; return this; }
        public Builder currentLogicalSizeBytes(Long currentLogicalSizeBytes) { this.currentLogicalSizeBytes = currentLogicalSizeBytes; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder branchType(String branchType) { this.branchType = branchType; return this; }

        public BranchResponse build() {
            return new BranchResponse(id, name, parentBranch, isDefault, status,
                computeStatus, connectionUri, parentBranchId, neonTimelineId,
                ancestorLsn, lastRecordLsn, currentLogicalSizeBytes, createdAt, branchType);
        }
    }
}
