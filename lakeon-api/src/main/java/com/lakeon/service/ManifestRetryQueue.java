package com.lakeon.service;

/**
 * Persistent retry queue for failed manifest / owners-index writes.
 *
 * <p>Defined here as an interface so {@link ManifestWriter} (Task 16) can compile
 * and unit-test against a {@code null} queue while the full implementation —
 * backed by a {@code manifest_retry} JPA entity and a scheduled sweeper — is
 * delivered in Task 17.
 */
public interface ManifestRetryQueue {

    /**
     * Record a manifest-write failure so a background sweeper can retry it.
     *
     * @param tenantId tenant the failed write targeted
     * @param kind     {@code "tenant_manifest"} or {@code "owners_index"}
     * @param reason   short human-readable error message (truncated by the impl)
     */
    void enqueue(String tenantId, String kind, String reason);
}
