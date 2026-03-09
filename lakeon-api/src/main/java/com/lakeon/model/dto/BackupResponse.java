package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.BackupStatus;
import com.lakeon.model.enums.BackupType;

import java.time.Instant;

public record BackupResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    @JsonProperty("tenant_id") String tenantId,
    String name,
    BackupStatus status,
    BackupType type,
    @JsonProperty("neon_tenant_id") String neonTenantId,
    @JsonProperty("neon_timeline_id") String neonTimelineId,
    @JsonProperty("source_tenant_id") String sourceTenantId,
    @JsonProperty("source_timeline_id") String sourceTimelineId,
    String lsn,
    @JsonProperty("size_bytes") Long sizeBytes,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("completed_at") Instant completedAt,
    @JsonProperty("error_message") String errorMessage
) {}
