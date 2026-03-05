package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TableStats(
    @JsonProperty("row_count") long rowCount,
    @JsonProperty("size_bytes") long sizeBytes,
    @JsonProperty("size_pretty") String sizePretty
) {}
