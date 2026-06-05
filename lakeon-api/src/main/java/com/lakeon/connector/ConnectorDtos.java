package com.lakeon.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.dto.SourceTableInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConnectorDtos {
    private ConnectorDtos() {
    }

    public record CreateConnectorRequest(
        ConnectorType type,
        String name,
        Map<String, Object> config,
        Map<String, Object> secret
    ) {
    }

    public record UpdateConnectorRequest(
        String name,
        Map<String, Object> config,
        Map<String, Object> secret
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorResponse(
        String id,
        ConnectorType type,
        String name,
        ConnectorStatus status,
        Map<String, Object> config,
        @JsonProperty("target_summary") String targetSummary,
        @JsonProperty("last_tested_at") Instant lastTestedAt,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("usage_count") Long usageCount,
        @JsonProperty("usage_hint") String usageHint
    ) {
    }

    public record ConnectorTestResponse(
        boolean ok,
        String error,
        Map<String, Object> metadata
    ) {
    }

    public record PostgresConnectionSnapshot(
        String connectorId,
        String connectorName,
        String host,
        Integer port,
        String dbname,
        String user,
        String password
    ) {
    }

    public record PostgresTableListResponse(
        List<SourceTableInfo> tables
    ) {
    }
}
