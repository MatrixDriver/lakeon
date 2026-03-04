package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateQuotaRequest(
    @JsonProperty("max_databases") Integer maxDatabases,
    @JsonProperty("max_storage_gb") Integer maxStorageGb,
    @JsonProperty("max_compute_cu") Integer maxComputeCu
) {}
