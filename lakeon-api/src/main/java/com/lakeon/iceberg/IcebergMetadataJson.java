package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public final class IcebergMetadataJson {
    private IcebergMetadataJson() {
    }

    public static JsonNode initialMetadata(ObjectMapper mapper, String tableId, String location, JsonNode schema) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(schema, "schema");
        if (!schema.isObject()) {
            throw new IllegalArgumentException("Iceberg schema metadata must be a JSON object");
        }

        ObjectNode schemaNode = schema.deepCopy();
        int schemaId = schemaNode.path("schema-id").asInt(0);
        if (!schemaNode.has("schema-id")) {
            schemaNode.put("schema-id", schemaId);
        }

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("format-version", 2);
        metadata.put("table-uuid", tableId);
        metadata.put("location", location);
        metadata.put("last-sequence-number", 0);
        metadata.put("last-updated-ms", System.currentTimeMillis());
        metadata.put("last-column-id", maxFieldId(schemaNode.path("fields")));

        ArrayNode schemas = metadata.putArray("schemas");
        schemas.add(schemaNode);
        metadata.put("current-schema-id", schemaId);

        ObjectNode partitionSpec = mapper.createObjectNode();
        partitionSpec.put("spec-id", 0);
        partitionSpec.putArray("fields");
        metadata.putArray("partition-specs").add(partitionSpec);
        metadata.put("default-spec-id", 0);
        metadata.put("last-partition-id", 0);

        ObjectNode sortOrder = mapper.createObjectNode();
        sortOrder.put("order-id", 0);
        sortOrder.putArray("fields");
        metadata.putArray("sort-orders").add(sortOrder);
        metadata.put("default-sort-order-id", 0);

        metadata.putArray("snapshots");
        metadata.putArray("snapshot-log");
        metadata.putArray("metadata-log");

        ObjectNode refs = metadata.putObject("refs");
        refs.putObject("main").put("type", "branch");

        metadata.putObject("properties").put("lakeon.managed", "true");
        return metadata;
    }

    private static int maxFieldId(JsonNode fields) {
        int max = 0;
        if (!fields.isArray()) {
            return max;
        }
        for (JsonNode field : fields) {
            max = Math.max(max, fieldId(field, "id"));
            max = Math.max(max, maxTypeFieldId(field.path("type")));
        }
        return max;
    }

    private static int maxTypeFieldId(JsonNode type) {
        if (!type.isObject()) {
            return 0;
        }

        int max = 0;
        max = Math.max(max, fieldId(type, "element-id"));
        max = Math.max(max, fieldId(type, "key-id"));
        max = Math.max(max, fieldId(type, "value-id"));
        max = Math.max(max, maxFieldId(type.path("fields")));
        max = Math.max(max, maxTypeFieldId(type.path("element")));
        max = Math.max(max, maxTypeFieldId(type.path("key")));
        max = Math.max(max, maxTypeFieldId(type.path("value")));
        return max;
    }

    private static int fieldId(JsonNode node, String fieldName) {
        return node.has(fieldName) ? node.path(fieldName).asInt(0) : 0;
    }
}
