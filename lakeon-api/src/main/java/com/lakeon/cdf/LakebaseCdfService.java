package com.lakeon.cdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.iceberg.IcebergTenantSchemaManager;
import com.lakeon.iceberg.IcebergExportMaterializer;
import com.lakeon.iceberg.IcebergTableBootstrapService;
import com.lakeon.iceberg.LakebaseBranchConnectionProvider;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.LakebaseCdfStreamRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

@Service
public class LakebaseCdfService {

    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_SCHEMA = "public";
    private static final String DEFAULT_MODE = "APPEND_CHANGELOG";
    private static final String DEFAULT_STATUS = "PAUSED";
    private static final String DEFAULT_BACKFILL_STATUS = "PENDING";
    private static final String DEFAULT_EXPORT_STATUS = "NOT_MATERIALIZED";

    private final LakebaseCdfStreamRepository repository;
    private final LakebaseBranchConnectionProvider branchConnectionProvider;
    private final IcebergTenantSchemaManager schemaManager;
    private final IcebergTableBootstrapService tableBootstrapService;
    private final LakebaseBackfillService backfillService;
    private final IcebergExportMaterializer exportMaterializer;

    @Autowired
    public LakebaseCdfService(LakebaseCdfStreamRepository repository,
                              LakebaseBranchConnectionProvider branchConnectionProvider,
                              IcebergTableBootstrapService tableBootstrapService,
                              LakebaseBackfillService backfillService,
                              IcebergExportMaterializer exportMaterializer) {
        this(
                repository,
                branchConnectionProvider,
                new IcebergTenantSchemaManager(),
                tableBootstrapService,
                backfillService,
                exportMaterializer);
    }

    LakebaseCdfService(LakebaseCdfStreamRepository repository,
                       LakebaseBranchConnectionProvider branchConnectionProvider,
                       IcebergTenantSchemaManager schemaManager,
                       IcebergTableBootstrapService tableBootstrapService,
                       LakebaseBackfillService backfillService,
                       IcebergExportMaterializer exportMaterializer) {
        this.repository = repository;
        this.branchConnectionProvider = branchConnectionProvider;
        this.schemaManager = schemaManager;
        this.tableBootstrapService = tableBootstrapService;
        this.backfillService = backfillService;
        this.exportMaterializer = exportMaterializer == null
                ? new IcebergExportMaterializer(new ObjectMapper())
                : exportMaterializer;
    }

    public LakebaseCdfController.CdfStreamResponse create(TenantEntity tenant,
                                                          String databaseId,
                                                          LakebaseCdfController.CreateCdfStreamRequest request) {
        if (request == null) {
            throw new BadRequestException("request body required");
        }
        String requestDatabaseId = normalize(request.database_id());
        if (requestDatabaseId != null && !requestDatabaseId.equals(databaseId)) {
            throw new BadRequestException("database_id must match path databaseId");
        }

        String branchId = defaultString(request.branch_id(), DEFAULT_BRANCH);
        String sourceSchema = defaultString(request.source_schema(), DEFAULT_SCHEMA);
        String targetNamespace = defaultString(request.target_namespace(), sourceSchema);
        String mode = defaultString(request.mode(), DEFAULT_MODE);
        if (!DEFAULT_MODE.equals(mode)) {
            throw new BadRequestException("unsupported CDF mode: " + mode);
        }
        String sourceTable = requireString(request.source_table(), "source_table");
        String targetTable = requireString(request.target_table(), "target_table");

        repository.findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
                        tenant.getId(), databaseId, branchId, targetNamespace, targetTable)
                .ifPresent(existing -> {
                    throw new ConflictException("CDF stream target already exists");
                });

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        LakebaseCdfStreamEntity stream = new LakebaseCdfStreamEntity();
        stream.setId("cdf_" + shortId);
        stream.setTenantId(tenant.getId());
        stream.setDatabaseId(databaseId);
        stream.setBranchId(branchId);
        stream.setSourceSchema(sourceSchema);
        stream.setSourceTable(sourceTable);
        stream.setTargetNamespace(targetNamespace);
        stream.setTargetTable(targetTable);
        stream.setMode(mode);
        stream.setStatus(DEFAULT_STATUS);
        stream.setBackfillStatus(DEFAULT_BACKFILL_STATUS);
        stream.setBackfillLsn(null);
        stream.setExportStatus(DEFAULT_EXPORT_STATUS);
        stream.setPublicationName("lakeon_cdf_pub_" + shortId);
        stream.setSlotName("lakeon_cdf_slot_" + shortId);

        return toResponse(repository.save(stream));
    }

    public List<LakebaseCdfController.CdfStreamResponse> list(TenantEntity tenant, String databaseId) {
        return repository.findByTenantIdAndDatabaseId(tenant.getId(), databaseId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public LakebaseCdfController.CdfStreamResponse resume(TenantEntity tenant,
                                                          String databaseId,
                                                          String streamId) {
        LakebaseCdfStreamEntity stream = findStream(tenant, databaseId, streamId);
        try (Connection connection = branchConnectionProvider.open(tenant, databaseId, stream.getBranchId())) {
            schemaManager.ensureSchema(connection);
            tableBootstrapService.ensureTable(connection, stream);
            try (Statement statement = connection.createStatement()) {
                for (String sql : setupSql(stream)) {
                    statement.execute(sql);
                }
            }
            if (!"SUCCEEDED".equals(stream.getBackfillStatus())) {
                try {
                    backfillService.runBackfill(connection, stream);
                } catch (BadRequestException e) {
                    repository.save(stream);
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new BadRequestException("failed to setup CDF stream resume resources: " + e.getMessage());
        }
        stream.setStatus("RUNNING");
        return toResponse(repository.save(stream));
    }

    public LakebaseCdfController.CdfStreamResponse pause(TenantEntity tenant,
                                                         String databaseId,
                                                         String streamId) {
        LakebaseCdfStreamEntity stream = findStream(tenant, databaseId, streamId);
        stream.setStatus("PAUSED");
        return toResponse(repository.save(stream));
    }

    public LakebaseCdfController.ExportResponse export(TenantEntity tenant,
                                                       String databaseId,
                                                       String streamId) {
        LakebaseCdfStreamEntity stream = findStream(tenant, databaseId, streamId);
        try (Connection connection = branchConnectionProvider.open(tenant, databaseId, stream.getBranchId())) {
            IcebergExportMaterializer.MaterializedExport export = exportMaterializer.materialize(
                    connection,
                    databaseId,
                    stream.getBranchId(),
                    stream.getTargetNamespace(),
                    stream.getTargetTable());
            stream.setExportStatus(export.status());
            repository.save(stream);
            return new LakebaseCdfController.ExportResponse(export.status(), export.metadata_location());
        } catch (SQLException e) {
            throw new BadRequestException("failed to materialize CDF export: " + e.getMessage());
        }
    }

    public LakebaseCdfController.ExportResponse getExport(TenantEntity tenant,
                                                          String databaseId,
                                                          String streamId) {
        LakebaseCdfStreamEntity stream = findStream(tenant, databaseId, streamId);
        try (Connection connection = branchConnectionProvider.open(tenant, databaseId, stream.getBranchId())) {
            IcebergExportMaterializer.ExportStatus export = exportMaterializer.currentExport(
                    connection,
                    databaseId,
                    stream.getBranchId(),
                    stream.getTargetNamespace(),
                    stream.getTargetTable());
            return new LakebaseCdfController.ExportResponse(export.status(), export.metadata_location());
        } catch (SQLException e) {
            throw new BadRequestException("failed to read CDF export status: " + e.getMessage());
        }
    }

    public List<String> setupSql(LakebaseCdfStreamEntity stream) {
        String publicationName = quoteLiteral(stream.getPublicationName());
        String sourceSchema = quoteLiteral(stream.getSourceSchema());
        String sourceTable = quoteLiteral(stream.getSourceTable());
        String slotName = quoteLiteral(stream.getSlotName());
        return List.of(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = %s) THEN
                        EXECUTE format('CREATE PUBLICATION %%I FOR TABLE %%I.%%I', %s, %s, %s);
                    END IF;
                END
                $$""".formatted(publicationName, publicationName, sourceSchema, sourceTable),
                """
                DO $$
                DECLARE
                    existing_slot_type text;
                    existing_plugin text;
                BEGIN
                    SELECT slot_type, plugin
                    INTO existing_slot_type, existing_plugin
                    FROM pg_replication_slots
                    WHERE slot_name = %s;
                    IF NOT FOUND THEN
                        PERFORM pg_create_logical_replication_slot(%s, 'pgoutput');
                    ELSIF existing_slot_type <> 'logical' OR existing_plugin IS DISTINCT FROM 'pgoutput' THEN
                        RAISE EXCEPTION 'replication slot %% exists with incompatible type/plugin %%/%%',
                            %s, existing_slot_type, existing_plugin;
                    END IF;
                END
                $$""".formatted(slotName, slotName, slotName));
    }

    public List<String> teardownSql(LakebaseCdfStreamEntity stream) {
        return List.of(
                "DROP PUBLICATION IF EXISTS " + quoteIdentifier(stream.getPublicationName()),
                "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = "
                        + quoteLiteral(stream.getSlotName()));
    }

    private LakebaseCdfStreamEntity findStream(TenantEntity tenant, String databaseId, String streamId) {
        return repository.findByIdAndTenantIdAndDatabaseId(streamId, tenant.getId(), databaseId)
                .orElseThrow(() -> new NotFoundException("CDF stream not found: " + streamId));
    }

    private LakebaseCdfController.CdfStreamResponse toResponse(LakebaseCdfStreamEntity stream) {
        return new LakebaseCdfController.CdfStreamResponse(
                stream.getId(),
                stream.getDatabaseId(),
                stream.getBranchId(),
                stream.getSourceSchema(),
                stream.getSourceTable(),
                stream.getTargetNamespace(),
                stream.getTargetTable(),
                stream.getMode(),
                stream.getStatus(),
                stream.getBackfillStatus(),
                stream.getBackfillLsn(),
                stream.getSlotName(),
                stream.getPublicationName(),
                stream.getExportStatus(),
                "SUCCEEDED".equals(stream.getBackfillStatus()));
    }

    private static String defaultString(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private static String requireString(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BadRequestException(field + " required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private static String quoteLiteral(String value) {
        if (value == null || value.isEmpty()) {
            throw new BadRequestException("SQL literal must not be empty");
        }
        if (value.indexOf('\0') >= 0) {
            throw new BadRequestException("SQL literal must not contain null byte");
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
