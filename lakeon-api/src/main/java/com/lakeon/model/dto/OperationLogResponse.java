package com.lakeon.model.dto;

import com.lakeon.model.entity.OperationLogEntity;
import java.time.Instant;

public record OperationLogResponse(
    String id,
    String databaseId,
    String databaseName,
    String operationType,
    String status,
    Instant startedAt,
    Instant completedAt,
    Long durationMs,
    String errorMessage,
    String resumeType
) {
    public static OperationLogResponse from(OperationLogEntity entity) {
        return new OperationLogResponse(
            entity.getId(),
            entity.getDatabaseId(),
            entity.getDatabaseName(),
            entity.getOperationType().name(),
            entity.getStatus().name(),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            entity.getDurationMs(),
            entity.getErrorMessage(),
            entity.getResumeType()
        );
    }
}
