package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class IcebergExportMaterializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void materializeReadsCatalogRowsWritesExportMetadataAndPreservesCurrentSnapshot() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement snapshotStatement = mock(PreparedStatement.class);
        PreparedStatement dataFileStatement = mock(PreparedStatement.class);
        PreparedStatement deleteFileStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class);
        ResultSet snapshotRows = mock(ResultSet.class);
        ResultSet dataFileRows = mock(ResultSet.class);
        ResultSet deleteFileRows = mock(ResultSet.class);

        when(connection.getAutoCommit()).thenReturn(true);
        String tableLocation = tempDir.resolve("orders").toString();
        when(connection.prepareStatement("""
                SELECT table_id, database_id, branch_id, namespace, table_name, table_location,
                       current_metadata_location, current_metadata_json, current_metadata_hash,
                       current_snapshot_id, metadata_version, export_status
                FROM _lakeon_iceberg.tables
                WHERE database_id = ?
                  AND branch_id = ?
                  AND namespace = ?
                  AND table_name = ?
                FOR UPDATE
                """)).thenReturn(tableStatement);
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true);
        when(tableRows.getString("table_id")).thenReturn("tbl_orders");
        when(tableRows.getString("database_id")).thenReturn("db_123");
        when(tableRows.getString("branch_id")).thenReturn("main");
        when(tableRows.getString("namespace")).thenReturn("public");
        when(tableRows.getString("table_name")).thenReturn("orders_cdf");
        when(tableRows.getString("table_location")).thenReturn(tableLocation);
        when(tableRows.getString("current_metadata_location")).thenReturn(tableLocation + "/metadata/00000.json");
        when(tableRows.getString("current_metadata_json")).thenReturn("""
                {
                  "format-version":2,
                  "table-uuid":"tbl_orders",
                  "location":"%s",
                  "last-sequence-number":0,
                  "last-updated-ms":1,
                  "last-column-id":1,
                  "schemas":[{"schema-id":0,"type":"struct","fields":[{"id":1,"name":"id","required":true,"type":"long"}]}],
                  "current-schema-id":0,
                  "partition-specs":[{"spec-id":0,"fields":[]}],
                  "default-spec-id":0,
                  "last-partition-id":0,
                  "sort-orders":[{"order-id":0,"fields":[]}],
                  "default-sort-order-id":0,
                  "snapshots":[],
                  "snapshot-log":[],
                  "metadata-log":[],
                  "refs":{"main":{"type":"branch"}},
                  "properties":{"lakeon.managed":"true"},
                  "lakeon-export":{"status":"STALE"}
                }
                """.formatted(tableLocation));
        when(tableRows.getString("current_metadata_hash")).thenReturn("hash_1");
        when(tableRows.getLong("current_snapshot_id")).thenReturn(88L);
        when(tableRows.wasNull()).thenReturn(false);
        when(tableRows.getLong("metadata_version")).thenReturn(3L);
        when(tableRows.getString("export_status")).thenReturn("NOT_MATERIALIZED");

        when(connection.prepareStatement("""
                SELECT snapshot_id, parent_snapshot_id, sequence_number, operation,
                       EXTRACT(EPOCH FROM committed_at) * 1000 AS committed_at_ms, summary_json
                FROM _lakeon_iceberg.snapshots
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY sequence_number, snapshot_id
                """)).thenReturn(snapshotStatement);
        when(snapshotStatement.executeQuery()).thenReturn(snapshotRows);
        when(snapshotRows.next()).thenReturn(true, true, false);
        when(snapshotRows.getLong("snapshot_id")).thenReturn(77L, 88L);
        when(snapshotRows.getLong("parent_snapshot_id")).thenReturn(0L, 77L);
        when(snapshotRows.wasNull()).thenReturn(true, false);
        when(snapshotRows.getLong("sequence_number")).thenReturn(12L, 13L);
        when(snapshotRows.getString("operation")).thenReturn("append");
        when(snapshotRows.getLong("committed_at_ms")).thenReturn(1_782_000_000_000L);
        when(snapshotRows.getString("summary_json")).thenReturn("{\"record-count\":2}", "{\"added-records\":\"3\"}");

        when(connection.prepareStatement("""
                SELECT snapshot_id, file_path, content_type, partition_json, record_count, file_size_bytes,
                       lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY snapshot_id, file_path
                """)).thenReturn(dataFileStatement);
        when(dataFileStatement.executeQuery()).thenReturn(dataFileRows);
        when(dataFileRows.next()).thenReturn(true, true, false);
        when(dataFileRows.getLong("snapshot_id")).thenReturn(77L, 88L);
        when(dataFileRows.getString("file_path"))
                .thenReturn(tableLocation + "/data/part-0001.parquet", tableLocation + "/data/part-0002.parquet");
        when(dataFileRows.getString("content_type")).thenReturn("DATA");
        when(dataFileRows.getString("partition_json")).thenReturn("{\"bucket\":0}");
        when(dataFileRows.getLong("record_count")).thenReturn(2L, 3L);
        when(dataFileRows.getLong("file_size_bytes")).thenReturn(4096L, 8192L);
        when(dataFileRows.getString("lower_bounds_json")).thenReturn("{\"id\":1}", "{\"id\":3}");
        when(dataFileRows.getString("upper_bounds_json")).thenReturn("{\"id\":2}", "{\"id\":5}");
        when(dataFileRows.getString("null_counts_json")).thenReturn("{\"id\":0}");
        when(dataFileRows.getString("value_counts_json")).thenReturn("{\"id\":2}", "{\"id\":3}");

        when(connection.prepareStatement("""
                SELECT snapshot_id, file_path, delete_type, partition_json, record_count, file_size_bytes,
                       lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
                FROM _lakeon_iceberg.delete_files
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY snapshot_id, file_path
                """)).thenReturn(deleteFileStatement);
        when(deleteFileStatement.executeQuery()).thenReturn(deleteFileRows);
        when(deleteFileRows.next()).thenReturn(false);

        when(connection.prepareStatement("""
                UPDATE _lakeon_iceberg.tables
                SET export_status = ?,
                    current_metadata_location = ?,
                    current_metadata_json = ?::jsonb,
                    metadata_version = metadata_version + 1,
                    updated_at = now()
                WHERE table_id = ?
                  AND metadata_version = ?
                """)).thenReturn(updateStatement);
        when(updateStatement.executeUpdate()).thenReturn(1);

        IcebergExportMaterializer.MaterializedExport export =
                new IcebergExportMaterializer(objectMapper).materialize(connection, "db_123", "main", "public", "orders_cdf");

        assertThat(export.status()).isEqualTo("MATERIALIZED");
        assertThat(export.metadata_location()).startsWith(tableLocation + "/export/metadata/");
        assertThat(export.metadata_location()).endsWith(".metadata.json");
        assertThat(Files.exists(Path.of(export.metadata_location()))).isTrue();
        assertThat(Files.exists(Path.of(export.manifest_list_location()))).isTrue();
        assertThat(export.manifest_list_location()).endsWith(".manifest-list.avro");

        JsonNode metadata = objectMapper.readTree(Files.readString(Path.of(export.metadata_location())));
        assertThat(metadata.path("current-snapshot-id").asLong()).isEqualTo(88L);
        assertThat(metadata.path("snapshots").get(1).path("manifest-list").asText())
                .isEqualTo(export.manifest_list_location());
        assertThat(metadata.has("lakeon-export")).isFalse();
        assertThat(metadata.path("snapshots").get(0).path("operation").isMissingNode()).isTrue();
        assertThat(metadata.path("snapshots").get(0).path("summary").path("operation").asText()).isEqualTo("append");
        assertThat(metadata.path("snapshots").get(0).path("summary").path("record-count").isTextual()).isTrue();
        assertThat(metadata.path("snapshots").get(0).path("summary").path("record-count").asText()).isEqualTo("2");

        ManifestFile manifest = readSingleManifest(export.manifest_list_location());
        assertThat(manifest.path()).endsWith(".manifest.avro");
        assertThat(manifest.addedFilesCount()).isEqualTo(1);
        assertThat(manifest.addedRowsCount()).isEqualTo(3L);
        assertThat(manifest.existingFilesCount()).isEqualTo(1);
        assertThat(manifest.existingRowsCount()).isEqualTo(2L);
        try (CloseableIterable<org.apache.iceberg.DataFile> rows =
                     ManifestFiles.read(manifest, new HadoopFileIO(new Configuration()), Map.of(0, PartitionSpec.unpartitioned()))
                             .select(List.of("file_path", "lower_bounds", "upper_bounds", "value_counts", "null_value_counts"))) {
            assertThat(rows).hasSize(2);
            assertThat(rows).anySatisfy(file -> {
                assertThat(file.path().toString()).endsWith("/data/part-0002.parquet");
                assertThat(file.valueCounts()).containsEntry(1, 3L);
                assertThat(file.nullValueCounts()).containsEntry(1, 0L);
                assertThat((Long) Conversions.fromByteBuffer(Types.LongType.get(), file.lowerBounds().get(1))).isEqualTo(3L);
                assertThat((Long) Conversions.fromByteBuffer(Types.LongType.get(), file.upperBounds().get(1))).isEqualTo(5L);
            });
        }

        verify(tableStatement).setString(1, "db_123");
        verify(tableStatement).setString(2, "main");
        verify(tableStatement).setString(3, "public");
        verify(tableStatement).setString(4, "orders_cdf");
        verify(updateStatement).setString(1, "MATERIALIZED");
        verify(updateStatement).setString(2, export.metadata_location());
        verify(updateStatement).setString(4, "tbl_orders");
        verify(updateStatement).setLong(5, 3L);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, atLeastOnce()).setAutoCommit(true);
    }

    @Test
    void materializeRejectsConcurrentMetadataChange() throws Exception {
        Connection connection = successfulConnection();
        PreparedStatement update = mock(PreparedStatement.class);
        when(connection.prepareStatement("""
                UPDATE _lakeon_iceberg.tables
                SET export_status = ?,
                    current_metadata_location = ?,
                    current_metadata_json = ?::jsonb,
                    metadata_version = metadata_version + 1,
                    updated_at = now()
                WHERE table_id = ?
                  AND metadata_version = ?
                """)).thenReturn(update);
        when(update.executeUpdate()).thenReturn(0);

        assertThatThrownBy(() -> new IcebergExportMaterializer(objectMapper)
                .materialize(connection, "db_123", "main", "public", "orders_cdf"))
                .hasMessageContaining("metadata changed concurrently");
        assertThat(Files.exists(tempDir.resolve("orders-concurrent").resolve("export").resolve("metadata")
                .resolve("00004-snap-77-data.manifest.avro"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("orders-concurrent").resolve("export").resolve("metadata")
                .resolve("00004-snap-77.manifest-list.avro"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("orders-concurrent").resolve("export").resolve("metadata")
                .resolve("00004.metadata.json"))).isFalse();
        verify(connection).rollback();
    }

    private ManifestFile readSingleManifest(String manifestListLocation) throws Exception {
        Method read = Class.forName("org.apache.iceberg.ManifestLists")
                .getDeclaredMethod("read", org.apache.iceberg.io.InputFile.class);
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ManifestFile> manifests = (List<ManifestFile>) read.invoke(
                null,
                org.apache.iceberg.Files.localInput(manifestListLocation));
        assertThat(manifests).hasSize(1);
        return manifests.get(0);
    }

    private Connection successfulConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement tableStatement = mock(PreparedStatement.class);
        PreparedStatement snapshotStatement = mock(PreparedStatement.class);
        PreparedStatement dataFileStatement = mock(PreparedStatement.class);
        PreparedStatement deleteFileStatement = mock(PreparedStatement.class);
        ResultSet tableRows = mock(ResultSet.class);
        ResultSet snapshotRows = mock(ResultSet.class);
        ResultSet dataFileRows = mock(ResultSet.class);
        ResultSet deleteFileRows = mock(ResultSet.class);

        when(connection.getAutoCommit()).thenReturn(true);
        String tableLocation = tempDir.resolve("orders-concurrent").toString();
        when(connection.prepareStatement("""
                SELECT table_id, database_id, branch_id, namespace, table_name, table_location,
                       current_metadata_location, current_metadata_json, current_metadata_hash,
                       current_snapshot_id, metadata_version, export_status
                FROM _lakeon_iceberg.tables
                WHERE database_id = ?
                  AND branch_id = ?
                  AND namespace = ?
                  AND table_name = ?
                FOR UPDATE
                """)).thenReturn(tableStatement);
        when(tableStatement.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true);
        when(tableRows.getString("table_id")).thenReturn("tbl_orders");
        when(tableRows.getString("database_id")).thenReturn("db_123");
        when(tableRows.getString("branch_id")).thenReturn("main");
        when(tableRows.getString("namespace")).thenReturn("public");
        when(tableRows.getString("table_name")).thenReturn("orders_cdf");
        when(tableRows.getString("table_location")).thenReturn(tableLocation);
        when(tableRows.getString("current_metadata_location")).thenReturn(tableLocation + "/metadata/00000.json");
        when(tableRows.getString("current_metadata_json")).thenReturn("""
                {
                  "format-version":2,
                  "table-uuid":"tbl_orders",
                  "location":"%s",
                  "last-sequence-number":0,
                  "last-updated-ms":1,
                  "last-column-id":1,
                  "schemas":[{"schema-id":0,"type":"struct","fields":[{"id":1,"name":"id","required":true,"type":"long"}]}],
                  "current-schema-id":0,
                  "partition-specs":[{"spec-id":0,"fields":[]}],
                  "default-spec-id":0,
                  "last-partition-id":0,
                  "sort-orders":[{"order-id":0,"fields":[]}],
                  "default-sort-order-id":0,
                  "snapshots":[],
                  "snapshot-log":[],
                  "metadata-log":[],
                  "refs":{"main":{"type":"branch"}},
                  "properties":{"lakeon.managed":"true"}
                }
                """.formatted(tableLocation));
        when(tableRows.getString("current_metadata_hash")).thenReturn("hash_1");
        when(tableRows.getLong("current_snapshot_id")).thenReturn(77L);
        when(tableRows.wasNull()).thenReturn(false);
        when(tableRows.getLong("metadata_version")).thenReturn(3L);
        when(tableRows.getString("export_status")).thenReturn("NOT_MATERIALIZED");

        when(connection.prepareStatement("""
                SELECT snapshot_id, parent_snapshot_id, sequence_number, operation,
                       EXTRACT(EPOCH FROM committed_at) * 1000 AS committed_at_ms, summary_json
                FROM _lakeon_iceberg.snapshots
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY sequence_number, snapshot_id
                """)).thenReturn(snapshotStatement);
        when(snapshotStatement.executeQuery()).thenReturn(snapshotRows);
        when(snapshotRows.next()).thenReturn(true, false);
        when(snapshotRows.getLong("snapshot_id")).thenReturn(77L);
        when(snapshotRows.getLong("parent_snapshot_id")).thenReturn(0L);
        when(snapshotRows.wasNull()).thenReturn(true);
        when(snapshotRows.getLong("sequence_number")).thenReturn(12L);
        when(snapshotRows.getString("operation")).thenReturn("append");
        when(snapshotRows.getLong("committed_at_ms")).thenReturn(1_782_000_000_000L);
        when(snapshotRows.getString("summary_json")).thenReturn("{}");

        when(connection.prepareStatement("""
                SELECT snapshot_id, file_path, content_type, partition_json, record_count, file_size_bytes,
                       lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
                FROM _lakeon_iceberg.data_files
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY snapshot_id, file_path
                """)).thenReturn(dataFileStatement);
        when(dataFileStatement.executeQuery()).thenReturn(dataFileRows);
        when(dataFileRows.next()).thenReturn(false);

        when(connection.prepareStatement("""
                SELECT snapshot_id, file_path, delete_type, partition_json, record_count, file_size_bytes,
                       lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
                FROM _lakeon_iceberg.delete_files
                WHERE table_id = ?
                  AND branch_id = ?
                ORDER BY snapshot_id, file_path
                """)).thenReturn(deleteFileStatement);
        when(deleteFileStatement.executeQuery()).thenReturn(deleteFileRows);
        when(deleteFileRows.next()).thenReturn(false);
        return connection;
    }
}
