package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QueryResult(
    List<String> columns,
    List<List<Object>> rows,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("execution_time_ms") long executionTimeMs,
    @JsonProperty("is_select") boolean isSelect
) {}
