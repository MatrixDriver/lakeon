package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.DatabaseStatus;
import java.time.Instant;
import java.util.List;

public class DatabaseResponse {
    private String id;
    private String name;
    private DatabaseStatus status;
    @JsonProperty("connection_uri")
    private String connectionUri;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String password;  // Only returned on creation, null otherwise
    @JsonProperty("compute_size")
    private String computeSize;
    @JsonProperty("suspend_timeout")
    private String suspendTimeout;
    @JsonProperty("storage_limit_gb")
    private Integer storageLimitGb;
    @JsonProperty("storage_used_gb")
    private Double storageUsedGb;
    private List<BranchSummary> branches;
    @JsonProperty("created_at")
    private Instant createdAt;

    public DatabaseResponse() {}

    public DatabaseResponse(String id, String name, DatabaseStatus status, String connectionUri,
                            String computeSize, String suspendTimeout, Integer storageLimitGb,
                            Double storageUsedGb, List<BranchSummary> branches, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.connectionUri = connectionUri;
        this.computeSize = computeSize;
        this.suspendTimeout = suspendTimeout;
        this.storageLimitGb = storageLimitGb;
        this.storageUsedGb = storageUsedGb;
        this.branches = branches;
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DatabaseStatus getStatus() { return status; }
    public void setStatus(DatabaseStatus status) { this.status = status; }
    public String getConnectionUri() { return connectionUri; }
    public void setConnectionUri(String connectionUri) { this.connectionUri = connectionUri; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getComputeSize() { return computeSize; }
    public void setComputeSize(String computeSize) { this.computeSize = computeSize; }
    public String getSuspendTimeout() { return suspendTimeout; }
    public void setSuspendTimeout(String suspendTimeout) { this.suspendTimeout = suspendTimeout; }
    public Integer getStorageLimitGb() { return storageLimitGb; }
    public void setStorageLimitGb(Integer storageLimitGb) { this.storageLimitGb = storageLimitGb; }
    public Double getStorageUsedGb() { return storageUsedGb; }
    public void setStorageUsedGb(Double storageUsedGb) { this.storageUsedGb = storageUsedGb; }
    public List<BranchSummary> getBranches() { return branches; }
    public void setBranches(List<BranchSummary> branches) { this.branches = branches; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class BranchSummary {
        private String id;
        private String name;
        @JsonProperty("is_default")
        private boolean isDefault;
        private String status;
        @JsonProperty("compute_status")
        private String computeStatus;

        public BranchSummary() {}

        public BranchSummary(String id, String name, boolean isDefault, String status, String computeStatus) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
            this.status = status;
            this.computeStatus = computeStatus;
        }

        public static BranchSummaryBuilder builder() {
            return new BranchSummaryBuilder();
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isIsDefault() { return isDefault; }
        public void setIsDefault(boolean isDefault) { this.isDefault = isDefault; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getComputeStatus() { return computeStatus; }
        public void setComputeStatus(String computeStatus) { this.computeStatus = computeStatus; }

        public static class BranchSummaryBuilder {
            private String id;
            private String name;
            private boolean isDefault;
            private String status;
            private String computeStatus;

            public BranchSummaryBuilder id(String id) { this.id = id; return this; }
            public BranchSummaryBuilder name(String name) { this.name = name; return this; }
            public BranchSummaryBuilder isDefault(boolean isDefault) { this.isDefault = isDefault; return this; }
            public BranchSummaryBuilder status(String status) { this.status = status; return this; }
            public BranchSummaryBuilder computeStatus(String computeStatus) { this.computeStatus = computeStatus; return this; }

            public BranchSummary build() {
                return new BranchSummary(id, name, isDefault, status, computeStatus);
            }
        }
    }

    public static class Builder {
        private String id;
        private String name;
        private DatabaseStatus status;
        private String connectionUri;
        private String computeSize;
        private String suspendTimeout;
        private Integer storageLimitGb;
        private Double storageUsedGb;
        private List<BranchSummary> branches;
        private Instant createdAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder status(DatabaseStatus status) { this.status = status; return this; }
        public Builder connectionUri(String connectionUri) { this.connectionUri = connectionUri; return this; }
        public Builder computeSize(String computeSize) { this.computeSize = computeSize; return this; }
        public Builder suspendTimeout(String suspendTimeout) { this.suspendTimeout = suspendTimeout; return this; }
        public Builder storageLimitGb(Integer storageLimitGb) { this.storageLimitGb = storageLimitGb; return this; }
        public Builder storageUsedGb(Double storageUsedGb) { this.storageUsedGb = storageUsedGb; return this; }
        public Builder branches(List<BranchSummary> branches) { this.branches = branches; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public DatabaseResponse build() {
            return new DatabaseResponse(id, name, status, connectionUri, computeSize,
                suspendTimeout, storageLimitGb, storageUsedGb, branches, createdAt);
        }
    }
}
