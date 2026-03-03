package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateDatabaseRequest(
    @NotBlank String name,
    @JsonProperty("compute_size") @Pattern(regexp = "^[1248]cu$") String computeSize,
    @JsonProperty("suspend_timeout") @Pattern(regexp = "^\\d+m$") String suspendTimeout,
    @JsonProperty("storage_limit_gb") Integer storageLimitGb
) {}
