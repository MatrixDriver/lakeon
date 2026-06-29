package com.lakeon.cdf;

import com.lakeon.obs.LakeonObsClient;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class CdfParquetWriter {
    private static final String PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet";

    private final LakeonObsClient obsClient;

    public CdfParquetWriter() {
        this((LakeonObsClient) null);
    }

    @Autowired
    public CdfParquetWriter(ObjectProvider<LakeonObsClient> obsClientProvider) {
        this(obsClientProvider.getIfAvailable());
    }

    CdfParquetWriter(LakeonObsClient obsClient) {
        this.obsClient = obsClient;
    }

    public List<WrittenDataFile> write(String warehousePrefix, String tableId, long snapshotId, CdfBatch batch) {
        Objects.requireNonNull(warehousePrefix, "warehousePrefix must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        Objects.requireNonNull(batch, "batch must not be null");
        if (batch.rows() == null || batch.rows().isEmpty()) {
            throw new IllegalArgumentException("CDF batch rows must not be empty");
        }

        List<Map<String, Object>> rows = batch.rows();
        validateTableId(tableId);
        Map<String, FieldType> fieldTypes = inferFieldTypes(rows);
        Schema schema = inferSchema(tableId, fieldTypes);
        if (warehousePrefix.startsWith("obs://")) {
            return writeObs(warehousePrefix, tableId, snapshotId, rows, schema);
        }
        return writeLocal(warehousePrefix, tableId, snapshotId, rows, schema);
    }

    private List<WrittenDataFile> writeLocal(
            String warehousePrefix,
            String tableId,
            long snapshotId,
            List<Map<String, Object>> rows,
            Schema schema) {
        Path outputDir = Path.of(warehousePrefix).resolve("data").resolve(tableId).resolve(Long.toString(snapshotId));
        Path outputFile = outputDir.resolve("part-" + UUID.randomUUID() + ".parquet");

        try {
            Files.createDirectories(outputDir);
            writeParquet(outputFile, schema, rows);
            return List.of(new WrittenDataFile(
                    outputFile.toString(),
                    rows.size(),
                    Files.size(outputFile),
                    Map.of(),
                    computeBounds(rows, true),
                    computeBounds(rows, false)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to write CDF Parquet data file: " + e.getMessage(), e);
        }
    }

    private List<WrittenDataFile> writeObs(
            String warehousePrefix,
            String tableId,
            long snapshotId,
            List<Map<String, Object>> rows,
            Schema schema) {
        if (obsClient == null) {
            throw new IllegalStateException("OBS client not configured; cannot write CDF Parquet data file to " + warehousePrefix);
        }
        URI warehouseUri = URI.create(warehousePrefix);
        String bucket = warehouseUri.getHost();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("OBS warehouse URI must include bucket: " + warehousePrefix);
        }
        if (!bucket.equals(obsClient.bucket())) {
            throw new IllegalArgumentException("OBS warehouse bucket '" + bucket
                    + "' does not match configured bucket '" + obsClient.bucket() + "'");
        }

        String relativePath = "data/" + tableId + "/" + snapshotId + "/part-" + UUID.randomUUID() + ".parquet";
        String baseKey = stripLeadingSlash(warehouseUri.getPath());
        String objectKey = baseKey.isBlank() ? relativePath : baseKey + "/" + relativePath;
        String objectUri = "obs://" + bucket + "/" + objectKey;
        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("lakeon-cdf-parquet-");
            tempFile = tempDir.resolve("part.parquet");
            writeParquet(tempFile, schema, rows);
            byte[] body = Files.readAllBytes(tempFile);
            obsClient.putObjectBytes(objectKey, body, PARQUET_CONTENT_TYPE);
            return List.of(new WrittenDataFile(
                    objectUri,
                    rows.size(),
                    body.length,
                    Map.of(),
                    computeBounds(rows, true),
                    computeBounds(rows, false)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to write CDF Parquet data file: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
        }
    }

    private static void writeParquet(Path outputFile, Schema schema, List<Map<String, Object>> rows) throws IOException {
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(new LocalOutputFile(outputFile))
                .withSchema(schema)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {
            for (Map<String, Object> row : rows) {
                writer.write(toRecord(schema, row));
            }
        }
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) {
            return "";
        }
        String out = path;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        return out;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void validateTableId(String tableId) {
        if (tableId.isBlank()
                || tableId.contains("/")
                || tableId.contains("\\")
                || tableId.contains("..")
                || tableId.contains(":")
                || Path.of(tableId).isAbsolute()) {
            throw new IllegalArgumentException("unsafe tableId '" + tableId + "'");
        }
    }

    private static Map<String, FieldType> inferFieldTypes(List<Map<String, Object>> rows) {
        Map<String, FieldType> fieldTypes = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("CDF batch contains a null row");
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                validateAvroFieldName(entry.getKey());
                FieldType valueType = fieldTypeFor(entry.getKey(), entry.getValue());
                if (valueType == null) {
                    continue;
                }
                FieldType existing = fieldTypes.putIfAbsent(entry.getKey(), valueType);
                if (existing != null && existing != valueType) {
                    throw new IllegalArgumentException(
                            "field '" + entry.getKey() + "' has mixed incompatible types: "
                                    + existing.displayName() + " and " + valueType.displayName());
                }
            }
        }
        if (fieldTypes.isEmpty()) {
            throw new IllegalArgumentException("CDF batch rows must contain at least one field");
        }
        return fieldTypes;
    }

    private static void validateAvroFieldName(String fieldName) {
        if (fieldName == null || !fieldName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid Avro field name '" + fieldName + "'");
        }
    }

    private static Schema inferSchema(String tableId, Map<String, FieldType> fieldTypes) {
        List<Schema.Field> fields = new ArrayList<>();
        for (String fieldName : new TreeSet<>(fieldTypes.keySet())) {
            Schema fieldSchema = nullableSchema(fieldTypes.get(fieldName).schema());
            fields.add(new Schema.Field(fieldName, fieldSchema, null, Schema.Field.NULL_DEFAULT_VALUE));
        }
        Schema schema = Schema.createRecord(sanitizeName(tableId) + "_cdf_record", null, "com.lakeon.cdf", false);
        schema.setFields(fields);
        return schema;
    }

    private static Schema nullableSchema(Schema schema) {
        return Schema.createUnion(List.of(Schema.create(Schema.Type.NULL), schema));
    }

    private static FieldType fieldTypeFor(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return FieldType.INT;
        }
        if (value instanceof Long) {
            return FieldType.LONG;
        }
        if (value instanceof Float) {
            return FieldType.FLOAT;
        }
        if (value instanceof Double) {
            return FieldType.DOUBLE;
        }
        if (value instanceof Boolean) {
            return FieldType.BOOLEAN;
        }
        if (value instanceof String || value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof Instant) {
            return FieldType.STRING;
        }
        if (value instanceof BigDecimal) {
            return FieldType.STRING;
        }
        if (value instanceof BigInteger) {
            return FieldType.STRING;
        }
        throw new IllegalArgumentException(
                "field '" + fieldName + "' contains unsupported value type " + value.getClass().getName());
    }

    private static GenericRecord toRecord(Schema schema, Map<String, Object> row) {
        GenericRecord record = new GenericData.Record(schema);
        for (Schema.Field field : schema.getFields()) {
            Object value = row == null ? null : row.get(field.name());
            record.put(field.name(), coerceValue(value));
        }
        return record;
    }

    private static Object coerceValue(Object value) {
        if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        return value;
    }

    private static Map<String, Object> computeBounds(List<Map<String, Object>> rows, boolean lower) {
        Map<String, Object> bounds = new LinkedHashMap<>();
        TreeSet<String> fieldNames = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            if (row != null) {
                fieldNames.addAll(row.keySet());
            }
        }

        for (String fieldName : fieldNames) {
            Object bound = null;
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                Object value = comparablePrimitiveValue(fieldName, row.get(fieldName));
                if (value == null) {
                    continue;
                }
                if (bound == null || compare(value, bound) * (lower ? 1 : -1) < 0) {
                    bound = value;
                }
            }
            if (bound != null) {
                bounds.put(fieldName, bound);
            }
        }
        return Collections.unmodifiableMap(bounds);
    }

    private static Object comparablePrimitiveValue(String fieldName, Object value) {
        if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) {
        return ((Comparable) left).compareTo(right);
    }

    private static String sanitizeName(String tableId) {
        String sanitized = tableId.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank() || Character.isDigit(sanitized.charAt(0))) {
            return "table_" + sanitized;
        }
        return sanitized;
    }

    public record WrittenDataFile(
            String path,
            long recordCount,
            long fileSizeBytes,
            Map<String, Object> partition,
            Map<String, Object> lowerBounds,
            Map<String, Object> upperBounds) {
    }

    private enum FieldType {
        INT(Schema.Type.INT, "int"),
        LONG(Schema.Type.LONG, "long"),
        FLOAT(Schema.Type.FLOAT, "float"),
        DOUBLE(Schema.Type.DOUBLE, "double"),
        BOOLEAN(Schema.Type.BOOLEAN, "boolean"),
        STRING(Schema.Type.STRING, "string");

        private final Schema.Type schemaType;
        private final String displayName;

        FieldType(Schema.Type schemaType, String displayName) {
            this.schemaType = schemaType;
            this.displayName = displayName;
        }

        Schema schema() {
            return Schema.create(schemaType);
        }

        String displayName() {
            return displayName;
        }
    }
}
