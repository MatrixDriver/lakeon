package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IcebergTableBootstrapService {
    private static final String SOURCE_COLUMNS_SQL = """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
            ORDER BY ordinal_position
            """;
    private static final String INSERT_TABLE_SQL = """
            INSERT INTO _lakeon_iceberg.tables
                (table_id, database_id, branch_id, namespace, table_name, table_location,
                 current_metadata_location, current_metadata_json, current_metadata_hash, current_snapshot_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (database_id, branch_id, namespace, table_name) DO NOTHING
            """;

    private final ObjectMapper objectMapper;
    private final String obsBucket;

    public IcebergTableBootstrapService(ObjectMapper objectMapper) {
        this(objectMapper, "lakeon-managed");
    }

    @Autowired
    public IcebergTableBootstrapService(ObjectMapper objectMapper, LakeonProperties properties) {
        this(objectMapper, defaultBucket(properties));
    }

    private IcebergTableBootstrapService(ObjectMapper objectMapper, String obsBucket) {
        this.objectMapper = objectMapper;
        this.obsBucket = obsBucket;
    }

    public void ensureTable(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        String tableId = tableId(stream);
        String tableLocation = tableLocation(stream);
        ObjectNode metadata = metadata(connection, stream, tableId, tableLocation);
        String metadataJson = metadata.toString();
        String metadataLocation = tableLocation + "/metadata/lakeon-lazy-00000.metadata.json";

        try (PreparedStatement statement = connection.prepareStatement(INSERT_TABLE_SQL)) {
            statement.setString(1, tableId);
            statement.setString(2, stream.getDatabaseId());
            statement.setString(3, stream.getBranchId());
            statement.setString(4, stream.getTargetNamespace());
            statement.setString(5, stream.getTargetTable());
            statement.setString(6, tableLocation);
            statement.setString(7, metadataLocation);
            statement.setString(8, metadataJson);
            statement.setString(9, sha256(metadataJson));
            statement.setNull(10, Types.BIGINT);
            statement.executeUpdate();
        }
    }

    private ObjectNode metadata(Connection connection,
                                LakebaseCdfStreamEntity stream,
                                String tableId,
                                String tableLocation) throws SQLException {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("format-version", 2);
        metadata.put("table-uuid", stableTableUuid(tableId));
        metadata.put("location", tableLocation);
        metadata.put("last-sequence-number", 0);
        metadata.put("last-updated-ms", System.currentTimeMillis());
        metadata.put("last-column-id", 0);

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "struct");
        schema.put("schema-id", 0);
        ArrayNode fields = schema.putArray("fields");
        int fieldId = 1;
        try (PreparedStatement statement = connection.prepareStatement(SOURCE_COLUMNS_SQL)) {
            statement.setString(1, stream.getSourceSchema());
            statement.setString(2, stream.getSourceTable());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    addStringField(fields, fieldId++, resultSet.getString("column_name"));
                }
            }
        }
        addStringField(fields, fieldId++, "_lakeon_cdf_op");
        addStringField(fields, fieldId++, "_lakeon_cdf_lsn");
        metadata.put("last-column-id", fieldId - 1);
        metadata.set("schemas", objectMapper.createArrayNode().add(schema));
        metadata.put("current-schema-id", 0);
        metadata.set("partition-specs", objectMapper.createArrayNode().add(partitionSpec()));
        metadata.put("default-spec-id", 0);
        metadata.put("last-partition-id", 999);
        metadata.set("sort-orders", objectMapper.createArrayNode().add(sortOrder()));
        metadata.put("default-sort-order-id", 0);
        metadata.set("properties", objectMapper.createObjectNode());
        metadata.set("snapshots", objectMapper.createArrayNode());
        metadata.set("snapshot-log", objectMapper.createArrayNode());
        metadata.set("metadata-log", objectMapper.createArrayNode());
        return metadata;
    }

    private void addStringField(ArrayNode fields, int id, String name) {
        ObjectNode field = objectMapper.createObjectNode();
        field.put("id", id);
        field.put("name", name);
        field.put("required", false);
        field.put("type", "string");
        fields.add(field);
    }

    private ObjectNode partitionSpec() {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("spec-id", 0);
        spec.set("fields", objectMapper.createArrayNode());
        return spec;
    }

    private ObjectNode sortOrder() {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("order-id", 0);
        order.set("fields", objectMapper.createArrayNode());
        return order;
    }

    private String tableId(LakebaseCdfStreamEntity stream) {
        return stream.getDatabaseId() + "_" + stream.getBranchId() + "_"
                + stream.getTargetNamespace() + "_" + stream.getTargetTable();
    }

    private String stableTableUuid(String tableId) {
        return UUID.nameUUIDFromBytes(tableId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String tableLocation(LakebaseCdfStreamEntity stream) {
        return "obs://" + obsBucket + "/lakeon-managed/iceberg/" + stream.getTenantId() + "/" + stream.getDatabaseId()
                + "/" + stream.getBranchId() + "/" + stream.getTargetNamespace() + "/" + stream.getTargetTable();
    }

    private static String defaultBucket(LakeonProperties properties) {
        if (properties == null || properties.getObs() == null
                || properties.getObs().getBucket() == null
                || properties.getObs().getBucket().isBlank()) {
            return "lakeon-managed";
        }
        return properties.getObs().getBucket();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
