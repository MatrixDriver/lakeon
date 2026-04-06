package com.lakeon.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "documents", indexes = {
    @Index(name = "idx_documents_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_documents_database_id", columnList = "database_id"),
    @Index(name = "idx_documents_kb_id", columnList = "kb_id"),
    @Index(name = "idx_documents_status", columnList = "status")
})
public class DocumentEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "kb_id", length = 32)
    private String kbId;

    @Column(name = "filename", nullable = false, length = 256)
    private String filename;

    @Column(name = "obs_key", length = 512)
    private String obsKey;

    @Column(name = "datasource_id", length = 32)
    private String datasourceId;

    @Column(name = "obs_etag", length = 64)
    private String obsEtag;

    @Column(name = "obs_size")
    private Long obsSize;

    @Column(name = "obs_last_modified")
    private Instant obsLastModified;

    @Column(name = "format", length = 16)
    private String format;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "job_id", length = 32)
    private String jobId;

    @Column(name = "chunks_count")
    private Integer chunksCount;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(name = "rechunk_status")
    private RechunkStatus rechunkStatus = RechunkStatus.IDLE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @Column(name = "folder", length = 512)
    private String folder = "";

    @Column(name = "doc_type", length = 16)
    private String docType = "raw";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata = new LinkedHashMap<>();

    @Column(name = "rechunk_started_at")
    private Instant rechunkStartedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getObsKey() { return obsKey; }
    public void setObsKey(String obsKey) { this.obsKey = obsKey; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public Integer getChunksCount() { return chunksCount; }
    public void setChunksCount(Integer chunksCount) { this.chunksCount = chunksCount; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public RechunkStatus getRechunkStatus() { return rechunkStatus; }
    public void setRechunkStatus(RechunkStatus rechunkStatus) { this.rechunkStatus = rechunkStatus; }

    public Instant getRechunkStartedAt() { return rechunkStartedAt; }
    public void setRechunkStartedAt(Instant rechunkStartedAt) { this.rechunkStartedAt = rechunkStartedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }

    public String getObsEtag() { return obsEtag; }
    public void setObsEtag(String obsEtag) { this.obsEtag = obsEtag; }

    public Long getObsSize() { return obsSize; }
    public void setObsSize(Long obsSize) { this.obsSize = obsSize; }

    public Instant getObsLastModified() { return obsLastModified; }
    public void setObsLastModified(Instant obsLastModified) { this.obsLastModified = obsLastModified; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    @JsonProperty("type")
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
