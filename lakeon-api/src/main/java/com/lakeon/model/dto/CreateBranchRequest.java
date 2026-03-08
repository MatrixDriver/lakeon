package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateBranchRequest(
    @NotBlank String name,
    @JsonProperty("start_compute") Boolean startCompute,
    @JsonProperty("parent_branch_id") String parentBranchId,
    @JsonProperty("ancestor_lsn") String ancestorLsn
) {}
