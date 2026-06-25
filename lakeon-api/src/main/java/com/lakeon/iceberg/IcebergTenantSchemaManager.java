package com.lakeon.iceberg;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class IcebergTenantSchemaManager {
    private static final String SCHEMA_SQL = """
            CREATE SCHEMA IF NOT EXISTS _lakeon_iceberg;

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.tables (
                table_id TEXT PRIMARY KEY,
                database_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                namespace TEXT NOT NULL,
                table_name TEXT NOT NULL,
                table_location TEXT NOT NULL,
                current_metadata_location TEXT NOT NULL,
                current_metadata_json JSONB NOT NULL,
                current_metadata_hash TEXT NOT NULL,
                current_snapshot_id BIGINT,
                metadata_version BIGINT NOT NULL DEFAULT 0,
                export_status TEXT NOT NULL DEFAULT 'NOT_MATERIALIZED',
                last_commit_lsn TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE (database_id, branch_id, namespace, table_name)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.snapshots (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                parent_snapshot_id BIGINT,
                sequence_number BIGINT NOT NULL,
                operation TEXT NOT NULL,
                committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                manifest_materialized BOOLEAN NOT NULL DEFAULT false,
                PRIMARY KEY (table_id, branch_id, snapshot_id)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.data_files (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                file_path TEXT NOT NULL,
                content_type TEXT NOT NULL DEFAULT 'DATA',
                partition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                record_count BIGINT NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                lower_bounds_json JSONB,
                upper_bounds_json JSONB,
                null_counts_json JSONB,
                value_counts_json JSONB,
                added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (table_id, branch_id, snapshot_id, file_path)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.delete_files (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                file_path TEXT NOT NULL,
                delete_type TEXT NOT NULL,
                partition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                record_count BIGINT NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                lower_bounds_json JSONB,
                upper_bounds_json JSONB,
                null_counts_json JSONB,
                value_counts_json JSONB,
                added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (table_id, branch_id, snapshot_id, file_path)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_streams (
                stream_id TEXT PRIMARY KEY,
                database_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                source_schema TEXT NOT NULL,
                source_table TEXT NOT NULL,
                target_namespace TEXT NOT NULL,
                target_table TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                backfill_status TEXT NOT NULL DEFAULT 'PENDING',
                backfill_lsn TEXT,
                slot_name TEXT NOT NULL,
                publication_name TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_offsets (
                stream_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                last_commit_lsn TEXT NOT NULL,
                last_txid TEXT,
                last_snapshot_id BIGINT NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (stream_id, branch_id)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_change_events (
                event_id BIGSERIAL PRIMARY KEY,
                stream_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                op TEXT NOT NULL,
                row_json JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_table_branch_snapshot
                ON _lakeon_iceberg.data_files(table_id, branch_id, snapshot_id);
            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_partition
                ON _lakeon_iceberg.data_files USING gin(partition_json);
            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_snapshots_table_branch
                ON _lakeon_iceberg.snapshots(table_id, branch_id, sequence_number);
            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_cdf_change_events_stream
                ON _lakeon_iceberg.cdf_change_events(stream_id, branch_id, event_id);
            """;

    public static String schemaSql() {
        return SCHEMA_SQL;
    }

    public void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(schemaSql());
        }
    }
}
