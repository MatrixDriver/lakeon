package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateTableRequest(
    @NotBlank String name,
    @NotEmpty List<ColumnDef> columns,
    @JsonProperty("primary_key") List<String> primaryKey
) {
    public record ColumnDef(
        @NotBlank String name,
        @NotBlank String type,
        boolean nullable,
        @JsonProperty("default_value") String defaultValue
    ) {}
}
