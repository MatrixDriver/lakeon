package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only invariant checks against lakeon-api production DB.
 * Mirrors the 4 rules originally implemented in dbay-sre-mcp 0.2.0
 * Python tool. Returned shape is identical to the Python tool so
 * downstream consumers (sre-agent watchers) need no changes.
 */
@Service
public class DataConsistencyCheckService {

    private final EntityManager em;

    public DataConsistencyCheckService(EntityManager em) {
        this.em = em;
    }

    private record RuleSpec(String description, String sql,
                            List<String> columns, List<String> params) {}

    private static final Map<String, RuleSpec> RULES = Map.of(
            "kb_implies_db_id", new RuleSpec(
                    "Knowledge bases marked READY but with NULL database_id (event timing bug)",
                    """
                    SELECT id, name, tenant_id, database_id
                    FROM knowledge_bases
                    WHERE status = 'READY' AND database_id IS NULL
                    """,
                    List.of("kb_id", "name", "tenant_id", "db_id"),
                    List.of()),
            "enqueued_implies_drained", new RuleSpec(
                    "kb_write_tasks queued/running beyond threshold (tx commit ordering or worker stall)",
                    """
                    SELECT id, kb_id, status, created_at,
                           EXTRACT(EPOCH FROM (NOW() - created_at))::int AS age_sec
                    FROM kb_write_tasks
                    WHERE status IN ('QUEUED', 'RUNNING')
                      AND created_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY created_at ASC
                    """,
                    List.of("write_id", "kb_id", "status", "created_at", "age_sec"),
                    List.of("threshold_minutes")),
            "db_ready_implies_pod_running", new RuleSpec(
                    "Databases marked RUNNING but compute_host is unknown / pod missing",
                    """
                    SELECT id, name, tenant_id, status, compute_host
                    FROM database_instances
                    WHERE status = 'RUNNING' AND (compute_host IS NULL OR compute_host = '')
                    """,
                    List.of("db_id", "name", "tenant_id", "status", "compute_host"),
                    List.of()));
    // Note: the original 'schema_seeded' rule was dropped — wiki seed pages live in OBS,
    // not in a SQL table, so there is no pure-SQL invariant to check. `WikiSchemaSeeder`
    // failures show up in lakeon-api logs (search for "schema seeder" / "wiki seed").

    public List<String> availableRules() {
        return new ArrayList<>(RULES.keySet());
    }

    /**
     * Normalises the raw result list from {@link jakarta.persistence.Query#getResultList()}.
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

    @Transactional(readOnly = true)
    public Map<String, Object> run(String rule, int thresholdMinutes) {
        if ("__list__".equals(rule)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rules", availableRules());
            Map<String, String> details = new LinkedHashMap<>();
            RULES.forEach((k, v) -> details.put(k, v.description()));
            out.put("details", details);
            return out;
        }
        RuleSpec spec = RULES.get(rule);
        if (spec == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", "unknown rule '" + rule + "'; available: " + availableRules());
            return err;
        }

        Query q = em.createNativeQuery(spec.sql());
        if (spec.params().contains("threshold_minutes")) {
            q.setParameter("threshold_minutes", thresholdMinutes);
        }
        q.setMaxResults(100);
        @SuppressWarnings("unchecked")
        List<Object> rawRows = q.getResultList();

        // Hibernate returns List<Object[]> for multi-column native queries.
        // Convert each row (Object[] or scalar) into a column map.
        List<Object[]> rows = toRowList(rawRows, spec.columns().size());

        List<Map<String, Object>> violations = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < spec.columns().size() && i < row.length; i++) {
                r.put(spec.columns().get(i), row[i]);
            }
            violations.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", violations.isEmpty());
        out.put("rule", rule);
        out.put("description", spec.description());
        out.put("count", violations.size());
        out.put("violations", violations);
        return out;
    }
}
