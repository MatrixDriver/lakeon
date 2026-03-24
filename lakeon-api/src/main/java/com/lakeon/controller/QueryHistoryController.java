package com.lakeon.controller;

import com.lakeon.model.entity.QueryHistoryEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.QueryHistoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tenant-level query history (across all databases).
 */
@RestController
@RequestMapping("/api/v1/query-history")
public class QueryHistoryController {

    private final QueryHistoryRepository queryHistoryRepository;

    public QueryHistoryController(QueryHistoryRepository queryHistoryRepository) {
        this.queryHistoryRepository = queryHistoryRepository;
    }

    @GetMapping
    public Map<String, Object> listAll(HttpServletRequest req,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size,
                                       @RequestParam(required = false) String q) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200));
        Page<QueryHistoryEntity> result;
        if (q != null && !q.isBlank()) {
            result = queryHistoryRepository.searchByKeyword(tenant.getId(), q.trim(), pageable);
        } else {
            result = queryHistoryRepository.findByTenantIdOrderByCreatedAtDesc(tenant.getId(), pageable);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result.getContent().stream().map(this::toMap).toList());
        response.put("total", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("pages", result.getTotalPages());
        return response;
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void clearAll(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        queryHistoryRepository.deleteAllByTenantId(tenant.getId());
    }

    private Map<String, Object> toMap(QueryHistoryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("database_id", e.getDatabaseId());
        m.put("database_name", e.getDatabaseName());
        m.put("sql", e.getSqlText());
        m.put("success", e.isSuccess());
        m.put("row_count", e.getRowCount());
        m.put("duration_ms", e.getDurationMs());
        m.put("error", e.getErrorMessage());
        m.put("created_at", e.getCreatedAt());
        return m;
    }
}
