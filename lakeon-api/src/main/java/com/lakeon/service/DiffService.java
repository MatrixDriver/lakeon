package com.lakeon.service;

import com.lakeon.model.dto.SchemaDiffResponse;
import com.lakeon.model.dto.SchemaDiffResponse.*;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiffService {

    private final BranchRepository branchRepository;
    private final DatabaseRepository databaseRepository;

    @Autowired
    public DiffService(BranchRepository branchRepository, DatabaseRepository databaseRepository) {
        this.branchRepository = branchRepository;
        this.databaseRepository = databaseRepository;
    }

    public SchemaDiffResponse schemaDiff(TenantEntity tenant, String dbId,
                                          String sourceType, String sourceId,
                                          String targetType, String targetId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found"));

        String sourceUrl = resolveJdbcUrl(db, sourceType, sourceId);
        String targetUrl = resolveJdbcUrl(db, targetType, targetId);

        Map<String, List<ColumnInfo>> sourceTables = queryTables(sourceUrl);
        Map<String, List<ColumnInfo>> targetTables = queryTables(targetUrl);
        Map<String, String> sourceIndexes = queryIndexes(sourceUrl);
        Map<String, String> targetIndexes = queryIndexes(targetUrl);

        return computeDiff(sourceTables, targetTables, sourceIndexes, targetIndexes);
    }

    private String resolveJdbcUrl(DatabaseEntity db, String type, String id) {
        if (!"branch".equals(type)) {
            throw new BadRequestException(
                "Only branch-based diff is supported. Version-based diff requires temp compute (not yet implemented).");
        }
        BranchEntity branch = branchRepository.findByIdAndDatabaseId(id, db.getId())
            .orElseThrow(() -> new NotFoundException("Branch not found: " + id));
        if (branch.getComputeHost() == null) {
            throw new BadRequestException(
                "Branch '" + branch.getName() + "' has no running compute. Start compute first.");
        }
        return String.format("jdbc:postgresql://%s:%d/neondb?user=%s&password=%s",
            branch.getComputeHost(), branch.getComputePort(),
            db.getDbUser(), db.getDbPassword());
    }

    private Map<String, List<ColumnInfo>> queryTables(String jdbcUrl) {
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<>();
        String sql = """
            SELECT table_name, column_name, data_type, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
            ORDER BY table_name, ordinal_position
            """;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                ColumnInfo col = new ColumnInfo(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    "YES".equals(rs.getString("is_nullable")),
                    rs.getString("column_default")
                );
                result.computeIfAbsent(table, k -> new ArrayList<>()).add(col);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query schema: " + e.getMessage(), e);
        }
        return result;
    }

    private Map<String, String> queryIndexes(String jdbcUrl) {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = """
            SELECT indexname, tablename, indexdef
            FROM pg_indexes
            WHERE schemaname = 'public'
            ORDER BY indexname
            """;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString("indexname"),
                    rs.getString("tablename") + ":" + rs.getString("indexdef"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query indexes: " + e.getMessage(), e);
        }
        return result;
    }

    // Package-private for testing without JDBC
    SchemaDiffResponse computeDiff(
            Map<String, List<ColumnInfo>> sourceTables,
            Map<String, List<ColumnInfo>> targetTables,
            Map<String, String> sourceIndexes,
            Map<String, String> targetIndexes) {

        List<TableInfo> addedTables = new ArrayList<>();
        List<TableInfo> removedTables = new ArrayList<>();
        List<TableModification> modifiedTables = new ArrayList<>();

        // Tables added in target
        for (var entry : targetTables.entrySet()) {
            if (!sourceTables.containsKey(entry.getKey())) {
                addedTables.add(new TableInfo(entry.getKey(), "public", entry.getValue()));
            }
        }
        // Tables removed from source
        for (var entry : sourceTables.entrySet()) {
            if (!targetTables.containsKey(entry.getKey())) {
                removedTables.add(new TableInfo(entry.getKey(), "public", entry.getValue()));
            }
        }
        // Tables modified
        for (var entry : sourceTables.entrySet()) {
            if (targetTables.containsKey(entry.getKey())) {
                ColumnDiffs colDiff = diffColumns(entry.getValue(), targetTables.get(entry.getKey()));
                if (!colDiff.added().isEmpty() || !colDiff.removed().isEmpty() || !colDiff.modified().isEmpty()) {
                    modifiedTables.add(new TableModification(entry.getKey(), "public", colDiff));
                }
            }
        }

        // Index diffs
        List<IndexInfo> addedIndexes = new ArrayList<>();
        List<IndexInfo> removedIndexes = new ArrayList<>();
        for (var entry : targetIndexes.entrySet()) {
            if (!sourceIndexes.containsKey(entry.getKey())) {
                String[] parts = entry.getValue().split(":", 2);
                addedIndexes.add(new IndexInfo(entry.getKey(), parts[0], parts.length > 1 ? parts[1] : ""));
            }
        }
        for (var entry : sourceIndexes.entrySet()) {
            if (!targetIndexes.containsKey(entry.getKey())) {
                String[] parts = entry.getValue().split(":", 2);
                removedIndexes.add(new IndexInfo(entry.getKey(), parts[0], parts.length > 1 ? parts[1] : ""));
            }
        }

        return new SchemaDiffResponse(
            new TableDiffs(addedTables, removedTables, modifiedTables),
            new IndexDiffs(addedIndexes, removedIndexes)
        );
    }

    private ColumnDiffs diffColumns(List<ColumnInfo> source, List<ColumnInfo> target) {
        Map<String, ColumnInfo> sourceMap = source.stream()
            .collect(Collectors.toMap(ColumnInfo::name, c -> c));
        Map<String, ColumnInfo> targetMap = target.stream()
            .collect(Collectors.toMap(ColumnInfo::name, c -> c));

        List<ColumnInfo> added = target.stream()
            .filter(c -> !sourceMap.containsKey(c.name())).toList();
        List<ColumnInfo> removed = source.stream()
            .filter(c -> !targetMap.containsKey(c.name())).toList();
        List<ColumnModification> modified = new ArrayList<>();

        for (var s : source) {
            ColumnInfo t = targetMap.get(s.name());
            if (t != null) {
                boolean typeChanged = !s.dataType().equals(t.dataType());
                boolean nullableChanged = s.isNullable() != t.isNullable();
                boolean defaultChanged = !Objects.equals(s.columnDefault(), t.columnDefault());
                if (typeChanged || nullableChanged || defaultChanged) {
                    modified.add(new ColumnModification(s.name(),
                        typeChanged ? s.dataType() : null, typeChanged ? t.dataType() : null,
                        nullableChanged ? s.isNullable() : null, nullableChanged ? t.isNullable() : null,
                        defaultChanged ? s.columnDefault() : null, defaultChanged ? t.columnDefault() : null));
                }
            }
        }

        return new ColumnDiffs(added, removed, modified);
    }
}
