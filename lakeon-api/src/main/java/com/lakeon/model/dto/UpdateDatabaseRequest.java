package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateDatabaseRequest(
    @JsonProperty("compute_size") String computeSize,
    @JsonProperty("suspend_timeout") String suspendTimeout,
    @JsonProperty("storage_limit_gb") Integer storageLimitGb
) {}
