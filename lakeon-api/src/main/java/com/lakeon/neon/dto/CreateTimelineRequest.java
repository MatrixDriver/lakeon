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

    public CreateTimelineRequest() {}

    public CreateTimelineRequest(String newTimelineId, Integer pgVersion) {
        this.newTimelineId = newTimelineId;
        this.pgVersion = pgVersion;
    }

    public CreateTimelineRequest(String newTimelineId, String ancestorTimelineId) {
        this.newTimelineId = newTimelineId;
        this.ancestorTimelineId = ancestorTimelineId;
    }

    public CreateTimelineRequest(String newTimelineId, String ancestorTimelineId, String ancestorStartLsn) {
        this.newTimelineId = newTimelineId;
        this.ancestorTimelineId = ancestorTimelineId;
        this.ancestorStartLsn = ancestorStartLsn;
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
