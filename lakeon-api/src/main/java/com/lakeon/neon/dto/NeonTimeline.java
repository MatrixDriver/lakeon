package com.lakeon.neon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NeonTimeline {
    @JsonProperty("timeline_id")
    private String timelineId;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("ancestor_timeline_id")
    private String ancestorTimelineId;

    @JsonProperty("current_logical_size")
    private Long currentLogicalSize;

    @JsonProperty("last_record_lsn")
    private String lastRecordLsn;

    public NeonTimeline() {}

    public NeonTimeline(String timelineId) {
        this.timelineId = timelineId;
    }

    public String getTimelineId() { return timelineId; }
    public void setTimelineId(String timelineId) { this.timelineId = timelineId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAncestorTimelineId() { return ancestorTimelineId; }
    public void setAncestorTimelineId(String ancestorTimelineId) { this.ancestorTimelineId = ancestorTimelineId; }
    public Long getCurrentLogicalSize() { return currentLogicalSize; }
    public void setCurrentLogicalSize(Long currentLogicalSize) { this.currentLogicalSize = currentLogicalSize; }
    public String getLastRecordLsn() { return lastRecordLsn; }
    public void setLastRecordLsn(String lastRecordLsn) { this.lastRecordLsn = lastRecordLsn; }
}
