package com.lakeon.connector;

import com.lakeon.model.dto.SourceTableInfo;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Component
public class PostgresConnectorAdapter {
    public Map<String, Object> test(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl(snapshot), jdbcProperties(snapshot));
             Statement stmt = conn.createStatement()) {
            ResultSet versionRs = stmt.executeQuery("SELECT version()");
            if (versionRs.next()) {
                result.put("version", versionRs.getString(1));
            }
            result.put("ok", true);
            try {
                ResultSet walRs = stmt.executeQuery("SHOW wal_level");
                if (walRs.next()) {
                    result.put("wal_level", walRs.getString(1));
                }
            } catch (Exception ignored) {
                result.put("wal_level", "unknown");
            }
            try {
                ResultSet replRs = stmt.executeQuery("SELECT rolreplication FROM pg_roles WHERE rolname = current_user");
                result.put("has_replication", replRs.next() && replRs.getBoolean(1));
            } catch (Exception ignored) {
                result.put("has_replication", false);
            }
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    public List<SourceTableInfo> listTables(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        String sql = "SELECT t.table_schema, t.table_name, COALESCE(c.reltuples::bigint, 0) " +
            "FROM information_schema.tables t " +
            "LEFT JOIN pg_class c ON c.relname = t.table_name " +
            "AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = t.table_schema) " +
            "WHERE t.table_type = 'BASE TABLE' " +
            "AND t.table_schema NOT IN ('pg_catalog', 'information_schema') " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM pg_depend d " +
            "  WHERE d.classid = 'pg_class'::regclass " +
            "  AND d.objid = c.oid " +
            "  AND d.deptype = 'e'" +
            ") " +
            "ORDER BY t.table_schema, t.table_name";

        List<SourceTableInfo> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl(snapshot), jdbcProperties(snapshot));
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(new SourceTableInfo(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
            return tables;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list source tables: " + e.getMessage(), e);
        }
    }

    private String jdbcUrl(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        return "jdbc:postgresql://" + snapshot.host() + ":" + snapshot.port() + "/" + snapshot.dbname();
    }

    private Properties jdbcProperties(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        Properties props = new Properties();
        props.setProperty("user", snapshot.user());
        props.setProperty("password", snapshot.password());
        props.setProperty("loginTimeout", "5");
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "10");
        return props;
    }
}
