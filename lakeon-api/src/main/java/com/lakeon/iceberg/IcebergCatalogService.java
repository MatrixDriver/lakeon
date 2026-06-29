package com.lakeon.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
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
            SELECT current_metadata_location, current_metadata_json, current_snapshot_id
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
    private final String obsBucket;

    public IcebergCatalogService(LakebaseBranchConnectionProvider connectionProvider,
                                 ObjectMapper objectMapper,
                                 IcebergPlanningService planningService) {
        this(connectionProvider, objectMapper, planningService, "lakeon-managed");
    }

    @Autowired
    public IcebergCatalogService(LakebaseBranchConnectionProvider connectionProvider,
                                 ObjectMapper objectMapper,
                                 IcebergPlanningService planningService,
                                 LakeonProperties properties) {
        this(connectionProvider, objectMapper, planningService, defaultBucket(properties));
    }

    private IcebergCatalogService(LakebaseBranchConnectionProvider connectionProvider,
                                  ObjectMapper objectMapper,
                                  IcebergPlanningService planningService,
                                  String obsBucket) {
        this.connectionProvider = connectionProvider;
        this.objectMapper = objectMapper;
        this.planningService = planningService;
        this.obsBucket = obsBucket;
    }

    public Map<String, Object> config(TenantEntity tenant, String databaseId, String branchId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warehouse", warehouse(tenant, databaseId, branchId));
        out.put("defaults", Map.of("scan-planning-mode", "server"));
        out.put("endpoints", List.of(
                "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
                "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
                "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan"
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
                long currentSnapshotId = rs.getLong("current_snapshot_id");
                if (!rs.wasNull() && metadata instanceof ObjectNode objectMetadata) {
                    objectMetadata.put("current-snapshot-id", currentSnapshotId);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("metadata-location", rs.getString("current_metadata_location"));
                out.put("metadata", metadata);
                out.put("config", Map.of(
                        "warehouse", warehouse(tenant, databaseId, branchId),
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

    private String warehouse(TenantEntity tenant, String databaseId, String branchId) {
        return "obs://" + obsBucket + "/lakeon-managed/iceberg/" + tenant.getId() + "/" + databaseId + "/" + branchId;
    }

    private static String defaultBucket(LakeonProperties properties) {
        if (properties == null || properties.getObs() == null
                || properties.getObs().getBucket() == null
                || properties.getObs().getBucket().isBlank()) {
            return "lakeon-managed";
        }
        return properties.getObs().getBucket();
    }

    public record FetchPlanTasksRequest(
            @JsonProperty("plan-task") String planTask
    ) {
    }
}
