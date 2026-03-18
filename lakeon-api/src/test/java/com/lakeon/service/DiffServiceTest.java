package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.SchemaDiffResponse;
import com.lakeon.model.dto.SchemaDiffResponse.*;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DiffService — computeDiff 纯函数测试")
class DiffServiceTest {

    private DiffService diffService;

    @BeforeEach
    void setUp() {
        diffService = new DiffService(
            mock(BranchRepository.class),
            mock(DatabaseRepository.class),
            mock(VersionRepository.class),
            mock(ComputePodManager.class)
        );
    }

    @Test
    @DisplayName("新增表 — target 有 orders 表，source 没有，应出现在 added")
    void computeDiff_newTable_showsAsAdded() {
        Map<String, List<ColumnInfo>> source = new LinkedHashMap<>();
        Map<String, List<ColumnInfo>> target = new LinkedHashMap<>();
        target.put("orders", List.of(
            new ColumnInfo("id", "integer", false, null),
            new ColumnInfo("amount", "numeric", true, null)
        ));

        SchemaDiffResponse result = diffService.computeDiff(source, target, Map.of(), Map.of());

        assertThat(result.tables().added()).hasSize(1);
        assertThat(result.tables().added().get(0).name()).isEqualTo("orders");
        assertThat(result.tables().added().get(0).columns()).hasSize(2);
        assertThat(result.tables().removed()).isEmpty();
        assertThat(result.tables().modified()).isEmpty();
    }

    @Test
    @DisplayName("删除表 — source 有 temp 表，target 没有，应出现在 removed")
    void computeDiff_removedTable_showsAsRemoved() {
        Map<String, List<ColumnInfo>> source = new LinkedHashMap<>();
        source.put("temp", List.of(
            new ColumnInfo("id", "integer", false, null)
        ));
        Map<String, List<ColumnInfo>> target = new LinkedHashMap<>();

        SchemaDiffResponse result = diffService.computeDiff(source, target, Map.of(), Map.of());

        assertThat(result.tables().removed()).hasSize(1);
        assertThat(result.tables().removed().get(0).name()).isEqualTo("temp");
        assertThat(result.tables().added()).isEmpty();
        assertThat(result.tables().modified()).isEmpty();
    }

    @Test
    @DisplayName("修改列 — 同表列类型变化，应出现在 modified")
    void computeDiff_modifiedColumn_showsTypeChange() {
        Map<String, List<ColumnInfo>> source = new LinkedHashMap<>();
        source.put("users", List.of(
            new ColumnInfo("age", "integer", false, null)
        ));
        Map<String, List<ColumnInfo>> target = new LinkedHashMap<>();
        target.put("users", List.of(
            new ColumnInfo("age", "bigint", false, null)
        ));

        SchemaDiffResponse result = diffService.computeDiff(source, target, Map.of(), Map.of());

        assertThat(result.tables().added()).isEmpty();
        assertThat(result.tables().removed()).isEmpty();
        assertThat(result.tables().modified()).hasSize(1);

        TableModification mod = result.tables().modified().get(0);
        assertThat(mod.name()).isEqualTo("users");
        assertThat(mod.columns().modified()).hasSize(1);
        assertThat(mod.columns().modified().get(0).oldType()).isEqualTo("integer");
        assertThat(mod.columns().modified().get(0).newType()).isEqualTo("bigint");
    }

    @Test
    @DisplayName("新增索引 — target 有索引 source 没有，应出现在 indexes.added")
    void computeDiff_newIndex_showsAsAdded() {
        Map<String, String> sourceIndexes = Map.of();
        Map<String, String> targetIndexes = Map.of(
            "idx_users_email", "users:CREATE INDEX idx_users_email ON users (email)"
        );

        SchemaDiffResponse result = diffService.computeDiff(
            Map.of(), Map.of(), sourceIndexes, targetIndexes);

        assertThat(result.indexes().added()).hasSize(1);
        assertThat(result.indexes().added().get(0).name()).isEqualTo("idx_users_email");
        assertThat(result.indexes().added().get(0).tableName()).isEqualTo("users");
        assertThat(result.indexes().removed()).isEmpty();
    }

    @Test
    @DisplayName("相同 schema — 两侧完全一致，所有 diff 列表为空")
    void computeDiff_identicalSchemas_emptyDiff() {
        List<ColumnInfo> cols = List.of(
            new ColumnInfo("id", "integer", false, null),
            new ColumnInfo("name", "text", true, null)
        );
        Map<String, List<ColumnInfo>> tables = new LinkedHashMap<>();
        tables.put("users", cols);

        Map<String, String> indexes = Map.of(
            "users_pkey", "users:CREATE UNIQUE INDEX users_pkey ON users (id)"
        );

        SchemaDiffResponse result = diffService.computeDiff(tables, tables, indexes, indexes);

        assertThat(result.tables().added()).isEmpty();
        assertThat(result.tables().removed()).isEmpty();
        assertThat(result.tables().modified()).isEmpty();
        assertThat(result.indexes().added()).isEmpty();
        assertThat(result.indexes().removed()).isEmpty();
    }
}
