package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class LakebaseBackfillServiceTest {

    @Test
    void runBackfillCapturesLsnScansRowsCommitsBatchAndMarksStreamRunning() throws SQLException {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer);
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        Statement lsnStatement = mock(Statement.class);
        Statement scanStatement = mock(Statement.class);
        ResultSet lsnResultSet = mock(ResultSet.class);
        ResultSet rowsResultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);

        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(lsnStatement, scanStatement);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "sales", "orders")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()")).thenReturn(lsnResultSet);
        when(lsnResultSet.next()).thenReturn(true);
        when(lsnResultSet.getString(1)).thenReturn("0/16B6C50");
        when(scanStatement.executeQuery("SELECT * FROM \"sales\".\"orders\" ORDER BY \"id\"")).thenReturn(rowsResultSet);
        when(rowsResultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(metaData.getColumnLabel(2)).thenReturn("total");
        when(rowsResultSet.next()).thenReturn(true, true, false);
        when(rowsResultSet.getObject(1)).thenReturn(101L, 102L);
        when(rowsResultSet.getObject(2)).thenReturn("19.95", "24.50");

        LakebaseBackfillService.BackfillResult result = service.runBackfill(connection, stream);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.backfillLsn()).isEqualTo("0/16B6C50");
        assertThat(result.snapshotId()).isEqualTo(1L);
        assertThat(result.rowCount()).isEqualTo(2L);
        assertThat(stream.getBackfillStatus()).isEqualTo("SUCCEEDED");
        assertThat(stream.getBackfillLsn()).isEqualTo("0/16B6C50");
        assertThat(stream.getStatus()).isEqualTo("RUNNING");

        var batchCaptor = forClass(CdfBatch.class);
        verify(committer).commitBatch(batchCaptor.capture());
        CdfBatch batch = batchCaptor.getValue();
        assertThat(batch.streamId()).isEqualTo("cdf_abcd1234");
        assertThat(batch.branchId()).isEqualTo("main");
        assertThat(batch.startLsn()).isEqualTo("0/16B6C50");
        assertThat(batch.endLsn()).isEqualTo("0/16B6C50");
        assertThat(batch.operation()).isEqualTo("backfill");
        assertThat(batch.snapshotId()).isEqualTo(1L);
        assertThat(batch.rows()).containsExactly(
                Map.of("id", 101L, "total", "19.95"),
                Map.of("id", 102L, "total", "24.50"));

        var inOrder = inOrder(connection, lsnStatement, scanStatement, committer);
        inOrder.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).createStatement();
        inOrder.verify(lsnStatement).executeQuery("SELECT pg_current_wal_lsn()");
        inOrder.verify(connection).createStatement();
        inOrder.verify(scanStatement).setFetchSize(1000);
        inOrder.verify(scanStatement).executeQuery("SELECT * FROM \"sales\".\"orders\" ORDER BY \"id\"");
        inOrder.verify(committer).commitBatch(batch);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void repeatedRunBackfillForSucceededStreamIsIdempotent() throws SQLException {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer);
        LakebaseCdfStreamEntity stream = stream();
        stream.setBackfillStatus("SUCCEEDED");
        stream.setBackfillLsn("0/OLD");
        Connection connection = mock(Connection.class);

        LakebaseBackfillService.BackfillResult result = service.runBackfill(connection, stream);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.backfillLsn()).isEqualTo("0/OLD");
        assertThat(result.snapshotId()).isEqualTo(1L);
        assertThat(result.rowCount()).isZero();
        verify(connection, never()).createStatement();
        verify(committer, never()).commitBatch(org.mockito.Mockito.any());
    }

    @Test
    void committerFailureMarksBackfillFailedAndThrowsBadRequest() throws SQLException {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer);
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        Statement lsnStatement = mock(Statement.class);
        Statement scanStatement = mock(Statement.class);
        ResultSet lsnResultSet = mock(ResultSet.class);
        ResultSet rowsResultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);

        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(lsnStatement, scanStatement);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "sales", "orders")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()")).thenReturn(lsnResultSet);
        when(lsnResultSet.next()).thenReturn(true);
        when(lsnResultSet.getString(1)).thenReturn("0/16B6C50");
        when(scanStatement.executeQuery("SELECT * FROM \"sales\".\"orders\" ORDER BY \"id\"")).thenReturn(rowsResultSet);
        when(rowsResultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(rowsResultSet.next()).thenReturn(true, false);
        when(rowsResultSet.getObject(1)).thenReturn(101L);
        org.mockito.Mockito.doThrow(new SQLException("iceberg commit failed"))
                .when(committer).commitBatch(org.mockito.Mockito.any());

        assertThatThrownBy(() -> service.runBackfill(connection, stream))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("initial backfill failed: iceberg commit failed");

        assertThat(stream.getBackfillStatus()).isEqualTo("BACKFILL_FAILED");
        assertThat(stream.getStatus()).isEqualTo("PAUSED");
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void scanSqlQuotesIdentifiersAndRejectsInvalidIdentifiers() {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer);
        LakebaseCdfStreamEntity stream = stream();
        stream.setSourceSchema("odd\"schema");
        stream.setSourceTable("bad\u0000table");

        assertThatThrownBy(() -> service.runBackfill(mock(Connection.class), stream))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("initial backfill failed:");

        assertThat(stream.getBackfillStatus()).isEqualTo("BACKFILL_FAILED");
    }

    @Test
    void runBackfillFallsBackToAllSourceColumnsWhenPrimaryKeyIsAbsent() throws SQLException {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer);
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        Statement lsnStatement = mock(Statement.class);
        Statement scanStatement = mock(Statement.class);
        PreparedStatement columnsStatement = mock(PreparedStatement.class);
        ResultSet lsnResultSet = mock(ResultSet.class);
        ResultSet primaryKeys = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        ResultSet rowsResultSet = mock(ResultSet.class);
        ResultSetMetaData rowMetaData = mock(ResultSetMetaData.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);

        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(lsnStatement, scanStatement);
        when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()")).thenReturn(lsnResultSet);
        when(lsnResultSet.next()).thenReturn(true);
        when(lsnResultSet.getString(1)).thenReturn("0/16B6C50");
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "sales", "orders")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(columnsStatement);
        when(columnsStatement.executeQuery()).thenReturn(columns);
        String[] names = {"id", "payload", "total"};
        String[] dataTypes = {"bigint", "json", "numeric"};
        String[] udtNames = {"int8", "json", "numeric"};
        AtomicInteger columnRow = new AtomicInteger(-1);
        when(columns.next()).thenAnswer(invocation -> columnRow.incrementAndGet() < names.length);
        when(columns.getString("column_name")).thenAnswer(invocation -> names[columnRow.get()]);
        when(columns.getString("data_type")).thenAnswer(invocation -> dataTypes[columnRow.get()]);
        when(columns.getString("udt_name")).thenAnswer(invocation -> udtNames[columnRow.get()]);
        when(scanStatement.executeQuery(anyString())).thenReturn(rowsResultSet);
        when(rowsResultSet.getMetaData()).thenReturn(rowMetaData);
        when(rowMetaData.getColumnCount()).thenReturn(0);
        when(rowsResultSet.next()).thenReturn(false);

        LakebaseBackfillService.BackfillResult result = service.runBackfill(connection, stream);

        assertThat(result.rowCount()).isZero();
        verify(scanStatement).setFetchSize(1000);
        verify(scanStatement).executeQuery("SELECT * FROM \"sales\".\"orders\" ORDER BY \"id\", \"total\"");
        verify(columnsStatement).setString(1, "sales");
        verify(columnsStatement).setString(2, "orders");
        verify(committer, never()).commitBatch(org.mockito.Mockito.any());
    }

    @Test
    void runBackfillCommitsRowsInBoundedBatches() throws SQLException {
        BackfillBatchCommitter committer = mock(BackfillBatchCommitter.class);
        LakebaseBackfillService service = new LakebaseBackfillService(committer, 1);
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        Statement lsnStatement = mock(Statement.class);
        Statement scanStatement = mock(Statement.class);
        ResultSet lsnResultSet = mock(ResultSet.class);
        ResultSet rowsResultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        ResultSet primaryKeys = mock(ResultSet.class);

        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(lsnStatement, scanStatement);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getPrimaryKeys(null, "sales", "orders")).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()")).thenReturn(lsnResultSet);
        when(lsnResultSet.next()).thenReturn(true);
        when(lsnResultSet.getString(1)).thenReturn("0/16B6C50");
        when(scanStatement.executeQuery("SELECT * FROM \"sales\".\"orders\" ORDER BY \"id\"")).thenReturn(rowsResultSet);
        when(rowsResultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(rowsResultSet.next()).thenReturn(true, true, false);
        when(rowsResultSet.getObject(1)).thenReturn(101L, 102L);

        LakebaseBackfillService.BackfillResult result = service.runBackfill(connection, stream);

        assertThat(result.rowCount()).isEqualTo(2L);
        verify(scanStatement).setFetchSize(1);
        var batchCaptor = forClass(CdfBatch.class);
        verify(committer, times(2)).commitBatch(batchCaptor.capture());
        assertThat(batchCaptor.getAllValues().get(0).rows()).containsExactly(Map.of("id", 101L));
        assertThat(batchCaptor.getAllValues().get(1).rows()).containsExactly(Map.of("id", 102L));
    }

    private static LakebaseCdfStreamEntity stream() {
        LakebaseCdfStreamEntity stream = new LakebaseCdfStreamEntity();
        stream.setId("cdf_abcd1234");
        stream.setTenantId("tn_123");
        stream.setDatabaseId("db_123");
        stream.setBranchId("main");
        stream.setSourceSchema("sales");
        stream.setSourceTable("orders");
        stream.setTargetNamespace("sales");
        stream.setTargetTable("orders_cdf");
        stream.setMode("APPEND_CHANGELOG");
        stream.setStatus("PAUSED");
        stream.setBackfillStatus("PENDING");
        stream.setBackfillLsn(null);
        stream.setSlotName("lakeon_cdf_slot_abcd1234");
        stream.setPublicationName("lakeon_cdf_pub_abcd1234");
        stream.setExportStatus("NOT_MATERIALIZED");
        return stream;
    }
}
