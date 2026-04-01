package com.lakeon.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class LogQueryService {
    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    @Value("${lakeon.log.db-dsn:}")
    private String logDbDsn;

    // Helper: execute query, return list of maps
    private List<Map<String, Object>> query(String sql, Object... params) {
        if (logDbDsn == null || logDbDsn.isBlank()) {
            return List.of(); // Log DB not configured
        }
        try (Connection conn = DriverManager.getConnection(logDbDsn);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            log.error("Log query failed: {}", e.getMessage());
            return List.of();
        }
    }

    // Convert "1h"/"30m"/"2d" to PostgreSQL interval
    private String parseInterval(String since) {
        if (since == null || since.isBlank()) return "1 hour";
        char unit = since.charAt(since.length() - 1);
        String val = since.substring(0, since.length() - 1);
        return switch (unit) {
            case 'm' -> val + " minutes";
            case 'h' -> val + " hours";
            case 'd' -> val + " days";
            default -> "1 hour";
        };
    }

    public List<Map<String, Object>> search(String component, String level, String keyword,
                                             String tenantId, String since, int limit) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("ts > now() - ?::interval");
        params.add(parseInterval(since));

        if (component != null && !component.isBlank()) {
            conditions.add("component = ?");
            params.add(component);
        }
        if (level != null && !level.isBlank()) {
            conditions.add("level = ?");
            params.add(level.toUpperCase());
        }
        if (tenantId != null && !tenantId.isBlank()) {
            conditions.add("tenant_id = ?");
            params.add(tenantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("to_tsvector('simple', msg) @@ plainto_tsquery('simple', ?)");
            params.add(keyword);
        }

        String sql = "SELECT ts, level, component, request_id, tenant_id, logger, msg, duration_ms " +
                "FROM logs WHERE " + String.join(" AND ", conditions) +
                " ORDER BY ts DESC LIMIT ?";
        params.add(limit);
        return query(sql, params.toArray());
    }

    public List<Map<String, Object>> trace(String requestId) {
        return query("SELECT ts, level, component, logger, msg, duration_ms, extra FROM logs WHERE request_id = ? ORDER BY ts",
                requestId);
    }

    public List<Map<String, Object>> errors(String since, String component) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("level IN ('ERROR', 'WARN')");
        conditions.add("ts > now() - ?::interval");
        params.add(parseInterval(since));
        if (component != null && !component.isBlank()) {
            conditions.add("component = ?");
            params.add(component);
        }
        return query("SELECT ts, level, component, request_id, tenant_id, msg FROM logs WHERE " +
                String.join(" AND ", conditions) + " ORDER BY ts DESC LIMIT 200", params.toArray());
    }

    public Map<String, Object> stats(String since) {
        String interval = parseInterval(since);
        List<Map<String, Object>> counts = query(
                "SELECT component, level, count(*) as count FROM logs WHERE ts > now() - ?::interval GROUP BY component, level ORDER BY component, count DESC",
                interval);
        List<Map<String, Object>> slow = query(
                "SELECT ts, component, request_id, msg, duration_ms FROM logs WHERE duration_ms IS NOT NULL AND ts > now() - ?::interval ORDER BY duration_ms DESC LIMIT 10",
                interval);
        return Map.of("stats", counts, "slow_top10", slow);
    }
}
