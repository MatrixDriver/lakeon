package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateBranchRequest(
    @NotBlank String name,
    @JsonProperty("start_compute") Boolean startCompute
) {}
