package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakebaseCdfWorkerTest {

    @Test
    void emptyBatchDoesNotQueryOrUpdateOffsets() throws SQLException {
        LakebaseCdfWorker worker = new LakebaseCdfWorker();
        Connection connection = mock(Connection.class);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/10", List.of()));

        assertThat(result.status()).isEqualTo("EMPTY_BATCH");
        assertThat(result.snapshotId()).isZero();
        assertThat(result.endLsn()).isEqualTo("0/10");
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void oldBatchEqualToCommittedOffsetIsSkippedWithoutUpsert() throws SQLException {
        LakebaseCdfWorker worker = new LakebaseCdfWorker();
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(select, upsert);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/20", rows()));

        assertThat(result.status()).isEqualTo("SKIPPED_OLD_LSN");
        assertThat(result.snapshotId()).isEqualTo(7L);
        assertThat(result.endLsn()).isEqualTo("0/20");
        verify(connection, never()).prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL);
        verify(upsert, never()).executeUpdate();
    }

    @Test
    void olderBatchThanCommittedOffsetIsSkippedWithoutUpsert() throws SQLException {
        LakebaseCdfWorker worker = new LakebaseCdfWorker();
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(select);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/1F", rows()));

        assertThat(result.status()).isEqualTo("SKIPPED_OLD_LSN");
        assertThat(result.snapshotId()).isEqualTo(7L);
        assertThat(result.endLsn()).isEqualTo("0/20");
        verify(connection, never()).prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL);
    }

    @Test
    void autoCommitDisabledConnectionIsRejectedBeforeStartingWorkerTransaction() throws SQLException {
        LakebaseCdfWorker worker = new LakebaseCdfWorker(stubWriter());
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(select);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(false);
        when(connection.getAutoCommit()).thenReturn(false);

        assertThatThrownBy(() -> worker.commitBatch(connection, stream(), batch("0/30", rows())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CDF worker requires an auto-commit connection");

        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).setAutoCommit(true);
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL);
        verify(connection, never()).prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL);
    }

    @Test
    void newBatchWithNoOffsetCreatesSnapshotOneAndUpsertsOffset() throws SQLException {
        CdfParquetWriter writer = stubWriter();
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement updateTable = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(select);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsert);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPDATE_TABLE_SQL)).thenReturn(updateTable);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(false);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(0L);
        when(table.wasNull()).thenReturn(true);
        when(upsert.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(true);
        when(upsertResult.getLong("last_snapshot_id")).thenReturn(1L);
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(updateTable.executeUpdate()).thenReturn(1);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/30", rows()));

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.snapshotId()).isEqualTo(1L);
        assertThat(result.endLsn()).isEqualTo("0/30");
        verify(upsert).setString(1, "cdf_abcd1234");
        verify(upsert).setString(2, "main");
        verify(upsert).setString(3, "0/30");
        verify(upsert).setLong(4, 1L);
        verify(upsert).executeQuery();
        verify(connection).commit();
    }

    @Test
    void committedBatchWritesSnapshotDataFilesOffsetAndTableStatusInOneTransaction() throws SQLException {
        CdfParquetWriter writer = mock(CdfParquetWriter.class);
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement selectOffset = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement upsertOffset = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement updateTable = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        LakebaseCdfStreamEntity stream = stream();
        stream.setDatabaseId("db_123");
        stream.setTargetNamespace("sales");
        stream.setTargetTable("orders_cdf");
        CdfBatch batch = batch("0/30", rows());

        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(selectOffset);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsertOffset);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPDATE_TABLE_SQL)).thenReturn(updateTable);
        when(selectOffset.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(7L);
        when(table.wasNull()).thenReturn(false);
        when(upsertOffset.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(true);
        when(upsertResult.getLong("last_snapshot_id")).thenReturn(8L);
        when(writer.write("/tmp/lakeon-warehouse", "sales.orders_cdf", 8L, batch))
                .thenReturn(List.of(new CdfParquetWriter.WrittenDataFile(
                        "/tmp/lakeon-warehouse/data/sales.orders_cdf/8/part-1.parquet",
                        1L,
                        512L,
                        Map.of(),
                        Map.of("id", 101L),
                        Map.of("id", 101L))));
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(updateTable.executeUpdate()).thenReturn(1);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(connection, stream, batch);

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.snapshotId()).isEqualTo(8L);
        assertThat(result.endLsn()).isEqualTo("0/30");
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).setAutoCommit(true);
        verify(selectTable).setString(1, "db_123");
        verify(selectTable).setString(2, "main");
        verify(selectTable).setString(3, "sales");
        verify(selectTable).setString(4, "orders_cdf");
        verify(writer).write("/tmp/lakeon-warehouse", "sales.orders_cdf", 8L, batch);
        verify(insertSnapshot).setString(1, "sales.orders_cdf");
        verify(insertSnapshot).setString(2, "main");
        verify(insertSnapshot).setLong(3, 8L);
        verify(insertSnapshot).setLong(4, 7L);
        verify(insertSnapshot).setLong(5, 8L);
        verify(insertSnapshot).setString(6, "insert");
        verify(insertSnapshot).setString(eq(7), contains("\"record-count\":\"1\""));
        verify(insertSnapshot).executeUpdate();
        verify(insertDataFile).setString(1, "sales.orders_cdf");
        verify(insertDataFile).setString(2, "main");
        verify(insertDataFile).setLong(3, 8L);
        verify(insertDataFile).setString(4, "/tmp/lakeon-warehouse/data/sales.orders_cdf/8/part-1.parquet");
        verify(insertDataFile).setString(5, "{}");
        verify(insertDataFile).setLong(6, 1L);
        verify(insertDataFile).setLong(7, 512L);
        verify(insertDataFile).setString(8, "{\"id\":101}");
        verify(insertDataFile).setString(9, "{\"id\":101}");
        verify(insertDataFile).executeUpdate();
        verify(upsertOffset).setString(1, "cdf_abcd1234");
        verify(upsertOffset).setString(2, "main");
        verify(upsertOffset).setString(3, "0/30");
        verify(upsertOffset).setLong(4, 8L);
        verify(upsertOffset).executeQuery();
        verify(updateTable).setLong(1, 8L);
        verify(updateTable).setString(2, "NOT_MATERIALIZED");
        verify(updateTable).setString(3, "0/30");
        verify(updateTable).setString(4, "sales.orders_cdf");
        verify(updateTable).setString(5, "main");
        verify(updateTable).setString(6, "hash-before");
        verify(updateTable).executeUpdate();
        var order = inOrder(connection, selectTable, writer, insertSnapshot, insertDataFile, upsertOffset, updateTable);
        order.verify(connection).setAutoCommit(false);
        order.verify(selectTable).executeQuery();
        order.verify(writer).write("/tmp/lakeon-warehouse", "sales.orders_cdf", 8L, batch);
        order.verify(insertSnapshot).executeUpdate();
        order.verify(insertDataFile).executeUpdate();
        order.verify(upsertOffset).executeQuery();
        order.verify(updateTable).executeUpdate();
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
    }

    @Test
    void newBatchWithExistingOffsetIncrementsSnapshotIdAndUpsertsOffset() throws SQLException {
        CdfParquetWriter writer = stubWriter();
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement updateTable = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(select);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsert);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPDATE_TABLE_SQL)).thenReturn(updateTable);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(7L);
        when(table.wasNull()).thenReturn(false);
        when(upsert.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(true);
        when(upsertResult.getLong("last_snapshot_id")).thenReturn(8L);
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(updateTable.executeUpdate()).thenReturn(1);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/30", rows()));

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.snapshotId()).isEqualTo(8L);
        assertThat(result.endLsn()).isEqualTo("0/30");
        verify(upsert).setString(1, "cdf_abcd1234");
        verify(upsert).setString(2, "main");
        verify(upsert).setString(3, "0/30");
        verify(upsert).setLong(4, 8L);
        verify(upsert).executeQuery();
        verify(connection).commit();
    }

    @Test
    void tableSnapshotAheadOfOffsetCommitsCandidateSnapshotId() throws SQLException {
        CdfParquetWriter writer = mock(CdfParquetWriter.class);
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement updateTable = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        CdfBatch batch = batch("0/30", rows());

        assertThat(LakebaseCdfWorker.UPSERT_OFFSET_SQL)
                .contains("last_snapshot_id = EXCLUDED.last_snapshot_id");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(select);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsert);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPDATE_TABLE_SQL)).thenReturn(updateTable);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(10L);
        when(table.wasNull()).thenReturn(false);
        when(writer.write("/tmp/lakeon-warehouse", "sales.orders_cdf", 11L, batch))
                .thenReturn(List.of(new CdfParquetWriter.WrittenDataFile(
                        "/tmp/lakeon-warehouse/data/sales.orders_cdf/11/part-1.parquet",
                        1L,
                        512L,
                        Map.of(),
                        Map.of("id", 101L),
                        Map.of("id", 101L))));
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(upsert.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(true);
        when(upsertResult.getLong("last_snapshot_id")).thenReturn(11L);
        when(updateTable.executeUpdate()).thenReturn(1);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(connection, stream(), batch);

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.snapshotId()).isEqualTo(11L);
        verify(writer).write("/tmp/lakeon-warehouse", "sales.orders_cdf", 11L, batch);
        verify(insertSnapshot).setLong(3, 11L);
        verify(insertSnapshot).setLong(4, 10L);
        verify(insertSnapshot).setLong(5, 11L);
        verify(insertDataFile).setLong(3, 11L);
        verify(upsert).setLong(4, 11L);
        verify(updateTable).setLong(1, 11L);
        verify(connection).commit();
    }

    @Test
    void staleReaderForwardBatchRollsBackWhenGuardedUpsertReturnsDifferentSnapshot() throws SQLException {
        CdfParquetWriter writer = stubWriter();
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement updateTable = mock(PreparedStatement.class);
        ResultSet offsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(select);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsert);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPDATE_TABLE_SQL)).thenReturn(updateTable);
        when(select.executeQuery()).thenReturn(offsets);
        when(offsets.next()).thenReturn(true);
        when(offsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(offsets.getLong("last_snapshot_id")).thenReturn(7L);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(7L);
        when(table.wasNull()).thenReturn(false);
        when(upsert.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(true);
        when(upsertResult.getLong("last_snapshot_id")).thenReturn(9L);
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(updateTable.executeUpdate()).thenReturn(1);

        assertThatThrownBy(() -> worker.commitBatch(
                connection,
                stream(),
                batch("0/40", rows())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CDF offset snapshot conflict")
                .hasMessageContaining("candidate 8")
                .hasMessageContaining("committed 9");
        verify(upsert).setString(1, "cdf_abcd1234");
        verify(upsert).setString(2, "main");
        verify(upsert).setString(3, "0/40");
        verify(upsert).setLong(4, 8L);
        verify(upsert).executeQuery();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void staleAfterReadBatchReturnsCurrentOffsetWhenGuardedUpsertAffectsNoRows() throws SQLException {
        CdfParquetWriter writer = stubWriter();
        LakebaseCdfWorker worker = new LakebaseCdfWorker(writer);
        Connection connection = mock(Connection.class);
        PreparedStatement firstSelect = mock(PreparedStatement.class);
        PreparedStatement selectTable = mock(PreparedStatement.class);
        PreparedStatement insertSnapshot = mock(PreparedStatement.class);
        PreparedStatement insertDataFile = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement secondSelect = mock(PreparedStatement.class);
        ResultSet firstOffsets = mock(ResultSet.class);
        ResultSet table = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        ResultSet secondOffsets = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_OFFSET_SQL)).thenReturn(firstSelect, secondSelect);
        when(connection.prepareStatement(LakebaseCdfWorker.SELECT_TABLE_SQL)).thenReturn(selectTable);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_SNAPSHOT_SQL)).thenReturn(insertSnapshot);
        when(connection.prepareStatement(LakebaseCdfWorker.INSERT_DATA_FILE_SQL)).thenReturn(insertDataFile);
        when(connection.prepareStatement(LakebaseCdfWorker.UPSERT_OFFSET_SQL)).thenReturn(upsert);
        when(firstSelect.executeQuery()).thenReturn(firstOffsets);
        when(firstOffsets.next()).thenReturn(true);
        when(firstOffsets.getString("last_commit_lsn")).thenReturn("0/20");
        when(firstOffsets.getLong("last_snapshot_id")).thenReturn(7L);
        when(selectTable.executeQuery()).thenReturn(table);
        when(table.next()).thenReturn(true);
        when(table.getString("table_id")).thenReturn("sales.orders_cdf");
        when(table.getString("table_location")).thenReturn("/tmp/lakeon-warehouse");
        when(table.getString("current_metadata_hash")).thenReturn("hash-before");
        when(table.getLong("current_snapshot_id")).thenReturn(7L);
        when(table.wasNull()).thenReturn(false);
        when(insertSnapshot.executeUpdate()).thenReturn(1);
        when(insertDataFile.executeUpdate()).thenReturn(1);
        when(upsert.executeQuery()).thenReturn(upsertResult);
        when(upsertResult.next()).thenReturn(false);
        when(secondSelect.executeQuery()).thenReturn(secondOffsets);
        when(secondOffsets.next()).thenReturn(true);
        when(secondOffsets.getString("last_commit_lsn")).thenReturn("0/40");
        when(secondOffsets.getLong("last_snapshot_id")).thenReturn(9L);

        LakebaseCdfWorker.CommitResult result = worker.commitBatch(
                connection,
                stream(),
                batch("0/30", rows()));

        assertThat(result.status()).isEqualTo("SKIPPED_OLD_LSN");
        assertThat(result.snapshotId()).isEqualTo(9L);
        assertThat(result.endLsn()).isEqualTo("0/40");
        verify(upsert).setString(1, "cdf_abcd1234");
        verify(upsert).setString(2, "main");
        verify(upsert).setString(3, "0/30");
        verify(upsert).setLong(4, 8L);
        verify(upsert).executeQuery();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void invalidEndLsnThrowsBadRequest() throws SQLException {
        LakebaseCdfWorker worker = new LakebaseCdfWorker();

        assertThatThrownBy(() -> worker.commitBatch(
                mock(Connection.class),
                stream(),
                batch("not-an-lsn", rows())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid CDF batch end_lsn")
                .hasMessageContaining("not-an-lsn");
    }

    private static LakebaseCdfStreamEntity stream() {
        LakebaseCdfStreamEntity stream = new LakebaseCdfStreamEntity();
        stream.setId("cdf_abcd1234");
        stream.setDatabaseId("db_123");
        stream.setBranchId("main");
        stream.setTargetNamespace("sales");
        stream.setTargetTable("orders_cdf");
        return stream;
    }

    private static CdfBatch batch(String endLsn, List<Map<String, Object>> rows) {
        return new CdfBatch("cdf_abcd1234", "main", "0/1", endLsn, "insert", rows, 0L);
    }

    private static List<Map<String, Object>> rows() {
        return List.of(Map.of("id", 101L));
    }

    private static CdfParquetWriter stubWriter() {
        CdfParquetWriter writer = mock(CdfParquetWriter.class);
        when(writer.write(anyString(), anyString(), anyLong(), any(CdfBatch.class)))
                .thenReturn(List.of(new CdfParquetWriter.WrittenDataFile(
                        "/tmp/lakeon-warehouse/data/sales.orders_cdf/8/part-1.parquet",
                        1L,
                        512L,
                        Map.of(),
                        Map.of("id", 101L),
                        Map.of("id", 101L))));
        return writer;
    }
}
