package com.lakeon.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.ServiceException;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExtensionService {

    private static final Logger log = LoggerFactory.getLogger(ExtensionService.class);

    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;

    public ExtensionService(DatabaseRepository databaseRepository, DatabaseService databaseService) {
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
    }

    // ---- Extension catalog (hardcoded from compute-node-v17 image) ----

    public record ExtensionCatalogEntry(String name, String category, String description) {}

    private static final List<ExtensionCatalogEntry> CATALOG = List.of(
        // AI / Vector / RAG
        new ExtensionCatalogEntry("vector", "AI / 向量", "pgvector 向量存储和相似度检索"),
        new ExtensionCatalogEntry("rag", "AI / 向量", "内置 RAG 管线"),
        new ExtensionCatalogEntry("rag_bge_small_en_v15", "AI / 向量", "RAG embedding 模型 (BGE)"),
        new ExtensionCatalogEntry("rag_jina_reranker_v1_tiny_en", "AI / 向量", "RAG reranker 模型 (Jina)"),
        new ExtensionCatalogEntry("pg_tiktoken", "AI / 向量", "OpenAI tiktoken 分词器"),

        // Full-text Search
        new ExtensionCatalogEntry("pg_search", "全文检索", "ParadeDB BM25 全文检索 (Elasticsearch 级别)"),
        new ExtensionCatalogEntry("rum", "全文检索", "高级全文索引 (替代 GIN)"),
        new ExtensionCatalogEntry("pg_trgm", "全文检索", "三字母组模糊匹配"),
        new ExtensionCatalogEntry("unaccent", "全文检索", "去重音符搜索"),
        new ExtensionCatalogEntry("fuzzystrmatch", "全文检索", "模糊字符串匹配"),
        new ExtensionCatalogEntry("dict_int", "全文检索", "整数字典"),
        new ExtensionCatalogEntry("dict_xsyn", "全文检索", "扩展同义词字典"),

        // Geospatial
        new ExtensionCatalogEntry("postgis", "地理空间", "PostGIS 地理空间支持"),
        new ExtensionCatalogEntry("postgis_raster", "地理空间", "PostGIS 栅格数据"),
        new ExtensionCatalogEntry("postgis_topology", "地理空间", "PostGIS 拓扑"),
        new ExtensionCatalogEntry("postgis_sfcgal", "地理空间", "PostGIS 3D/SFCGAL"),
        new ExtensionCatalogEntry("postgis_tiger_geocoder", "地理空间", "PostGIS 地理编码 (US)"),
        new ExtensionCatalogEntry("address_standardizer", "地理空间", "地址标准化"),
        new ExtensionCatalogEntry("address_standardizer_data_us", "地理空间", "地址标准化数据 (US)"),
        new ExtensionCatalogEntry("h3", "地理空间", "Uber H3 六边形索引"),
        new ExtensionCatalogEntry("h3_postgis", "地理空间", "H3 与 PostGIS 集成"),
        new ExtensionCatalogEntry("pgrouting", "地理空间", "路径规划"),
        new ExtensionCatalogEntry("earthdistance", "地理空间", "地球距离计算"),
        new ExtensionCatalogEntry("cube", "地理空间", "多维立方体 (earthdistance 依赖)"),

        // Data Types
        new ExtensionCatalogEntry("uuid-ossp", "数据类型", "UUID 生成函数"),
        new ExtensionCatalogEntry("pg_uuidv7", "数据类型", "UUIDv7 支持"),
        new ExtensionCatalogEntry("hstore", "数据类型", "键值对存储"),
        new ExtensionCatalogEntry("ltree", "数据类型", "层级/树形数据"),
        new ExtensionCatalogEntry("citext", "数据类型", "大小写无关文本"),
        new ExtensionCatalogEntry("intarray", "数据类型", "整数数组操作"),
        new ExtensionCatalogEntry("intagg", "数据类型", "整数聚合"),
        new ExtensionCatalogEntry("hll", "数据类型", "HyperLogLog 基数估算"),
        new ExtensionCatalogEntry("roaringbitmap", "数据类型", "Roaring 位图"),
        new ExtensionCatalogEntry("ip4r", "数据类型", "IP 地址范围"),
        new ExtensionCatalogEntry("isn", "数据类型", "ISBN/ISSN 标准编号"),
        new ExtensionCatalogEntry("seg", "数据类型", "线段/浮点区间"),
        new ExtensionCatalogEntry("prefix", "数据类型", "前缀匹配"),
        new ExtensionCatalogEntry("semver", "数据类型", "语义版本号"),
        new ExtensionCatalogEntry("unit", "数据类型", "物理单位"),
        new ExtensionCatalogEntry("pg_hashids", "数据类型", "Hashids 编码"),
        new ExtensionCatalogEntry("pgx_ulid", "数据类型", "ULID 支持"),
        new ExtensionCatalogEntry("pg_jsonschema", "数据类型", "JSON Schema 校验"),
        new ExtensionCatalogEntry("xml2", "数据类型", "XML 处理"),

        // GraphQL
        new ExtensionCatalogEntry("pg_graphql", "GraphQL", "GraphQL 查询支持"),

        // Time Series
        new ExtensionCatalogEntry("timescaledb", "时序数据", "时序数据库扩展"),

        // Index & Performance
        new ExtensionCatalogEntry("btree_gin", "索引与性能", "B-tree GIN 操作符"),
        new ExtensionCatalogEntry("btree_gist", "索引与性能", "B-tree GiST 操作符"),
        new ExtensionCatalogEntry("bloom", "索引与性能", "Bloom 过滤器索引"),
        new ExtensionCatalogEntry("pg_hint_plan", "索引与性能", "SQL 执行计划提示"),
        new ExtensionCatalogEntry("pg_stat_statements", "索引与性能", "SQL 执行统计"),
        new ExtensionCatalogEntry("pg_prewarm", "索引与性能", "缓冲区预热"),
        new ExtensionCatalogEntry("pg_buffercache", "索引与性能", "缓冲区查看"),
        new ExtensionCatalogEntry("hypopg", "索引与性能", "虚拟索引分析"),
        new ExtensionCatalogEntry("pg_ivm", "索引与性能", "增量物化视图"),
        new ExtensionCatalogEntry("pg_partman", "索引与性能", "分区管理"),

        // Security
        new ExtensionCatalogEntry("pgcrypto", "安全与加密", "加密函数"),
        new ExtensionCatalogEntry("pgaudit", "安全与加密", "审计日志"),
        new ExtensionCatalogEntry("anon", "安全与加密", "数据脱敏"),
        new ExtensionCatalogEntry("pgjwt", "安全与加密", "JWT 生成与验证"),
        new ExtensionCatalogEntry("pg_session_jwt", "安全与加密", "JWT 会话管理"),
        new ExtensionCatalogEntry("sslinfo", "安全与加密", "SSL 连接信息"),

        // Programming Languages
        new ExtensionCatalogEntry("plv8", "编程语言", "JavaScript 存储过程 (V8)"),
        new ExtensionCatalogEntry("plcoffee", "编程语言", "CoffeeScript 存储过程"),
        new ExtensionCatalogEntry("plpgsql_check", "编程语言", "PL/pgSQL 语法检查"),
        new ExtensionCatalogEntry("plls", "编程语言", "LiveScript 存储过程"),

        // Foreign Data & Integration
        new ExtensionCatalogEntry("postgres_fdw", "外部数据", "远程 PostgreSQL 访问"),
        new ExtensionCatalogEntry("dblink", "外部数据", "跨库查询"),
        new ExtensionCatalogEntry("file_fdw", "外部数据", "文件读取"),
        new ExtensionCatalogEntry("pg_duckdb", "外部数据", "DuckDB 分析引擎集成"),
        new ExtensionCatalogEntry("pg_mooncake", "外部数据", "Mooncake 列式存储"),

        // Operations & Diagnostics
        new ExtensionCatalogEntry("pg_repack", "运维工具", "在线表重组 (消除膨胀)"),
        new ExtensionCatalogEntry("pg_surgery", "运维工具", "损坏数据修复"),
        new ExtensionCatalogEntry("amcheck", "运维工具", "B-tree 索引校验"),
        new ExtensionCatalogEntry("pgstattuple", "运维工具", "元组级统计"),
        new ExtensionCatalogEntry("pgrowlocks", "运维工具", "行锁查看"),
        new ExtensionCatalogEntry("pg_visibility", "运维工具", "可见性映射查看"),
        new ExtensionCatalogEntry("pg_freespacemap", "运维工具", "空闲空间查看"),
        new ExtensionCatalogEntry("pg_walinspect", "运维工具", "WAL 日志检查"),
        new ExtensionCatalogEntry("pageinspect", "运维工具", "页面检查"),
        new ExtensionCatalogEntry("pg_cron", "运维工具", "定时任务调度"),
        new ExtensionCatalogEntry("pgtap", "运维工具", "数据库单元测试"),
        new ExtensionCatalogEntry("online_advisor", "运维工具", "在线优化建议"),

        // Utility Functions
        new ExtensionCatalogEntry("tablefunc", "工具函数", "交叉表/行列转换"),
        new ExtensionCatalogEntry("lo", "工具函数", "大对象管理"),
        new ExtensionCatalogEntry("moddatetime", "工具函数", "自动更新修改时间"),
        new ExtensionCatalogEntry("autoinc", "工具函数", "自增触发器"),
        new ExtensionCatalogEntry("insert_username", "工具函数", "自动记录操作用户"),
        new ExtensionCatalogEntry("refint", "工具函数", "引用完整性触发器"),
        new ExtensionCatalogEntry("tcn", "工具函数", "触发器变更通知"),
        new ExtensionCatalogEntry("rdkit", "工具函数", "化学信息学 (RDKit)")
    );

    private static final Set<String> CATALOG_NAMES = CATALOG.stream()
            .map(ExtensionCatalogEntry::name).collect(Collectors.toSet());

    // Hidden internal extensions
    private static final Set<String> HIDDEN = Set.of(
            "neon", "neon_rmgr", "neon_test_utils", "neon_utils", "plpgsql",
            "pgauditlogtofile"
    );

    // ---- Editable parameters whitelist ----

    private static final Set<String> EDITABLE_PARAMS = Set.of(
            "work_mem",
            "maintenance_work_mem",
            "statement_timeout",
            "lock_timeout",
            "idle_in_transaction_session_timeout",
            "default_statistics_target",
            "random_page_cost",
            "effective_cache_size",
            "tcp_keepalives_idle",
            "tcp_keepalives_interval",
            "log_min_duration_statement",
            "timezone"
    );

    // ---- DTOs ----

    public record ExtensionInfo(
            String name,
            String category,
            String description,
            @JsonProperty("installed_version") String installedVersion,
            boolean installed) {}

    public record ParameterInfo(
            String name,
            String setting,
            String unit,
            String category,
            String description,
            boolean editable,
            String context) {}

    // ---- Extension operations ----

    public List<ExtensionInfo> listExtensions(TenantEntity tenant, String dbId) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // If database is not running, return catalog only (all as not installed)
        if (db.getStatus() != DatabaseStatus.RUNNING) {
            return CATALOG.stream()
                    .map(e -> new ExtensionInfo(e.name(), e.category(), e.description(), null, false))
                    .toList();
        }

        // Query installed extensions from the database
        Map<String, String> installed = new LinkedHashMap<>();
        try (Connection conn = getConnection(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT extname, extversion FROM pg_extension")) {
            while (rs.next()) {
                installed.put(rs.getString("extname"), rs.getString("extversion"));
            }
        } catch (SQLException e) {
            log.warn("Failed to query extensions for db {}: {}", dbId, e.getMessage());
            // Return catalog without install status
            return CATALOG.stream()
                    .map(entry -> new ExtensionInfo(entry.name(), entry.category(), entry.description(), null, false))
                    .toList();
        }

        return CATALOG.stream()
                .map(entry -> new ExtensionInfo(
                        entry.name(), entry.category(), entry.description(),
                        installed.get(entry.name()),
                        installed.containsKey(entry.name())))
                .toList();
    }

    public void enableExtension(TenantEntity tenant, String dbId, String name) {
        validateExtensionName(name);
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS \"" + name + "\" CASCADE");
        } catch (SQLException e) {
            throw new ServiceException("Failed to enable extension " + name + ": " + e.getMessage(), e);
        }
    }

    public void disableExtension(TenantEntity tenant, String dbId, String name) {
        validateExtensionName(name);
        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("DROP EXTENSION IF EXISTS \"" + name + "\" CASCADE");
        } catch (SQLException e) {
            throw new ServiceException("Failed to disable extension " + name + ": " + e.getMessage(), e);
        }
    }

    // ---- Parameter operations ----

    public List<ParameterInfo> listParameters(TenantEntity tenant, String dbId) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        if (db.getStatus() != DatabaseStatus.RUNNING) {
            throw new BadRequestException("Database is not running. Start it to view parameters.");
        }

        List<ParameterInfo> result = new ArrayList<>();
        try (Connection conn = getConnection(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, setting, unit, category, short_desc, context " +
                     "FROM pg_settings WHERE context != 'internal' ORDER BY category, name")) {
            while (rs.next()) {
                String paramName = rs.getString("name");
                // Skip neon internal params
                if (paramName.startsWith("neon.")) continue;
                result.add(new ParameterInfo(
                        paramName,
                        rs.getString("setting"),
                        rs.getString("unit"),
                        rs.getString("category"),
                        rs.getString("short_desc"),
                        EDITABLE_PARAMS.contains(paramName),
                        rs.getString("context")));
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to query parameters: " + e.getMessage(), e);
        }
        return result;
    }

    public void updateParameter(TenantEntity tenant, String dbId, String name, String value) {
        if (!EDITABLE_PARAMS.contains(name)) {
            throw new BadRequestException("Parameter '" + name + "' is not user-editable");
        }
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Parameter value cannot be empty");
        }

        DatabaseEntity db = findDatabase(tenant, dbId);
        try (Connection conn = getConnection(db);
             PreparedStatement ps = conn.prepareStatement(
                     "ALTER DATABASE " + db.getName() + " SET " + name + " = ?")) {
            ps.setString(1, value);
            ps.execute();
        } catch (SQLException e) {
            throw new ServiceException("Failed to update parameter " + name + ": " + e.getMessage(), e);
        }
    }

    // ---- Helpers ----

    private void validateExtensionName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Extension name cannot be empty");
        }
        if (HIDDEN.contains(name)) {
            throw new BadRequestException("Extension '" + name + "' cannot be managed");
        }
        if (!CATALOG_NAMES.contains(name)) {
            throw new BadRequestException("Unknown extension: " + name);
        }
    }

    private DatabaseEntity findDatabase(TenantEntity tenant, String dbId) {
        return databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
    }

    private Connection getConnection(DatabaseEntity db) throws SQLException {
        databaseService.wakeCompute(db);

        if (db.getComputeHost() == null) {
            DatabaseEntity refreshed = databaseRepository.findById(db.getId()).orElse(db);
            db.setComputeHost(refreshed.getComputeHost());
            db.setComputePort(refreshed.getComputePort());
        }

        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db.getName();

        Connection conn = null;
        for (int i = 0; i < 5; i++) {
            try {
                conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
                break;
            } catch (SQLException e) {
                if (i == 4) throw e;
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        return conn;
    }
}
