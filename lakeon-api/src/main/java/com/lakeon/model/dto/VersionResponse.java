package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class VersionResponse {
    private String id;
    @JsonProperty("branch_id")
    private String branchId;
    private String name;
    private String description;
    private String lsn;
    @JsonProperty("snapshot_timeline_id")
    private String snapshotTimelineId;
    @JsonProperty("created_by")
    private String createdBy;
    @JsonProperty("created_at")
    private Instant createdAt;

    public VersionResponse() {}

    public VersionResponse(String id, String branchId, String name, String description,
                           String lsn, String snapshotTimelineId, String createdBy, Instant createdAt) {
        this.id = id;
        this.branchId = branchId;
        this.name = name;
        this.description = description;
        this.lsn = lsn;
        this.snapshotTimelineId = snapshotTimelineId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLsn() { return lsn; }
    public void setLsn(String lsn) { this.lsn = lsn; }
    public String getSnapshotTimelineId() { return snapshotTimelineId; }
    public void setSnapshotTimelineId(String snapshotTimelineId) { this.snapshotTimelineId = snapshotTimelineId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class Builder {
        private String id;
        private String branchId;
        private String name;
        private String description;
        private String lsn;
        private String snapshotTimelineId;
        private String createdBy;
        private Instant createdAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder branchId(String branchId) { this.branchId = branchId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder lsn(String lsn) { this.lsn = lsn; return this; }
        public Builder snapshotTimelineId(String snapshotTimelineId) { this.snapshotTimelineId = snapshotTimelineId; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public VersionResponse build() {
            return new VersionResponse(id, branchId, name, description, lsn,
                snapshotTimelineId, createdBy, createdAt);
        }
    }
}
