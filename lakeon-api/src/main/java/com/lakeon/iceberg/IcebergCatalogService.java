package com.lakeon.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IcebergCatalogService {

    private static final String LOAD_TABLE_SQL = """
            SELECT current_metadata_location, current_metadata_json
            FROM _lakeon_iceberg.tables
            WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
            """;
    private static final String TABLE_ID_SQL = """
            SELECT table_id
            FROM _lakeon_iceberg.tables
            WHERE database_id = ? AND branch_id = ? AND namespace = ? AND table_name = ?
            """;
    private static final String SYNC_PLAN_ID = "sync-20260624-0001";

    private final LakebaseBranchConnectionProvider connectionProvider;
    private final ObjectMapper objectMapper;
    private final IcebergPlanningService planningService;

    public IcebergCatalogService(ObjectProvider<LakebaseBranchConnectionProvider> connectionProvider,
                                 ObjectMapper objectMapper,
                                 IcebergPlanningService planningService) {
        this.connectionProvider = connectionProvider.getIfAvailable(() -> (tenant, databaseId, branchId) -> {
            throw new SQLException("Lakebase branch connection provider is not implemented yet");
        });
        this.objectMapper = objectMapper;
        this.planningService = planningService;
    }

    public Map<String, Object> config(TenantEntity tenant, String databaseId, String branchId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warehouse", warehouse(databaseId, branchId));
        out.put("defaults", Map.of("scan-planning-mode", "server"));
        out.put("endpoints", Map.of(
                "load-table", catalogBasePath(databaseId, branchId) + "/namespaces/{namespace}/tables/{table}",
                "commit-table", catalogBasePath(databaseId, branchId) + "/namespaces/{namespace}/tables/{table}"
        ));
        return out;
    }

    public Map<String, Object> loadTable(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String namespace,
            String table
    ) {
        try (Connection connection = connectionProvider.open(tenant, databaseId, branchId);
             PreparedStatement statement = connection.prepareStatement(LOAD_TABLE_SQL)) {
            statement.setString(1, databaseId);
            statement.setString(2, branchId);
            statement.setString(3, namespace);
            statement.setString(4, table);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("Iceberg table not found: " + namespace + "." + table);
                }

                JsonNode metadata = parseMetadataJson(rs.getString("current_metadata_json"), namespace, table);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("metadata-location", rs.getString("current_metadata_location"));
                out.put("metadata", metadata);
                out.put("config", Map.of(
                        "warehouse", warehouse(databaseId, branchId),
                        "scan-planning-mode", "server"
                ));
                return out;
            }
        } catch (SQLException e) {
            throw new BadRequestException("Failed to load Iceberg table " + namespace + "." + table + ": " + e.getMessage());
        }
    }

    public IcebergPlanningService.PlanTableScanResponse planTableScan(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String namespace,
            String table,
            IcebergPlanningService.PlanTableScanRequest request
    ) {
        String tableId = resolveTableId(tenant, databaseId, branchId, namespace, table);
        return planningService.planTableScan(tenant, databaseId, branchId, tableId, request);
    }

    public IcebergPlanningService.PlanTableScanResponse planTableScanTasks(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String namespace,
            String table,
            FetchPlanTasksRequest request
    ) {
        resolveTableId(tenant, databaseId, branchId, namespace, table);
        throw new BadRequestException("Iceberg async scan task fetch is not supported for synchronous plans");
    }

    public IcebergPlanningService.PlanTableScanResponse getPlan(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String namespace,
            String table,
            String planId
    ) {
        resolveTableId(tenant, databaseId, branchId, namespace, table);
        if (!SYNC_PLAN_ID.equals(planId)) {
            throw new NotFoundException("Iceberg scan plan not found: " + planId);
        }
        return new IcebergPlanningService.PlanTableScanResponse(planId, "completed", List.of());
    }

    private String resolveTableId(
            TenantEntity tenant,
            String databaseId,
            String branchId,
            String namespace,
            String table
    ) {
        try (Connection connection = connectionProvider.open(tenant, databaseId, branchId);
             PreparedStatement statement = connection.prepareStatement(TABLE_ID_SQL)) {
            statement.setString(1, databaseId);
            statement.setString(2, branchId);
            statement.setString(3, namespace);
            statement.setString(4, table);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("Iceberg table not found: " + namespace + "." + table);
                }
                return rs.getString("table_id");
            }
        } catch (SQLException e) {
            throw new BadRequestException("Failed to resolve Iceberg table " + namespace + "." + table + ": " + e.getMessage());
        }
    }

    private JsonNode parseMetadataJson(String rawJson, String namespace, String table) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Failed to parse Iceberg table metadata JSON for " + namespace + "." + table + ": " + e.getOriginalMessage());
        }
    }

    private static String warehouse(String databaseId, String branchId) {
        return "obs://lakeon-managed/iceberg/" + databaseId + "/" + branchId;
    }

    private static String catalogBasePath(String databaseId, String branchId) {
        return "/api/v1/iceberg/catalog/" + databaseId + "/" + branchId + "/v1";
    }

    public record FetchPlanTasksRequest(
            @JsonProperty("plan-task") String planTask
    ) {
    }
}
