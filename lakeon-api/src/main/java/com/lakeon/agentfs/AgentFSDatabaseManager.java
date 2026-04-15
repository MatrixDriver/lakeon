package com.lakeon.agentfs;

import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/**
 * Provisions and routes per-tenant AgentFS databases.
 *
 * <p>First time a tenant uses AgentFS, we:
 *   1) create a new Lakebase database (name like agentfs_xxxxxxxx),
 *   2) wait for it to be READY,
 *   3) connect with admin creds and create the `files` table,
 *   4) record (tenant_id → database_id) in agentfs_assignments.
 *
 * <p>Subsequent calls: lookup the assignment, connect to that DB, run SQL.
 */
@Component
public class AgentFSDatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(AgentFSDatabaseManager.class);

    /** Schema applied to each new AgentFS database (NOT the metadata DB). */
    private static final String FILES_SCHEMA = """
        CREATE TABLE IF NOT EXISTS files (
            path        TEXT PRIMARY KEY,
            kind        VARCHAR(8)  NOT NULL,
            size        BIGINT      NOT NULL DEFAULT 0,
            mtime_ns    BIGINT      NOT NULL,
            etag        VARCHAR(64) NOT NULL,
            properties  JSONB       NOT NULL DEFAULT '{}'::jsonb,
            data        BYTEA,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS files_path_pattern ON files(path text_pattern_ops);
        CREATE INDEX IF NOT EXISTS files_kind ON files(kind);
        """;

    private final AgentFSAssignmentRepository assignmentRepo;
    private final DatabaseService databaseService;
    private final DatabaseRepository databaseRepository;

    @Autowired
    public AgentFSDatabaseManager(AgentFSAssignmentRepository assignmentRepo,
                                  @Lazy DatabaseService databaseService,
                                  DatabaseRepository databaseRepository) {
        this.assignmentRepo = assignmentRepo;
        this.databaseService = databaseService;
        this.databaseRepository = databaseRepository;
    }

    /**
     * Get a JDBC connection to the tenant's AgentFS database. Auto-provisions
     * on first use. Caller is responsible for closing.
     */
    public Connection openConnection(TenantEntity tenant) throws SQLException {
        DatabaseEntity db = ensureProvisioned(tenant);
        return openAdmin(db);
    }

    /** Return tenant's AgentFS DatabaseEntity, blocking on provision if needed.
     *  NOT @Transactional: we deliberately want to see fresh DB status on each
     *  poll while we wait for another tx (DatabaseProvisioningService) to commit
     *  the RUNNING transition. */
    public DatabaseEntity ensureProvisioned(TenantEntity tenant) {
        var existing = assignmentRepo.findByTenantId(tenant.getId());
        if (existing.isPresent()) {
            DatabaseEntity db = databaseRepository.findById(existing.get().getDatabaseId())
                    .orElseThrow(() -> new ServiceException("agentfs database missing for tenant " + tenant.getId()));
            // If DB still PROVISIONING at our table level but the underlying database
            // has become READY, complete the handoff.
            if (!"READY".equals(existing.get().getStatus())) {
                if (db.getStatus() == DatabaseStatus.RUNNING) {
                    initSchemaAndMarkReady(existing.get(), db);
                } else if (db.getStatus() == DatabaseStatus.ERROR) {
                    var asg = existing.get();
                    asg.setStatus("ERROR");
                    asg.setError("underlying database failed to provision");
                    assignmentRepo.save(asg);
                    throw new ServiceException("AgentFS database provision failed");
                } else {
                    throw new ServiceException("AgentFS database still provisioning, retry shortly");
                }
            }
            return db;
        }

        // First time — kick off provisioning.
        String slug = "agentfs_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("provisioning AgentFS database {} for tenant {}", slug, tenant.getId());
        DatabaseResponse resp = databaseService.create(tenant, new CreateDatabaseRequest(slug, null, null, null));

        var asg = new AgentFSAssignmentEntity();
        asg.setTenantId(tenant.getId());
        asg.setDatabaseId(resp.getId());
        asg.setStatus("PROVISIONING");
        assignmentRepo.save(asg);

        // Block briefly waiting for READY (most provisions complete in a few seconds).
        DatabaseEntity db = waitReady(resp.getId(), 30);
        if (db.getStatus() != DatabaseStatus.RUNNING) {
            throw new ServiceException("AgentFS database not READY after 30s; retry shortly");
        }
        initSchemaAndMarkReady(asg, db);
        return db;
    }

    private DatabaseEntity waitReady(String dbId, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            DatabaseEntity db = databaseRepository.findById(dbId).orElse(null);
            if (db != null && db.getStatus() == DatabaseStatus.RUNNING) return db;
            if (db != null && db.getStatus() == DatabaseStatus.ERROR) return db;
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return databaseRepository.findById(dbId).orElseThrow();
    }

    private void initSchemaAndMarkReady(AgentFSAssignmentEntity asg, DatabaseEntity db) {
        try (Connection conn = openAdmin(db);
             Statement st = conn.createStatement()) {
            for (String stmt : FILES_SCHEMA.split(";")) {
                String s = stmt.trim();
                if (!s.isEmpty()) st.execute(s);
            }
            asg.setStatus("READY");
            asg.setReadyAt(Instant.now());
            assignmentRepo.save(asg);
            log.info("AgentFS schema ready in db {}", db.getName());
        } catch (SQLException e) {
            log.error("schema init failed for db {}: {}", db.getName(), e.getMessage());
            asg.setStatus("ERROR");
            asg.setError(e.getMessage());
            assignmentRepo.save(asg);
            throw new ServiceException("schema init failed: " + e.getMessage());
        }
    }

    /** Direct connection to the per-tenant AgentFS database with admin creds. */
    private Connection openAdmin(DatabaseEntity entity) throws SQLException {
        String host = entity.getComputeHost() != null ? entity.getComputeHost() : "proxy.lakeon.svc.cluster.local";
        int port = entity.getComputePort() != 0 ? entity.getComputePort() : 55433;
        boolean directPod = entity.getComputeHost() != null;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + entity.getName()
                + (directPod ? "" : "?options=endpoint%3D" + entity.getName());
        return DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
    }
}
