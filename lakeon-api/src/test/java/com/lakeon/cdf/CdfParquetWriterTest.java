package com.lakeon.cdf;

import com.lakeon.obs.LakeonObsClient;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CdfParquetWriterTest {

    @TempDir
    java.nio.file.Path warehouse;

    @Test
    void emptyBatchIsRejectedAndDoesNotWriteFiles() throws IOException {
        CdfParquetWriter writer = new CdfParquetWriter();
        CdfBatch batch = batch(List.of());

        assertThatThrownBy(() -> writer.write(warehouse.toString(), "orders", 42L, batch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");

        java.nio.file.Path dataDir = warehouse.resolve("data");
        assertThat(dataDir).doesNotExist();
    }

    @Test
    void nullRowsAreRejectedAndDoNotWriteFiles() {
        CdfParquetWriter writer = new CdfParquetWriter();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("id", 101L));
        rows.add(null);

        assertThatThrownBy(() -> writer.write(warehouse.toString(), "orders", 42L, batch(rows)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null row");

        assertThat(warehouse.resolve("data")).doesNotExist();
    }

    @Test
    void unsafeTableIdsAreRejectedAndDoNotWriteFiles() {
        CdfParquetWriter writer = new CdfParquetWriter();
        CdfBatch batch = batch(List.of(row("id", 101L)));

        for (String unsafeTableId : List.of("", " ", "../orders", "orders/2026", "orders\\2026", "/tmp/orders", "C:orders")) {
            assertThatThrownBy(() -> writer.write(warehouse.toString(), unsafeTableId, 42L, batch))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tableId");
        }

        assertThat(warehouse.resolve("data")).doesNotExist();
    }

    @Test
    void invalidAvroFieldNamesAreRejectedAndDoNotWriteFiles() {
        CdfParquetWriter writer = new CdfParquetWriter();

        assertThatThrownBy(() -> writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(row("bad-field", 101L)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Avro field name")
                .hasMessageContaining("bad-field");

        assertThat(warehouse.resolve("data")).doesNotExist();
    }

    @Test
    void bigDecimalAndBigIntegerValuesAreWrittenAsStrings() throws IOException {
        CdfParquetWriter writer = new CdfParquetWriter();

        CdfParquetWriter.WrittenDataFile file = writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(row(
                        "amount", new BigDecimal("12.34"),
                        "id", new BigInteger("9223372036854775808")))))
                .get(0);

        GenericRecord record = readRecords(java.nio.file.Path.of(file.path())).get(0);
        assertThat(record.get("amount").toString()).isEqualTo("12.34");
        assertThat(record.get("id").toString()).isEqualTo("9223372036854775808");
        assertThat(file.lowerBounds())
                .containsEntry("amount", "12.34")
                .containsEntry("id", "9223372036854775808");
    }

    @Test
    void mixedIncompatibleFieldTypesAreRejectedAndDoNotWriteFiles() {
        CdfParquetWriter writer = new CdfParquetWriter();

        assertThatThrownBy(() -> writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(row("id", 101L), row("id", "101")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixed incompatible types")
                .hasMessageContaining("id");

        assertThat(warehouse.resolve("data")).doesNotExist();
    }

    @Test
    void writesParquetFileUnderExpectedPathWithActualCountAndSize() throws IOException {
        CdfParquetWriter writer = new CdfParquetWriter();

        List<CdfParquetWriter.WrittenDataFile> files = writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(row("id", 101L, "name", "alfa"), row("id", 102L, "name", "bravo"))));

        assertThat(files).hasSize(1);
        CdfParquetWriter.WrittenDataFile file = files.get(0);
        java.nio.file.Path path = java.nio.file.Path.of(file.path());
        assertThat(path).exists().isRegularFile();
        assertThat(path.getParent()).isEqualTo(warehouse.resolve("data/orders/42"));
        assertThat(path.getFileName().toString()).endsWith(".parquet");
        assertThat(file.recordCount()).isEqualTo(2L);
        assertThat(file.fileSizeBytes()).isEqualTo(Files.size(path)).isPositive();
        assertThat(readRecords(path)).hasSize(2);
    }

    @Test
    void obsWarehouseUploadsParquetAndReturnsObsUri() {
        LakeonObsClient obs = mock(LakeonObsClient.class);
        org.mockito.Mockito.when(obs.bucket()).thenReturn("lakeon-test");
        CdfParquetWriter writer = new CdfParquetWriter(obs);

        List<CdfParquetWriter.WrittenDataFile> files = writer.write(
                "obs://lakeon-test/lakeon-managed/iceberg/tn_1/db_123/br_main/public/orders_cdf",
                "orders_cdf",
                42L,
                batch(List.of(row("id", 101L, "name", "alfa"))));

        assertThat(files).hasSize(1);
        CdfParquetWriter.WrittenDataFile file = files.get(0);
        assertThat(file.path())
                .startsWith("obs://lakeon-test/lakeon-managed/iceberg/tn_1/db_123/br_main/public/orders_cdf/data/orders_cdf/42/part-")
                .endsWith(".parquet");
        assertThat(file.recordCount()).isEqualTo(1L);
        assertThat(file.fileSizeBytes()).isPositive();
        verify(obs).putObjectBytes(
                eq(file.path().substring("obs://lakeon-test/".length())),
                any(byte[].class),
                eq("application/vnd.apache.parquet"));
    }

    @Test
    void computesLowerAndUpperBoundsForComparablePrimitiveFields() {
        CdfParquetWriter writer = new CdfParquetWriter();

        CdfParquetWriter.WrittenDataFile file = writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(
                        row("id", 3L, "price", 12.50D, "active", true, "name", "delta"),
                        row("id", 1L, "price", 9.75D, "active", false, "name", "alpha"),
                        row("id", 2L, "price", 14.00D, "active", true, "name", "charlie"))))
                .get(0);

        assertThat(file.partition()).isEmpty();
        assertThat(file.lowerBounds())
                .containsEntry("id", 1L)
                .containsEntry("price", 9.75D)
                .containsEntry("active", false)
                .containsEntry("name", "alpha");
        assertThat(file.upperBounds())
                .containsEntry("id", 3L)
                .containsEntry("price", 14.00D)
                .containsEntry("active", true)
                .containsEntry("name", "delta");
    }

    @Test
    void schemaUsesStableAlphabeticalFieldOrderingAcrossRowMapOrder() throws IOException {
        CdfParquetWriter writer = new CdfParquetWriter();
        Map<String, Object> first = row("zeta", 1L, "alpha", "a", "middle", true);
        Map<String, Object> second = row("middle", false, "zeta", 2L, "alpha", "b");

        CdfParquetWriter.WrittenDataFile file = writer.write(
                warehouse.toString(),
                "orders",
                42L,
                batch(List.of(first, second)))
                .get(0);

        GenericRecord record = readRecords(java.nio.file.Path.of(file.path())).get(0);
        assertThat(record.getSchema().getFields())
                .extracting(field -> field.name())
                .containsExactly("alpha", "middle", "zeta");
    }

    private static CdfBatch batch(List<Map<String, Object>> rows) {
        return new CdfBatch("cdf_abcd1234", "main", "0/1", "0/10", "insert", rows, 0L);
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static List<GenericRecord> readRecords(java.nio.file.Path file) throws IOException {
        List<GenericRecord> records = new ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                records.add(record);
            }
        }
        return records;
    }
}
