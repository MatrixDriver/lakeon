package com.lakeon.knowledge;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared helper for resolving compute database JDBC connections for knowledge base operations.
 * Always connects directly to the compute pod's internal IP (bypasses Neon Proxy).
 */
@Component
public class KnowledgeDbHelper {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDbHelper.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DatabaseRepository databaseRepository;
    private final ComputeLifecycleService computeLifecycleService;
    private final ComputePodManager computePodManager;

    public KnowledgeDbHelper(KnowledgeBaseRepository knowledgeBaseRepository,
                             DatabaseRepository databaseRepository,
                             @Lazy ComputeLifecycleService computeLifecycleService,
                             ComputePodManager computePodManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.databaseRepository = databaseRepository;
        this.computeLifecycleService = computeLifecycleService;
        this.computePodManager = computePodManager;
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
        return resolveComputeConnstr(databaseId, tenantId, null);
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

    /**
     * Get a JDBC Connection directly by databaseId (for TABLE type KBs using sourceDatabaseId).
     */
    public Connection getComputeConnectionByDbId(String tenantId, String databaseId) throws SQLException {
        String connstr = resolveComputeConnstr(databaseId, tenantId);
        String jdbcUrl = connstrToJdbc(connstr);
        String user = extractUser(connstr);
        String pass = extractPassword(connstr);
        return DriverManager.getConnection(jdbcUrl, user, pass);
    }

    // ── Internal helpers ──────────

    String resolveComputeConnstr(String databaseId, String tenantId) {
        return resolveComputeConnstr(databaseId, tenantId, null);
    }

    /**
     * Wake the compute pod and connect directly via internal pod IP.
     * Always bypasses Neon Proxy to avoid "Control plane request failed" errors.
     */
    String resolveComputeConnstr(String databaseId, String tenantId, String plaintextPassword) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));

        // Wake compute pod (no-op if already running)
        computeLifecycleService.wakeCompute(databaseId);

        // Get direct pod IP
        String podName = "compute-" + databaseId.replace("_", "-");
        if (!computePodManager.waitForPodReady(podName, 360_000)) {
            throw new RuntimeException("Compute pod not ready: " + podName);
        }
        String podIp = computePodManager.getPodIp(podName);
        if (podIp == null) {
            throw new RuntimeException("Compute pod IP not available for: " + podName);
        }

        log.info("resolveComputeConnstr: db={} pod={} ip={}", databaseId, podName, podIp);
        return "postgresql://cloud_admin:cloud-admin-internal@" + podIp + ":55433/" + db.getName()
                + "?sslmode=disable";
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
