package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RestoreBranchRequest(
    @JsonProperty("target_version_id") String targetVersionId,
    @JsonProperty("target_lsn") String targetLsn
) {}
