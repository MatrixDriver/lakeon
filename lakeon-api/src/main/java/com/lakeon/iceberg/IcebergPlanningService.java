package com.lakeon.iceberg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class IcebergPlanningService {

    private static final int DEFAULT_LIMIT = 10_000;
    private static final String PLAN_ID = "sync-20260624-0001";
    private static final Pattern SAFE_PARTITION_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String FULL_SCAN_SQL = """
            SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
            FROM _lakeon_iceberg.data_files
            WHERE table_id = ?
              AND branch_id = ?
              AND snapshot_id <= ?
            ORDER BY file_path
            LIMIT ?
            """;
    private static final String PARTITION_SCAN_SQL = """
            SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
            FROM _lakeon_iceberg.data_files
            WHERE table_id = ?
              AND branch_id = ?
              AND snapshot_id <= ?
              AND partition_json @> ?::jsonb
            ORDER BY file_path
            LIMIT ?
            """;
    private static final String APPEND_ONLY_SNAPSHOT_SQL = """
            SELECT snapshot_id, operation
            FROM _lakeon_iceberg.snapshots
            WHERE table_id = ?
              AND branch_id = ?
              AND snapshot_id <= ?
            ORDER BY snapshot_id
            """;

    private final LakebaseBranchConnectionProvider connectionProvider;
    private final ObjectMapper objectMapper;

    public IcebergPlanningService(ObjectProvider<LakebaseBranchConnectionProvider> connectionProvider,
                                  ObjectMapper objectMapper) {
        this.connectionProvider = connectionProvider.getIfAvailable(() -> (tenant, databaseId, branchId) -> {
            throw new SQLException("Lakebase branch connection provider is not implemented yet");
        });
        this.objectMapper = objectMapper;
    }

    public PlanTableScanResponse planTableScan(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String tableId,
            PlanTableScanRequest request
    ) {
        PlanTableScanRequest effectiveRequest = request == null ? PlanTableScanRequest.empty() : request;
        SafePartitionEquality partitionEquality = parseSafePartitionEquality(effectiveRequest);
        String sql = partitionEquality == null ? FULL_SCAN_SQL : PARTITION_SCAN_SQL;
        long snapshotId = effectiveRequest.snapshotId() == null ? Long.MAX_VALUE : effectiveRequest.snapshotId();

        try (Connection connection = connectionProvider.open(tenant, databaseId, branchId)) {
            validateAppendOnlySnapshots(connection, tableId, branchId, snapshotId);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tableId);
                statement.setString(2, branchId);
                statement.setLong(3, snapshotId);
                if (partitionEquality == null) {
                    statement.setInt(4, DEFAULT_LIMIT);
                } else {
                    statement.setString(4, partitionEquality.partitionJson());
                    statement.setInt(5, DEFAULT_LIMIT);
                }

                List<FileScanTask> fileScanTasks = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        fileScanTasks.add(new FileScanTask(
                                new RestDataFile(
                                        0,
                                        partitionValues(rs.getString("partition_json"), effectiveRequest.partitionFields()),
                                        "data",
                                        rs.getString("file_path"),
                                        "PARQUET",
                                        rs.getLong("file_size_bytes"),
                                        rs.getLong("record_count")
                                ),
                                effectiveRequest.select()
                        ));
                    }
                }

                return new PlanTableScanResponse(
                        PLAN_ID,
                        "completed",
                        fileScanTasks
                );
            }
        } catch (SQLException e) {
            throw new BadRequestException("Failed to plan Iceberg table scan for " + tableId + ": " + e.getMessage());
        }
    }

    private void validateAppendOnlySnapshots(Connection connection, String tableId, String branchId, long snapshotId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPEND_ONLY_SNAPSHOT_SQL)) {
            statement.setString(1, tableId);
            statement.setString(2, branchId);
            statement.setLong(3, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String operation = rs.getString("operation");
                    if (!isAppendCompatibleOperation(operation)) {
                        throw new BadRequestException(
                                "Iceberg server-side planning currently supports append-only snapshots; "
                                        + "found operation " + operation + " for snapshot " + rs.getLong("snapshot_id")
                        );
                    }
                }
            }
        }
    }

    private boolean isAppendCompatibleOperation(String operation) {
        if (operation == null) {
            return false;
        }
        String normalized = operation.trim().toLowerCase(Locale.ROOT);
        return "append".equals(normalized) || "insert".equals(normalized) || "backfill".equals(normalized);
    }

    private List<JsonNode> partitionValues(String partitionJson, List<String> partitionFields) {
        if (partitionFields == null || partitionFields.isEmpty()) {
            return List.of();
        }
        JsonNode partition = parseNullableJson(partitionJson);
        if (partition == null || !partition.isObject()) {
            throw new BadRequestException("Iceberg data file partition_json must be a JSON object when partition-fields are requested");
        }
        List<JsonNode> values = new ArrayList<>(partitionFields.size());
        for (String field : partitionFields) {
            JsonNode value = partition.get(field);
            values.add(value == null ? objectMapper.nullNode() : value.deepCopy());
        }
        return values;
    }

    private SafePartitionEquality parseSafePartitionEquality(PlanTableScanRequest request) {
        JsonNode filter = request.filter();
        if (filter == null || !filter.isObject()) {
            return null;
        }
        if (Boolean.FALSE.equals(request.caseSensitive())) {
            return null;
        }
        List<String> partitionFields = request.partitionFields();
        if (partitionFields == null || partitionFields.isEmpty()) {
            return null;
        }
        JsonNode op = filter.get("op");
        JsonNode field = filter.get("field");
        JsonNode value = filter.get("value");
        if (op == null || field == null || value == null || !op.isTextual() || !field.isTextual()) {
            return null;
        }
        if (!"=".equals(op.asText()) || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String fieldName = field.asText();
        if (!SAFE_PARTITION_FIELD.matcher(fieldName).matches()) {
            return null;
        }
        if (!partitionFields.contains(fieldName)) {
            return null;
        }

        ObjectNode partition = objectMapper.createObjectNode();
        partition.set(fieldName, value.deepCopy());
        try {
            return new SafePartitionEquality(objectMapper.writeValueAsString(partition));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseNullableJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new BadRequestException("Failed to parse Iceberg planning JSON: " + e.getMessage());
        }
    }

    private record SafePartitionEquality(String partitionJson) {
    }

    public record PlanTableScanRequest(
            @JsonProperty("select") List<String> select,
            @JsonProperty("filter") JsonNode filter,
            @JsonProperty("case-sensitive") Boolean caseSensitive,
            @JsonProperty("snapshot-id") Long snapshotId,
            @JsonProperty("stats-fields") List<String> statsFields,
            @JsonProperty("partition-fields") List<String> partitionFields
    ) {
        private static PlanTableScanRequest empty() {
            return new PlanTableScanRequest(null, null, true, null, null, null);
        }
    }

    public record PlanTableScanResponse(
            @JsonProperty("plan-id") String planId,
            @JsonProperty("status") String status,
            @JsonProperty("file-scan-tasks") List<FileScanTask> fileScanTasks
    ) {
        public PlanTableScanResponse {
            status = status == null ? "completed" : status.toLowerCase(Locale.ROOT);
        }
    }

    public record FileScanTask(
            @JsonProperty("data-file") RestDataFile dataFile,
            @JsonIgnore List<String> selectedColumns
    ) {
    }

    public record RestDataFile(
            @JsonProperty("spec-id") int specId,
            @JsonProperty("partition") List<JsonNode> partition,
            @JsonProperty("content") String content,
            @JsonProperty("file-path") String filePath,
            @JsonProperty("file-format") String fileFormat,
            @JsonProperty("file-size-in-bytes") long fileSizeInBytes,
            @JsonProperty("record-count") long recordCount
    ) {
    }
}
