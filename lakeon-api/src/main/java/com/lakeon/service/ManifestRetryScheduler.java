package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.obs.LakeonObsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps the {@code _retry_queue/} prefix on OBS and re-invokes the manifest writer
 * for each pending entry.
 *
 * <h3>Re-read, don't replay</h3>
 * Critical: the scheduler does NOT store or replay the body of the failed write.
 * It re-invokes {@link ManifestWriter#writeManifestForTenant(String)} (which
 * rebuilds the manifest from current RDS state + does a fresh ETag CAS against
 * OBS) or {@link ManifestWriter#updateOwnersIndexForTenant(String)} (which
 * re-reads the existing shard, merges, and writes). Replaying a stale body would
 * silently overwrite newer changes and make race conditions worse.
 *
 * <h3>Cooldown</h3>
 * Each retry attempt that still fails causes the writer to re-enqueue a NEW
 * entry. To avoid a tight retry loop when OBS is unhealthy, we skip entries that
 * are less than 30 seconds old. The {@code @Scheduled} fixed delay (default 60s)
 * combined with this cooldown bounds the retry rate per failure.
 *
 * <h3>Success semantics</h3>
 * The writer methods swallow exceptions internally — they return normally even
 * on failure (re-enqueueing a fresh entry as a side effect). That means an
 * exception escaping a writer call here is unexpected. We treat any escape as a
 * transient hiccup, log it, and leave the entry for the next sweep cycle.
 */
@Component
public class ManifestRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ManifestRetryScheduler.class);

    /** Skip entries fresher than this so we don't loop on persistent OBS errors. */
    static final long COOLDOWN_SECONDS = 30;

    private final LakeonObsClient obs;
    private final ObjectMapper om;
    private final ManifestWriter writer;

    public ManifestRetryScheduler(LakeonObsClient obs, ObjectMapper om, ManifestWriter writer) {
        this.obs = obs;
        this.om = om;
        this.writer = writer;
    }

    /**
     * Drain pending retry entries. Runs at a fixed delay (default 60s, override
     * via {@code lakeon.manifest.retry-fixed-delay-ms}).
     */
    @Scheduled(fixedDelayString = "${lakeon.manifest.retry-fixed-delay-ms:60000}")
    public void retryPending() {
        List<LakeonObsClient.ObsListItem> items;
        try {
            items = obs.listPrefix(ManifestRetryQueue.PREFIX);
        } catch (Exception e) {
            // OBS itself is unhappy; nothing to do this cycle.
            log.warn("ManifestRetryScheduler: list failed, skipping cycle", e);
            return;
        }
        if (items.isEmpty()) {
            return;
        }
        log.debug("ManifestRetryScheduler: {} pending entries", items.size());

        for (LakeonObsClient.ObsListItem item : items) {
            processOne(item);
        }
    }

    private void processOne(LakeonObsClient.ObsListItem item) {
        ManifestRetryQueue.RetryEntry entry;
        try {
            String content = obs.getObject(item.key()).content();
            entry = om.readValue(content, ManifestRetryQueue.RetryEntry.class);
        } catch (Exception e) {
            log.warn("ManifestRetryScheduler: failed to read entry {}, will retry next cycle",
                    item.key(), e);
            return;
        }

        // Cooldown: don't hammer a freshly-enqueued entry — give the failing
        // backend a moment to recover. Each failed retry re-enqueues a new
        // entry, so this also bounds retry frequency per failure.
        if (entry.enqueuedAt() != null
                && Instant.now().minusSeconds(COOLDOWN_SECONDS).isBefore(entry.enqueuedAt())) {
            log.debug("ManifestRetryScheduler: entry {} too fresh (cooldown {}s), skipping",
                    item.key(), COOLDOWN_SECONDS);
            return;
        }

        try {
            switch (entry.kind() == null ? "" : entry.kind()) {
                case "tenant_manifest" -> writer.writeManifestForTenant(entry.tenantId());
                case "owners_index" -> writer.updateOwnersIndexForTenant(entry.tenantId());
                default -> {
                    log.warn("ManifestRetryScheduler: unknown kind '{}' in entry {}, dropping",
                            entry.kind(), item.key());
                }
            }
            // Writer swallows its own exceptions and re-enqueues a fresh entry
            // on failure, so reaching here means either success or a new entry
            // already exists. Either way, the OLD entry is safe to delete.
            obs.deleteKey(item.key());
            log.info("ManifestRetryScheduler: processed tenant={} kind={} key={}",
                    entry.tenantId(), entry.kind(), item.key());
        } catch (Exception e) {
            // Should be rare: writer methods don't normally throw. Leave the
            // entry; next cycle will pick it up.
            log.warn("ManifestRetryScheduler: retry {} still failing, leaving for next cycle",
                    item.key(), e);
        }
    }
}
