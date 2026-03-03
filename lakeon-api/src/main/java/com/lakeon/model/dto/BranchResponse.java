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
    @JsonProperty("created_at")
    private Instant createdAt;

    public BranchResponse() {}

    public BranchResponse(String id, String name, String parentBranch, boolean isDefault,
                          String status, String computeStatus, String connectionUri, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.parentBranch = parentBranch;
        this.isDefault = isDefault;
        this.status = status;
        this.computeStatus = computeStatus;
        this.connectionUri = connectionUri;
        this.createdAt = createdAt;
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
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class Builder {
        private String id;
        private String name;
        private String parentBranch;
        private boolean isDefault;
        private String status;
        private String computeStatus;
        private String connectionUri;
        private Instant createdAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder parentBranch(String parentBranch) { this.parentBranch = parentBranch; return this; }
        public Builder isDefault(boolean isDefault) { this.isDefault = isDefault; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder computeStatus(String computeStatus) { this.computeStatus = computeStatus; return this; }
        public Builder connectionUri(String connectionUri) { this.connectionUri = connectionUri; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public BranchResponse build() {
            return new BranchResponse(id, name, parentBranch, isDefault, status,
                computeStatus, connectionUri, createdAt);
        }
    }
}
