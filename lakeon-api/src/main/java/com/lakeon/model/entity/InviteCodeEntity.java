package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.Instant;

@Entity
@Table(name = "invite_codes")
public class InviteCodeEntity {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1 ambiguity

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "max_uses", nullable = false)
    private int maxUses = -1; // -1 = unlimited

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (code == null) {
            SecureRandom random = new SecureRandom();
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
            code = sb.toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isValid() {
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses >= 0 && usedCount >= maxUses) return false;
        return true;
    }

    public void incrementUsed() {
        usedCount++;
    }

    // Getters and setters
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }

    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
