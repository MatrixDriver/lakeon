package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.event.BranchChangedEvent;
import com.lakeon.model.event.DatabaseChangedEvent;
import com.lakeon.model.event.TenantChangedEvent;
import com.lakeon.obs.LakeonObsClient;
import com.lakeon.obs.ManifestObjects;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the per-tenant manifest ({@code tenants/{tenantId}/_manifest.json}) and
 * the global owners-index shard ({@code _global/owners/{shard}.idx}) to OBS after
 * the database transaction that produced the change has committed.
 *
 * <p>Listens to {@link TenantChangedEvent}, {@link DatabaseChangedEvent} and
 * {@link BranchChangedEvent} on {@link TransactionPhase#AFTER_COMMIT} and runs
 * the OBS work {@link Async asynchronously} so request latency is unaffected.
 *
 * <p>Each write uses optimistic concurrency: if the object already exists we read
 * its current ETag first, then pass it as {@code If-Match} on the new PUT. A
 * {@link com.obs.services.exception.ObsException} or any other failure delegates
 * the (tenantId, kind) tuple to the (optional) {@link ManifestRetryQueue} so a
 * background sweeper can drain it later — Task 17 wires the queue in.
 */
@Component
public class ManifestWriter {

    private static final Logger log = LoggerFactory.getLogger(ManifestWriter.class);

    private final TenantRepository tenantRepo;
    private final DatabaseRepository dbRepo;
    private final BranchRepository branchRepo;
    private final LakeonObsClient obs;
    private final ObjectMapper om;
    private final ManifestRetryQueue retryQueue;

    public ManifestWriter(TenantRepository tenantRepo,
                          DatabaseRepository dbRepo,
                          BranchRepository branchRepo,
                          LakeonObsClient obs,
                          ObjectMapper om,
                          @Autowired(required = false) ManifestRetryQueue retryQueue) {
        this.tenantRepo = tenantRepo;
        this.dbRepo = dbRepo;
        this.branchRepo = branchRepo;
        this.obs = obs;
        this.om = om;
        this.retryQueue = retryQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTenantChanged(TenantChangedEvent e) {
        writeManifestForTenant(e.tenantId());
        updateOwnersIndexForTenant(e.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDatabaseChanged(DatabaseChangedEvent e) {
        writeManifestForTenant(e.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBranchChanged(BranchChangedEvent e) {
        writeManifestForTenant(e.tenantId());
    }

    void writeManifestForTenant(String tenantId) {
        try {
            TenantEntity tenant = tenantRepo.findById(tenantId).orElse(null);
            if (tenant == null) {
                log.debug("ManifestWriter: tenant {} not found, skipping manifest write", tenantId);
                return;
            }
            ManifestObjects.TenantManifest manifest = buildManifest(tenant);
            String body = om.writeValueAsString(manifest);
            String key = "tenants/" + tenantId + "/_manifest.json";
            String ifMatch = obs.exists(key) ? obs.getObject(key).etag() : null;
            obs.putObject(key, body, ifMatch);
        } catch (Exception ex) {
            log.error("ManifestWriter failed for tenant {}, enqueue retry", tenantId, ex);
            if (retryQueue != null) {
                retryQueue.enqueue(tenantId, "tenant_manifest", ex.getMessage());
            }
        }
    }

    private ManifestObjects.TenantManifest buildManifest(TenantEntity tenant) {
        List<DatabaseEntity> dbs = dbRepo.findAllByTenantId(tenant.getId());
        List<ManifestObjects.DatabaseEntry> dbEntries = dbs.stream().map(db -> {
            List<ManifestObjects.BranchEntry> branches = branchRepo.findAllByDatabaseId(db.getId())
                    .stream()
                    .map(this::toBranchEntry)
                    .collect(Collectors.toList());
            return new ManifestObjects.DatabaseEntry(
                    db.getId(),
                    db.getNeonTenantId(),
                    db.getName(),
                    db.getNeonTimelineId(),
                    db.getCreatedAt(),
                    db.getDeletedAt(),
                    branches);
        }).collect(Collectors.toList());
        return new ManifestObjects.TenantManifest(
                1,
                tenant.getId(),
                tenant.getEmail(),
                tenant.getCreatedAt(),
                Instant.now(),
                System.currentTimeMillis(),
                dbEntries);
    }

    private ManifestObjects.BranchEntry toBranchEntry(BranchEntity b) {
        // BranchEntity does not currently persist a start LSN; emit null so the JSON
        // field is omitted (@JsonInclude(NON_NULL)). When LSN tracking lands the
        // accessor will replace the literal null.
        return new ManifestObjects.BranchEntry(
                b.getId(),
                b.getParentBranchId(),
                null);
    }

    void updateOwnersIndexForTenant(String tenantId) {
        try {
            TenantEntity tenant = tenantRepo.findById(tenantId).orElse(null);
            if (tenant == null || tenant.getEmail() == null) {
                log.debug("ManifestWriter: tenant {} missing or has no email, skipping owners.idx", tenantId);
                return;
            }
            String email = tenant.getEmail();
            String shard = emailShard(email);
            String key = "_global/owners/" + shard + ".idx";

            String oldEtag = null;
            Map<String, List<String>> owners = new HashMap<>();
            if (obs.exists(key)) {
                LakeonObsClient.ObsGetResult r = obs.getObject(key);
                oldEtag = r.etag();
                ManifestObjects.OwnersIndex idx = om.readValue(r.content(), ManifestObjects.OwnersIndex.class);
                if (idx.owners() != null) {
                    owners = new HashMap<>(idx.owners());
                }
            }
            owners.computeIfAbsent(email, k -> new ArrayList<>());
            if (!owners.get(email).contains(tenantId)) {
                owners.get(email).add(tenantId);
            }
            ManifestObjects.OwnersIndex newIdx = new ManifestObjects.OwnersIndex(1, Instant.now(), owners);
            obs.putObject(key, om.writeValueAsString(newIdx), oldEtag);
        } catch (Exception ex) {
            log.error("OwnersIndex update failed for tenant {}", tenantId, ex);
            if (retryQueue != null) {
                retryQueue.enqueue(tenantId, "owners_index", ex.getMessage());
            }
        }
    }

    static String emailShard(String email) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x", h[0] & 0xff);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
