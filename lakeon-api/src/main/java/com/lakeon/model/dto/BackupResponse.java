package com.lakeon.model.dto;

import com.lakeon.model.enums.BackupStatus;
import com.lakeon.model.enums.BackupType;

import java.time.Instant;

public record BackupResponse(
    String id,
    String databaseId,
    String tenantId,
    String name,
    BackupStatus status,
    BackupType type,
    String neonTenantId,
    String neonTimelineId,
    String sourceTenantId,
    String sourceTimelineId,
    String lsn,
    Long sizeBytes,
    Instant createdAt,
    Instant completedAt,
    String errorMessage
) {}
