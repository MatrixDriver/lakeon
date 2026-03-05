package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record IndexInfo(
    String name,
    List<String> columns,
    @JsonProperty("is_unique") boolean isUnique,
    @JsonProperty("is_primary") boolean isPrimary
) {}
