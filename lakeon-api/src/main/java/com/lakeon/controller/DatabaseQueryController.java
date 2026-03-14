package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.QueryHistoryEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.QueryHistoryRepository;
import com.lakeon.service.DatabaseQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases/{dbId}")
public class DatabaseQueryController {
    private final DatabaseQueryService queryService;
    private final QueryHistoryRepository queryHistoryRepository;

    public DatabaseQueryController(DatabaseQueryService queryService,
                                   QueryHistoryRepository queryHistoryRepository) {
        this.queryService = queryService;
        this.queryHistoryRepository = queryHistoryRepository;
    }

    @GetMapping("/schemas")
    public List<SchemaInfo> listSchemas(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.listSchemas(tenant, dbId);
    }

    @GetMapping("/schemas/{schema}/tables")
    public List<TableInfo> listTables(HttpServletRequest req,
                                      @PathVariable String dbId,
                                      @PathVariable String schema) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.listTables(tenant, dbId, schema);
    }

    @GetMapping("/schemas/{schema}/tables/{table}/columns")
    public List<ColumnInfo> listColumns(HttpServletRequest req,
                                        @PathVariable String dbId,
                                        @PathVariable String schema,
                                        @PathVariable String table) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.listColumns(tenant, dbId, schema, table);
    }

    @GetMapping("/schemas/{schema}/tables/{table}/indexes")
    public List<IndexInfo> listIndexes(HttpServletRequest req,
                                       @PathVariable String dbId,
                                       @PathVariable String schema,
                                       @PathVariable String table) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.listIndexes(tenant, dbId, schema, table);
    }

    @GetMapping("/schemas/{schema}/tables/{table}/constraints")
    public List<ConstraintInfo> listConstraints(HttpServletRequest req,
                                                @PathVariable String dbId,
                                                @PathVariable String schema,
                                                @PathVariable String table) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.listConstraints(tenant, dbId, schema, table);
    }

    @GetMapping("/schemas/{schema}/tables/{table}/data")
    public DataPage queryData(HttpServletRequest req,
                              @PathVariable String dbId,
                              @PathVariable String schema,
                              @PathVariable String table,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              @RequestParam(required = false) String sort,
                              @RequestParam(defaultValue = "asc") String dir) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.queryData(tenant, dbId, schema, table, page, size, sort, dir);
    }

    @PostMapping("/query")
    public QueryResult executeQuery(HttpServletRequest req,
                                    @PathVariable String dbId,
                                    @Valid @RequestBody ExecuteQueryRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        long start = System.currentTimeMillis();
        QueryResult result = null;
        Exception error = null;
        try {
            result = queryService.executeQuery(tenant, dbId, request.sql());
            return result;
        } catch (Exception e) {
            error = e;
            throw e;
        } finally {
            // Save to query history (best-effort, don't fail the request)
            try {
                long durationMs = System.currentTimeMillis() - start;
                QueryHistoryEntity history = new QueryHistoryEntity();
                history.setTenantId(tenant.getId());
                history.setDatabaseId(dbId);
                history.setSqlText(request.sql().length() > 10000
                    ? request.sql().substring(0, 10000) : request.sql());
                history.setSuccess(error == null);
                history.setDurationMs(durationMs);
                if (result != null && result.rows() != null) {
                    history.setRowCount(result.rows().size());
                }
                if (error != null) {
                    String msg = error.getMessage();
                    history.setErrorMessage(msg != null && msg.length() > 1000
                        ? msg.substring(0, 1000) : msg);
                }
                queryHistoryRepository.save(history);
            } catch (Exception ignored) {}
        }
    }

    // ── Query History ──────────────────────────────────────────────

    @GetMapping("/query-history")
    public Map<String, Object> getQueryHistory(HttpServletRequest req,
                                               @PathVariable String dbId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size,
                                               @RequestParam(required = false) String q) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200));
        Page<QueryHistoryEntity> result;
        if (q != null && !q.isBlank()) {
            result = queryHistoryRepository.searchByDatabaseAndKeyword(
                tenant.getId(), dbId, q.trim(), pageable);
        } else {
            result = queryHistoryRepository.findByTenantIdAndDatabaseIdOrderByCreatedAtDesc(
                tenant.getId(), dbId, pageable);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result.getContent().stream().map(this::historyToMap).toList());
        response.put("total", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("pages", result.getTotalPages());
        return response;
    }

    @DeleteMapping("/query-history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearQueryHistory(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryHistoryRepository.deleteAllByTenantIdAndDatabaseId(tenant.getId(), dbId);
    }

    private Map<String, Object> historyToMap(QueryHistoryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sql", e.getSqlText());
        m.put("success", e.isSuccess());
        m.put("row_count", e.getRowCount());
        m.put("duration_ms", e.getDurationMs());
        m.put("error", e.getErrorMessage());
        m.put("created_at", e.getCreatedAt());
        return m;
    }

    @GetMapping("/schemas/{schema}/tables/{table}/stats")
    public TableStats getTableStats(HttpServletRequest req,
                                    @PathVariable String dbId,
                                    @PathVariable String schema,
                                    @PathVariable String table) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.getTableStats(tenant, dbId, schema, table);
    }

    @PostMapping("/schemas/{schema}/tables")
    @ResponseStatus(HttpStatus.CREATED)
    public void createTable(HttpServletRequest req,
                            @PathVariable String dbId,
                            @PathVariable String schema,
                            @Valid @RequestBody CreateTableRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryService.createTable(tenant, dbId, schema, request);
    }

    @DeleteMapping("/schemas/{schema}/tables/{table}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dropTable(HttpServletRequest req,
                          @PathVariable String dbId,
                          @PathVariable String schema,
                          @PathVariable String table) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryService.dropTable(tenant, dbId, schema, table);
    }

    @PostMapping("/schemas/{schema}/tables/{table}/columns")
    @ResponseStatus(HttpStatus.CREATED)
    public void addColumn(HttpServletRequest req,
                          @PathVariable String dbId,
                          @PathVariable String schema,
                          @PathVariable String table,
                          @Valid @RequestBody CreateTableRequest.ColumnDef column) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryService.addColumn(tenant, dbId, schema, table, column);
    }

    @DeleteMapping("/schemas/{schema}/tables/{table}/columns/{column}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dropColumn(HttpServletRequest req,
                           @PathVariable String dbId,
                           @PathVariable String schema,
                           @PathVariable String table,
                           @PathVariable String column) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryService.dropColumn(tenant, dbId, schema, table, column);
    }

    @PostMapping("/schema-cache/refresh")
    public CacheRefreshResponse refreshSchemaCache(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.refreshSchemaCacheForApi(tenant, dbId);
    }

    @GetMapping("/schema-cache/status")
    public CacheStatusResponse getCacheStatus(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return queryService.getCacheStatus(tenant, dbId);
    }
}
