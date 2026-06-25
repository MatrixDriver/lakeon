package com.lakeon.cdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.iceberg.LakebaseBranchConnectionProvider;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.repository.LakebaseCdfStreamRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakebaseCdfIncrementalPollerTest {

    @Test
    void pollRunningStreamsCommitsEventsAndDeletesOnlyAfterSuccessfulCommit() throws Exception {
        LakebaseCdfStreamRepository repository = mock(LakebaseCdfStreamRepository.class);
        LakebaseBranchConnectionProvider connectionProvider = mock(LakebaseBranchConnectionProvider.class);
        LakebaseCdfWorker worker = mock(LakebaseCdfWorker.class);
        LakebaseCdfIncrementalPoller poller = new LakebaseCdfIncrementalPoller(
                repository,
                connectionProvider,
                worker,
                new ObjectMapper());
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        PreparedStatement selectEvents = mock(PreparedStatement.class);
        PreparedStatement deleteEvents = mock(PreparedStatement.class);
        Statement lsnStatement = mock(Statement.class);
        ResultSet events = mock(ResultSet.class);
        ResultSet lsn = mock(ResultSet.class);

        when(repository.findByStatusAndBackfillStatus("RUNNING", "SUCCEEDED")).thenReturn(List.of(stream));
        when(connectionProvider.open(any(), eq("db_123"), eq("main"))).thenReturn(connection);
        when(connection.prepareStatement(sqlContaining("SELECT event_id"))).thenReturn(selectEvents);
        when(connection.prepareStatement(sqlContaining("DELETE FROM _lakeon_iceberg.cdf_change_events"))).thenReturn(deleteEvents);
        when(selectEvents.executeQuery()).thenReturn(events);
        when(events.next()).thenReturn(true, true, false);
        when(events.getLong("event_id")).thenReturn(41L, 42L);
        when(events.getString("op")).thenReturn("INSERT", "UPDATE");
        when(events.getString("row_json")).thenReturn("{\"id\":101,\"total\":\"19.95\"}", "{\"id\":102,\"total\":\"24.50\"}");
        when(connection.createStatement()).thenReturn(lsnStatement);
        when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()")).thenReturn(lsn);
        when(lsn.next()).thenReturn(true);
        when(lsn.getString(1)).thenReturn("0/30");
        when(worker.commitBatch(eq(connection), eq(stream), any(CdfBatch.class)))
                .thenReturn(new LakebaseCdfWorker.CommitResult("COMMITTED", 2L, "0/30"));

        poller.pollRunningStreams();

        ArgumentCaptor<CdfBatch> batchCaptor = ArgumentCaptor.forClass(CdfBatch.class);
        verify(worker).commitBatch(eq(connection), eq(stream), batchCaptor.capture());
        CdfBatch batch = batchCaptor.getValue();
        assertThat(batch.streamId()).isEqualTo("cdf_abcd1234");
        assertThat(batch.branchId()).isEqualTo("main");
        assertThat(batch.startLsn()).isEqualTo("0/30");
        assertThat(batch.endLsn()).isEqualTo("0/30");
        assertThat(batch.operation()).isEqualTo("append");
        assertThat(batch.rows()).containsExactly(
                java.util.Map.of("id", 101, "total", "19.95", "_lakeon_cdf_op", "insert", "_lakeon_cdf_event_id", 41L),
                java.util.Map.of("id", 102, "total", "24.50", "_lakeon_cdf_op", "update", "_lakeon_cdf_event_id", 42L));
        verify(deleteEvents).setString(1, "cdf_abcd1234");
        verify(deleteEvents).setString(2, "main");
        verify(deleteEvents).setLong(3, 42L);
        verify(deleteEvents).executeUpdate();
    }

    @Test
    void emptyEventBatchDoesNotCommitOrDelete() throws Exception {
        LakebaseCdfStreamRepository repository = mock(LakebaseCdfStreamRepository.class);
        LakebaseBranchConnectionProvider connectionProvider = mock(LakebaseBranchConnectionProvider.class);
        LakebaseCdfWorker worker = mock(LakebaseCdfWorker.class);
        LakebaseCdfIncrementalPoller poller = new LakebaseCdfIncrementalPoller(
                repository,
                connectionProvider,
                worker,
                new ObjectMapper());
        LakebaseCdfStreamEntity stream = stream();
        Connection connection = mock(Connection.class);
        PreparedStatement selectEvents = mock(PreparedStatement.class);
        ResultSet events = mock(ResultSet.class);

        when(repository.findByStatusAndBackfillStatus("RUNNING", "SUCCEEDED")).thenReturn(List.of(stream));
        when(connectionProvider.open(any(), eq("db_123"), eq("main"))).thenReturn(connection);
        when(connection.prepareStatement(sqlContaining("SELECT event_id"))).thenReturn(selectEvents);
        when(selectEvents.executeQuery()).thenReturn(events);
        when(events.next()).thenReturn(false);

        poller.pollRunningStreams();

        verify(worker, never()).commitBatch(any(), any(), any());
        verify(connection, never()).createStatement();
    }

    private static String sqlContaining(String expected) {
        return org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains(expected));
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
        stream.setStatus("RUNNING");
        stream.setBackfillStatus("SUCCEEDED");
        stream.setSlotName("lakeon_cdf_slot_abcd1234");
        stream.setPublicationName("lakeon_cdf_pub_abcd1234");
        return stream;
    }
}
