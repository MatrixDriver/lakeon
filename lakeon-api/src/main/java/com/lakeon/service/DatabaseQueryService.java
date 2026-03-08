package com.lakeon.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.SchemaCacheEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.SchemaCacheRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DatabaseQueryService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseQueryService.class);

    private static final int MAX_ROWS = 1000;
    private static final int STATEMENT_TIMEOUT_SECONDS = 30;
    private static final Duration CACHE_TTL = Duration.ofHours(1); // 缓存1小时过期
    private static final Set<String> EXCLUDED_SCHEMAS = Set.of(
        "pg_catalog", "pg_toast", "information_schema"
    );
    private static final Pattern DANGEROUS_SQL = Pattern.compile(
        "\\b(DROP\\s+DATABASE|REASSIGN\\s+OWNER|ALTER\\s+SYSTEM|CREATE\\s+EXTENSION|COPY\\s+.*\\bTO\\b|COPY\\s+.*\\bFROM\\b.*\\bPROGRAM\\b)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;
    private final SchemaCacheRepository schemaCacheRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public DatabaseQueryService(DatabaseRepository databaseRepository,
                                DatabaseService databaseService,
                                SchemaCacheRepository schemaCacheRepository,
                                ObjectMapper objectMapper,
                                AuditService auditService) {
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
        this.schemaCacheRepository = schemaCacheRepository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public List<SchemaInfo> listSchemas(TenantEntity tenant, String dbId) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // 尝试从缓存读取
        Instant cacheTime = schemaCacheRepository.findLatestUpdateTime(dbId);
        if (cacheTime != null && !isCacheExpired(cacheTime)) {
            log.debug("Using cached schema for database {}", dbId);
            return buildSchemasFromCache(dbId);
        }

        // 缓存未命中或过期，查询数据库并更新缓存
        log.debug("Cache miss or expired for database {}, querying from compute", dbId);
        return refreshSchemaCache(db);
    }

    @Transactional
    public List<SchemaInfo> refreshSchemaCache(DatabaseEntity db) {
        // 先删除旧缓存，避免 unique constraint 冲突
        schemaCacheRepository.deleteByDatabaseId(db.getId());

        try (Connection conn = getConnection(db)) {
            // 1. 查询所有 schema 和 table 信息
            Map<String, List<TableInfo>> schemaTablesMap = new LinkedHashMap<>();
            // key: "schema.table" -> (tableType, rowCount, tableSize)
            Map<String, String[]> tableMetaMap = new LinkedHashMap<>();

            String tableSql = """
                SELECT
                    n.nspname as schema_name,
                    c.relname as table_name,
                    CASE c.relkind
                        WHEN 'r' THEN 'TABLE'
                        WHEN 'v' THEN 'VIEW'
                        WHEN 'm' THEN 'MATERIALIZED VIEW'
                        WHEN 'f' THEN 'FOREIGN TABLE'
                    END as table_type,
                    pg_stat_get_live_tuples(c.oid) as row_count,
                    pg_total_relation_size(c.oid) as table_size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'v', 'm', 'f')
                  AND n.nspname NOT IN ('pg_catalog', 'pg_toast', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_%'
                ORDER BY n.nspname, c.relname
                """;

            try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String schemaName = rs.getString("schema_name");
                    String tableName = rs.getString("table_name");
                    String tableType = rs.getString("table_type");
                    long rowCount = rs.getLong("row_count");
                    long tableSize = rs.getLong("table_size");

                    TableInfo tableInfo = new TableInfo(tableName, tableType, rowCount, tableSize);
                    schemaTablesMap.computeIfAbsent(schemaName, k -> new ArrayList<>()).add(tableInfo);
                    tableMetaMap.put(schemaName + "\0" + tableName,
                        new String[]{tableType, String.valueOf(rowCount), String.valueOf(tableSize)});
                }
            }

            // 2. 批量查询所有列信息（单次查询替代 N+1）
            // key: "schema\0table" -> List<ColumnInfo>
            Map<String, List<ColumnInfo>> allColumns = queryAllColumns(conn);

            // 3. 保存缓存
            for (Map.Entry<String, String[]> entry : tableMetaMap.entrySet()) {
                String[] parts = entry.getKey().split("\0", 2);
                String schemaName = parts[0];
                String tableName = parts[1];
                String[] meta = entry.getValue();
                List<ColumnInfo> columns = allColumns.getOrDefault(entry.getKey(), List.of());
                saveCacheEntry(db.getId(), schemaName, tableName, meta[0], columns,
                    Long.parseLong(meta[1]), Long.parseLong(meta[2]));
            }

            // 构建返回结果
            List<SchemaInfo> schemas = new ArrayList<>();
            for (Map.Entry<String, List<TableInfo>> entry : schemaTablesMap.entrySet()) {
                SchemaInfo schema = new SchemaInfo(entry.getKey());
                schemas.add(schema);
            }

            return schemas;
        } catch (SQLException e) {
            throw new ServiceException("Failed to refresh schema cache: " + e.getMessage(), e);
        }
    }

    /**
     * 批量查询所有用户表的列信息（单次 SQL 查询，避免 N+1 问题）
     */
    private Map<String, List<ColumnInfo>> queryAllColumns(Connection conn) throws SQLException {
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<>();
        String sql = """
            SELECT
                n.nspname as schema_name,
                c.relname as table_name,
                a.attname as column_name,
                pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                NOT a.attnotnull as is_nullable,
                pg_get_expr(d.adbin, d.adrelid) as column_default,
                a.attnum as position
            FROM pg_attribute a
            JOIN pg_class c ON a.attrelid = c.oid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON a.attrelid = d.adrelid AND a.attnum = d.adnum
            WHERE c.relkind IN ('r', 'v', 'm', 'f')
              AND n.nspname NOT IN ('pg_catalog', 'pg_toast', 'information_schema')
              AND n.nspname NOT LIKE 'pg_%'
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY n.nspname, c.relname, a.attnum
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String key = rs.getString("schema_name") + "\0" + rs.getString("table_name");
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(new ColumnInfo(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getBoolean("is_nullable"),
                    rs.getString("column_default"),
                    null,
                    rs.getInt("position")
                ));
            }
        }
        return result;
    }

    private List<ColumnInfo> queryColumns(Connection conn, String schema, String table) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String sql = """
            SELECT
                a.attname as column_name,
                pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                NOT a.attnotnull as is_nullable,
                pg_get_expr(d.adbin, d.adrelid) as column_default
            FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON a.attrelid = d.adrelid AND a.attnum = d.adnum
            WHERE a.attrelid = (
                SELECT c.oid FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ?
            )
            AND a.attnum > 0
            AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            int position = 1;
            while (rs.next()) {
                columns.add(new ColumnInfo(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getBoolean("is_nullable"),
                    rs.getString("column_default"),
                    null, // comment
                    position++
                ));
            }
        }
        return columns;
    }

    private void saveCacheEntry(String dbId, String schema, String table, String type,
                                 List<ColumnInfo> columns, long rowCount, long tableSize) {
        try {
            SchemaCacheEntity cache = new SchemaCacheEntity();
            cache.setDatabaseId(dbId);
            cache.setSchemaName(schema);
            cache.setTableName(table);
            cache.setTableType(type);
            cache.setColumnsJson(objectMapper.writeValueAsString(columns));
            cache.setRowCount(rowCount);
            cache.setTableSizeBytes(tableSize);
            schemaCacheRepository.save(cache);
        } catch (Exception e) {
            log.warn("Failed to save cache entry: {}", e.getMessage());
        }
    }

    private List<SchemaInfo> buildSchemasFromCache(String dbId) {
        List<SchemaCacheEntity> cacheEntries = schemaCacheRepository.findByDatabaseIdOrderBySchemaNameAscTableNameAsc(dbId);
        Map<String, SchemaInfo> schemaMap = new LinkedHashMap<>();

        for (SchemaCacheEntity entry : cacheEntries) {
            schemaMap.computeIfAbsent(entry.getSchemaName(), SchemaInfo::new);
        }

        return new ArrayList<>(schemaMap.values());
    }

    private boolean isCacheExpired(Instant cacheTime) {
        return Duration.between(cacheTime, Instant.now()).compareTo(CACHE_TTL) > 0;
    }

    @Transactional
    public List<TableInfo> listTables(TenantEntity tenant, String dbId, String schema) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // 尝试从缓存读取
        Instant cacheTime = schemaCacheRepository.findLatestUpdateTime(dbId);
        if (cacheTime != null && !isCacheExpired(cacheTime)) {
            log.debug("Using cached tables for database {} schema {}", dbId, schema);
            return buildTablesFromCache(dbId, schema);
        }

        // 缓存未命中，刷新缓存后返回
        refreshSchemaCache(db);
        return buildTablesFromCache(dbId, schema);
    }

    private List<TableInfo> buildTablesFromCache(String dbId, String schema) {
        List<SchemaCacheEntity> cacheEntries = schemaCacheRepository.findByDatabaseIdAndSchemaNameOrderByTableNameAsc(dbId, schema);
        List<TableInfo> tables = new ArrayList<>();

        for (SchemaCacheEntity entry : cacheEntries) {
            if (entry.getTableName() != null) {
                tables.add(new TableInfo(
                    entry.getTableName(),
                    entry.getTableType(),
                    entry.getRowCount() != null ? entry.getRowCount() : 0L,
                    entry.getTableSizeBytes() != null ? entry.getTableSizeBytes() : 0L
                ));
            }
        }

        return tables;
    }

    @Transactional
    public List<ColumnInfo> listColumns(TenantEntity tenant, String dbId, String schema, String table) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // 尝试从缓存读取
        Instant cacheTime = schemaCacheRepository.findLatestUpdateTime(dbId);
        if (cacheTime != null && !isCacheExpired(cacheTime)) {
            log.debug("Using cached columns for database {} table {}.{}", dbId, schema, table);
            return buildColumnsFromCache(dbId, schema, table);
        }

        // 缓存未命中，刷新缓存后返回
        refreshSchemaCache(db);
        return buildColumnsFromCache(dbId, schema, table);
    }

    private List<ColumnInfo> buildColumnsFromCache(String dbId, String schema, String table) {
        return schemaCacheRepository.findByDatabaseIdAndSchemaNameAndTableName(dbId, schema, table)
            .map(entry -> {
                try {
                    return objectMapper.readValue(entry.getColumnsJson(), new TypeReference<List<ColumnInfo>>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse cached columns: {}", e.getMessage());
                    return new ArrayList<ColumnInfo>();
                }
            })
            .orElse(new ArrayList<>());
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

                // Record audit log (best-effort, non-blocking)
                try {
                    auditService.recordAuditLog(dbId, tenant.getId(),
                            db.getDbUser(), sql, null, elapsed);
                } catch (Exception e) {
                    log.warn("Failed to record audit log for database {}: {}", dbId, e.getMessage());
                }

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
            throw new BadRequestException(e.getMessage());
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

        // Re-read entity in case wakeCompute updated host/port in a separate transaction
        if (db.getComputeHost() == null) {
            DatabaseEntity refreshed = databaseRepository.findById(db.getId()).orElse(db);
            db.setComputeHost(refreshed.getComputeHost());
            db.setComputePort(refreshed.getComputePort());
            db.setComputePodName(refreshed.getComputePodName());
            db.setStatus(refreshed.getStatus());
        }

        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db.getName();

        // Retry JDBC connection — compute pod may need a moment after k8s reports Ready
        Connection conn = null;
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
                break;
            } catch (SQLException e) {
                if (i == maxRetries - 1) throw e;
                log.debug("JDBC connection attempt {} failed, retrying in 2s: {}", i + 1, e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
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

    @Transactional
    public CacheRefreshResponse refreshSchemaCacheForApi(TenantEntity tenant, String dbId) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // 刷新缓存（内部已先删除旧缓存）
        List<SchemaInfo> schemas = refreshSchemaCache(db);

        // 统计表数量
        int tablesCount = (int) schemaCacheRepository.findByDatabaseIdOrderBySchemaNameAscTableNameAsc(dbId)
            .stream()
            .filter(e -> e.getTableName() != null)
            .count();

        return new CacheRefreshResponse(
            "Schema cache refreshed successfully",
            Instant.now(),
            schemas.size(),
            tablesCount
        );
    }

    public CacheStatusResponse getCacheStatus(TenantEntity tenant, String dbId) {
        findDatabase(tenant, dbId);

        Instant lastUpdated = schemaCacheRepository.findLatestUpdateTime(dbId);
        boolean cached = lastUpdated != null;
        boolean expired = cached && isCacheExpired(lastUpdated);
        long ttlSeconds = cached ? CACHE_TTL.getSeconds() - Duration.between(lastUpdated, Instant.now()).getSeconds() : 0;

        return new CacheStatusResponse(cached, lastUpdated, expired, Math.max(0, ttlSeconds));
    }
}
