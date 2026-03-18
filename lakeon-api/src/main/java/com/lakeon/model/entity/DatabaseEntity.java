package com.lakeon.model.entity;

import com.lakeon.model.enums.DatabaseStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_instances",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class DatabaseEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "neon_tenant_id", length = 64)
    private String neonTenantId;

    @Column(name = "neon_timeline_id", length = 64)
    private String neonTimelineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DatabaseStatus status;

    @Column(name = "compute_size", nullable = false, length = 16)
    private String computeSize;

    @Column(name = "suspend_timeout", nullable = false, length = 16)
    private String suspendTimeout;

    @Column(name = "storage_limit_gb", nullable = false)
    private Integer storageLimitGb;

    @Column(name = "db_user", length = 64)
    private String dbUser;

    @Column(name = "db_password", length = 256)
    private String dbPassword;

    @Column(name = "compute_pod_name", length = 128)
    private String computePodName;

    @Column(name = "compute_host", length = 256)
    private String computeHost;

    @Column(name = "compute_port")
    private Integer computePort;

    @Column(name = "connection_uri", length = 512)
    private String connectionUri;

    @Column(name = "status_message", length = 1024)
    private String statusMessage;

    @Column(name = "allowed_ips", length = 2000)
    private String allowedIps;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "db_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getNeonTenantId() {
        return neonTenantId;
    }

    public void setNeonTenantId(String neonTenantId) {
        this.neonTenantId = neonTenantId;
    }

    public String getNeonTimelineId() {
        return neonTimelineId;
    }

    public void setNeonTimelineId(String neonTimelineId) {
        this.neonTimelineId = neonTimelineId;
    }

    public DatabaseStatus getStatus() {
        return status;
    }

    public void setStatus(DatabaseStatus status) {
        this.status = status;
    }

    public String getComputeSize() {
        return computeSize;
    }

    public void setComputeSize(String computeSize) {
        this.computeSize = computeSize;
    }

    public String getSuspendTimeout() {
        return suspendTimeout;
    }

    public void setSuspendTimeout(String suspendTimeout) {
        this.suspendTimeout = suspendTimeout;
    }

    public Integer getStorageLimitGb() {
        return storageLimitGb;
    }

    public void setStorageLimitGb(Integer storageLimitGb) {
        this.storageLimitGb = storageLimitGb;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getComputePodName() {
        return computePodName;
    }

    public void setComputePodName(String computePodName) {
        this.computePodName = computePodName;
    }

    public String getComputeHost() {
        return computeHost;
    }

    public void setComputeHost(String computeHost) {
        this.computeHost = computeHost;
    }

    public Integer getComputePort() {
        return computePort;
    }

    public void setComputePort(Integer computePort) {
        this.computePort = computePort;
    }

    public String getConnectionUri() {
        return connectionUri;
    }

    public void setConnectionUri(String connectionUri) {
        this.connectionUri = connectionUri;
    }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }
}
