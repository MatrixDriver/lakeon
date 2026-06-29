package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.service.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LakebaseBackfillService {

    private static final long INITIAL_SNAPSHOT_ID = 1L;
    private static final String BACKFILL_OPERATION = "backfill";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String BACKFILL_FAILED = "BACKFILL_FAILED";
    private static final String RUNNING = "RUNNING";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final BackfillBatchCommitter committer;
    private final int batchSize;

    @Autowired
    public LakebaseBackfillService(BackfillBatchCommitter committer) {
        this(committer, DEFAULT_BATCH_SIZE);
    }

    LakebaseBackfillService(BackfillBatchCommitter committer, int batchSize) {
        this.committer = committer;
        this.batchSize = batchSize;
    }

    public BackfillResult runBackfill(Connection connection, LakebaseCdfStreamEntity stream) {
        if (SUCCEEDED.equals(stream.getBackfillStatus())) {
            return new BackfillResult(
                    SUCCEEDED,
                    stream.getBackfillLsn(),
                    stream.getLastSnapshotId() == null ? INITIAL_SNAPSHOT_ID : stream.getLastSnapshotId(),
                    0L,
                    stream.getLastCommitLsn() == null ? stream.getBackfillLsn() : stream.getLastCommitLsn());
        }

        boolean capturedConnectionState = false;
        boolean originalAutoCommit = true;
        int originalIsolation = Connection.TRANSACTION_READ_COMMITTED;
        try {
            originalAutoCommit = connection.getAutoCommit();
            originalIsolation = connection.getTransactionIsolation();
            capturedConnectionState = true;
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);

            String backfillLsn = captureBackfillLsn(connection);
            BackfillScanResult scan = scanAndCommitBatches(connection, stream, backfillLsn);
            connection.commit();

            stream.setBackfillStatus(SUCCEEDED);
            stream.setBackfillLsn(backfillLsn);
            stream.setStatus(RUNNING);
            long snapshotId = scan.lastCommit() == null ? INITIAL_SNAPSHOT_ID : scan.lastCommit().snapshotId();
            String lastCommitLsn = scan.lastCommit() == null ? backfillLsn : scan.lastCommit().endLsn();
            return new BackfillResult(SUCCEEDED, backfillLsn, snapshotId, scan.rowCount(), lastCommitLsn);
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection);
            stream.setBackfillStatus(BACKFILL_FAILED);
            throw new BadRequestException("initial backfill failed: " + e.getMessage());
        } finally {
            if (capturedConnectionState) {
                restoreConnectionState(connection, originalAutoCommit, originalIsolation);
            }
        }
    }

    private String captureBackfillLsn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT pg_current_wal_lsn()")) {
            if (!resultSet.next()) {
                throw new SQLException("pg_current_wal_lsn returned no rows");
            }
            return resultSet.getString(1);
        }
    }

    private BackfillScanResult scanAndCommitBatches(Connection connection, LakebaseCdfStreamEntity stream, String backfillLsn)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(batchSize);
            try (ResultSet resultSet = statement.executeQuery(scanSql(connection, stream))) {
                return readRowsAndCommitBatches(resultSet, stream, backfillLsn);
            }
        }
    }

    private BackfillScanResult readRowsAndCommitBatches(ResultSet resultSet, LakebaseCdfStreamEntity stream, String backfillLsn)
            throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> batchRows = new ArrayList<>(batchSize);
        long rowCount = 0;
        LakebaseCdfWorker.CommitResult lastCommit = null;
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= columnCount; column++) {
                row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
            }
            batchRows.add(row);
            rowCount++;
            if (batchRows.size() >= batchSize) {
                lastCommit = commitRows(stream, backfillLsn, batchRows);
                batchRows.clear();
            }
        }
        if (!batchRows.isEmpty()) {
            lastCommit = commitRows(stream, backfillLsn, batchRows);
        }
        return new BackfillScanResult(rowCount, lastCommit);
    }

    private LakebaseCdfWorker.CommitResult commitRows(
            LakebaseCdfStreamEntity stream,
            String backfillLsn,
            List<Map<String, Object>> rows)
            throws SQLException {
        return committer.commitBatch(stream, new CdfBatch(
                stream.getId(),
                stream.getBranchId(),
                backfillLsn,
                backfillLsn,
                BACKFILL_OPERATION,
                List.copyOf(rows),
                INITIAL_SNAPSHOT_ID));
    }

    private String scanSql(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        return "SELECT * FROM "
                + quoteIdentifier(stream.getSourceSchema())
                + "."
                + quoteIdentifier(stream.getSourceTable())
                + " ORDER BY "
                + orderByColumns(connection, stream);
    }

    private String orderByColumns(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        List<String> primaryKeys = primaryKeyColumns(connection, stream);
        List<String> orderColumns = primaryKeys.isEmpty() ? sourceColumns(connection, stream) : primaryKeys;
        if (orderColumns.isEmpty()) {
            throw new SQLException("source table has no columns for deterministic backfill ordering");
        }
        return orderColumns.stream()
                .map(LakebaseBackfillService::quoteIdentifier)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
    }

    private List<String> primaryKeyColumns(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<PrimaryKeyColumn> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getPrimaryKeys(null, stream.getSourceSchema(), stream.getSourceTable())) {
            while (resultSet.next()) {
                columns.add(new PrimaryKeyColumn(resultSet.getShort("KEY_SEQ"), resultSet.getString("COLUMN_NAME")));
            }
        }
        return columns.stream()
                .sorted((left, right) -> Short.compare(left.keySeq(), right.keySeq()))
                .map(PrimaryKeyColumn::columnName)
                .toList();
    }

    private List<String> sourceColumns(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        String sql = """
                SELECT column_name, data_type, udt_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stream.getSourceSchema());
            statement.setString(2, stream.getSourceTable());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (isSortableType(resultSet.getString("data_type"), resultSet.getString("udt_name"))) {
                        columns.add(resultSet.getString("column_name"));
                    }
                }
            }
        }
        return columns;
    }

    private static boolean isSortableType(String dataType, String udtName) {
        String normalizedDataType = dataType == null ? "" : dataType;
        String normalizedUdtName = udtName == null ? "" : udtName;
        if (normalizedDataType.equals("json") || normalizedDataType.equals("xml")) {
            return false;
        }
        return !normalizedUdtName.equals("json") && !normalizedUdtName.equals("xml");
    }

    private static String quoteIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            throw new BadRequestException("SQL identifier must not be empty");
        }
        if (value.indexOf('\0') >= 0) {
            throw new BadRequestException("SQL identifier must not contain null byte");
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreConnectionState(Connection connection, boolean autoCommit, int isolation) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
        try {
            connection.setTransactionIsolation(isolation);
        } catch (SQLException ignored) {
        }
    }

    private record PrimaryKeyColumn(short keySeq, String columnName) {
    }

    private record BackfillScanResult(long rowCount, LakebaseCdfWorker.CommitResult lastCommit) {
    }

    public record BackfillResult(
            String status,
            String backfillLsn,
            long snapshotId,
            long rowCount,
            String lastCommitLsn) {
    }
}
