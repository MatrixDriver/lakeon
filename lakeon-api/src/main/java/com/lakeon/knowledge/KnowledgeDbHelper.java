package com.lakeon.knowledge;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared helper for resolving compute database JDBC connections for knowledge base operations.
 * Extracts the duplicated credential/connstr logic from KnowledgeService.
 */
@Component
public class KnowledgeDbHelper {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDbHelper.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DatabaseRepository databaseRepository;

    public KnowledgeDbHelper(KnowledgeBaseRepository knowledgeBaseRepository,
                             DatabaseRepository databaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.databaseRepository = databaseRepository;
    }

    /**
     * Resolve KB -> databaseId -> compute pod -> JDBC URL.
     */
    public String resolveJdbcUrl(String tenantId, String kbId) {
        String connstr = resolveConnstr(tenantId, kbId);
        return connstrToJdbc(connstr);
    }

    /**
     * Resolve KB -> databaseId -> compute connection string.
     */
    public String resolveConnstr(String tenantId, String kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        if (kb.getStatus() != KnowledgeBaseStatus.READY) {
            throw new BadRequestException("Knowledge base is not ready. Current status: " + kb.getStatus());
        }
        String databaseId = kb.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Knowledge base has no backing database");
        }
        return resolveComputeConnstr(databaseId, tenantId);
    }

    /**
     * Extract username from a Neon-style connection string.
     * Format: postgresql://user:pass@host:port/db
     */
    public String extractUser(String connstr) {
        String raw = connstr.replaceFirst("^postgres(ql)?://", "");
        int atIdx = raw.indexOf('@');
        if (atIdx >= 0) {
            String userInfo = raw.substring(0, atIdx);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                return userInfo.substring(0, colonIdx);
            }
            return userInfo;
        }
        return "cloud_admin";
    }

    /**
     * Extract password from a Neon-style connection string.
     */
    public String extractPassword(String connstr) {
        String raw = connstr.replaceFirst("^postgres(ql)?://", "");
        int atIdx = raw.indexOf('@');
        if (atIdx >= 0) {
            String userInfo = raw.substring(0, atIdx);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                return userInfo.substring(colonIdx + 1);
            }
        }
        return "cloud-admin-internal";
    }

    /**
     * Get a JDBC Connection for a KB's compute database.
     */
    public Connection getComputeConnection(String tenantId, String kbId) throws SQLException {
        String connstr = resolveConnstr(tenantId, kbId);
        String jdbcUrl = connstrToJdbc(connstr);
        String user = extractUser(connstr);
        String pass = extractPassword(connstr);
        return DriverManager.getConnection(jdbcUrl, user, pass);
    }

    // ── Internal helpers (same logic as KnowledgeService) ──────────

    String resolveComputeConnstr(String databaseId, String tenantId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));

        // 1. Direct to compute pod
        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        boolean isProxyHost = host != null && (host.contains("dbay.cloud") || host.contains("proxy."));
        if (host != null && !host.isBlank() && !isProxyHost) {
            return "postgresql://cloud_admin@" + host + ":" + port + "/" + db.getName();
        }

        // 2. Via internal proxy with DB credentials
        String dbUser = db.getDbUser();
        String dbPass = db.getDbPassword();
        String neonTenantId = db.getNeonTenantId();
        if (dbUser != null && neonTenantId != null) {
            String pass = dbPass != null ? dbPass : "";
            return "postgresql://" + dbUser + ":" + pass
                    + "@proxy.lakeon.svc.cluster.local:4432/" + db.getName()
                    + "?options=endpoint=" + neonTenantId + "&sslmode=require";
        }

        // 3. Fallback: parse connectionUri
        String connUri = db.getConnectionUri();
        if (connUri != null && !connUri.isBlank()) {
            connUri = connUri.replace("%3D", "=");
            connUri = connUri.replace("pg.dbay.cloud:4432", "proxy.lakeon.svc.cluster.local:4432");
            String dbPassword = db.getDbPassword();
            if (dbPassword != null && !connUri.contains(":" + dbPassword + "@")) {
                connUri = connUri.replaceFirst("://([^:@]+)@", "://$1:" + dbPassword + "@");
            }
            if (!connUri.contains("sslmode=")) {
                connUri += (connUri.contains("?") ? "&" : "?") + "sslmode=require";
            }
            return connUri;
        }

        throw new BadRequestException("Database has no connection info. id=" + databaseId + " status=" + db.getStatus());
    }

    String connstrToJdbc(String connstr) {
        String withoutScheme = connstr.replaceFirst("^postgres(ql)?://", "");
        int atIdx = withoutScheme.indexOf('@');
        if (atIdx >= 0) {
            withoutScheme = withoutScheme.substring(atIdx + 1);
        }
        return "jdbc:postgresql://" + withoutScheme;
    }
}
