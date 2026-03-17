package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record SquashVersionsRequest(
    @NotBlank @JsonProperty("from_version_id") String fromVersionId,
    @NotBlank @JsonProperty("to_version_id") String toVersionId
) {}
