package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ColumnInfo(
    String name,
    @JsonProperty("data_type") String dataType,
    boolean nullable,
    @JsonProperty("default_value") String defaultValue,
    String comment,
    @JsonProperty("ordinal_position") int ordinalPosition
) {}
