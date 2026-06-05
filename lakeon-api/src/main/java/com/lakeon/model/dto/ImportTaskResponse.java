package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportTaskResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    @JsonProperty("connector_id") String connectorId,
    @JsonProperty("connector_name") String connectorName,
    @JsonProperty("source_host") String sourceHost,
    @JsonProperty("source_port") Integer sourcePort,
    @JsonProperty("source_dbname") String sourceDbname,
    @JsonProperty("source_user") String sourceUser,
    ImportMode mode,
    @JsonProperty("conflict_strategy") ConflictStrategy conflictStrategy,
    ImportTaskStatus status,
    @JsonProperty("total_tables") Integer totalTables,
    @JsonProperty("completed_tables") Integer completedTables,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("started_at") Instant startedAt,
    @JsonProperty("finished_at") Instant finishedAt,
    List<ImportTableTaskResponse> tables,
    @JsonProperty("publication_name") String publicationName,
    @JsonProperty("subscription_name") String subscriptionName,
    @JsonProperty("sync_status") String syncStatus,
    @JsonProperty("replay_lag_seconds") Double replayLagSeconds,
    @JsonProperty("sync_rate_rows_per_sec") Long syncRateRowsPerSec,
    @JsonProperty("last_sync_at") Instant lastSyncAt,
    @JsonProperty("wal_retained_bytes") Long walRetainedBytes,
    @JsonProperty("wal_warning") Boolean walWarning
) {}
