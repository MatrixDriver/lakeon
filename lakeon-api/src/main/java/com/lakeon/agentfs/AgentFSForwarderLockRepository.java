package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentFSForwarderLockRepository
        extends JpaRepository<AgentFSForwarderLockEntity, String> {

    /**
     * Try to acquire or renew the per-tenant lock. Returns 1 if this pod
     * now holds the lock (fresh acquisition OR renewal of its own expired
     * lease OR expiry-takeover from another pod), 0 if another pod's
     * lease is still active.
     *
     * The upsert semantics: INSERT if no row exists, else UPDATE only
     * when the current lease has expired. Postgres-specific (native).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO agentfs_forwarder_locks
               (tenant_id, locked_by, locked_until, last_event_id, updated_at)
        VALUES (:tenantId, :podId,
                now() + (:secs || ' seconds')::interval, 0, now())
        ON CONFLICT (tenant_id) DO UPDATE
           SET locked_by = EXCLUDED.locked_by,
               locked_until = EXCLUDED.locked_until,
               updated_at = now()
         WHERE agentfs_forwarder_locks.locked_until < now()
        """, nativeQuery = true)
    int tryAcquire(@Param("tenantId") String tenantId,
                   @Param("podId") String podId,
                   @Param("secs") int secs);

    /** Update the last_event_id cursor after successful processing. */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE agentfs_forwarder_locks
           SET last_event_id = :eventId, updated_at = now()
         WHERE tenant_id = :tenantId AND locked_by = :podId
        """, nativeQuery = true)
    int advanceCursor(@Param("tenantId") String tenantId,
                      @Param("podId") String podId,
                      @Param("eventId") long eventId);
}
