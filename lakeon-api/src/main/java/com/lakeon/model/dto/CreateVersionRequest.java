package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateVersionRequest(
    @NotBlank String name,
    String description,
    String at,
    @JsonProperty("at_lsn") String atLsn
) {}
