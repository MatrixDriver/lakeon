package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record AuditConfigResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    @JsonProperty("tenant_id") String tenantId,
    boolean enabled,
    @JsonProperty("log_ddl") boolean logDdl,
    @JsonProperty("log_dml") boolean logDml,
    @JsonProperty("log_select") boolean logSelect,
    @JsonProperty("retention_days") int retentionDays,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {}
