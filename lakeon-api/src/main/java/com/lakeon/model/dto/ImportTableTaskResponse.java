package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.ImportTaskStatus;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportTableTaskResponse(
    String id,
    @JsonProperty("schema_name") String schemaName,
    @JsonProperty("table_name") String tableName,
    ImportTaskStatus status,
    @JsonProperty("row_count") Long rowCount,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("started_at") Instant startedAt,
    @JsonProperty("finished_at") Instant finishedAt
) {}
