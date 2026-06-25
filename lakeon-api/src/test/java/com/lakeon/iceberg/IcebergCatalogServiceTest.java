package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IcebergCatalogServiceTest {

    private final LakebaseBranchConnectionProvider connectionProvider = mock(LakebaseBranchConnectionProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IcebergPlanningService planningService = mock(IcebergPlanningService.class);
    private final TenantEntity tenant = new TenantEntity();
    private IcebergCatalogService service;

    @BeforeEach
    void setUp() {
        service = new IcebergCatalogService(connectionProvider, objectMapper, planningService);
    }


    @Test
    void configReturnsWarehouseDefaultsAndEndpoints() {
        Map<String, Object> result = service.config(tenant, "db_123", "br_main");

        Map<?, ?> defaults = (Map<?, ?>) result.get("defaults");
        Map<?, ?> endpoints = (Map<?, ?>) result.get("endpoints");

        assertThat(result).containsEntry("warehouse", "obs://lakeon-managed/iceberg/db_123/br_main");
        assertThat(defaults.get("scan-planning-mode")).isEqualTo("server");
        assertThat(endpoints.get("load-table")).isEqualTo("/api/v1/iceberg/catalog/db_123/br_main/v1/namespaces/{namespace}/tables/{table}");
        assertThat(endpoints.get("commit-table")).isEqualTo("/api/v1/iceberg/catalog/db_123/br_main/v1/namespaces/{namespace}/tables/{table}");
    }

    @Test
    void loadTableReturnsMetadataLocationMetadataAndScopedConfig() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT current_metadata_location, current_metadata_json, current_snapshot_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("current_metadata_location")).thenReturn("obs://lakeon-managed/metadata/v1.json");
        when(resultSet.getString("current_metadata_json")).thenReturn("{\"format-version\":2,\"table-uuid\":\"tbl-1\"}");
        when(resultSet.getLong("current_snapshot_id")).thenReturn(42L);
        when(resultSet.wasNull()).thenReturn(false);

        Map<String, Object> result = service.loadTable(tenant, "db_123", "br_main", "sales", "orders");
        Map<?, ?> config = (Map<?, ?>) result.get("config");

        assertThat(result).containsEntry("metadata-location", "obs://lakeon-managed/metadata/v1.json");
        assertThat(result.get("metadata")).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) result.get("metadata")).isObject()).isTrue();
        assertThat(((JsonNode) result.get("metadata")).get("table-uuid").asText()).isEqualTo("tbl-1");
        assertThat(((JsonNode) result.get("metadata")).get("current-snapshot-id").asLong()).isEqualTo(42L);
        assertThat(config.get("warehouse")).isEqualTo("obs://lakeon-managed/iceberg/db_123/br_main");
        assertThat(config.get("scan-planning-mode")).isEqualTo("server");
        verify(statement).setString(1, "db_123");
        verify(statement).setString(2, "br_main");
        verify(statement).setString(3, "sales");
        verify(statement).setString(4, "orders");
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void missingTableThrowsNotFoundException() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT current_metadata_location, current_metadata_json, current_snapshot_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThatThrownBy(() -> service.loadTable(tenant, "db_123", "br_main", "sales", "orders"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Iceberg table not found");
    }

    @Test
    void invalidJsonThrowsBadRequestException() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT current_metadata_location, current_metadata_json, current_snapshot_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("current_metadata_json")).thenReturn("{not-json");

        assertThatThrownBy(() -> service.loadTable(tenant, "db_123", "br_main", "sales", "orders"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Failed to parse Iceberg table metadata JSON");
    }

    @Test
    void planTableScanResolvesRegisteredTableIdAndDelegatesToPlanningService() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        IcebergPlanningService.PlanTableScanRequest request =
                new IcebergPlanningService.PlanTableScanRequest(
                        List.of("id", "region"),
                        objectMapper.readTree("{\"op\":\"=\",\"field\":\"region\",\"value\":\"us\"}"),
                        true,
                        42L,
                        null,
                        List.of("region")
                );
        IcebergPlanningService.PlanTableScanResponse expected =
                new IcebergPlanningService.PlanTableScanResponse("sync-20260624-0001", "completed", List.of());
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("table_id")).thenReturn("tbl_orders");
        when(planningService.planTableScan(tenant, "db_123", "br_main", "tbl_orders", request))
                .thenReturn(expected);

        IcebergPlanningService.PlanTableScanResponse response =
                service.planTableScan(tenant, "db_123", "br_main", "sales", "orders", request);

        assertThat(response).isSameAs(expected);
        verify(statement).setString(1, "db_123");
        verify(statement).setString(2, "br_main");
        verify(statement).setString(3, "sales");
        verify(statement).setString(4, "orders");
        verify(planningService).planTableScan(tenant, "db_123", "br_main", "tbl_orders", request);
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void planTasksValidatesTableScopeThenRejectsAsyncTaskFetchWithoutPlanning() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        IcebergCatalogService.FetchPlanTasksRequest request =
                new IcebergCatalogService.FetchPlanTasksRequest("sync-20260624-0001/task-1");
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("table_id")).thenReturn("tbl_orders");

        assertThatThrownBy(() ->
                service.planTableScanTasks(tenant, "db_123", "br_main", "sales", "orders", request)
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Iceberg async scan task fetch is not supported for synchronous plans");

        verify(statement).setString(1, "db_123");
        verify(statement).setString(2, "br_main");
        verify(statement).setString(3, "sales");
        verify(statement).setString(4, "orders");
        verify(planningService, never()).planTableScan(
                any(TenantEntity.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(IcebergPlanningService.PlanTableScanRequest.class)
        );
    }

    @Test
    void planTasksRejectsUnknownTableBeforeUnsupportedTaskFetch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        IcebergCatalogService.FetchPlanTasksRequest request =
                new IcebergCatalogService.FetchPlanTasksRequest("sync-20260624-0001/task-1");
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThatThrownBy(() ->
                service.planTableScanTasks(tenant, "db_123", "br_main", "sales", "missing", request)
        )
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Iceberg table not found");

        verify(planningService, never()).planTableScan(
                any(TenantEntity.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(IcebergPlanningService.PlanTableScanRequest.class)
        );
    }

    @Test
    void completedPlanLookupValidatesTableScopeBeforeReturningSynchronousStatus() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("table_id")).thenReturn("tbl_orders");

        IcebergPlanningService.PlanTableScanResponse response =
                service.getPlan(tenant, "db_123", "br_main", "sales", "orders", "sync-20260624-0001");

        assertThat(response.planId()).isEqualTo("sync-20260624-0001");
        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.fileScanTasks()).isEmpty();
        verify(statement).setString(1, "db_123");
        verify(statement).setString(2, "br_main");
        verify(statement).setString(3, "sales");
        verify(statement).setString(4, "orders");
    }

    @Test
    void completedPlanLookupRejectsUnknownTableBeforePlanIdCheck() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThatThrownBy(() ->
                service.getPlan(tenant, "db_123", "br_main", "sales", "missing", "sync-20260624-0001")
        )
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Iceberg table not found");
    }

    @Test
    void unknownPlanLookupThrowsNotFoundExceptionBecausePlansAreNotPersisted() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connectionProvider.open(tenant, "db_123", "br_main")).thenReturn(connection);
        when(connection.prepareStatement("""
                SELECT table_id
                FROM _lakeon_iceberg.tables
                WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
                """)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("table_id")).thenReturn("tbl_orders");

        assertThatThrownBy(() ->
                service.getPlan(tenant, "db_123", "br_main", "sales", "orders", "missing-plan")
        )
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Iceberg scan plan not found");
    }

    @Test
    void controllerPlanEndpointPreservesTenantAndDelegatesToCatalogService() throws Exception {
        IcebergCatalogService catalogService = mock(IcebergCatalogService.class);
        IcebergCatalogController controller = new IcebergCatalogController(catalogService);
        MockHttpServletRequest request = authenticatedRequest();
        IcebergPlanningService.PlanTableScanRequest body =
                new IcebergPlanningService.PlanTableScanRequest(null, null, true, 42L, null, null);
        IcebergPlanningService.PlanTableScanResponse expected =
                new IcebergPlanningService.PlanTableScanResponse("sync-20260624-0001", "completed", List.of());
        when(catalogService.planTableScan(
                same(tenant),
                org.mockito.ArgumentMatchers.eq("db_123"),
                org.mockito.ArgumentMatchers.eq("br_main"),
                org.mockito.ArgumentMatchers.eq("sales"),
                org.mockito.ArgumentMatchers.eq("orders"),
                same(body)
        )).thenReturn(expected);

        IcebergPlanningService.PlanTableScanResponse response =
                controller.planTableScan(request, "db_123", "br_main", "sales", "orders", body);

        assertThat(response).isSameAs(expected);
        verify(catalogService).planTableScan(tenant, "db_123", "br_main", "sales", "orders", body);
    }

    @Test
    void controllerTasksEndpointPreservesTenantAndDelegatesToCatalogService() {
        IcebergCatalogService catalogService = mock(IcebergCatalogService.class);
        IcebergCatalogController controller = new IcebergCatalogController(catalogService);
        MockHttpServletRequest request = authenticatedRequest();
        IcebergCatalogService.FetchPlanTasksRequest body =
                new IcebergCatalogService.FetchPlanTasksRequest("sync-20260624-0001/task-1");
        IcebergPlanningService.PlanTableScanResponse expected =
                new IcebergPlanningService.PlanTableScanResponse("sync-20260624-0001", "completed", List.of());
        when(catalogService.planTableScanTasks(tenant, "db_123", "br_main", "sales", "orders", body))
                .thenReturn(expected);

        IcebergPlanningService.PlanTableScanResponse response =
                controller.planTableScanTasks(request, "db_123", "br_main", "sales", "orders", body);

        assertThat(response).isSameAs(expected);
        verify(catalogService).planTableScanTasks(tenant, "db_123", "br_main", "sales", "orders", body);
    }

    @Test
    void controllerGetPlanEndpointPassesTenantAndCatalogScopeToService() {
        IcebergCatalogService catalogService = mock(IcebergCatalogService.class);
        IcebergCatalogController controller = new IcebergCatalogController(catalogService);
        MockHttpServletRequest request = authenticatedRequest();
        IcebergPlanningService.PlanTableScanResponse expected =
                new IcebergPlanningService.PlanTableScanResponse("sync-20260624-0001", "completed", List.of());
        when(catalogService.getPlan(tenant, "db_123", "br_main", "sales", "orders", "sync-20260624-0001"))
                .thenReturn(expected);

        IcebergPlanningService.PlanTableScanResponse response =
                controller.getPlan(request, "db_123", "br_main", "sales", "orders", "sync-20260624-0001");

        assertThat(response).isSameAs(expected);
        verify(catalogService).getPlan(tenant, "db_123", "br_main", "sales", "orders", "sync-20260624-0001");
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("tenant", tenant);
        return request;
    }
}
