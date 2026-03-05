package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.DatabaseQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/databases/{dbId}")
public class DatabaseQueryController {
    private final DatabaseQueryService queryService;

    public DatabaseQueryController(DatabaseQueryService queryService) {
        this.queryService = queryService;
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
        return queryService.executeQuery(tenant, dbId, request.sql());
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
}
