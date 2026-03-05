package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TableInfo(
    String name,
    String type,
    @JsonProperty("row_count_estimate") long rowCountEstimate
) {}
