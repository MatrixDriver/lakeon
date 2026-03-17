package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SchemaDiffResponse(
    TableDiffs tables,
    IndexDiffs indexes
) {
    public record TableDiffs(
        List<TableInfo> added,
        List<TableInfo> removed,
        List<TableModification> modified
    ) {}

    public record TableInfo(
        String name,
        String schema,
        List<ColumnInfo> columns
    ) {}

    public record ColumnInfo(
        String name,
        @JsonProperty("data_type") String dataType,
        @JsonProperty("is_nullable") boolean isNullable,
        @JsonProperty("column_default") String columnDefault
    ) {}

    public record TableModification(
        String name,
        String schema,
        ColumnDiffs columns
    ) {}

    public record ColumnDiffs(
        List<ColumnInfo> added,
        List<ColumnInfo> removed,
        List<ColumnModification> modified
    ) {}

    public record ColumnModification(
        String name,
        @JsonProperty("old_type") String oldType,
        @JsonProperty("new_type") String newType,
        @JsonProperty("old_nullable") Boolean oldNullable,
        @JsonProperty("new_nullable") Boolean newNullable,
        @JsonProperty("old_default") String oldDefault,
        @JsonProperty("new_default") String newDefault
    ) {}

    public record IndexDiffs(
        List<IndexInfo> added,
        List<IndexInfo> removed
    ) {}

    public record IndexInfo(
        String name,
        @JsonProperty("table_name") String tableName,
        String definition
    ) {}
}
