package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DatabaseUsageSummary(
        @JsonProperty("database_id") String databaseId,
        @JsonProperty("database_name") String databaseName,
        @JsonProperty("compute_size") String computeSize,
        @JsonProperty("compute_seconds") long computeSeconds,
        @JsonProperty("compute_cu_hours") double computeCuHours,
        @JsonProperty("estimated_cost") double estimatedCost) {
}
