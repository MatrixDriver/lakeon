package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record PitrWindow(
    Instant earliest,
    Instant latest,
    @JsonProperty("earliest_lsn") String earliestLsn,
    @JsonProperty("latest_lsn") String latestLsn
) {}
