package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_files")
@IdClass(AgentFileEntity.PK.class)
public class AgentFileEntity {

    @Id
    @Column(name = "tenant_id", length = 32, nullable = false)
    private String tenantId;

    @Id
    @Column(name = "path", columnDefinition = "TEXT", nullable = false)
    private String path;

    @Column(name = "kind", length = 8, nullable = false)
    private String kind;   // "file" | "dir"

    @Column(name = "size", nullable = false)
    private long size;

    @Column(name = "mtime_ns", nullable = false)
    private long mtimeNs;

    @Column(name = "etag", length = 64, nullable = false)
    private String etag;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", nullable = false, columnDefinition = "jsonb")
    private JsonNode properties = JsonNodeFactory.instance.objectNode();

    @Column(name = "data")
    private byte[] data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AgentFileEntity() {}

    // --- Getters / setters ---
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }

    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }

    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }

    public long getSize() { return size; }
    public void setSize(long v) { this.size = v; }

    public long getMtimeNs() { return mtimeNs; }
    public void setMtimeNs(long v) { this.mtimeNs = v; }

    public String getEtag() { return etag; }
    public void setEtag(String v) { this.etag = v; }

    public JsonNode getProperties() { return properties; }
    public void setProperties(JsonNode v) { this.properties = v; }

    public byte[] getData() { return data; }
    public void setData(byte[] v) { this.data = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    /** Composite primary key. */
    public static class PK implements Serializable {
        private String tenantId;
        private String path;

        public PK() {}
        public PK(String tenantId, String path) {
            this.tenantId = tenantId;
            this.path = path;
        }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK)) return false;
            PK pk = (PK) o;
            return Objects.equals(tenantId, pk.tenantId) && Objects.equals(path, pk.path);
        }
        @Override
        public int hashCode() { return Objects.hash(tenantId, path); }
    }
}
