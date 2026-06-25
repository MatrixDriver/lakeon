package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IcebergMetadataJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initialMetadataContainsRequiredIcebergFields() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "struct",
                  "fields": [
                    {"id": 1, "name": "id", "required": true, "type": "long"}
                  ]
                }
                """);

        JsonNode metadata = IcebergMetadataJson.initialMetadata(
                mapper,
                "table-123",
                "obs://lakeon/warehouse/table-123",
                schema
        );

        assertThat(metadata.path("format-version").asInt()).isEqualTo(2);
        assertThat(metadata.path("table-uuid").asText()).isEqualTo("table-123");
        assertThat(metadata.path("location").asText()).startsWith("obs://");
        assertThat(metadata.path("last-sequence-number").asInt()).isZero();
        assertThat(metadata.path("last-updated-ms").asLong()).isPositive();
        assertThat(metadata.path("last-column-id").asInt()).isEqualTo(1);
        assertThat(metadata.path("partition-specs").path(0).path("spec-id").asInt()).isZero();
        assertThat(metadata.path("partition-specs").path(0).path("fields")).isEmpty();
        assertThat(metadata.path("default-spec-id").asInt()).isZero();
        assertThat(metadata.path("last-partition-id").asInt()).isZero();
        assertThat(metadata.path("sort-orders").path(0).path("order-id").asInt()).isZero();
        assertThat(metadata.path("sort-orders").path(0).path("fields")).isEmpty();
        assertThat(metadata.path("default-sort-order-id").asInt()).isZero();
        assertThat(metadata.path("snapshots")).isEmpty();
        assertThat(metadata.path("snapshot-log")).isEmpty();
        assertThat(metadata.path("metadata-log")).isEmpty();
        assertThat(metadata.path("refs").path("main").path("type").asText()).isEqualTo("branch");
        assertThat(metadata.path("refs").path("main").has("snapshot-id")).isFalse();
    }

    @Test
    void initialMetadataAddsSchemaIdWithoutMutatingInput() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "struct",
                  "fields": [
                    {"id": 1, "name": "id", "required": true, "type": "long"},
                    {"id": 3, "name": "name", "required": false, "type": "string"}
                  ]
                }
                """);
        JsonNode originalSchema = schema.deepCopy();

        JsonNode metadata = IcebergMetadataJson.initialMetadata(
                mapper,
                "table-123",
                "obs://lakeon/warehouse/table-123",
                schema
        );

        assertThat(metadata.path("schemas").path(0).path("schema-id").asInt()).isZero();
        assertThat(metadata.path("current-schema-id").asInt()).isZero();
        assertThat(metadata.path("last-column-id").asInt()).isEqualTo(3);
        assertThat(schema).isEqualTo(originalSchema);
    }

    @Test
    void initialMetadataPreservesExistingSchemaIdAndMarksLakeonManaged() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "schema-id": 7,
                  "type": "struct",
                  "fields": []
                }
                """);

        JsonNode metadata = IcebergMetadataJson.initialMetadata(
                mapper,
                "table-123",
                "obs://lakeon/warehouse/table-123",
                schema
        );

        assertThat(metadata.path("schemas").path(0).path("schema-id").asInt()).isEqualTo(7);
        assertThat(metadata.path("current-schema-id").asInt()).isEqualTo(7);
        assertThat(metadata.path("last-column-id").asInt()).isZero();
        assertThat(metadata.path("properties").path("lakeon.managed").asText()).isEqualTo("true");
    }

    @Test
    void initialMetadataDerivesLastColumnIdFromNestedIcebergTypeIds() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "struct",
                  "fields": [
                    {
                      "id": 1,
                      "name": "profile",
                      "required": false,
                      "type": {
                        "type": "struct",
                        "fields": [
                          {"id": 11, "name": "city", "required": false, "type": "string"}
                        ]
                      }
                    },
                    {
                      "id": 2,
                      "name": "scores",
                      "required": false,
                      "type": {
                        "type": "list",
                        "element-id": 21,
                        "element-required": false,
                        "element": "long"
                      }
                    },
                    {
                      "id": 3,
                      "name": "tags",
                      "required": false,
                      "type": {
                        "type": "map",
                        "key-id": 31,
                        "key": "string",
                        "value-id": 41,
                        "value": {
                          "type": "struct",
                          "fields": [
                            {"id": 51, "name": "weight", "required": false, "type": "double"}
                          ]
                        }
                      }
                    }
                  ]
                }
                """);

        JsonNode metadata = IcebergMetadataJson.initialMetadata(
                mapper,
                "table-123",
                "obs://lakeon/warehouse/table-123",
                schema
        );

        assertThat(metadata.path("last-column-id").asInt()).isEqualTo(51);
    }
}
