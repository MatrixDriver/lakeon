package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.LakebaseCdfStreamRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.iceberg.IcebergExportMaterializer;
import com.lakeon.iceberg.IcebergTableBootstrapService;
import com.lakeon.iceberg.LakebaseBranchConnectionProvider;
import com.lakeon.iceberg.IcebergTenantSchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentCaptor.forClass;

class LakebaseCdfServiceTest {

    private final LakebaseCdfStreamRepository repository = mock(LakebaseCdfStreamRepository.class);
    private final LakebaseBranchConnectionProvider branchConnectionProvider = mock(LakebaseBranchConnectionProvider.class);
    private final IcebergTableBootstrapService tableBootstrapService = mock(IcebergTableBootstrapService.class);
    private final LakebaseBackfillService backfillService = mock(LakebaseBackfillService.class);
    private final IcebergExportMaterializer exportMaterializer = mock(IcebergExportMaterializer.class);
    private final TenantEntity tenant = new TenantEntity();
    private LakebaseCdfService service;

    @BeforeEach
    void setUp() {
        tenant.setId("tn_123");
        service = new LakebaseCdfService(
                repository, branchConnectionProvider, new IcebergTenantSchemaManager(),
                tableBootstrapService, backfillService, exportMaterializer);
        when(repository.save(any(LakebaseCdfStreamEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createGeneratesStreamIdAndReplicationNamesFromShortSuffix() {
        var request = new LakebaseCdfController.CreateCdfStreamRequest(
                null, null, "sales", "orders", null, "orders_changes", null, null);
        when(repository.findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
                "tn_123", "db_123", "main", "sales", "orders_changes"))
                .thenReturn(Optional.empty());

        LakebaseCdfController.CdfStreamResponse response = service.create(tenant, "db_123", request);
        String shortId = response.id().substring("cdf_".length());

        assertThat(response.id()).startsWith("cdf_");
        assertThat(response.publication_name()).isEqualTo("lakeon_cdf_pub_" + shortId);
        assertThat(response.slot_name()).isEqualTo("lakeon_cdf_slot_" + shortId);
        assertThat(response.database_id()).isEqualTo("db_123");
        assertThat(response.branch_id()).isEqualTo("main");
        assertThat(response.source_schema()).isEqualTo("sales");
        assertThat(response.target_namespace()).isEqualTo("sales");
    }

    @Test
    void duplicateTargetNamespaceAndTableIsRejected() {
        var request = new LakebaseCdfController.CreateCdfStreamRequest(
                null, "br_main", "public", "orders", "analytics", "orders_cdf", null, null);
        when(repository.findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
                "tn_123", "db_123", "br_main", "analytics", "orders_cdf"))
                .thenReturn(Optional.of(new LakebaseCdfStreamEntity()));

        assertThatThrownBy(() -> service.create(tenant, "db_123", request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CDF stream target already exists");
    }

    @Test
    void createAppliesLifecycleDefaultsAndIsNotReadableBeforeBackfillSucceeds() {
        var request = new LakebaseCdfController.CreateCdfStreamRequest(
                null, null, " ", " orders ", null, " orders_cdf ", " ", false);
        when(repository.findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
                "tn_123", "db_123", "main", "public", "orders_cdf"))
                .thenReturn(Optional.empty());

        LakebaseCdfController.CdfStreamResponse response = service.create(tenant, "db_123", request);

        assertThat(response.source_schema()).isEqualTo("public");
        assertThat(response.target_namespace()).isEqualTo("public");
        assertThat(response.mode()).isEqualTo("APPEND_CHANGELOG");
        assertThat(response.status()).isEqualTo("PAUSED");
        assertThat(response.backfill_status()).isEqualTo("PENDING");
        assertThat(response.readable()).isFalse();

        var captor = forClass(LakebaseCdfStreamEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceTable()).isEqualTo("orders");
        assertThat(captor.getValue().getTargetTable()).isEqualTo("orders_cdf");
    }

    @Test
    void createRejectsMismatchedBodyDatabaseId() {
        var request = new LakebaseCdfController.CreateCdfStreamRequest(
                "db_other", null, "public", "orders", null, "orders_cdf", null, null);

        assertThatThrownBy(() -> service.create(tenant, "db_123", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("database_id must match");
    }

    @Test
    void createRejectsNullRequestBody() {
        assertThatThrownBy(() -> service.create(tenant, "db_123", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("request body required");
    }

    @Test
    void createRejectsUnsupportedMode() {
        var request = new LakebaseCdfController.CreateCdfStreamRequest(
                null, null, "public", "orders", null, "orders_cdf", "CURRENT_STATE", null);

        assertThatThrownBy(() -> service.create(tenant, "db_123", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unsupported CDF mode");
    }

    @Test
    void succeededBackfillMapsToReadableResponse() {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        stream.setBackfillStatus("SUCCEEDED");
        when(repository.findByTenantIdAndDatabaseId("tn_123", "db_123")).thenReturn(List.of(stream));

        List<LakebaseCdfController.CdfStreamResponse> responses = service.list(tenant, "db_123");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).readable()).isTrue();
    }

    @Test
    void setupSqlQuotesIdentifiersAndCreatesPublicationAndSlotSql() {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");

        List<String> sql = service.setupSql(stream);

        assertThat(sql).hasSize(2);
        assertThat(sql.get(0))
                .contains("IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'lakeon_cdf_pub_abcd1234')")
                .contains("EXECUTE format('CREATE PUBLICATION %I FOR TABLE %I.%I', 'lakeon_cdf_pub_abcd1234', 'public', 'orders')");
        assertThat(sql.get(1))
                .contains("DECLARE\n    existing_slot_type text;\n    existing_plugin text;")
                .contains("SELECT slot_type, plugin")
                .contains("INTO existing_slot_type, existing_plugin")
                .contains("IF NOT FOUND THEN")
                .contains("PERFORM pg_create_logical_replication_slot('lakeon_cdf_slot_abcd1234', 'pgoutput')")
                .contains("existing_slot_type <> 'logical' OR existing_plugin IS DISTINCT FROM 'pgoutput'");
        assertThat(sql.get(1)).doesNotContain("DECLARE existing_plugin");
    }

    @Test
    void setupSqlEscapesSingleQuotesInsideIdentifiersForPublicationDoBlock() {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        stream.setSourceSchema("odd'schema");
        stream.setSourceTable("odd'table");

        assertThat(service.setupSql(stream).get(0))
                .contains("EXECUTE format('CREATE PUBLICATION %I FOR TABLE %I.%I', 'lakeon_cdf_pub_abcd1234', 'odd''schema', 'odd''table')");
    }

    @Test
    void setupSqlRejectsInvalidIdentifiersAndLiterals() {
        LakebaseCdfStreamEntity emptyIdentifier = stream("cdf_abcd1234", "PAUSED");
        emptyIdentifier.setSourceTable("");
        LakebaseCdfStreamEntity nullByteIdentifier = stream("cdf_abcd1234", "PAUSED");
        nullByteIdentifier.setPublicationName("bad\u0000pub");
        LakebaseCdfStreamEntity emptyLiteral = stream("cdf_abcd1234", "PAUSED");
        emptyLiteral.setSlotName("");
        LakebaseCdfStreamEntity nullByteLiteral = stream("cdf_abcd1234", "PAUSED");
        nullByteLiteral.setSlotName("bad\u0000slot");

        assertThatThrownBy(() -> service.setupSql(emptyIdentifier))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setupSql(nullByteIdentifier))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setupSql(emptyLiteral))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setupSql(nullByteLiteral))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void teardownSqlDropsPublicationAndSlotIfPresent() {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");

        assertThat(service.teardownSql(stream)).containsExactly(
                "DROP PUBLICATION IF EXISTS \"lakeon_cdf_pub_abcd1234\"",
                "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = 'lakeon_cdf_slot_abcd1234'");
    }

    @Test
    void resumeOpensBranchConnectionEnsuresSchemaExecutesSetupAndSavesRunning() throws SQLException {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        stream.setBackfillStatus("SUCCEEDED");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        List<String> setupSql = service.setupSql(stream);

        LakebaseCdfController.CdfStreamResponse resumed = service.resume(tenant, "db_123", "cdf_abcd1234");

        assertThat(resumed.status()).isEqualTo("RUNNING");
        var inOrder = inOrder(branchConnectionProvider, connection, statement, repository);
        inOrder.verify(branchConnectionProvider).open(tenant, "db_123", "main");
        inOrder.verify(connection).createStatement();
        inOrder.verify(statement).execute(IcebergTenantSchemaManager.schemaSql());
        inOrder.verify(connection).createStatement();
        inOrder.verify(statement).execute(setupSql.get(0));
        inOrder.verify(statement).execute(setupSql.get(1));
        inOrder.verify(repository).save(stream);
        verify(repository).save(stream);
        verify(backfillService, never()).runBackfill(connection, stream);

        LakebaseCdfController.CdfStreamResponse paused = service.pause(tenant, "db_123", "cdf_abcd1234");
        assertThat(paused.status()).isEqualTo("PAUSED");
    }

    @Test
    void resumeWithPendingBackfillRunsBackfillBeforeSavingRunning() throws SQLException {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            LakebaseCdfStreamEntity backfilled = invocation.getArgument(1);
            backfilled.setBackfillStatus("SUCCEEDED");
            backfilled.setBackfillLsn("0/16B6C50");
            backfilled.setStatus("RUNNING");
            return new LakebaseBackfillService.BackfillResult("SUCCEEDED", "0/16B6C50", 1L, 0L);
        }).when(backfillService).runBackfill(connection, stream);

        LakebaseCdfController.CdfStreamResponse resumed = service.resume(tenant, "db_123", "cdf_abcd1234");

        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(resumed.backfill_status()).isEqualTo("SUCCEEDED");
        var inOrder = inOrder(statement, backfillService, repository);
        inOrder.verify(statement).execute(service.setupSql(stream).get(0));
        inOrder.verify(statement).execute(service.setupSql(stream).get(1));
        inOrder.verify(backfillService).runBackfill(connection, stream);
        inOrder.verify(repository).save(stream);
    }

    @Test
    void resumeWithBackfillFailurePersistsFailedBackfillWithoutSavingRunning() throws SQLException {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            LakebaseCdfStreamEntity failed = invocation.getArgument(1);
            failed.setBackfillStatus("BACKFILL_FAILED");
            throw new BadRequestException("initial backfill failed: commit failed");
        }).when(backfillService).runBackfill(connection, stream);

        assertThatThrownBy(() -> service.resume(tenant, "db_123", "cdf_abcd1234"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("initial backfill failed");

        assertThat(stream.getStatus()).isEqualTo("PAUSED");
        assertThat(stream.getBackfillStatus()).isEqualTo("BACKFILL_FAILED");
        verify(repository).save(stream);
    }

    @Test
    void resumeSetupSqlExceptionThrowsBadRequestAndDoesNotSaveRunning() throws SQLException {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "PAUSED");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(eq(service.setupSql(stream).get(0))))
                .thenThrow(new SQLException("publication failed"));

        assertThatThrownBy(() -> service.resume(tenant, "db_123", "cdf_abcd1234"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("failed to setup CDF stream")
                .hasMessageContaining("publication failed");

        assertThat(stream.getStatus()).isEqualTo("PAUSED");
        verify(repository, never()).save(stream);
    }

    @Test
    void repeatedResumeExecutesIdempotentSetupAndConvergesToRunning() throws SQLException {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "RUNNING");
        stream.setBackfillStatus("SUCCEEDED");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        LakebaseCdfController.CdfStreamResponse resumed = service.resume(tenant, "db_123", "cdf_abcd1234");

        assertThat(resumed.status()).isEqualTo("RUNNING");
        verify(statement).execute(service.setupSql(stream).get(0));
        verify(statement).execute(service.setupSql(stream).get(1));
        verify(repository).save(stream);
    }

    @Test
    void exportMaterializesTargetTableAndReturnsStatusAndLocation() throws Exception {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "RUNNING");
        Connection connection = mock(Connection.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        when(exportMaterializer.materialize(connection, "db_123", "main", "public", "orders_cdf"))
                .thenReturn(new IcebergExportMaterializer.MaterializedExport(
                        "MATERIALIZED",
                        "/tmp/orders/export/metadata/00004.metadata.json",
                        "/tmp/orders/export/metadata/00004.manifest-list.json"));

        LakebaseCdfController.ExportResponse response = service.export(tenant, "db_123", "cdf_abcd1234");

        assertThat(response.status()).isEqualTo("MATERIALIZED");
        assertThat(response.metadata_location()).isEqualTo("/tmp/orders/export/metadata/00004.metadata.json");
        assertThat(stream.getExportStatus()).isEqualTo("MATERIALIZED");
        verify(repository).save(stream);
        verify(exportMaterializer).materialize(connection, "db_123", "main", "public", "orders_cdf");
    }

    @Test
    void getExportStatusReturnsCurrentStreamStatusWithoutMaterializing() {
        LakebaseCdfStreamEntity stream = stream("cdf_abcd1234", "RUNNING");
        stream.setExportStatus("NOT_MATERIALIZED");
        Connection connection = mock(Connection.class);
        when(repository.findByIdAndTenantIdAndDatabaseId("cdf_abcd1234", "tn_123", "db_123"))
                .thenReturn(Optional.of(stream));
        try {
            when(branchConnectionProvider.open(tenant, "db_123", "main")).thenReturn(connection);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        when(exportMaterializer.currentExport(connection, "db_123", "main", "public", "orders_cdf"))
                .thenReturn(new IcebergExportMaterializer.ExportStatus("NOT_MATERIALIZED", null));

        LakebaseCdfController.ExportResponse response = service.getExport(tenant, "db_123", "cdf_abcd1234");

        assertThat(response.status()).isEqualTo("NOT_MATERIALIZED");
        assertThat(response.metadata_location()).isNull();
        verify(exportMaterializer, never()).materialize(any(), any(), any(), any(), any());
        verify(exportMaterializer).currentExport(connection, "db_123", "main", "public", "orders_cdf");
    }

    private static LakebaseCdfStreamEntity stream(String id, String status) {
        LakebaseCdfStreamEntity stream = new LakebaseCdfStreamEntity();
        stream.setId(id);
        stream.setTenantId("tn_123");
        stream.setDatabaseId("db_123");
        stream.setBranchId("main");
        stream.setSourceSchema("public");
        stream.setSourceTable("orders");
        stream.setTargetNamespace("public");
        stream.setTargetTable("orders_cdf");
        stream.setMode("APPEND_CHANGELOG");
        stream.setStatus(status);
        stream.setBackfillStatus("PENDING");
        stream.setBackfillLsn(null);
        stream.setSlotName("lakeon_cdf_slot_abcd1234");
        stream.setPublicationName("lakeon_cdf_pub_abcd1234");
        stream.setExportStatus("NOT_MATERIALIZED");
        return stream;
    }
}
