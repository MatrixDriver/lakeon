package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record AuditLogResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    @JsonProperty("tenant_id") String tenantId,
    Instant timestamp,
    @JsonProperty("user_name") String userName,
    String statement,
    @JsonProperty("statement_type") String statementType,
    @JsonProperty("object_name") String objectName,
    @JsonProperty("client_addr") String clientAddr,
    Long duration
) {}
