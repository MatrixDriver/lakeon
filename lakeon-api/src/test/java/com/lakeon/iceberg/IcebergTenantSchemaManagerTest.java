package com.lakeon.iceberg;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class IcebergTenantSchemaManagerTest {

    @Container
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("iceberg_catalog_schema_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void schemaSqlCreatesCatalogAndPlanningTables() {
        String sql = IcebergTenantSchemaManager.schemaSql();

        assertThat(sql).contains("CREATE SCHEMA IF NOT EXISTS _lakeon_iceberg");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.tables");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.snapshots");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.data_files");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.delete_files");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_streams");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_offsets");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_change_events");
        assertThat(sql).contains("current_metadata_json JSONB NOT NULL");
        assertThat(sql).contains("lower_bounds_json JSONB");
        assertThat(sql).contains("upper_bounds_json JSONB");
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_table_branch_snapshot");
    }

    @Test
    void schemaSqlTracksLazyExportStatus() {
        String sql = IcebergTenantSchemaManager.schemaSql();

        assertThat(sql).contains("export_status TEXT NOT NULL DEFAULT 'NOT_MATERIALIZED'");
    }

    @Test
    void ensureSchemaExecutesSql() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        new IcebergTenantSchemaManager().ensureSchema(connection);

        verify(statement).execute(IcebergTenantSchemaManager.schemaSql());
        verify(statement).close();
    }

    @Test
    void ensureSchemaIsValidPostgresSqlAndIdempotent() throws Exception {
        try (Connection connection = openPg(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS _lakeon_iceberg CASCADE");

            IcebergTenantSchemaManager manager = new IcebergTenantSchemaManager();
            manager.ensureSchema(connection);
            manager.ensureSchema(connection);

            assertThat(tableExists(connection, "tables")).isTrue();
            assertThat(tableExists(connection, "snapshots")).isTrue();
            assertThat(tableExists(connection, "data_files")).isTrue();
            assertThat(tableExists(connection, "delete_files")).isTrue();
            assertThat(tableExists(connection, "cdf_streams")).isTrue();
            assertThat(tableExists(connection, "cdf_offsets")).isTrue();
            assertThat(tableExists(connection, "cdf_change_events")).isTrue();
            assertThat(indexExists(connection, "idx_lakeon_iceberg_data_files_table_branch_snapshot")).isTrue();
        }
    }

    private static Connection openPg() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = '_lakeon_iceberg'
                  AND table_name = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws Exception {
        String sql = """
                SELECT 1
                FROM pg_indexes
                WHERE schemaname = '_lakeon_iceberg'
                  AND indexname = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
