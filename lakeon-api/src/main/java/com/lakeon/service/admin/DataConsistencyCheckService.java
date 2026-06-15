package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only invariant checks against lakeon-api production DB.
 * Mirrors the production SRE consistency rules. Returned shape adds
 * {@code severity}, {@code self_healable}
 * and {@code max_age_seconds} so downstream watchers can suppress noisy
 * transient violations (e.g. cold-start windows that L3 reconcilers will fix).
 */
@Service
public class DataConsistencyCheckService {

    private final EntityManager em;

    public DataConsistencyCheckService(EntityManager em) {
        this.em = em;
    }

    /**
     * @param ageColumn  name of the column carrying age-in-seconds; null if rule has no age data.
     * @param selfHealable  true if the system has a periodic auto-recovery for this invariant
     *                      (e.g. {@code ComputePodReconcileService} for {@code db_ready_implies_pod_running}).
     */
    private record RuleSpec(String description, String sql,
                            List<String> columns, List<String> params,
                            String ageColumn, boolean selfHealable) {}

    private static final Map<String, RuleSpec> RULES = Map.of(
            "kb_implies_db_id", new RuleSpec(
                    "Knowledge bases marked READY but with NULL database_id (event timing bug)",
                    """
                    SELECT id, name, tenant_id, database_id
                    FROM knowledge_bases
                    WHERE status = 'READY' AND database_id IS NULL
                    """,
                    List.of("kb_id", "name", "tenant_id", "db_id"),
                    List.of(),
                    null, false),
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
                    List.of("threshold_minutes"),
                    "age_sec", false),
            // Filter `updated_at < NOW() - 7 minutes` excludes:
            //   - young pods inside ComputePodReconcileService 420s protection (cold-start in flight)
            //   - L3 60s scan-interval gap right after a pod IP drift
            // L3 lives at ComputePodReconcileService; if a row stays in this state
            // past 7 minutes, L3 has already had ≥6 chances to fix it — it's a real bug.
            // self_healable=true tells the watcher this rule has automatic recovery.
            "db_ready_implies_pod_running", new RuleSpec(
                    "Databases marked RUNNING but compute_host is unknown / pod missing for > 7min",
                    """
                    SELECT id, name, tenant_id, status, compute_host,
                           EXTRACT(EPOCH FROM (NOW() - updated_at))::int AS age_sec
                    FROM database_instances
                    WHERE status = 'RUNNING'
                      AND (compute_host IS NULL OR compute_host = '')
                      AND updated_at < NOW() - INTERVAL '7 minutes'
                    """,
                    List.of("db_id", "name", "tenant_id", "status", "compute_host", "age_sec"),
                    List.of(),
                    "age_sec", true));
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

    /**
     * Severity policy:
     *   ERROR — count ≥ 5, OR max_age > 2h (chronic / wide-blast)
     *   INFO  — count == 1 AND max_age < 30min (single transient; for self-healable
     *           rules the L3 reconciler will likely clean it up)
     *   WARN  — otherwise
     * Watchers should suppress INFO (no incident, no page).
     */
    static String classifySeverity(int count, OptionalInt maxAgeSeconds) {
        if (count >= 5) return "ERROR";
        if (maxAgeSeconds.isPresent() && maxAgeSeconds.getAsInt() > 7200) return "ERROR";
        if (count == 1 && maxAgeSeconds.orElse(Integer.MAX_VALUE) < 1800) return "INFO";
        return "WARN";
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
        int maxAgeSec = -1;
        int ageColumnIdx = spec.ageColumn() == null ? -1 : spec.columns().indexOf(spec.ageColumn());
        for (Object[] row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < spec.columns().size() && i < row.length; i++) {
                r.put(spec.columns().get(i), row[i]);
            }
            if (ageColumnIdx >= 0 && ageColumnIdx < row.length && row[ageColumnIdx] instanceof Number n) {
                int age = n.intValue();
                if (age > maxAgeSec) maxAgeSec = age;
            }
            violations.add(r);
        }

        OptionalInt maxAge = maxAgeSec >= 0 ? OptionalInt.of(maxAgeSec) : OptionalInt.empty();
        String severity = violations.isEmpty() ? "OK" : classifySeverity(violations.size(), maxAge);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", violations.isEmpty());
        out.put("rule", rule);
        out.put("description", spec.description());
        out.put("count", violations.size());
        out.put("severity", severity);
        out.put("self_healable", spec.selfHealable());
        if (maxAge.isPresent()) out.put("max_age_seconds", maxAge.getAsInt());
        out.put("violations", violations);
        return out;
    }
}
