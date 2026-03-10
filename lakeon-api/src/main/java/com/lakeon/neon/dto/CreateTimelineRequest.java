package com.lakeon.neon.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateTimelineRequest {
    @JsonProperty("new_timeline_id")
    private String newTimelineId;

    @JsonProperty("pg_version")
    private Integer pgVersion;

    @JsonProperty("ancestor_timeline_id")
    private String ancestorTimelineId;

    @JsonProperty("ancestor_start_lsn")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ancestorStartLsn;

    // Private constructor — use static factory methods
    private CreateTimelineRequest() {}

    /** Create a new root timeline with the given PG version. */
    public static CreateTimelineRequest forNewTimeline(String timelineId, int pgVersion) {
        CreateTimelineRequest req = new CreateTimelineRequest();
        req.newTimelineId = timelineId;
        req.pgVersion = pgVersion;
        return req;
    }

    /** Create a branch timeline from an ancestor. */
    public static CreateTimelineRequest forBranch(String timelineId, String ancestorTimelineId) {
        CreateTimelineRequest req = new CreateTimelineRequest();
        req.newTimelineId = timelineId;
        req.ancestorTimelineId = ancestorTimelineId;
        return req;
    }

    /** Create a branch timeline from an ancestor at a specific LSN. */
    public static CreateTimelineRequest forBranchAtLsn(String timelineId, String ancestorTimelineId, String ancestorStartLsn) {
        CreateTimelineRequest req = new CreateTimelineRequest();
        req.newTimelineId = timelineId;
        req.ancestorTimelineId = ancestorTimelineId;
        req.ancestorStartLsn = ancestorStartLsn;
        return req;
    }

    public String getNewTimelineId() { return newTimelineId; }
    public void setNewTimelineId(String newTimelineId) { this.newTimelineId = newTimelineId; }
    public Integer getPgVersion() { return pgVersion; }
    public void setPgVersion(Integer pgVersion) { this.pgVersion = pgVersion; }
    public String getAncestorTimelineId() { return ancestorTimelineId; }
    public void setAncestorTimelineId(String ancestorTimelineId) { this.ancestorTimelineId = ancestorTimelineId; }
    public String getAncestorStartLsn() { return ancestorStartLsn; }
    public void setAncestorStartLsn(String ancestorStartLsn) { this.ancestorStartLsn = ancestorStartLsn; }
}
