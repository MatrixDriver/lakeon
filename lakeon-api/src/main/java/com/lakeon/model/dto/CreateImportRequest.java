package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.ImportMode;
import java.util.List;

public record CreateImportRequest(
    @JsonProperty("connector_id") String connectorId,
    @JsonProperty("source_host") String sourceHost,
    @JsonProperty("source_port") Integer sourcePort,
    @JsonProperty("source_dbname") String sourceDbname,
    @JsonProperty("source_user") String sourceUser,
    @JsonProperty("source_password") String sourcePassword,
    ImportMode mode,
    @JsonProperty("conflict_strategy") ConflictStrategy conflictStrategy,
    List<String> tables
) {}
