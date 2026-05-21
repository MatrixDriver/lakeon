package com.lakeon.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.obs.LakeonObsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent retry queue for failed manifest / owners-index writes.
 *
 * <p>Entries are durable little JSON blobs in OBS under the {@code _retry_queue/}
 * prefix. Each blob carries only the (tenantId, kind, reason, enqueuedAt) tuple —
 * never the body of the original failed write. The {@link ManifestRetryScheduler}
 * sweeper lists the prefix and, for each entry, re-invokes the corresponding
 * {@link ManifestWriter} method which rebuilds fresh state from RDS + OBS. This
 * avoids replaying a stale body on top of newer changes (which would make race
 * conditions worse, not better).
 *
 * <p>Using OBS itself as the backing store keeps the dependency surface minimal
 * (no extra JPA entity / migration) and means the queue survives API pod restarts
 * for free.
 */
@Component
public class ManifestRetryQueue {

    private static final Logger log = LoggerFactory.getLogger(ManifestRetryQueue.class);

    /** Prefix under which queue entries are stored. */
    static final String PREFIX = "_retry_queue/";

    private final LakeonObsClient obs;
    private final ObjectMapper om;

    public ManifestRetryQueue(LakeonObsClient obs, ObjectMapper om) {
        this.obs = obs;
        this.om = om;
    }

    /** Persisted queue entry. {@code reason} is truncated to keep blobs small. */
    public record RetryEntry(
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("kind") String kind,
            @JsonProperty("reason") String reason,
            @JsonProperty("enqueuedAt") Instant enqueuedAt) {

        @JsonCreator
        public RetryEntry {}
    }

    /**
     * Record a manifest-write failure so a background sweeper can retry it.
     *
     * <p>Failures here are logged at {@code ERROR} but never thrown — the caller is
     * already on the failure path and should not lose visibility of the original
     * exception.
     *
     * @param tenantId tenant the failed write targeted
     * @param kind     {@code "tenant_manifest"} or {@code "owners_index"}
     * @param reason   short human-readable error message (truncated to 500 chars)
     */
    public void enqueue(String tenantId, String kind, String reason) {
        try {
            String safeReason = reason == null ? "" : reason;
            if (safeReason.length() > 500) {
                safeReason = safeReason.substring(0, 500);
            }
            Instant now = Instant.now();
            String key = PREFIX + now.toEpochMilli() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".json";
            String body = om.writeValueAsString(new RetryEntry(tenantId, kind, safeReason, now));
            obs.putObject(key, body, null);
            log.info("ManifestRetryQueue: enqueued tenant={} kind={} key={}", tenantId, kind, key);
        } catch (Exception e) {
            log.error("CRITICAL: retry queue write failed for tenant={} kind={}", tenantId, kind, e);
        }
    }
}
