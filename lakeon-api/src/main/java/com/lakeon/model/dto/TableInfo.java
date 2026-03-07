package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TableInfo(
    String name,
    String type,
    @JsonProperty("row_count_estimate") long rowCountEstimate,
    @JsonProperty("table_size_bytes") long tableSizeBytes
) {
    // 兼容旧的3参数构造
    public TableInfo(String name, String type, long rowCountEstimate) {
        this(name, type, rowCountEstimate, 0L);
    }
}
