package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceTableInfo(
    String schema,
    String table,
    @JsonProperty("estimated_rows") long estimatedRows
) {}
