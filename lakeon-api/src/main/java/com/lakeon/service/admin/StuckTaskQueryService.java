package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only stuck task query across known async task tables.
 * Mirrors the dbay-sre-mcp 0.2.0 stuck_task_query Python tool;
 * graceful UndefinedTable handling preserves robustness.
 */
@Service
public class StuckTaskQueryService {

    private final EntityManager em;

    public StuckTaskQueryService(EntityManager em) {
        this.em = em;
    }

    private record TableSpec(String tableName, String sql) {}

    private static final List<TableSpec> SOURCES = List.of(
            new TableSpec("wiki_run_logs",
                    """
                    SELECT id, kb_id, task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM wiki_run_logs
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """),
            new TableSpec("agentfs_jobs",
                    """
                    SELECT id, NULL::text AS kb_id, job_type AS task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM agentfs_jobs
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """),
            new TableSpec("kb_processing_tasks",
                    """
                    SELECT id, kb_id, task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM kb_processing_tasks
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """));

    private static final List<String> COLUMNS =
            List.of("task_id", "kb_id", "task_type", "status", "started_at", "age_sec");

    /**
     * Normalises the raw result list from {@link Query#getResultList()}.
     * Hibernate returns {@code List<Object[]>} for multi-column native queries in production.
     * In unit tests using {@code List.of(new Object[]{...})}, Java varargs unwrapping may
     * produce a flat {@code List<Object>} instead; this method re-wraps that case.
     */
    private static List<Object[]> toRowList(List<Object> rawRows, int columnCount) {
        if (rawRows.isEmpty()) return List.of();
        Object first = rawRows.get(0);
        if (first instanceof Object[]) {
            // Normal Hibernate path: each element is already a column array.
            @SuppressWarnings("unchecked")
            List<Object[]> typed = (List<Object[]>) (List<?>) rawRows;
            return typed;
        }
        // Flat list — Java varargs unwrapped a single Object[] into N scalars.
        // Reconstruct rows of `columnCount` elements each.
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i += columnCount) {
            int end = Math.min(i + columnCount, rawRows.size());
            rows.add(rawRows.subList(i, end).toArray());
        }
        return rows;
    }

    public Map<String, Object> run(int thresholdMinutes, String typeFilter) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TableSpec src : SOURCES) {
            try {
                List<Map<String, Object>> rows = querySource(src, thresholdMinutes);
                for (Map<String, Object> row : rows) {
                    if (typeFilter != null && !typeFilter.isBlank()
                            && !typeFilter.equals(row.get("task_type"))) {
                        continue;
                    }
                    row.put("source", src.tableName());
                    tasks.add(row);
                }
            } catch (PersistenceException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (msg.contains("does not exist") || msg.contains("undefined")) {
                    warnings.add("table " + src.tableName()
                            + " does not exist in this DB; skipped");
                } else {
                    warnings.add("query against " + src.tableName() + " failed: "
                            + (ex.getMessage() == null ? "(no message)" : ex.getMessage()));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", tasks.size());
        out.put("threshold_minutes", thresholdMinutes);
        out.put("tasks", tasks);
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true,
            noRollbackFor = PersistenceException.class)
    protected List<Map<String, Object>> querySource(TableSpec src, int thresholdMinutes) {
        Query q = em.createNativeQuery(src.sql());
        q.setParameter("threshold_minutes", thresholdMinutes);
        q.setMaxResults(50);
        @SuppressWarnings("unchecked")
        List<Object> rawRows = q.getResultList();
        List<Object[]> rows = toRowList(rawRows, COLUMNS.size());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < COLUMNS.size() && i < row.length; i++) {
                r.put(COLUMNS.get(i), row[i]);
            }
            out.add(r);
        }
        return out;
    }
}
