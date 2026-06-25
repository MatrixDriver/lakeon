package com.lakeon.cdf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.util.LsnUtil;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LakebaseCdfWorker {
    private static final String NOT_MATERIALIZED = "NOT_MATERIALIZED";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final String SELECT_OFFSET_SQL = """
            SELECT last_commit_lsn, last_snapshot_id
            FROM _lakeon_iceberg.cdf_offsets
            WHERE stream_id = ? AND branch_id = ?
            """;

    static final String SELECT_TABLE_SQL = """
            SELECT table_id, table_location, current_metadata_hash, current_snapshot_id
            FROM _lakeon_iceberg.tables
            WHERE database_id = ?
              AND branch_id = ?
              AND namespace = ?
              AND table_name = ?
            FOR UPDATE
            """;

    static final String UPSERT_OFFSET_SQL = """
            INSERT INTO _lakeon_iceberg.cdf_offsets (stream_id, branch_id, last_commit_lsn, last_snapshot_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (stream_id, branch_id)
            DO UPDATE SET last_commit_lsn = EXCLUDED.last_commit_lsn,
                          last_snapshot_id = EXCLUDED.last_snapshot_id,
                          applied_at = now()
            WHERE EXCLUDED.last_commit_lsn::pg_lsn > _lakeon_iceberg.cdf_offsets.last_commit_lsn::pg_lsn
            RETURNING last_snapshot_id
            """;

    static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO _lakeon_iceberg.snapshots
                (table_id, branch_id, snapshot_id, parent_snapshot_id, sequence_number, operation, summary_json)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    static final String INSERT_DATA_FILE_SQL = """
            INSERT INTO _lakeon_iceberg.data_files
                (table_id, branch_id, snapshot_id, file_path, content_type, partition_json,
                 record_count, file_size_bytes, lower_bounds_json, upper_bounds_json)
            VALUES (?, ?, ?, ?, 'DATA', ?::jsonb, ?, ?, ?::jsonb, ?::jsonb)
            """;

    static final String UPDATE_TABLE_SQL = """
            UPDATE _lakeon_iceberg.tables
            SET current_snapshot_id = ?,
                export_status = ?,
                last_commit_lsn = ?,
                metadata_version = metadata_version + 1,
                updated_at = now()
            WHERE table_id = ?
              AND branch_id = ?
              AND current_metadata_hash = ?
            """;

    private final CdfParquetWriter parquetWriter;

    public LakebaseCdfWorker() {
        this(new CdfParquetWriter());
    }

    LakebaseCdfWorker(CdfParquetWriter parquetWriter) {
        this.parquetWriter = Objects.requireNonNull(parquetWriter, "parquetWriter must not be null");
    }

    public CommitResult commitBatch(Connection connection, LakebaseCdfStreamEntity stream, CdfBatch batch) {
        if (batch.rows() == null || batch.rows().isEmpty()) {
            return new CommitResult("EMPTY_BATCH", 0L, batch.endLsn());
        }

        long batchEndLsn = parseBatchEndLsn(batch.endLsn());
        String streamId = stream.getId();
        String branchId = stream.getBranchId();

        try {
            Offset currentOffset = readOffset(connection, streamId, branchId);
            if (currentOffset.exists() && batchEndLsn <= currentOffset.lastCommitLsn()) {
                return new CommitResult("SKIPPED_OLD_LSN", currentOffset.lastSnapshotId(), currentOffset.lastCommitLsnText());
            }

            return commitBatchTransaction(connection, stream, batch, currentOffset);
        } catch (SQLException e) {
            throw new BadRequestException("failed to commit CDF batch: " + e.getMessage());
        }
    }

    private CommitResult commitBatchTransaction(
            Connection connection,
            LakebaseCdfStreamEntity stream,
            CdfBatch batch,
            Offset currentOffset) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new BadRequestException("CDF worker requires an auto-commit connection");
        }
        boolean transactionStarted = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;

            TableState table = readTableForUpdate(connection, stream);
            long candidateSnapshotId = nextSnapshotId(table, currentOffset);
            List<CdfParquetWriter.WrittenDataFile> dataFiles =
                    parquetWriter.write(table.tableLocation(), table.tableId(), candidateSnapshotId, batch);
            insertSnapshot(connection, table, stream.getBranchId(), candidateSnapshotId, batch);
            insertDataFiles(connection, table.tableId(), stream.getBranchId(), candidateSnapshotId, dataFiles);

            UpsertResult upsertResult =
                    upsertOffset(connection, stream.getId(), stream.getBranchId(), batch.endLsn(), candidateSnapshotId);
            if (!upsertResult.committed()) {
                connection.rollback();
                transactionStarted = false;
                restoreAutoCommit(connection, originalAutoCommit);
                Offset latestOffset = readOffset(connection, stream.getId(), stream.getBranchId());
                if (!latestOffset.exists()) {
                    throw new BadRequestException("CDF offset was not updated and no current offset exists");
                }
                return new CommitResult("SKIPPED_OLD_LSN", latestOffset.lastSnapshotId(), latestOffset.lastCommitLsnText());
            }

            if (upsertResult.snapshotId() != candidateSnapshotId) {
                throw new BadRequestException("CDF offset snapshot conflict for stream " + stream.getId()
                        + ": candidate " + candidateSnapshotId + ", committed " + upsertResult.snapshotId());
            }

            updateTable(connection, table, stream.getBranchId(), candidateSnapshotId, batch.endLsn());
            connection.commit();
            return new CommitResult("COMMITTED", candidateSnapshotId, batch.endLsn());
        } catch (SQLException e) {
            rollbackIfNeeded(connection, transactionStarted);
            throw e;
        } catch (RuntimeException e) {
            rollbackIfNeeded(connection, transactionStarted);
            if (e instanceof BadRequestException) {
                throw e;
            }
            throw new BadRequestException("failed to commit CDF batch: " + e.getMessage());
        } finally {
            restoreAutoCommit(connection, originalAutoCommit);
        }
    }

    private Offset readOffset(Connection connection, String streamId, String branchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_OFFSET_SQL)) {
            statement.setString(1, streamId);
            statement.setString(2, branchId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Offset.none();
                }
                String lastCommitLsn = resultSet.getString("last_commit_lsn");
                return new Offset(
                        true,
                        lastCommitLsn,
                        parseStoredOffsetLsn(lastCommitLsn),
                        resultSet.getLong("last_snapshot_id"));
            }
        }
    }

    private TableState readTableForUpdate(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_TABLE_SQL)) {
            statement.setString(1, require(stream.getDatabaseId(), "database_id"));
            statement.setString(2, require(stream.getBranchId(), "branch_id"));
            statement.setString(3, require(stream.getTargetNamespace(), "target_namespace"));
            statement.setString(4, require(stream.getTargetTable(), "target_table"));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new BadRequestException("CDF target Iceberg table was not found for "
                            + stream.getDatabaseId() + "/" + stream.getBranchId() + "/"
                            + stream.getTargetNamespace() + "." + stream.getTargetTable());
                }
                long currentSnapshotId = resultSet.getLong("current_snapshot_id");
                if (resultSet.wasNull()) {
                    currentSnapshotId = 0L;
                }
                return new TableState(
                        resultSet.getString("table_id"),
                        resultSet.getString("table_location"),
                        resultSet.getString("current_metadata_hash"),
                        currentSnapshotId);
            }
        }
    }

    private UpsertResult upsertOffset(
            Connection connection,
            String streamId,
            String branchId,
            String endLsn,
            long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_OFFSET_SQL)) {
            statement.setString(1, streamId);
            statement.setString(2, branchId);
            statement.setString(3, endLsn);
            statement.setLong(4, snapshotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return UpsertResult.skipped();
                }
                return new UpsertResult(true, resultSet.getLong("last_snapshot_id"));
            }
        }
    }

    private static long nextSnapshotId(TableState table, Offset currentOffset) {
        long lastTableSnapshotId = table.currentSnapshotId();
        long lastOffsetSnapshotId = currentOffset.exists() ? currentOffset.lastSnapshotId() : 0L;
        return Math.max(lastTableSnapshotId, lastOffsetSnapshotId) + 1L;
    }

    private void insertSnapshot(Connection connection, TableState table, String branchId, long snapshotId, CdfBatch batch)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SNAPSHOT_SQL)) {
            statement.setString(1, table.tableId());
            statement.setString(2, branchId);
            statement.setLong(3, snapshotId);
            if (table.currentSnapshotId() == 0L) {
                statement.setNull(4, Types.BIGINT);
            } else {
                statement.setLong(4, table.currentSnapshotId());
            }
            statement.setLong(5, snapshotId);
            statement.setString(6, normalizeOperation(batch.operation()));
            statement.setString(7, toJson(Map.of(
                    "operation", normalizeOperation(batch.operation()),
                    "record-count", String.valueOf(batch.rows().size()),
                    "start-lsn", batch.startLsn(),
                    "end-lsn", batch.endLsn())));
            statement.executeUpdate();
        }
    }

    private void insertDataFiles(
            Connection connection,
            String tableId,
            String branchId,
            long snapshotId,
            List<CdfParquetWriter.WrittenDataFile> dataFiles) throws SQLException {
        for (CdfParquetWriter.WrittenDataFile dataFile : dataFiles) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_DATA_FILE_SQL)) {
                statement.setString(1, tableId);
                statement.setString(2, branchId);
                statement.setLong(3, snapshotId);
                statement.setString(4, dataFile.path());
                statement.setString(5, toJson(dataFile.partition()));
                statement.setLong(6, dataFile.recordCount());
                statement.setLong(7, dataFile.fileSizeBytes());
                statement.setString(8, toJson(dataFile.lowerBounds()));
                statement.setString(9, toJson(dataFile.upperBounds()));
                statement.executeUpdate();
            }
        }
    }

    private void updateTable(Connection connection, TableState table, String branchId, long snapshotId, String endLsn)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_TABLE_SQL)) {
            statement.setLong(1, snapshotId);
            statement.setString(2, NOT_MATERIALIZED);
            statement.setString(3, endLsn);
            statement.setString(4, table.tableId());
            statement.setString(5, branchId);
            statement.setString(6, table.currentMetadataHash());
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new BadRequestException("stale Iceberg table metadata for table " + table.tableId());
            }
        }
    }

    private static long parseBatchEndLsn(String endLsn) {
        try {
            return LsnUtil.parse(endLsn);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid CDF batch end_lsn '" + endLsn + "': " + e.getMessage());
        }
    }

    private static long parseStoredOffsetLsn(String lsn) {
        try {
            return LsnUtil.parse(lsn);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid stored CDF offset last_commit_lsn '" + lsn + "': " + e.getMessage());
        }
    }

    private static String normalizeOperation(String operation) {
        return operation == null || operation.isBlank() ? "append" : operation;
    }

    private static String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("CDF stream " + fieldName + " must not be empty");
        }
        return value;
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("failed to serialize CDF commit JSON: " + e.getMessage());
        }
    }

    private static void rollbackIfNeeded(Connection connection, boolean transactionStarted) {
        if (!transactionStarted) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    private record Offset(boolean exists, String lastCommitLsnText, long lastCommitLsn, long lastSnapshotId) {
        static Offset none() {
            return new Offset(false, null, 0L, 0L);
        }
    }

    private record UpsertResult(boolean committed, long snapshotId) {
        static UpsertResult skipped() {
            return new UpsertResult(false, 0L);
        }
    }

    private record TableState(String tableId, String tableLocation, String currentMetadataHash, long currentSnapshotId) {
    }

    public record CommitResult(String status, long snapshotId, String endLsn) {
    }
}
