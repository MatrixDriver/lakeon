package com.lakeon.lakebasefs;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lbfs_forwarder_locks")
public class LakebaseFSForwarderLockEntity {

    @Id
    @Column(name = "tenant_id", length = 32, nullable = false)
    private String tenantId;

    @Column(name = "locked_by", length = 64, nullable = false)
    private String lockedBy;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public LakebaseFSForwarderLockEntity() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String v) { this.lockedBy = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
    public Long getLastEventId() { return lastEventId; }
    public void setLastEventId(Long v) { this.lastEventId = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
