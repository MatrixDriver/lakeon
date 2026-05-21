package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record PitrRequest(
    @NotNull @JsonProperty("target_time") Instant targetTime,
    @JsonProperty("new_db_name") String newDbName
) {}
