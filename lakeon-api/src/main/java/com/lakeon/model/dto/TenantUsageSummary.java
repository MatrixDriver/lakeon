package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TenantUsageSummary(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("tenant_name") String tenantName,
        List<DatabaseUsageSummary> databases,
        @JsonProperty("total_compute_seconds") long totalComputeSeconds,
        @JsonProperty("total_compute_cu_hours") double totalComputeCuHours,
        @JsonProperty("total_compute_cost") double totalComputeCost,
        @JsonProperty("total_storage_used_gb") double totalStorageUsedGb,
        @JsonProperty("total_storage_cost") double totalStorageCost,
        @JsonProperty("total_estimated_cost") double totalEstimatedCost) {
}
