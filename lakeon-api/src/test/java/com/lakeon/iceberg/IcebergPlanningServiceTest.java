package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IcebergPlanningServiceTest {

    private static final String APPEND_ONLY_SNAPSHOT_SQL = """
            SELECT snapshot_id, operation
            FROM _lakeon_iceberg.snapshots
            WHERE table_id = ?
              AND branch_id = ?
              AND snapshot_id <= ?
            ORDER BY snapshot_id
            """;

    private final LakebaseBranchConnectionProvider connectionProvider = mock(LakebaseBranchConnectionProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LakebaseBranchConnectionProvider> connectionProviderObjectProvider =
            mock(ObjectProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantEntity tenant = new TenantEntity();
    private IcebergPlanningService service;

    @BeforeEach
    void setUp() {
        when(connectionProviderObjectProvider.getIfAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(connectionProvider);
        service = new IcebergPlanningService(connectionProviderObjectProvider, objectMapper);
    }

    @Test
    void exactPartitionEqualityPrunesUnrelatedFiles() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                  AND partition_json @> ?::jsonb
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("file_path")).thenReturn("obs://lakeon/orders/region=us/0001.parquet");
        when(resultSet.getLong("record_count")).thenReturn(10L);
        when(resultSet.getLong("file_size_bytes")).thenReturn(2048L);
        when(resultSet.getString("partition_json")).thenReturn("{\"region\":\"us\"}");
        when(resultSet.getString("lower_bounds_json")).thenReturn("{\"id\":1}");
        when(resultSet.getString("upper_bounds_json")).thenReturn("{\"id\":10}");

        IcebergPlanningService.PlanTableScanResponse response = service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(
                        null,
                        objectMapper.readTree("{\"op\":\"=\",\"field\":\"region\",\"value\":\"us\"}"),
                        true,
                        7L,
                        null,
                        List.of("region")
                )
        );

        assertThat(response.fileScanTasks()).extracting(task -> task.dataFile().filePath())
                .containsExactly("obs://lakeon/orders/region=us/0001.parquet");
        assertThat(response.planId()).isEqualTo("sync-20260624-0001");
        assertThat(response.status()).isEqualTo("completed");
        verify(statement).setString(1, "tbl_orders");
        verify(statement).setString(2, "br_main");
        verify(statement).setLong(3, 7L);
        verify(statement).setString(4, "{\"region\":\"us\"}");
        verify(statement).setInt(5, 10_000);
        assertThat(response.fileScanTasks()).singleElement()
                .extracting(task -> task.dataFile().partition())
                .isEqualTo(List.of(objectMapper.getNodeFactory().textNode("us")));
    }

    @Test
    void snapshotIdLimitsFilesToThatSnapshotAndAncestors() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(null, null, true, 42L, null, null)
        );

        verify(statement).setString(1, "tbl_orders");
        verify(statement).setString(2, "br_main");
        verify(statement).setLong(3, 42L);
        verify(statement).setInt(4, 10_000);
    }

    @Test
    void appendOnlySnapshotsAllowPlanning() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement snapshotStatement = mock(PreparedStatement.class);
        ResultSet snapshotResultSet = mock(ResultSet.class);
        PreparedStatement dataFileStatement = mock(PreparedStatement.class);
        ResultSet dataFileResultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement(APPEND_ONLY_SNAPSHOT_SQL)).thenReturn(snapshotStatement);
        when(snapshotStatement.executeQuery()).thenReturn(snapshotResultSet);
        when(snapshotResultSet.next()).thenReturn(true, true, true, false);
        when(snapshotResultSet.getString("operation")).thenReturn("append", "insert", "backfill");
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(dataFileStatement);
        when(dataFileStatement.executeQuery()).thenReturn(dataFileResultSet);
        when(dataFileResultSet.next()).thenReturn(false);

        service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(null, null, true, 42L, null, null)
        );

        verify(snapshotStatement).setString(1, "tbl_orders");
        verify(snapshotStatement).setString(2, "br_main");
        verify(snapshotStatement).setLong(3, 42L);
        verify(dataFileStatement).executeQuery();
    }

    @Test
    void nonAppendSnapshotRejectsPlanningBeforeDataFilesQuery() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement snapshotStatement = mock(PreparedStatement.class);
        ResultSet snapshotResultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement(APPEND_ONLY_SNAPSHOT_SQL)).thenReturn(snapshotStatement);
        when(snapshotStatement.executeQuery()).thenReturn(snapshotResultSet);
        when(snapshotResultSet.next()).thenReturn(true);
        when(snapshotResultSet.getString("operation")).thenReturn("overwrite");

        assertThatThrownBy(() -> service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(null, null, true, 42L, null, null)
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("server-side planning currently supports append-only snapshots");

        verify(connection, never()).prepareStatement(argThat(sql -> sql.contains("_lakeon_iceberg.data_files")));
    }

    @Test
    void selectedColumnsArePreservedInResponse() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("file_path")).thenReturn("obs://lakeon/orders/region=us/0001.parquet");
        when(resultSet.getLong("record_count")).thenReturn(10L);
        when(resultSet.getLong("file_size_bytes")).thenReturn(2048L);
        when(resultSet.getString("partition_json")).thenReturn("{\"region\":\"us\"}");
        when(resultSet.getString("lower_bounds_json")).thenReturn(null);
        when(resultSet.getString("upper_bounds_json")).thenReturn(null);

        IcebergPlanningService.PlanTableScanResponse response = service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(
                        List.of("id", "region", "amount"),
                        null,
                        true,
                        42L,
                        List.of("id"),
                        null
                )
        );

        assertThat(response.fileScanTasks()).singleElement()
                .extracting(IcebergPlanningService.FileScanTask::selectedColumns)
                .isEqualTo(List.of("id", "region", "amount"));
    }

    @Test
    void requestModelAcceptsRestCatalogJsonFields() throws Exception {
        IcebergPlanningService.PlanTableScanRequest request = objectMapper.readValue("""
                {
                  "select": ["id", "region"],
                  "filter": {"op": "=", "field": "region", "value": "us"},
                  "case-sensitive": true,
                  "snapshot-id": 123,
                  "stats-fields": ["id"],
                  "partition-fields": ["region"]
                }
                """, IcebergPlanningService.PlanTableScanRequest.class);

        assertThat(request.select()).containsExactly("id", "region");
        assertThat(request.filter().get("field").asText()).isEqualTo("region");
        assertThat(request.caseSensitive()).isTrue();
        assertThat(request.snapshotId()).isEqualTo(123L);
        assertThat(request.statsFields()).containsExactly("id");
        assertThat(request.partitionFields()).containsExactly("region");
    }

    @Test
    void responseSerializationDoesNotAddNonStandardTopLevelFields() throws Exception {
        IcebergPlanningService.PlanTableScanResponse response = new IcebergPlanningService.PlanTableScanResponse(
                "sync-20260624-0001",
                "COMPLETED",
                List.of(new IcebergPlanningService.FileScanTask(
                        new IcebergPlanningService.RestDataFile(
                                0,
                                List.of(),
                                "data",
                                "obs://lakeon/orders/region=us/0001.parquet",
                                "PARQUET",
                                2048L,
                                10L
                        ),
                        List.of("id", "region")
                ))
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.has("plan-id")).isTrue();
        assertThat(json.path("status").asText()).isEqualTo("completed");
        assertThat(json.has("plan-status")).isFalse();
        assertThat(json.has("file-scan-tasks")).isTrue();
        assertThat(json.has("select")).isFalse();
        assertThat(json.has("stats-fields")).isFalse();
        JsonNode dataFile = json.path("file-scan-tasks").get(0).path("data-file");
        assertThat(json.path("file-scan-tasks").get(0).has("selected-columns")).isFalse();
        assertThat(dataFile.path("spec-id").asInt()).isEqualTo(0);
        assertThat(dataFile.path("partition").isArray()).isTrue();
        assertThat(dataFile.path("partition")).isEmpty();
        assertThat(dataFile.path("content").asText()).isEqualTo("data");
        assertThat(dataFile.path("file-path").asText()).isEqualTo("obs://lakeon/orders/region=us/0001.parquet");
        assertThat(dataFile.path("file-format").asText()).isEqualTo("PARQUET");
        assertThat(dataFile.path("file-size-in-bytes").asLong()).isEqualTo(2048L);
        assertThat(dataFile.path("record-count").asLong()).isEqualTo(10L);
        assertThat(dataFile.has("lower-bounds")).isFalse();
        assertThat(dataFile.has("upper-bounds")).isFalse();
    }

    @Test
    void unsupportedFilterReturnsFullTablePlanWithoutPartitionPruning() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("file_path"))
                .thenReturn("obs://lakeon/orders/region=eu/0001.parquet")
                .thenReturn("obs://lakeon/orders/region=us/0001.parquet");
        when(resultSet.getLong("record_count")).thenReturn(10L, 11L);
        when(resultSet.getLong("file_size_bytes")).thenReturn(2048L, 4096L);
        when(resultSet.getString("partition_json"))
                .thenReturn("{\"region\":\"eu\"}")
                .thenReturn("{\"region\":\"us\"}");
        when(resultSet.getString("lower_bounds_json")).thenReturn(null);
        when(resultSet.getString("upper_bounds_json")).thenReturn(null);

        IcebergPlanningService.PlanTableScanResponse response = service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(
                        null,
                        objectMapper.readTree("{\"op\":\">\",\"field\":\"region\",\"value\":\"m\"}"),
                        true,
                        42L,
                        null,
                        List.of("region")
                )
        );

        assertThat(response.fileScanTasks()).extracting(task -> task.dataFile().filePath())
                .containsExactly(
                        "obs://lakeon/orders/region=eu/0001.parquet",
                        "obs://lakeon/orders/region=us/0001.parquet"
                );
        verify(statement).setString(1, "tbl_orders");
        verify(statement).setString(2, "br_main");
        verify(statement).setLong(3, 42L);
        verify(statement).setInt(4, 10_000);
    }

    @Test
    void equalityOnNonPartitionFieldReturnsFullTablePlan() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(
                        null,
                        objectMapper.readTree("{\"op\":\"=\",\"field\":\"id\",\"value\":1}"),
                        true,
                        42L,
                        null,
                        List.of("region")
                )
        );

        verify(statement).setString(1, "tbl_orders");
        verify(statement).setString(2, "br_main");
        verify(statement).setLong(3, 42L);
        verify(statement).setInt(4, 10_000);
    }

    @Test
    void caseInsensitiveEqualityReturnsFullTablePlan() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        allowAppendOnlySnapshots(connection);
        when(connection.prepareStatement("""
                SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                  AND snapshot_id <= ?
                ORDER BY file_path
                LIMIT ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        service.planTableScan(
                tenant,
                "db_123",
                "br_main",
                "tbl_orders",
                new IcebergPlanningService.PlanTableScanRequest(
                        null,
                        objectMapper.readTree("{\"op\":\"=\",\"field\":\"region\",\"value\":\"us\"}"),
                        false,
                        42L,
                        null,
                        List.of("region")
                )
        );

        verify(statement).setString(1, "tbl_orders");
        verify(statement).setString(2, "br_main");
        verify(statement).setLong(3, 42L);
        verify(statement).setInt(4, 10_000);
    }

    private void allowAppendOnlySnapshots(Connection connection) throws Exception {
        PreparedStatement snapshotStatement = mock(PreparedStatement.class);
        ResultSet snapshotResultSet = mock(ResultSet.class);
        when(connection.prepareStatement(APPEND_ONLY_SNAPSHOT_SQL)).thenReturn(snapshotStatement);
        when(snapshotStatement.executeQuery()).thenReturn(snapshotResultSet);
        when(snapshotResultSet.next()).thenReturn(false);
    }
}
