package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PitrResponse(
    @JsonProperty("new_db_id") String newDbId,
    @JsonProperty("branch_id") String branchId,
    String lsn,
    @JsonProperty("compute_endpoint") String computeEndpoint,
    String status
) {}
