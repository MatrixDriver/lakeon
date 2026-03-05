package com.lakeon.service;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DatabaseQueryService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseQueryService.class);

    private static final int MAX_ROWS = 1000;
    private static final int STATEMENT_TIMEOUT_SECONDS = 30;
    private static final Set<String> EXCLUDED_SCHEMAS = Set.of(
        "pg_catalog", "pg_toast", "information_schema"
    );
    private static final Pattern DANGEROUS_SQL = Pattern.compile(
        "\\b(DROP\\s+DATABASE|REASSIGN\\s+OWNER|ALTER\\s+SYSTEM|CREATE\\s+EXTENSION|COPY\\s+.*\\bTO\\b|COPY\\s+.*\\bFROM\\b.*\\bPROGRAM\\b)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;

    public DatabaseQueryService(DatabaseRepository databaseRepository,
                                DatabaseService databaseService) {
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
    }

    public List<SchemaInfo> listSchemas(TenantEntity tenant, String dbId) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            List<SchemaInfo> schemas = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!EXCLUDED_SCHEMAS.contains(name) && !name.startsWith("pg_")) {
                        schemas.add(new SchemaInfo(name));
                    }
                }
            }
            return schemas;
        } catch (SQLException e) {
            throw new ServiceException("Failed to list schemas: " + e.getMessage(), e);
        }
    }

    public List<TableInfo> listTables(TenantEntity tenant, String dbId, String schema) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            List<TableInfo> tables = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT t.table_name, t.table_type, " +
                    "COALESCE(c.reltuples::bigint, 0) AS row_estimate " +
                    "FROM information_schema.tables t " +
                    "LEFT JOIN pg_class c ON c.relname = t.table_name " +
                    "LEFT JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema " +
                    "WHERE t.table_schema = ? " +
                    "ORDER BY t.table_name")) {
                ps.setString(1, schema);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    tables.add(new TableInfo(
                        rs.getString("table_name"),
                        rs.getString("table_type"),
                        Math.max(0, rs.getLong("row_estimate"))
                    ));
                }
            }
            return tables;
        } catch (SQLException e) {
            throw new ServiceException("Failed to list tables: " + e.getMessage(), e);
        }
    }

    public List<ColumnInfo> listColumns(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            List<ColumnInfo> columns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, c.ordinal_position, " +
                    "pgd.description AS comment " +
                    "FROM information_schema.columns c " +
                    "LEFT JOIN pg_catalog.pg_statio_all_tables st ON st.schemaname = c.table_schema AND st.relname = c.table_name " +
                    "LEFT JOIN pg_catalog.pg_description pgd ON pgd.objoid = st.relid AND pgd.objsubid = c.ordinal_position " +
                    "WHERE c.table_schema = ? AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    columns.add(new ColumnInfo(
                        rs.getString("column_name"),
                        rs.getString("data_type"),
                        "YES".equals(rs.getString("is_nullable")),
                        rs.getString("column_default"),
                        rs.getString("comment"),
                        rs.getInt("ordinal_position")
                    ));
                }
            }
            return columns;
        } catch (SQLException e) {
            throw new ServiceException("Failed to list columns: " + e.getMessage(), e);
        }
    }

    public List<IndexInfo> listIndexes(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            List<IndexInfo> indexes = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT i.indexname, i.indexdef, " +
                    "ix.indisunique, ix.indisprimary, " +
                    "array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns " +
                    "FROM pg_indexes i " +
                    "JOIN pg_class c ON c.relname = i.indexname " +
                    "JOIN pg_index ix ON ix.indexrelid = c.oid " +
                    "JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = ANY(ix.indkey) " +
                    "WHERE i.schemaname = ? AND i.tablename = ? " +
                    "GROUP BY i.indexname, i.indexdef, ix.indisunique, ix.indisprimary " +
                    "ORDER BY i.indexname")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Array arr = rs.getArray("columns");
                    String[] cols = arr != null ? (String[]) arr.getArray() : new String[0];
                    indexes.add(new IndexInfo(
                        rs.getString("indexname"),
                        Arrays.asList(cols),
                        rs.getBoolean("indisunique"),
                        rs.getBoolean("indisprimary")
                    ));
                }
            }
            return indexes;
        } catch (SQLException e) {
            throw new ServiceException("Failed to list indexes: " + e.getMessage(), e);
        }
    }

    public List<ConstraintInfo> listConstraints(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            List<ConstraintInfo> constraints = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tc.constraint_name, tc.constraint_type, " +
                    "array_agg(DISTINCT kcu.column_name ORDER BY kcu.column_name) AS columns, " +
                    "ccu.table_name AS ref_table, " +
                    "array_agg(DISTINCT ccu.column_name ORDER BY ccu.column_name) FILTER (WHERE tc.constraint_type = 'FOREIGN KEY') AS ref_columns " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema " +
                    "LEFT JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema " +
                    "WHERE tc.table_schema = ? AND tc.table_name = ? " +
                    "GROUP BY tc.constraint_name, tc.constraint_type, ccu.table_name " +
                    "ORDER BY tc.constraint_name")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Array colArr = rs.getArray("columns");
                    String[] cols = colArr != null ? (String[]) colArr.getArray() : new String[0];
                    Array refColArr = rs.getArray("ref_columns");
                    String[] refCols = refColArr != null ? (String[]) refColArr.getArray() : new String[0];
                    constraints.add(new ConstraintInfo(
                        rs.getString("constraint_name"),
                        rs.getString("constraint_type"),
                        Arrays.asList(cols),
                        rs.getString("ref_table"),
                        refCols.length > 0 ? Arrays.asList(refCols) : null
                    ));
                }
            }
            return constraints;
        } catch (SQLException e) {
            throw new ServiceException("Failed to list constraints: " + e.getMessage(), e);
        }
    }

    public DataPage queryData(TenantEntity tenant, String dbId, String schema, String table,
                              int page, int pageSize, String sortCol, String sortDir) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        pageSize = Math.min(pageSize, MAX_ROWS);
        int offset = page * pageSize;

        try (Connection conn = getConnection(db)) {
            String quotedTable = quoteIdentifier(conn, schema) + "." + quoteIdentifier(conn, table);

            // Get total count
            long totalRows;
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quotedTable);
                rs.next();
                totalRows = rs.getLong(1);
            }

            // Build query with optional sort
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(quotedTable);
            if (sortCol != null && !sortCol.isBlank()) {
                sql.append(" ORDER BY ").append(quoteIdentifier(conn, sortCol));
                if ("desc".equalsIgnoreCase(sortDir)) {
                    sql.append(" DESC");
                } else {
                    sql.append(" ASC");
                }
            }
            sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);

            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery(sql.toString());
                return buildDataPage(rs, totalRows, page, pageSize);
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to query data: " + e.getMessage(), e);
        }
    }

    public QueryResult executeQuery(TenantEntity tenant, String dbId, String sql) {
        if (DANGEROUS_SQL.matcher(sql).find()) {
            throw new ServiceException("SQL contains prohibited statements");
        }

        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            long start = System.currentTimeMillis();
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(MAX_ROWS);
                boolean isResultSet = st.execute(sql);
                long elapsed = System.currentTimeMillis() - start;

                if (isResultSet) {
                    ResultSet rs = st.getResultSet();
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return new QueryResult(columns, rows, rows.size(), elapsed, true);
                } else {
                    int affected = st.getUpdateCount();
                    return new QueryResult(List.of(), List.of(), affected, elapsed, false);
                }
            }
        } catch (SQLException e) {
            throw new ServiceException("SQL execution failed: " + e.getMessage(), e);
        }
    }

    public TableStats getTableStats(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            String quotedTable = quoteIdentifier(conn, schema) + "." + quoteIdentifier(conn, table);
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery(
                    "SELECT reltuples::bigint AS row_count, " +
                    "pg_total_relation_size('" + quotedTable + "') AS size_bytes, " +
                    "pg_size_pretty(pg_total_relation_size('" + quotedTable + "')) AS size_pretty " +
                    "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE n.nspname = '" + escapeString(schema) + "' AND c.relname = '" + escapeString(table) + "'");
                if (rs.next()) {
                    return new TableStats(
                        Math.max(0, rs.getLong("row_count")),
                        rs.getLong("size_bytes"),
                        rs.getString("size_pretty")
                    );
                }
                return new TableStats(0, 0, "0 bytes");
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to get table stats: " + e.getMessage(), e);
        }
    }

    public void createTable(TenantEntity tenant, String dbId, String schema, CreateTableRequest request) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE ");
            ddl.append(quoteIdentifier(conn, schema)).append(".").append(quoteIdentifier(conn, request.name()));
            ddl.append(" (");

            List<String> colDefs = new ArrayList<>();
            for (CreateTableRequest.ColumnDef col : request.columns()) {
                StringBuilder colDef = new StringBuilder();
                colDef.append(quoteIdentifier(conn, col.name())).append(" ").append(col.type());
                if (!col.nullable()) {
                    colDef.append(" NOT NULL");
                }
                if (col.defaultValue() != null && !col.defaultValue().isBlank()) {
                    colDef.append(" DEFAULT ").append(col.defaultValue());
                }
                colDefs.add(colDef.toString());
            }
            ddl.append(String.join(", ", colDefs));

            if (request.primaryKey() != null && !request.primaryKey().isEmpty()) {
                ddl.append(", PRIMARY KEY (");
                ddl.append(String.join(", ",
                    request.primaryKey().stream()
                        .map(pk -> quoteIdentifier(conn, pk))
                        .toList()));
                ddl.append(")");
            }
            ddl.append(")");

            try (Statement st = conn.createStatement()) {
                st.execute(ddl.toString());
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to create table: " + e.getMessage(), e);
        }
    }

    public void dropTable(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            String sql = "DROP TABLE " + quoteIdentifier(conn, schema) + "." + quoteIdentifier(conn, table);
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to drop table: " + e.getMessage(), e);
        }
    }

    public void addColumn(TenantEntity tenant, String dbId, String schema, String table,
                          CreateTableRequest.ColumnDef column) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            StringBuilder sql = new StringBuilder("ALTER TABLE ");
            sql.append(quoteIdentifier(conn, schema)).append(".").append(quoteIdentifier(conn, table));
            sql.append(" ADD COLUMN ").append(quoteIdentifier(conn, column.name())).append(" ").append(column.type());
            if (!column.nullable()) {
                sql.append(" NOT NULL");
            }
            if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
                sql.append(" DEFAULT ").append(column.defaultValue());
            }
            try (Statement st = conn.createStatement()) {
                st.execute(sql.toString());
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to add column: " + e.getMessage(), e);
        }
    }

    public void dropColumn(TenantEntity tenant, String dbId, String schema, String table, String column) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db)) {
            String sql = "ALTER TABLE " + quoteIdentifier(conn, schema) + "." + quoteIdentifier(conn, table)
                + " DROP COLUMN " + quoteIdentifier(conn, column);
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to drop column: " + e.getMessage(), e);
        }
    }

    // ---- Internal helpers ----

    private DatabaseEntity findDatabase(TenantEntity tenant, String dbId) {
        return databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
    }

    private Connection getConnection(DatabaseEntity db) throws SQLException {
        // Ensure compute pod is running
        databaseService.wakeCompute(db);

        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db.getName();

        Connection conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", null);
        try (Statement st = conn.createStatement()) {
            st.execute("SET statement_timeout = '" + STATEMENT_TIMEOUT_SECONDS + "s'");
        }
        return conn;
    }

    private String quoteIdentifier(Connection conn, String identifier) {
        try {
            // Use PG's built-in quote_ident via a query for maximum safety
            try (PreparedStatement ps = conn.prepareStatement("SELECT quote_ident(?)")) {
                ps.setString(1, identifier);
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            // Fallback: double-quote and escape internal double quotes
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }
    }

    private String escapeString(String value) {
        return value.replace("'", "''");
    }

    private DataPage buildDataPage(ResultSet rs, long totalRows, int page, int pageSize) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return new DataPage(columns, rows, totalRows, page, pageSize);
    }
}
