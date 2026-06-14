package com.lakeon.lakebasefs;

import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.k8s.ComputePodManager;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Provisions and routes per-tenant LakebaseFS databases.
 *
 * <p>First time a tenant uses LakebaseFS, we:
 *   1) create a new Lakebase database (name like lbfs_xxxxxxxx),
 *   2) wait for it to be READY,
 *   3) connect with admin creds and create the `files` table,
 *   4) record (tenant_id → database_id) in lbfs_assignments.
 *
 * <p>Subsequent calls: lookup the assignment, connect to that DB, run SQL.
 */
@Component
public class LakebaseFSDatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(LakebaseFSDatabaseManager.class);

    /** Schema applied to each new LakebaseFS database (NOT the metadata DB).
     *  Includes per-tenant CDC: lbfs_events table + trigger fired on files
     *  inserts/updates/deletes. Executed as a single multi-statement blob
     *  because the plpgsql function body contains semicolons inside $$...$$
     *  and cannot be safely split on ';'. */
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

        CREATE TABLE IF NOT EXISTS lbfs_events (
            id          BIGSERIAL PRIMARY KEY,
            path        TEXT NOT NULL,
            etag        VARCHAR(64),
            event_type  VARCHAR(16) NOT NULL,
            status      VARCHAR(16) NOT NULL DEFAULT 'pending',
            retry_count INT NOT NULL DEFAULT 0,
            last_error  TEXT,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
            processed_at TIMESTAMPTZ
        );
        CREATE INDEX IF NOT EXISTS idx_lbfs_events_pending
            ON lbfs_events(status, id) WHERE status = 'pending';

        CREATE OR REPLACE FUNCTION lbfs_files_event_fn() RETURNS TRIGGER AS $$
        BEGIN
          IF (TG_OP = 'INSERT') THEN
            INSERT INTO lbfs_events(path, etag, event_type)
              VALUES (NEW.path, NEW.etag, 'create');
          ELSIF (TG_OP = 'UPDATE') THEN
            INSERT INTO lbfs_events(path, etag, event_type)
              VALUES (NEW.path, NEW.etag, 'update');
          ELSIF (TG_OP = 'DELETE') THEN
            INSERT INTO lbfs_events(path, etag, event_type)
              VALUES (OLD.path, OLD.etag, 'delete');
          END IF;
          RETURN NULL;
        END;
        $$ LANGUAGE plpgsql;

        DROP TRIGGER IF EXISTS lbfs_files_event_trg ON files;
        CREATE TRIGGER lbfs_files_event_trg
          AFTER INSERT OR UPDATE OR DELETE ON files
          FOR EACH ROW EXECUTE FUNCTION lbfs_files_event_fn();
        """;

    private final LakebaseFSAssignmentRepository assignmentRepo;
    private final DatabaseService databaseService;
    private final DatabaseRepository databaseRepository;
    private final ComputePodManager computePodManager;
    private final LakeonProperties props;

    @Autowired
    public LakebaseFSDatabaseManager(LakebaseFSAssignmentRepository assignmentRepo,
                                  @Lazy DatabaseService databaseService,
                                  DatabaseRepository databaseRepository,
                                  ComputePodManager computePodManager,
                                  LakeonProperties props) {
        this.assignmentRepo = assignmentRepo;
        this.databaseService = databaseService;
        this.databaseRepository = databaseRepository;
        this.computePodManager = computePodManager;
        this.props = props;
    }

    /**
     * Get a JDBC connection to the tenant's LakebaseFS database. Auto-provisions
     * on first use. Caller is responsible for closing.
     */
    public Connection openConnection(TenantEntity tenant) throws SQLException {
        DatabaseEntity db = ensureProvisioned(tenant);
        // Wake compute pod if suspended, then reload for fresh computeHost/Port.
        databaseService.ensureRunning(tenant, db.getId());
        db = databaseRepository.findById(db.getId()).orElseThrow();
        return openAdmin(db);
    }

    /** Return tenant's LakebaseFS DatabaseEntity, blocking on provision if needed.
     *  NOT @Transactional: we deliberately want to see fresh DB status on each
     *  poll while we wait for another tx (DatabaseProvisioningService) to commit
     *  the RUNNING transition. */
    public DatabaseEntity ensureProvisioned(TenantEntity tenant) {
        var existing = assignmentRepo.findByTenantId(tenant.getId());
        if (existing.isPresent()) {
            DatabaseEntity db = databaseRepository.findById(existing.get().getDatabaseId())
                    .orElseThrow(() -> new ServiceException("LakebaseFS database missing for tenant " + tenant.getId()));
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
                    throw new ServiceException("LakebaseFS database provision failed");
                } else {
                    throw new ServiceException("LakebaseFS database still provisioning, retry shortly");
                }
            }
            return db;
        }

        // First time — kick off provisioning.
        String slug = "lbfs_" + UUID.randomUUID().toString().substring(0, 8);
        log.info("provisioning LakebaseFS database {} for tenant {}", slug, tenant.getId());
        DatabaseResponse resp = databaseService.create(tenant, new CreateDatabaseRequest(slug, null, null, null));

        var asg = new LakebaseFSAssignmentEntity();
        asg.setTenantId(tenant.getId());
        asg.setDatabaseId(resp.getId());
        asg.setStatus("PROVISIONING");
        assignmentRepo.save(asg);

        // Block waiting for READY. Cold start involves compute pod creation
        // (~30s warm, up to 120s cold if node pool scale-up is needed), plus
        // Neon tenant/timeline init. 120s covers the typical worst case
        // without being so long clients give up. If the client-side retry
        // loop extends beyond this we'll surface "retry shortly" and the
        // next poll should hit the READY branch above.
        DatabaseEntity db = waitReady(resp.getId(), 120);
        if (db.getStatus() != DatabaseStatus.RUNNING) {
            throw new ServiceException("LakebaseFS database not READY after 120s; retry shortly");
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

    private void initSchemaAndMarkReady(LakebaseFSAssignmentEntity asg, DatabaseEntity db) {
        DatabaseEntity fresh = databaseRepository.findById(db.getId()).orElse(db);
        try (Connection conn = openAdmin(fresh);
             Statement st = conn.createStatement()) {
            // Execute the schema as a single multi-statement blob.
            // Naive split on ';' is unsafe because the plpgsql trigger function
            // body contains semicolons inside its $$...$$ quoted body. The
            // Postgres JDBC driver handles multi-statement execute() natively.
            st.execute(FILES_SCHEMA);
            asg.setStatus("READY");
            asg.setReadyAt(Instant.now());
            assignmentRepo.save(asg);
            log.info("LakebaseFS schema ready in db {}", db.getName());
        } catch (SQLException e) {
            log.error("schema init failed for db {}: {}", db.getName(), e.getMessage());
            asg.setStatus("ERROR");
            asg.setError(e.getMessage());
            assignmentRepo.save(asg);
            throw new ServiceException("schema init failed: " + e.getMessage());
        }
    }

    /**
     * Open an admin JDBC connection to the per-tenant LakebaseFS database.
     * <p>If the first attempt fails with a stale-host signature (e.g. compute
     * pod IP drifted and we landed on another tenant's pod — "database does
     * not exist"; or the pod is gone — "connection refused"), we ask
     * {@link ComputePodManager#reconcileComputeHost} to repair the row and
     * retry exactly once with the refreshed entity. Anything else surfaces
     * immediately.
     */
    private Connection openAdmin(DatabaseEntity entity) throws SQLException {
        try {
            return openAdminDirect(entity);
        } catch (SQLException e) {
            if (!isStaleHostError(e)) throw e;
            log.warn("openAdmin failed for db {} (likely stale host): {}; reconciling",
                    entity.getId(), e.getMessage());
            boolean recovered = computePodManager.reconcileComputeHost(entity);
            if (!recovered) {
                log.warn("reconcile could not repair db {} (pod gone); surfacing original error",
                        entity.getId());
                throw e;
            }
            DatabaseEntity fresh = databaseRepository.findById(entity.getId())
                    .orElseThrow(() -> new SQLException(
                            "DB row vanished after reconcile: " + entity.getId()));
            log.info("retrying openAdmin for db {} with reconciled host {}",
                    fresh.getId(), fresh.getComputeHost());
            return openAdminDirect(fresh);
        }
    }

    /** Single connect attempt against the current entity state — no retry. */
    private Connection openAdminDirect(DatabaseEntity entity) throws SQLException {
        String internalProxyHost = props.getProxy().getInternalHost();
        if (internalProxyHost != null && !internalProxyHost.isBlank()) {
            int port = props.getProxy().getInternalPort();
            String database = enc(entity.getName());
            String endpoint = enc(entity.getName());
            String jdbcUrl = "jdbc:postgresql://" + internalProxyHost + ":" + port + "/" + database
                    + "?sslmode=require&options=endpoint%3D" + endpoint;
            String user = entity.getDbUser() != null ? entity.getDbUser() : "cloud_admin";
            String password = entity.getDbPassword() != null ? entity.getDbPassword() : "";
            return DriverManager.getConnection(jdbcUrl, user, password);
        }

        String host = entity.getComputeHost();
        if (host == null) {
            throw new SQLException("compute host not available for db " + entity.getName()
                    + " (status=" + entity.getStatus() + ")");
        }
        int port = entity.getComputePort() != null ? entity.getComputePort() : 55433;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + entity.getName() + "?sslmode=disable";
        return DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
    }

    /** Heuristic: does this SQLException look like a stale compute host?
     *  Package-private for unit testing. */
    static boolean isStaleHostError(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("does not exist")
                || msg.contains("connection refused")
                || msg.contains("could not translate")
                || msg.contains("no route to host");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
