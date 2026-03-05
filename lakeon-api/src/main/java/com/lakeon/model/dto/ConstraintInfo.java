package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ConstraintInfo(
    String name,
    String type,
    List<String> columns,
    @JsonProperty("ref_table") String refTable,
    @JsonProperty("ref_columns") List<String> refColumns
) {}
