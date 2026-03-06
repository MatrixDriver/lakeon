package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.ImportTaskStatus;

public record ImportCallbackRequest(
    @JsonProperty("table_task_id") String tableTaskId,
    ImportTaskStatus status,
    @JsonProperty("row_count") Long rowCount,
    @JsonProperty("error_message") String errorMessage
) {}
