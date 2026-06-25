package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.service.exception.BadRequestException;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionData;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class IcebergExportMaterializer {

    private static final String MATERIALIZED = "MATERIALIZED";
    private static final String TABLE_SQL = """
            SELECT table_id, database_id, branch_id, namespace, table_name, table_location,
                   current_metadata_location, current_metadata_json, current_metadata_hash,
                   current_snapshot_id, metadata_version, export_status
            FROM _lakeon_iceberg.tables
            WHERE database_id = ?
              AND branch_id = ?
              AND namespace = ?
              AND table_name = ?
            FOR UPDATE
            """;
    private static final String SNAPSHOT_SQL = """
            SELECT snapshot_id, parent_snapshot_id, sequence_number, operation,
                   EXTRACT(EPOCH FROM committed_at) * 1000 AS committed_at_ms, summary_json
            FROM _lakeon_iceberg.snapshots
            WHERE table_id = ?
              AND branch_id = ?
            ORDER BY sequence_number, snapshot_id
            """;
    private static final String DATA_FILE_SQL = """
            SELECT snapshot_id, file_path, content_type, partition_json, record_count, file_size_bytes,
                   lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
            FROM _lakeon_iceberg.data_files
            WHERE table_id = ?
              AND branch_id = ?
            ORDER BY snapshot_id, file_path
            """;
    private static final String DELETE_FILE_SQL = """
            SELECT snapshot_id, file_path, delete_type, partition_json, record_count, file_size_bytes,
                   lower_bounds_json, upper_bounds_json, null_counts_json, value_counts_json
            FROM _lakeon_iceberg.delete_files
            WHERE table_id = ?
              AND branch_id = ?
            ORDER BY snapshot_id, file_path
            """;
    private static final String UPDATE_TABLE_SQL = """
            UPDATE _lakeon_iceberg.tables
            SET export_status = ?,
                current_metadata_location = ?,
                current_metadata_json = ?::jsonb,
                metadata_version = metadata_version + 1,
                updated_at = now()
            WHERE table_id = ?
              AND metadata_version = ?
            """;

    private final ObjectMapper objectMapper;

    public IcebergExportMaterializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public MaterializedExport materialize(Connection connection,
                                          String databaseId,
                                          String branchId,
                                          String namespace,
                                          String tableName) {
        Objects.requireNonNull(connection, "connection");
        List<Path> writtenFiles = new ArrayList<>();
        boolean manageTransaction = false;
        try {
            manageTransaction = connection.getAutoCommit();
            if (manageTransaction) {
                connection.setAutoCommit(false);
            }
            MaterializedExport export = materializeInTransaction(
                    connection,
                    databaseId,
                    branchId,
                    namespace,
                    tableName,
                    writtenFiles);
            if (manageTransaction) {
                connection.commit();
            }
            return export;
        } catch (SQLException | IOException | ReflectiveOperationException e) {
            rollbackIfManaged(connection, manageTransaction);
            cleanupFiles(writtenFiles);
            throw new BadRequestException("failed to materialize Iceberg export: " + e.getMessage());
        } catch (RuntimeException e) {
            rollbackIfManaged(connection, manageTransaction);
            cleanupFiles(writtenFiles);
            throw e;
        } finally {
            restoreAutoCommit(connection, manageTransaction);
        }
    }

    private MaterializedExport materializeInTransaction(Connection connection,
                                                       String databaseId,
                                                       String branchId,
                                                       String namespace,
                                                       String tableName,
                                                       List<Path> writtenFiles)
            throws SQLException, IOException, ReflectiveOperationException {
        TableRow table = readTable(connection, databaseId, branchId, namespace, tableName);
        long exportVersion = table.metadataVersion() + 1;
        Path metadataDir = metadataDirectory(table.tableLocation());
        Files.createDirectories(metadataDir);

        String versionPrefix = String.format("%05d", exportVersion);
        Path metadataPath = metadataDir.resolve(versionPrefix + ".metadata.json");
        IcebergTableShape tableShape = tableShape(table.currentMetadataJson());
        List<SnapshotRow> snapshots = readSnapshots(connection, table.tableId(), table.branchId());
        Map<Long, List<FileRow>> dataFiles = readDataFiles(connection, table.tableId(), table.branchId(), snapshots);
        ensureNoDeleteFiles(connection, table.tableId(), table.branchId());

        Map<Long, String> manifestLists = writeSnapshotManifests(
                metadataDir,
                versionPrefix,
                tableShape.spec(),
                tableShape.schema(),
                snapshots,
                dataFiles,
                writtenFiles);
        ObjectNode metadata = materializedMetadata(table, metadataPath.toString(), snapshots, manifestLists);
        writeJson(metadataPath, metadata);
        writtenFiles.add(metadataPath);

        updateTable(connection, table.tableId(), table.metadataVersion(), metadataPath.toString(), metadata);
        String currentManifestList = table.currentSnapshotId() == null ? null : manifestLists.get(table.currentSnapshotId());
        return new MaterializedExport(MATERIALIZED, metadataPath.toString(), currentManifestList);
    }

    public ExportStatus currentExport(Connection connection,
                                      String databaseId,
                                      String branchId,
                                      String namespace,
                                      String tableName) {
        Objects.requireNonNull(connection, "connection");
        try {
            TableRow table = readTable(connection, databaseId, branchId, namespace, tableName);
            return new ExportStatus(table.exportStatus(), table.currentMetadataLocation());
        } catch (SQLException | IOException e) {
            throw new BadRequestException("failed to read Iceberg export status: " + e.getMessage());
        }
    }

    private TableRow readTable(Connection connection,
                               String databaseId,
                               String branchId,
                               String namespace,
                               String tableName) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_SQL)) {
            statement.setString(1, databaseId);
            statement.setString(2, branchId);
            statement.setString(3, namespace);
            statement.setString(4, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new BadRequestException("Iceberg table not found for export: " + namespace + "." + tableName);
                }
                long currentSnapshotId = rs.getLong("current_snapshot_id");
                boolean snapshotNull = rs.wasNull();
                return new TableRow(
                        rs.getString("table_id"),
                        rs.getString("database_id"),
                        rs.getString("branch_id"),
                        rs.getString("namespace"),
                        rs.getString("table_name"),
                        rs.getString("table_location"),
                        rs.getString("current_metadata_location"),
                        objectMapper.readTree(rs.getString("current_metadata_json")),
                        rs.getString("current_metadata_hash"),
                        snapshotNull ? null : currentSnapshotId,
                        rs.getLong("metadata_version"),
                        rs.getString("export_status"));
            }
        }
    }

    private List<SnapshotRow> readSnapshots(Connection connection,
                                            String tableId,
                                            String branchId) throws SQLException, IOException {
        List<SnapshotRow> snapshots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SNAPSHOT_SQL)) {
            statement.setString(1, tableId);
            statement.setString(2, branchId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long snapshotId = rs.getLong("snapshot_id");
                    long parentSnapshotId = rs.getLong("parent_snapshot_id");
                    Long parent = rs.wasNull() ? null : parentSnapshotId;
                    snapshots.add(new SnapshotRow(
                            snapshotId,
                            parent,
                            rs.getLong("sequence_number"),
                            rs.getString("operation"),
                            rs.getLong("committed_at_ms"),
                            readJsonObject(rs.getString("summary_json"))));
                }
            }
        }
        return snapshots;
    }

    private Map<Long, List<FileRow>> readDataFiles(Connection connection,
                                                   String tableId,
                                                   String branchId,
                                                   List<SnapshotRow> snapshots)
            throws SQLException, IOException {
        Map<Long, List<FileRow>> files = new LinkedHashMap<>();
        Map<Long, Long> sequenceNumbers = snapshotSequenceNumbers(snapshots);
        try (PreparedStatement statement = connection.prepareStatement(DATA_FILE_SQL)) {
            statement.setString(1, tableId);
            statement.setString(2, branchId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (!"DATA".equalsIgnoreCase(rs.getString("content_type"))) {
                        throw new BadRequestException("unsupported Iceberg data file content type: " + rs.getString("content_type"));
                    }
                    long snapshotId = rs.getLong("snapshot_id");
                    files.computeIfAbsent(snapshotId, ignored -> new ArrayList<>())
                            .add(commonFileRow(rs, snapshotId, sequenceNumbers.getOrDefault(snapshotId, 0L)));
                }
            }
        }
        return files;
    }

    private void ensureNoDeleteFiles(Connection connection, String tableId, String branchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_FILE_SQL)) {
            statement.setString(1, tableId);
            statement.setString(2, branchId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new BadRequestException("lazy Iceberg export does not yet support delete manifests");
                }
            }
        }
    }

    private Map<Long, Long> snapshotSequenceNumbers(List<SnapshotRow> snapshots) {
        Map<Long, Long> sequenceNumbers = new LinkedHashMap<>();
        for (SnapshotRow snapshot : snapshots) {
            sequenceNumbers.put(snapshot.snapshotId(), snapshot.sequenceNumber());
        }
        return sequenceNumbers;
    }

    private FileRow commonFileRow(ResultSet rs, long snapshotId, long sequenceNumber) throws SQLException, IOException {
        return new FileRow(
                snapshotId,
                sequenceNumber,
                rs.getString("file_path"),
                readJsonObject(rs.getString("partition_json")),
                rs.getLong("record_count"),
                rs.getLong("file_size_bytes"),
                readJsonObject(rs.getString("lower_bounds_json")),
                readJsonObject(rs.getString("upper_bounds_json")),
                readJsonObject(rs.getString("null_counts_json")),
                readJsonObject(rs.getString("value_counts_json")));
    }

    private ObjectNode materializedMetadata(TableRow table,
                                            String metadataLocation,
                                            List<SnapshotRow> snapshots,
                                            Map<Long, String> manifestLists) {
        ObjectNode metadata = canonicalMetadata(table.currentMetadataJson());
        metadata.put("format-version", metadata.path("format-version").asInt(2));
        metadata.put("table-uuid", table.tableId());
        metadata.put("location", table.tableLocation());
        metadata.put("last-sequence-number", lastSequenceNumber(snapshots));
        metadata.put("last-updated-ms", System.currentTimeMillis());
        if (table.currentSnapshotId() != null) {
            metadata.put("current-snapshot-id", table.currentSnapshotId());
        } else {
            metadata.remove("current-snapshot-id");
        }
        metadata.set("snapshots", snapshotMetadata(snapshots, manifestLists));
        metadata.set("snapshot-log", snapshotLog(snapshots));
        appendMetadataLog(metadata, metadataLocation, System.currentTimeMillis());

        ObjectNode refs = metadata.with("refs");
        ObjectNode main = refs.with("main");
        main.put("type", "branch");
        if (table.currentSnapshotId() != null) {
            main.put("snapshot-id", table.currentSnapshotId());
        }

        TableMetadataParser.fromJson(metadataLocation, metadata);
        return metadata;
    }

    private ObjectNode canonicalMetadata(JsonNode metadataJson) {
        ObjectNode canonical = objectMapper.createObjectNode();
        copyIfPresent(metadataJson, canonical, "format-version");
        copyIfPresent(metadataJson, canonical, "table-uuid");
        copyIfPresent(metadataJson, canonical, "location");
        copyIfPresent(metadataJson, canonical, "last-sequence-number");
        copyIfPresent(metadataJson, canonical, "last-updated-ms");
        copyIfPresent(metadataJson, canonical, "last-column-id");
        copyIfPresent(metadataJson, canonical, "schemas");
        copyIfPresent(metadataJson, canonical, "current-schema-id");
        copyIfPresent(metadataJson, canonical, "partition-specs");
        copyIfPresent(metadataJson, canonical, "default-spec-id");
        copyIfPresent(metadataJson, canonical, "last-partition-id");
        copyIfPresent(metadataJson, canonical, "sort-orders");
        copyIfPresent(metadataJson, canonical, "default-sort-order-id");
        copyIfPresent(metadataJson, canonical, "properties");
        copyIfPresent(metadataJson, canonical, "snapshots");
        copyIfPresent(metadataJson, canonical, "snapshot-log");
        copyIfPresent(metadataJson, canonical, "metadata-log");
        return canonical;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode()) {
            target.set(field, value.deepCopy());
        }
    }

    private void updateTable(Connection connection,
                             String tableId,
                             long expectedMetadataVersion,
                             String metadataLocation,
                             JsonNode metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_TABLE_SQL)) {
            statement.setString(1, MATERIALIZED);
            statement.setString(2, metadataLocation);
            statement.setString(3, metadata.toString());
            statement.setString(4, tableId);
            statement.setLong(5, expectedMetadataVersion);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new BadRequestException("Iceberg export metadata changed concurrently");
            }
        }
    }

    private IcebergTableShape tableShape(JsonNode metadataJson) {
        try {
            var metadata = TableMetadataParser.fromJson(metadataJson);
            return new IcebergTableShape(metadata.schema(), metadata.spec());
        } catch (RuntimeException e) {
            JsonNode schemaJson = metadataJson.path("schemas").path(0);
            if (schemaJson.isMissingNode()) {
                throw new BadRequestException("Iceberg metadata has no schema");
            }
            Schema schema = SchemaParser.fromJson(schemaJson);
            return new IcebergTableShape(schema, PartitionSpec.unpartitioned());
        }
    }

    private Map<Long, String> writeSnapshotManifests(Path metadataDir,
                                                     String versionPrefix,
                                                     PartitionSpec spec,
                                                     Schema schema,
                                                     List<SnapshotRow> snapshots,
                                                     Map<Long, List<FileRow>> dataFiles,
                                                     List<Path> writtenFiles)
            throws IOException, ReflectiveOperationException {
        Map<Long, String> manifestLists = new LinkedHashMap<>();
        List<FileRow> liveFiles = new ArrayList<>();
        for (SnapshotRow snapshot : snapshots) {
            liveFiles.addAll(dataFiles.getOrDefault(snapshot.snapshotId(), List.of()));
            Path manifestPath = metadataDir.resolve(
                    versionPrefix + "-snap-" + snapshot.snapshotId() + "-data.manifest.avro");
            ManifestFile manifest = writeDataManifest(manifestPath.toString(), snapshot, spec, schema, liveFiles);
            writtenFiles.add(manifestPath);
            Path manifestListPath = metadataDir.resolve(
                    versionPrefix + "-snap-" + snapshot.snapshotId() + ".manifest-list.avro");
            writeManifestList(manifestListPath.toString(), snapshot, List.of(manifest));
            writtenFiles.add(manifestListPath);
            manifestLists.put(snapshot.snapshotId(), manifestListPath.toString());
        }
        return manifestLists;
    }

    private ManifestFile writeDataManifest(String manifestPath,
                                           SnapshotRow snapshot,
                                           PartitionSpec spec,
                                           Schema schema,
                                           List<FileRow> files) throws IOException {
        OutputFile outputFile = org.apache.iceberg.Files.localOutput(manifestPath);
        ManifestWriter<DataFile> writer = ManifestFiles.write(2, spec, outputFile, snapshot.snapshotId());
        try {
            for (FileRow file : files) {
                DataFile dataFile = toDataFile(spec, schema, file);
                if (file.snapshotId() == snapshot.snapshotId()) {
                    writer.add(dataFile);
                } else {
                    writer.existing(dataFile, file.snapshotId(), file.sequenceNumber(), null);
                }
            }
        } finally {
            writer.close();
        }
        return writer.toManifestFile();
    }

    @SuppressWarnings("unchecked")
    private void writeManifestList(String manifestListPath,
                                   SnapshotRow snapshot,
                                   List<ManifestFile> manifests) throws IOException, ReflectiveOperationException {
        Method write = Class.forName("org.apache.iceberg.ManifestLists")
                .getDeclaredMethod("write", int.class, OutputFile.class, long.class, Long.class, long.class, Long.class);
        write.setAccessible(true);
        OutputFile outputFile = org.apache.iceberg.Files.localOutput(manifestListPath);
        try (FileAppender<ManifestFile> appender = (FileAppender<ManifestFile>) write.invoke(
                null,
                2,
                outputFile,
                snapshot.snapshotId(),
                snapshot.parentSnapshotId(),
                snapshot.sequenceNumber(),
                null)) {
            for (ManifestFile manifest : manifests) {
                appender.add(manifest);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private DataFile toDataFile(PartitionSpec spec, Schema schema, FileRow file) {
        DataFiles.Builder builder = DataFiles.builder(spec)
                .withPath(file.filePath())
                .withFormat(FileFormat.PARQUET)
                .withRecordCount(file.recordCount())
                .withFileSizeInBytes(file.fileSizeBytes())
                .withMetrics(new Metrics(
                        file.recordCount(),
                        null,
                        metricLongMap(schema, file.valueCounts()),
                        metricLongMap(schema, file.nullCounts()),
                        null,
                        metricBoundsMap(schema, file.lowerBounds()),
                        metricBoundsMap(schema, file.upperBounds())));
        PartitionData partition = partitionData(spec, schema, file.partition());
        if (partition != null) {
            builder.withPartition(partition);
        }
        return builder.build();
    }

    private Map<Integer, Long> metricLongMap(Schema schema, JsonNode metrics) {
        if (metrics == null || !metrics.isObject() || metrics.isEmpty()) {
            return null;
        }
        Map<Integer, Long> values = new LinkedHashMap<>();
        metrics.fields().forEachRemaining(entry -> {
            var field = schema.findField(entry.getKey());
            if (field != null && entry.getValue().canConvertToLong()) {
                values.put(field.fieldId(), entry.getValue().asLong());
            }
        });
        return values.isEmpty() ? null : values;
    }

    private Map<Integer, java.nio.ByteBuffer> metricBoundsMap(Schema schema, JsonNode bounds) {
        if (bounds == null || !bounds.isObject() || bounds.isEmpty()) {
            return null;
        }
        Map<Integer, java.nio.ByteBuffer> values = new LinkedHashMap<>();
        bounds.fields().forEachRemaining(entry -> {
            var field = schema.findField(entry.getKey());
            if (field == null || !field.type().isPrimitiveType()) {
                return;
            }
            Object typedValue = typedPrimitiveValue(field.type(), entry.getValue());
            if (typedValue != null) {
                values.put(field.fieldId(), Conversions.toByteBuffer(field.type(), typedValue));
            }
        });
        return values.isEmpty() ? null : values;
    }

    private PartitionData partitionData(PartitionSpec spec, Schema schema, JsonNode partition) {
        if (spec.isUnpartitioned()) {
            return null;
        }
        PartitionData data = new PartitionData(spec.partitionType());
        for (int index = 0; index < spec.fields().size(); index++) {
            var partitionField = spec.fields().get(index);
            JsonNode value = partition.path(partitionField.name());
            if (value.isMissingNode() || value.isNull()) {
                String sourceName = schema.findColumnName(partitionField.sourceId());
                if (sourceName != null) {
                    value = partition.path(sourceName);
                }
            }
            if (!value.isMissingNode() && !value.isNull()) {
                Type resultType = partitionField.transform().getResultType(schema.findType(partitionField.sourceId()));
                data.set(index, typedPrimitiveValue(resultType, value));
            }
        }
        return data;
    }

    private Object typedPrimitiveValue(Type type, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || !type.isPrimitiveType()) {
            return null;
        }
        if (value.isTextual()) {
            if (type.typeId() == Type.TypeID.UUID) {
                return UUID.fromString(value.asText());
            }
            if (type.typeId() != Type.TypeID.STRING) {
                return Conversions.fromPartitionString(type, value.asText());
            }
        }
        return switch (type.typeId()) {
            case BOOLEAN -> value.asBoolean();
            case INTEGER, DATE -> requireIntegral(value, type).intValue();
            case LONG, TIME, TIMESTAMP, TIMESTAMP_NANO -> requireIntegral(value, type);
            case FLOAT -> (float) value.asDouble();
            case DOUBLE -> value.asDouble();
            case STRING -> value.asText();
            case UUID -> UUID.fromString(value.asText());
            default -> Conversions.fromPartitionString(type, value.asText());
        };
    }

    private Long requireIntegral(JsonNode value, Type type) {
        if (!value.canConvertToLong()) {
            throw new BadRequestException("invalid Iceberg " + type + " bound value: " + value);
        }
        return value.asLong();
    }

    private ArrayNode snapshotMetadata(List<SnapshotRow> snapshots, Map<Long, String> manifestLists) {
        ArrayNode nodes = objectMapper.createArrayNode();
        for (SnapshotRow row : snapshots) {
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("sequence-number", row.sequenceNumber());
            snapshot.put("snapshot-id", row.snapshotId());
            if (row.parentSnapshotId() != null) {
                snapshot.put("parent-snapshot-id", row.parentSnapshotId());
            }
            snapshot.put("timestamp-ms", row.timestampMs());
            ObjectNode summary = row.summary().deepCopy();
            summary.put("operation", row.operation());
            snapshot.set("summary", summary);
            snapshot.put("manifest-list", manifestLists.get(row.snapshotId()));
            nodes.add(snapshot);
        }
        return nodes;
    }

    private ArrayNode snapshotLog(List<SnapshotRow> snapshots) {
        ArrayNode nodes = objectMapper.createArrayNode();
        for (SnapshotRow row : snapshots) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("timestamp-ms", row.timestampMs());
            entry.put("snapshot-id", row.snapshotId());
            nodes.add(entry);
        }
        return nodes;
    }

    private long lastSequenceNumber(List<SnapshotRow> snapshots) {
        long max = 0L;
        for (SnapshotRow snapshot : snapshots) {
            max = Math.max(max, snapshot.sequenceNumber());
        }
        return max;
    }

    private void appendMetadataLog(ObjectNode metadata, String metadataLocation, long timestampMs) {
        ArrayNode log = metadata.withArray("metadata-log");
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("timestamp-ms", timestampMs);
        entry.put("metadata-file", metadataLocation);
        log.add(entry);
    }

    private Path metadataDirectory(String tableLocation) {
        try {
            URI uri = new URI(tableLocation);
            String scheme = uri.getScheme();
            if (scheme != null && !"file".equalsIgnoreCase(scheme)) {
                throw new BadRequestException("lazy Iceberg export currently requires a local or file:// table location");
            }
            Path tablePath = scheme == null ? Path.of(tableLocation) : Path.of(uri);
            return tablePath.resolve("export").resolve("metadata").normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new BadRequestException("invalid Iceberg table location: " + tableLocation);
        }
    }

    private ObjectNode readJsonObject(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode json = objectMapper.readTree(value);
        if (!json.isObject()) {
            return objectMapper.createObjectNode();
        }
        return (ObjectNode) json;
    }

    private void writeJson(Path path, JsonNode json) throws IOException {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json),
                StandardCharsets.UTF_8);
    }

    public record MaterializedExport(String status, String metadata_location, String manifest_list_location) {
    }

    public record ExportStatus(String status, String metadata_location) {
    }

    private record TableRow(String tableId,
                            String databaseId,
                            String branchId,
                            String namespace,
                            String tableName,
                            String tableLocation,
                            String currentMetadataLocation,
                            JsonNode currentMetadataJson,
                            String currentMetadataHash,
                            Long currentSnapshotId,
                            long metadataVersion,
                            String exportStatus) {
    }

    private record SnapshotRow(long snapshotId,
                               Long parentSnapshotId,
                               long sequenceNumber,
                               String operation,
                               long timestampMs,
                               ObjectNode summary) {
    }

    private void rollbackIfManaged(Connection connection, boolean manageTransaction) {
        if (!manageTransaction) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Best effort rollback; original exception is more useful to callers.
        }
    }

    private void restoreAutoCommit(Connection connection, boolean manageTransaction) {
        if (!manageTransaction) {
            return;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Connection close will discard this state if restoration fails.
        }
    }

    private void cleanupFiles(List<Path> writtenFiles) {
        for (int i = writtenFiles.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(writtenFiles.get(i));
            } catch (IOException ignored) {
                // Orphaned export files are not catalog-visible if the DB update failed.
            }
        }
    }

    private record FileRow(long snapshotId,
                           long sequenceNumber,
                           String filePath,
                           JsonNode partition,
                           long recordCount,
                           long fileSizeBytes,
                           JsonNode lowerBounds,
                           JsonNode upperBounds,
                           JsonNode nullCounts,
                           JsonNode valueCounts) {
    }

    private record IcebergTableShape(Schema schema, PartitionSpec spec) {
    }
}
