package com.lakeon.agentfs;

import com.lakeon.memory.MemoryBaseEntity;
import com.lakeon.memory.MemoryBaseType;
import com.lakeon.memory.MemoryDbHelper;
import com.lakeon.memory.MemoryService;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads per-tenant agentfs_events, forwards each pending event to memory-svc
 * /agentfs/derive, and ACKs (status=done / retry / poison) based on the HTTP
 * response. Leader-elected per tenant via agentfs_forwarder_locks so multiple
 * lakeon-api replicas don't double-process.
 *
 * <p>Runs every 30s. For each READY tenant:
 *   <ol>
 *     <li>try to acquire the forwarder lock (skip if another pod holds it)</li>
 *     <li>open a connection to the tenant's agentfs DB</li>
 *     <li>seed backfill events if the events table is empty (first-ever run)</li>
 *     <li>load up to {@link #BATCH_SIZE} pending events ordered by id</li>
 *     <li>resolve the target memory base (auto-provisioning if needed)</li>
 *     <li>for each event: whitelist-filter, forward, ACK or retry</li>
 *   </ol>
 */
@Component
@ConditionalOnProperty(name = "lakeon.agentfs.forwarder.enabled",
                       havingValue = "true", matchIfMissing = true)
public class AgentFSEventForwarder {

    private static final Logger log = LoggerFactory.getLogger(AgentFSEventForwarder.class);
    private static final int BATCH_SIZE = 100;
    private static final int LOCK_SECONDS = 30;
    private static final int MAX_RETRY = 5;
    private static final String AUTO_BASE_NAME = "agentfs-claude";

    private final AgentFSAssignmentRepository asgRepo;
    private final AgentFSForwarderLockRepository lockRepo;
    private final AgentFSMemoryTargetRepository targetRepo;
    private final AgentFSDatabaseManager dbm;
    private final TenantRepository tenantRepo;
    private final MemoryService memoryService;
    private final MemoryDbHelper memoryDbHelper;
    private final MemorySvcClient memorySvc;
    private final String podId;

    public AgentFSEventForwarder(AgentFSAssignmentRepository asgRepo,
                                 AgentFSForwarderLockRepository lockRepo,
                                 AgentFSMemoryTargetRepository targetRepo,
                                 AgentFSDatabaseManager dbm,
                                 TenantRepository tenantRepo,
                                 MemoryService memoryService,
                                 MemoryDbHelper memoryDbHelper,
                                 MemorySvcClient memorySvc) {
        this.asgRepo = asgRepo;
        this.lockRepo = lockRepo;
        this.targetRepo = targetRepo;
        this.dbm = dbm;
        this.tenantRepo = tenantRepo;
        this.memoryService = memoryService;
        this.memoryDbHelper = memoryDbHelper;
        this.memorySvc = memorySvc;
        this.podId = resolvePodId();
    }

    private static String resolvePodId() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown-" + UUID.randomUUID(); }
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void tick() {
        List<AgentFSAssignmentEntity> assignments;
        try {
            assignments = asgRepo.findAll();
        } catch (Exception e) {
            log.warn("forwarder: unable to list assignments: {}", e.getMessage());
            return;
        }
        for (AgentFSAssignmentEntity a : assignments) {
            if (!"READY".equals(a.getStatus())) continue;
            int acquired;
            try {
                acquired = lockRepo.tryAcquire(a.getTenantId(), podId, LOCK_SECONDS);
            } catch (Exception e) {
                log.warn("forwarder: lock acquire failed tenant={}: {}", a.getTenantId(), e.getMessage());
                continue;
            }
            if (acquired != 1) continue;
            try {
                processTenant(a);
            } catch (Exception e) {
                log.error("forwarder tenant={} error: {}", a.getTenantId(), e.getMessage(), e);
            }
        }
    }

    private void processTenant(AgentFSAssignmentEntity a) throws SQLException {
        TenantEntity tenant = tenantRepo.findById(a.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("forwarder: tenant {} not found", a.getTenantId());
            return;
        }
        try (Connection c = dbm.openConnection(tenant)) {
            seedBackfillIfEmpty(c);
            List<EventRow> events = loadPending(c, BATCH_SIZE);
            if (events.isEmpty()) return;

            String baseConnstr;
            try {
                baseConnstr = resolveTargetBaseConnstr(tenant);
            } catch (Exception e) {
                log.info("forwarder tenant={} target base unavailable: {}", tenant.getId(), e.getMessage());
                return;
            }
            if (baseConnstr == null) {
                log.info("forwarder tenant={} target base provisioning; will retry", tenant.getId());
                return;
            }

            long maxId = 0;
            for (EventRow e : events) {
                if (!PathWhitelist.accept(e.path)) {
                    markDone(c, e.id);
                    maxId = Math.max(maxId, e.id);
                    continue;
                }
                try {
                    boolean processed = forwardOne(c, baseConnstr, tenant, e);
                    if (processed) {
                        maxId = Math.max(maxId, e.id);
                    } else {
                        // Target still provisioning — stop this batch so we retry later.
                        break;
                    }
                } catch (Exception ex) {
                    bumpRetry(c, e, ex.getMessage());
                    maxId = Math.max(maxId, e.id);
                }
            }
            if (maxId > 0) {
                try {
                    lockRepo.advanceCursor(tenant.getId(), podId, maxId);
                } catch (Exception e) {
                    log.debug("forwarder: advanceCursor failed (non-fatal) tenant={}: {}",
                              tenant.getId(), e.getMessage());
                }
            }
        }
    }

    record EventRow(long id, String path, String etag, String eventType, int retryCount) {}

    private List<EventRow> loadPending(Connection c, int limit) throws SQLException {
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(
            "SELECT id, path, etag, event_type, retry_count FROM agentfs_events " +
            "WHERE status='pending' ORDER BY id LIMIT ?")) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                                          rs.getString(4), rs.getInt(5)));
                }
            }
        }
        return out;
    }

    /** Seed one-off backfill events for files already present when the trigger
     *  was installed. Skipped on subsequent ticks because the events table
     *  will have at least one row. */
    private void seedBackfillIfEmpty(Connection c) throws SQLException {
        try (PreparedStatement check = c.prepareStatement(
                "SELECT 1 FROM agentfs_events LIMIT 1");
             ResultSet rs = check.executeQuery()) {
            if (rs.next()) return;
        }
        try (PreparedStatement seed = c.prepareStatement(
            "INSERT INTO agentfs_events(path, etag, event_type) " +
            "SELECT path, etag, 'backfill' FROM files WHERE kind='file'")) {
            int n = seed.executeUpdate();
            if (n > 0) log.info("forwarder: seeded {} backfill events", n);
        }
    }

    private void markDone(Connection c, long eventId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "UPDATE agentfs_events SET status='done', processed_at=now() WHERE id=?")) {
            st.setLong(1, eventId);
            st.executeUpdate();
        }
    }

    private void bumpRetry(Connection c, EventRow e, String err) throws SQLException {
        int next = e.retryCount + 1;
        String status = next >= MAX_RETRY ? "poison" : "pending";
        try (PreparedStatement st = c.prepareStatement(
            "UPDATE agentfs_events SET retry_count=?, status=?, last_error=? WHERE id=?")) {
            st.setInt(1, next);
            st.setString(2, status);
            // Trim long errors so TEXT column stays reasonable.
            st.setString(3, err == null ? "unknown" : (err.length() > 500 ? err.substring(0, 500) : err));
            st.setLong(4, e.id);
            st.executeUpdate();
        }
    }

    /** @return true if the event was fully handled (marked done or poison);
     *          false if the target base is still provisioning and caller
     *          should stop the batch.
     *  Throws on transient errors so the caller bumps retry. */
    private boolean forwardOne(Connection c, String baseConnstr,
                               TenantEntity tenant, EventRow e) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenant.getId());
        payload.put("path", e.path);
        payload.put("source_etag", e.etag == null ? "" : e.etag);
        payload.put("source_agent", "claude");  // MVP single-agent

        if ("delete".equals(e.eventType)) {
            payload.put("op", "delete");
        } else {
            byte[] data = readFileContent(c, e.path);
            String raw = new String(data, StandardCharsets.UTF_8);
            FrontmatterParser.Parsed p = FrontmatterParser.parse(raw);
            Object ftype = p.frontmatter().get("type");
            payload.put("op", e.eventType);
            payload.put("content", p.body());
            payload.put("memory_type", PathWhitelist.frontmatterTypeToMemoryType(
                ftype == null ? null : ftype.toString()));
            payload.put("source_frontmatter", p.frontmatter());
        }

        MemorySvcClient.DeriveResponse resp = memorySvc.derive(baseConnstr, payload);
        if (resp.isAccepted()) {
            // Target base still warming up on the memory-svc side; leave pending.
            return false;
        }
        if (resp.isSuccess()) {
            markDone(c, e.id);
            return true;
        }
        throw new RuntimeException("memory-svc status=" + resp.statusCode()
                                    + " body=" + truncate(resp.body(), 200));
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private byte[] readFileContent(Connection c, String path) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT data FROM files WHERE path=?")) {
            st.setString(1, path);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return new byte[0];
                byte[] d = rs.getBytes(1);
                return d == null ? new byte[0] : d;
            }
        }
    }

    /**
     * Resolve (or lazily create) the tenant's target memory base and return a
     * memory-svc connection string for it. Returns {@code null} if the base
     * is still PROVISIONING so the caller can retry next cycle.
     */
    private String resolveTargetBaseConnstr(TenantEntity tenant) {
        AgentFSMemoryTargetEntity target = targetRepo.findByTenantId(tenant.getId()).orElse(null);
        String baseId;
        if (target != null) {
            baseId = target.getMemoryBaseId();
        } else {
            MemoryBaseEntity created;
            try {
                created = memoryService.createBase(
                    tenant,
                    AUTO_BASE_NAME,
                    "Auto-created target for Claude AgentFS derive events",
                    MemoryBaseType.BUILTIN,
                    /*embeddingModel*/ null,        // default BAAI/bge-m3
                    /*oneLlmMode*/   false,
                    /*scene*/        "DEVELOPER_TOOL",
                    /*encrypted*/    false,
                    /*encryptedDek*/ null,
                    /*kdfSalt*/      null,
                    /*embeddingDim*/ null);
            } catch (Exception e) {
                log.error("forwarder: auto-provision target base failed for tenant={}: {}",
                          tenant.getId(), e.getMessage());
                return null;
            }
            AgentFSMemoryTargetEntity newTarget = new AgentFSMemoryTargetEntity();
            newTarget.setTenantId(tenant.getId());
            newTarget.setMemoryBaseId(created.getId());
            newTarget.setAutoCreated(true);
            newTarget.setUpdatedAt(Instant.now());
            targetRepo.save(newTarget);
            log.info("forwarder: auto-created memory base {} for tenant {}",
                     created.getId(), tenant.getId());
            baseId = created.getId();
        }

        // getBase() lazily syncs PROVISIONING -> READY if the underlying DB is up.
        MemoryBaseEntity base;
        try {
            base = memoryService.getBase(tenant.getId(), baseId);
        } catch (Exception e) {
            log.warn("forwarder: target base {} not accessible for tenant={}: {}",
                     baseId, tenant.getId(), e.getMessage());
            return null;
        }
        if (!"READY".equals(base.getStatus())) {
            return null;
        }

        try {
            return memoryDbHelper.resolveConnstr(tenant.getId(), baseId);
        } catch (Exception e) {
            log.info("forwarder: resolveConnstr pending for tenant={} base={}: {}",
                     tenant.getId(), baseId, e.getMessage());
            return null;
        }
    }
}
