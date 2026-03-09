package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncStatusResponse(
    @JsonProperty("overall_status") String overallStatus,
    @JsonProperty("replay_lag_seconds") Double replayLagSeconds,
    @JsonProperty("sync_rate_rows_per_sec") Long syncRateRowsPerSec,
    @JsonProperty("last_sync_at") Instant lastSyncAt,
    @JsonProperty("wal_retained_bytes") Long walRetainedBytes,
    @JsonProperty("wal_warning") Boolean walWarning,
    List<TableSyncStatus> tables
) {
    public record TableSyncStatus(
        @JsonProperty("table_name") String tableName,
        @JsonProperty("schema_name") String schemaName,
        String status,
        @JsonProperty("synced_rows") Long syncedRows,
        String error
    ) {}
}
