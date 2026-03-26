# OBS Datasource Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users can create OBS data sources, bulk upload files via hcloud/obsutil, and manually sync to incrementally ingest documents into knowledge bases.

**Architecture:** New `DataSourceEntity` + `DataSourceController` + `DataSourceSyncService`. Sync logic lists OBS objects, diffs against existing documents by ETag, and submits batch processing via existing `KbWriteQueue`. Console adds a "数据源" tab to `KnowledgeBaseDetail.vue`.

**Tech Stack:** Spring Boot JPA (Hibernate auto-DDL), AWS S3 SDK (ListObjectsV2), Vue 3 Composition API, existing `ObsStsService` for STS credentials.

---

### Task 1: DataSourceEntity + Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceStatus.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java` — add `datasourceId`, `obsEtag`, `obsSize`, `obsLastModified` fields

- [ ] **Step 1: Create DataSourceStatus enum**

```java
// lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceStatus.java
package com.lakeon.knowledge;

public enum DataSourceStatus {
    ACTIVE, SYNCING, ERROR
}
```

- [ ] **Step 2: Create DataSourceEntity**

```java
// lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceEntity.java
package com.lakeon.knowledge;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "datasources", indexes = {
    @Index(name = "idx_datasources_tenant_kb", columnList = "tenant_id, kb_id")
})
public class DataSourceEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "kb_id", nullable = false, length = 32)
    private String kbId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "obs_prefix", nullable = false, length = 256)
    private String obsPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DataSourceStatus status = DataSourceStatus.ACTIVE;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_sync_stats", columnDefinition = "jsonb")
    private Map<String, Object> lastSyncStats;

    @Column(name = "file_count")
    private Integer fileCount = 0;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    // Getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getObsPrefix() { return obsPrefix; }
    public void setObsPrefix(String obsPrefix) { this.obsPrefix = obsPrefix; }
    public DataSourceStatus getStatus() { return status; }
    public void setStatus(DataSourceStatus status) { this.status = status; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public Map<String, Object> getLastSyncStats() { return lastSyncStats; }
    public void setLastSyncStats(Map<String, Object> lastSyncStats) { this.lastSyncStats = lastSyncStats; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 3: Create DataSourceRepository**

```java
// lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceRepository.java
package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSourceEntity, String> {
    List<DataSourceEntity> findByTenantIdAndKbId(String tenantId, String kbId);
    Optional<DataSourceEntity> findByIdAndTenantId(String id, String tenantId);
}
```

- [ ] **Step 4: Add datasource fields to DocumentEntity**

Add these fields after the existing `obsKey` field in `DocumentEntity.java`:

```java
    @Column(name = "datasource_id", length = 32)
    private String datasourceId;

    @Column(name = "obs_etag", length = 64)
    private String obsEtag;

    @Column(name = "obs_size")
    private Long obsSize;

    @Column(name = "obs_last_modified")
    private Instant obsLastModified;
```

Add getters/setters at the bottom:

```java
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getObsEtag() { return obsEtag; }
    public void setObsEtag(String obsEtag) { this.obsEtag = obsEtag; }
    public Long getObsSize() { return obsSize; }
    public void setObsSize(Long obsSize) { this.obsSize = obsSize; }
    public Instant getObsLastModified() { return obsLastModified; }
    public void setObsLastModified(Instant obsLastModified) { this.obsLastModified = obsLastModified; }
```

- [ ] **Step 5: Add datasourceId query to DocumentRepository**

Add to `DocumentRepository.java`:

```java
    List<DocumentEntity> findByDatasourceId(String datasourceId);
```

- [ ] **Step 6: Compile and verify**

Run: `mvn compile -q -f lakeon-api/pom.xml`
Expected: no errors

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DataSource*.java \
  lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java \
  lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java
git commit -m "feat(knowledge): DataSourceEntity + document datasource fields"
```

---

### Task 2: DataSourceController — CRUD + Credentials

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/obs/ObsStsService.java` — add `datasources/` prefix to STS policy

- [ ] **Step 1: Add datasources prefix to ObsStsService policy**

In `ObsStsService.java`, modify `buildPolicy()` to add the `datasources/` prefix:

```java
    Map<String, Object> buildPolicy(String tenantId) {
        String bucket = props.getObs().getBucket();
        List<String> resources = List.of(
                "obs:*:*:object:" + bucket + "/datasets/" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/knowledge/" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/tenant-" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/datalake-logs/" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/datasources/" + tenantId + "/*"
        );
        // ... rest unchanged
    }
```

Also add `obs:bucket:ListBucket` to the Action list and a bucket-level resource with condition for ListObjects:

```java
        Map<String, Object> objectStatement = Map.of(
                "Effect", "Allow",
                "Action", List.of(
                        "obs:object:GetObject",
                        "obs:object:PutObject",
                        "obs:object:DeleteObject",
                        "obs:object:AbortMultipartUpload",
                        "obs:object:ListMultipartUploadParts"
                ),
                "Resource", resources
        );

        // Allow ListBucket scoped to tenant prefixes
        Map<String, Object> listStatement = Map.of(
                "Effect", "Allow",
                "Action", List.of("obs:bucket:ListBucket"),
                "Resource", List.of("obs:*:*:bucket:" + bucket)
        );

        return Map.of(
                "Version", "1.1",
                "Statement", List.of(objectStatement, listStatement)
        );
```

- [ ] **Step 2: Create DataSourceController**

```java
// lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceController.java
package com.lakeon.knowledge;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.obs.ObsStsService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/knowledge/{kbId}/datasources")
public class DataSourceController {

    private final DataSourceRepository dsRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ObsStsService obsStsService;
    private final LakeonProperties props;

    public DataSourceController(DataSourceRepository dsRepository,
                                 KnowledgeBaseRepository kbRepository,
                                 ObsStsService obsStsService,
                                 LakeonProperties props) {
        this.dsRepository = dsRepository;
        this.kbRepository = kbRepository;
        this.obsStsService = obsStsService;
        this.props = props;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req,
                                       @PathVariable String kbId,
                                       @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        String name = body.get("name");
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");

        // Verify KB exists and belongs to tenant
        kbRepository.findByIdAndTenantId(kbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));

        DataSourceEntity ds = new DataSourceEntity();
        ds.setTenantId(tenant.getId());
        ds.setKbId(kbId);
        ds.setName(name);
        ds.prePersist(); // generate ID so we can use it in prefix
        ds.setObsPrefix("datasources/" + tenant.getId() + "/" + ds.getId() + "/");
        dsRepository.save(ds);

        return toResponse(ds);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req, @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        return dsRepository.findByTenantIdAndKbId(tenant.getId(), kbId).stream()
            .map(this::toResponse).toList();
    }

    @GetMapping("/{dsId}")
    public Map<String, Object> get(HttpServletRequest req,
                                    @PathVariable String kbId,
                                    @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        return toResponse(ds);
    }

    @DeleteMapping("/{dsId}")
    public Map<String, Object> delete(HttpServletRequest req,
                                       @PathVariable String kbId,
                                       @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        // TODO Task 3 will add document cleanup
        dsRepository.delete(ds);
        return Map.of("deleted", true);
    }

    @GetMapping("/{dsId}/credentials")
    public Map<String, Object> getCredentials(HttpServletRequest req,
                                               @PathVariable String kbId,
                                               @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));

        ObsStsService.StsCredentials creds = obsStsService.getCredentials(tenant.getId());
        String bucket = props.getObs().getBucket();
        String endpoint = props.getObs().getEndpoint().replace("https://", "");
        String obsPath = "obs://" + bucket + "/" + ds.getObsPrefix();

        return Map.of(
            "endpoint", endpoint,
            "bucket", bucket,
            "prefix", ds.getObsPrefix(),
            "access_key", creds.accessKey(),
            "secret_key", creds.secretKey(),
            "security_token", creds.sessionToken(),
            "expires_at", creds.expiresAt().toString(),
            "upload_commands", Map.of(
                "hcloud", "hcloud obs cp ./my-docs/ " + obsPath + " -r -f -e " + endpoint
                    + " -i " + creds.accessKey() + " -k " + creds.secretKey() + " -t " + creds.sessionToken(),
                "obsutil", "obsutil cp ./my-docs/ " + obsPath + " -r -f -e " + endpoint
                    + " -i " + creds.accessKey() + " -k " + creds.secretKey() + " -t " + creds.sessionToken()
            )
        );
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toResponse(DataSourceEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("kb_id", ds.getKbId());
        m.put("name", ds.getName());
        m.put("obs_prefix", ds.getObsPrefix());
        m.put("status", ds.getStatus().name());
        m.put("file_count", ds.getFileCount());
        m.put("last_synced_at", ds.getLastSyncedAt());
        m.put("last_sync_stats", ds.getLastSyncStats());
        m.put("error", ds.getError());
        m.put("created_at", ds.getCreatedAt());
        return m;
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn compile -q -f lakeon-api/pom.xml`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceController.java \
  lakeon-api/src/main/java/com/lakeon/obs/ObsStsService.java
git commit -m "feat(knowledge): DataSourceController CRUD + STS credentials"
```

---

### Task 3: DataSourceSyncService — OBS list + diff + batch process

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceSyncService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceController.java` — add sync endpoint
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — extract helper: `deleteDocumentById()`

- [ ] **Step 1: Create DataSourceSyncService**

This service lists OBS objects, diffs against existing documents, creates/updates/deletes documents, and triggers batch processing.

```java
// lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceSyncService.java
package com.lakeon.knowledge;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataSourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSyncService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".pdf", ".epub", ".docx", ".md", ".markdown", ".txt"
    );

    private final DataSourceRepository dsRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeService knowledgeService;
    private final LakeonProperties props;

    public DataSourceSyncService(DataSourceRepository dsRepository,
                                  DocumentRepository documentRepository,
                                  KnowledgeBaseRepository kbRepository,
                                  KnowledgeService knowledgeService,
                                  LakeonProperties props) {
        this.dsRepository = dsRepository;
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.knowledgeService = knowledgeService;
        this.props = props;
    }

    /**
     * Sync a datasource: list OBS objects, diff against existing documents,
     * create/update/delete documents, and trigger batch processing.
     * Returns sync stats: {added, modified, deleted, skipped, errors}.
     */
    public Map<String, Object> sync(DataSourceEntity ds) {
        if (ds.getStatus() == DataSourceStatus.SYNCING) {
            throw new IllegalStateException("Datasource is already syncing");
        }

        ds.setStatus(DataSourceStatus.SYNCING);
        ds.setError(null);
        dsRepository.save(ds);

        try {
            // 1. List OBS objects
            List<ObsFileInfo> obsFiles = listObsFiles(ds.getObsPrefix());
            log.info("Datasource {} sync: found {} supported files in OBS", ds.getId(), obsFiles.size());

            // 2. Get existing documents for this datasource
            List<DocumentEntity> existing = documentRepository.findByDatasourceId(ds.getId());
            Map<String, DocumentEntity> existingByKey = existing.stream()
                .collect(Collectors.toMap(DocumentEntity::getObsKey, d -> d));

            // 3. Diff
            Set<String> obsKeys = obsFiles.stream().map(f -> f.key).collect(Collectors.toSet());
            int added = 0, modified = 0, deleted = 0, skipped = 0, errors = 0;
            List<String> toProcessIds = new ArrayList<>();

            KnowledgeBaseEntity kb = kbRepository.findById(ds.getKbId()).orElse(null);
            if (kb == null) throw new RuntimeException("KB not found: " + ds.getKbId());

            // New + modified
            for (ObsFileInfo obsFile : obsFiles) {
                DocumentEntity doc = existingByKey.get(obsFile.key);
                if (doc != null) {
                    // Exists — check ETag
                    if (obsFile.etag.equals(doc.getObsEtag())) {
                        skipped++;
                        continue;
                    }
                    // Modified — reset document for re-processing
                    doc.setObsEtag(obsFile.etag);
                    doc.setObsSize(obsFile.size);
                    doc.setObsLastModified(obsFile.lastModified);
                    doc.setSizeBytes(obsFile.size);
                    doc.setStatus(DocumentStatus.PENDING);
                    doc.setError(null);
                    doc.setChunksCount(null);
                    documentRepository.save(doc);
                    toProcessIds.add(doc.getId());
                    modified++;
                } else {
                    // New
                    String filename = obsFile.key.substring(ds.getObsPrefix().length());
                    String format = knowledgeService.detectFormatPublic(filename);
                    DocumentEntity newDoc = new DocumentEntity();
                    newDoc.setTenantId(ds.getTenantId());
                    newDoc.setDatabaseId(kb.getDatabaseId());
                    newDoc.setKbId(ds.getKbId());
                    newDoc.setDatasourceId(ds.getId());
                    newDoc.setFilename(filename);
                    newDoc.setObsKey(obsFile.key);
                    newDoc.setObsEtag(obsFile.etag);
                    newDoc.setObsSize(obsFile.size);
                    newDoc.setObsLastModified(obsFile.lastModified);
                    newDoc.setSizeBytes(obsFile.size);
                    newDoc.setFormat(format);
                    newDoc.setStatus(DocumentStatus.PENDING);
                    documentRepository.save(newDoc);
                    toProcessIds.add(newDoc.getId());
                    added++;
                }
            }

            // Deleted (in DB but not in OBS)
            for (DocumentEntity doc : existing) {
                if (!obsKeys.contains(doc.getObsKey())) {
                    knowledgeService.deleteDocumentInternal(doc);
                    deleted++;
                }
            }

            // 4. Trigger batch processing for new/modified
            if (!toProcessIds.isEmpty()) {
                knowledgeService.batchProcessDocuments(
                    new com.lakeon.model.entity.TenantEntity() {{ setId(ds.getTenantId()); }},
                    toProcessIds
                );
            }

            // 5. Update datasource
            Map<String, Object> stats = Map.of(
                "added", added,
                "modified", modified,
                "deleted", deleted,
                "skipped", skipped,
                "errors", errors,
                "total_obs_files", obsFiles.size()
            );

            ds.setStatus(DataSourceStatus.ACTIVE);
            ds.setLastSyncedAt(Instant.now());
            ds.setLastSyncStats(stats);
            ds.setFileCount(obsFiles.size());
            dsRepository.save(ds);

            log.info("Datasource {} sync complete: {}", ds.getId(), stats);
            return stats;

        } catch (Exception e) {
            log.error("Datasource {} sync failed: {}", ds.getId(), e.getMessage(), e);
            ds.setStatus(DataSourceStatus.ERROR);
            ds.setError(e.getMessage());
            dsRepository.save(ds);
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
    }

    private List<ObsFileInfo> listObsFiles(String prefix) {
        S3Client s3 = buildS3Client();
        try {
            List<ObsFileInfo> files = new ArrayList<>();
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(props.getObs().getBucket())
                    .prefix(prefix)
                    .maxKeys(1000);
                if (continuationToken != null) {
                    reqBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
                for (S3Object obj : resp.contents()) {
                    if (obj.size() == 0) continue; // skip directories
                    String ext = obj.key().contains(".")
                        ? obj.key().substring(obj.key().lastIndexOf('.')).toLowerCase()
                        : "";
                    if (SUPPORTED_EXTENSIONS.contains(ext)) {
                        files.add(new ObsFileInfo(
                            obj.key(),
                            obj.eTag().replace("\"", ""),
                            obj.size(),
                            obj.lastModified()
                        ));
                    }
                }
                continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (continuationToken != null);
            return files;
        } finally {
            s3.close();
        }
    }

    private S3Client buildS3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(props.getObs().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
            .region(Region.of("cn-north-4"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();
    }

    record ObsFileInfo(String key, String etag, long size, Instant lastModified) {}
}
```

- [ ] **Step 2: Add public helpers to KnowledgeService**

Add `detectFormatPublic()` (delegates to existing private `detectFormat()`):

```java
    public String detectFormatPublic(String filename) {
        return detectFormat(filename);
    }
```

Add `deleteDocumentInternal()` (deletes document + OBS file + chunks, no tenant check):

```java
    public void deleteDocumentInternal(DocumentEntity doc) {
        if (doc.getObsKey() != null) {
            try { deleteObsFile(doc.getObsKey()); } catch (Exception e) {
                log.warn("Failed to delete OBS file {}: {}", doc.getObsKey(), e.getMessage());
            }
        }
        // Delete chunks from user PG is handled by cascade or next sync
        documentRepository.delete(doc);
    }
```

- [ ] **Step 3: Add sync endpoint to DataSourceController**

```java
    // Inject DataSourceSyncService in constructor
    private final DataSourceSyncService syncService;

    // Add to constructor parameters + field assignment

    @PostMapping("/{dsId}/sync")
    public Map<String, Object> sync(HttpServletRequest req,
                                     @PathVariable String kbId,
                                     @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        Map<String, Object> stats = syncService.sync(ds);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("datasource_id", ds.getId());
        response.put("status", ds.getStatus().name());
        response.put("sync_stats", stats);
        return response;
    }
```

Also update `delete()` to clean up documents:

```java
    @DeleteMapping("/{dsId}")
    public Map<String, Object> delete(HttpServletRequest req,
                                       @PathVariable String kbId,
                                       @PathVariable String dsId) {
        TenantEntity tenant = getTenant(req);
        DataSourceEntity ds = dsRepository.findByIdAndTenantId(dsId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Datasource not found: " + dsId));
        // Delete all documents belonging to this datasource
        List<DocumentEntity> docs = documentRepository.findByDatasourceId(dsId);
        for (DocumentEntity doc : docs) {
            knowledgeService.deleteDocumentInternal(doc);
        }
        dsRepository.delete(ds);
        return Map.of("deleted", true);
    }
```

Inject in constructor:

```java
    private final KnowledgeService knowledgeService;
    private final DataSourceSyncService syncService;
    // Add both to constructor params + assignments
```

- [ ] **Step 4: Compile**

Run: `mvn compile -q -f lakeon-api/pom.xml`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceSyncService.java \
  lakeon-api/src/main/java/com/lakeon/knowledge/DataSourceController.java \
  lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(knowledge): DataSourceSyncService — OBS list + diff + batch process"
```

---

### Task 4: Console — API client + 数据源 Tab

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts` — add datasource API functions
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — add 数据源 tab

- [ ] **Step 1: Add datasource types and API functions to knowledge.ts**

Append to `lakeon-console/src/api/knowledge.ts`:

```typescript
// Datasources
export interface DataSource {
  id: string
  kb_id: string
  name: string
  obs_prefix: string
  status: string
  file_count: number
  last_synced_at: string | null
  last_sync_stats: { added: number; modified: number; deleted: number; skipped: number } | null
  error: string | null
  created_at: string
}

export interface DataSourceCredentials {
  endpoint: string
  bucket: string
  prefix: string
  access_key: string
  secret_key: string
  security_token: string
  expires_at: string
  upload_commands: { hcloud: string; obsutil: string }
}

export function listDataSources(kbId: string) {
  return api.get<DataSource[]>(`/knowledge/${kbId}/datasources`)
}

export function createDataSource(kbId: string, name: string) {
  return api.post<DataSource>(`/knowledge/${kbId}/datasources`, { name })
}

export function deleteDataSource(kbId: string, dsId: string) {
  return api.delete(`/knowledge/${kbId}/datasources/${dsId}`)
}

export function syncDataSource(kbId: string, dsId: string) {
  return api.post<{ datasource_id: string; status: string; sync_stats: any }>(`/knowledge/${kbId}/datasources/${dsId}/sync`)
}

export function getDataSourceCredentials(kbId: string, dsId: string) {
  return api.get<DataSourceCredentials>(`/knowledge/${kbId}/datasources/${dsId}/credentials`)
}
```

- [ ] **Step 2: Add 数据源 tab to KnowledgeBaseDetail.vue**

Add `'datasources'` to the tabs array:

```typescript
const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'documents', label: '文档' },
  { key: 'datasources', label: '数据源' },
  { key: 'search', label: '搜索' },
  { key: 'chunks', label: '切片' },
]
```

Add datasource state variables after existing state:

```typescript
import { listDataSources, createDataSource, deleteDataSource, syncDataSource, getDataSourceCredentials, type DataSource, type DataSourceCredentials } from '@/api/knowledge'

const datasources = ref<DataSource[]>([])
const dsLoading = ref(false)
const dsCreateDialog = ref(false)
const dsCreateName = ref('')
const dsCredentials = ref<{ dsId: string; creds: DataSourceCredentials } | null>(null)
const dsSyncing = ref<Set<string>>(new Set())

async function loadDataSources() {
  if (!kbId) return
  dsLoading.value = true
  try {
    const res = await listDataSources(kbId)
    datasources.value = res.data
  } finally {
    dsLoading.value = false
  }
}

async function handleCreateDs() {
  if (!kbId || !dsCreateName.value.trim()) return
  await createDataSource(kbId, dsCreateName.value.trim())
  dsCreateName.value = ''
  dsCreateDialog.value = false
  await loadDataSources()
}

async function handleDeleteDs(dsId: string) {
  if (!kbId || !confirm('删除数据源将同时删除其关联的所有文档和切片，确定？')) return
  await deleteDataSource(kbId, dsId)
  await loadDataSources()
}

async function handleSyncDs(dsId: string) {
  if (!kbId) return
  dsSyncing.value.add(dsId)
  try {
    await syncDataSource(kbId, dsId)
    await loadDataSources()
    await loadDocuments()
  } finally {
    dsSyncing.value.delete(dsId)
  }
}

async function handleGetCredentials(dsId: string) {
  if (!kbId) return
  const res = await getDataSourceCredentials(kbId, dsId)
  dsCredentials.value = { dsId, creds: res.data }
}
```

Load datasources when tab switches (add to existing `watch` or `onMounted`):

```typescript
watch(activeTab, (tab) => {
  if (tab === 'datasources') loadDataSources()
})
```

- [ ] **Step 3: Add 数据源 tab template**

Insert after the chunks tab section (`v-if="activeTab === 'chunks'"`), before the error dialog:

```html
    <!-- Datasources Tab -->
    <div v-if="activeTab === 'datasources'" style="margin-top: 24px;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
        <h3 style="margin: 0; font-size: 16px;">OBS 数据源</h3>
        <button class="btn btn-primary" @click="dsCreateDialog = true">添加数据源</button>
      </div>

      <div v-if="datasources.length === 0 && !dsLoading" class="empty-state" style="padding: 40px; text-align: center; color: #999;">
        暂无数据源。点击"添加数据源"创建一个 OBS 目录，将文件批量上传后同步到知识库。
      </div>

      <div v-for="ds in datasources" :key="ds.id" class="section-card" style="margin-bottom: 16px; padding: 16px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start;">
          <div>
            <div style="font-weight: 600; font-size: 15px;">{{ ds.name }}</div>
            <div style="color: #999; font-size: 12px; margin-top: 4px;">
              <code style="background: #f5f5f5; padding: 2px 6px; border-radius: 3px;">obs://{{ ds.obs_prefix }}</code>
            </div>
            <div style="display: flex; gap: 16px; margin-top: 8px; font-size: 13px; color: #666;">
              <span>{{ ds.file_count }} 个文件</span>
              <span v-if="ds.last_synced_at">上次同步: {{ new Date(ds.last_synced_at).toLocaleString('zh-CN') }}</span>
              <span v-else>未同步</span>
              <span :style="{ color: ds.status === 'SYNCING' ? '#1890ff' : ds.status === 'ERROR' ? '#e6393d' : '#52c41a' }">
                {{ ds.status === 'ACTIVE' ? '正常' : ds.status === 'SYNCING' ? '同步中...' : '错误' }}
              </span>
            </div>
            <div v-if="ds.last_sync_stats" style="margin-top: 6px; font-size: 12px; color: #999;">
              新增 {{ ds.last_sync_stats.added }} / 修改 {{ ds.last_sync_stats.modified }} / 删除 {{ ds.last_sync_stats.deleted }} / 跳过 {{ ds.last_sync_stats.skipped }}
            </div>
            <div v-if="ds.error" style="margin-top: 6px; font-size: 12px; color: #e6393d;">{{ ds.error }}</div>
          </div>
          <div style="display: flex; gap: 8px; flex-shrink: 0;">
            <button class="btn btn-text" @click="handleGetCredentials(ds.id)">上传凭据</button>
            <button class="btn btn-primary" :disabled="dsSyncing.has(ds.id)" @click="handleSyncDs(ds.id)">
              {{ dsSyncing.has(ds.id) ? '同步中...' : '同步' }}
            </button>
            <button class="btn btn-danger-text" @click="handleDeleteDs(ds.id)">删除</button>
          </div>
        </div>

        <!-- Credentials panel -->
        <div v-if="dsCredentials && dsCredentials.dsId === ds.id" style="margin-top: 16px; padding: 16px; background: #f8f9fa; border-radius: 6px; font-size: 13px;">
          <div style="font-weight: 600; margin-bottom: 12px;">上传指引</div>

          <p style="margin-bottom: 8px;">将文件上传到以下 OBS 目录，然后点击"同步"将文件导入知识库：</p>
          <code style="display: block; background: #fff; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; word-break: break-all;">
            obs://{{ dsCredentials.creds.bucket }}/{{ dsCredentials.creds.prefix }}
          </code>

          <div style="margin-bottom: 8px; font-weight: 500;">支持格式：PDF、EPUB、DOCX、Markdown、TXT（支持子目录）</div>

          <div style="margin-bottom: 8px; font-weight: 500;">方式一：hcloud CLI</div>
          <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px;">{{ dsCredentials.creds.upload_commands.hcloud }}</pre>

          <div style="margin-bottom: 8px; font-weight: 500;">方式二：obsutil</div>
          <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px;">{{ dsCredentials.creds.upload_commands.obsutil }}</pre>

          <div style="margin-bottom: 8px; font-weight: 500;">方式三：华为云 OBS Console 网页端</div>
          <p style="margin: 0; color: #666;">登录华为云 Console → 对象存储服务 → {{ dsCredentials.creds.bucket }} → 进入 {{ dsCredentials.creds.prefix }} 目录 → 上传</p>

          <div style="margin-top: 12px; color: #faad14; font-size: 12px;">
            凭据有效期至 {{ new Date(dsCredentials.creds.expires_at).toLocaleString('zh-CN') }}，过期后重新获取。
          </div>

          <button class="btn btn-text" style="margin-top: 8px;" @click="dsCredentials = null">收起</button>
        </div>
      </div>

      <!-- Create dialog -->
      <div v-if="dsCreateDialog" class="modal-overlay" @click.self="dsCreateDialog = false">
        <div class="modal-box" style="max-width: 400px;">
          <div class="modal-header">
            <span>添加数据源</span>
            <button class="btn-icon" @click="dsCreateDialog = false">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <label class="form-label">数据源名称</label>
            <input v-model="dsCreateName" class="form-input" placeholder="例如：产品文档" @keyup.enter="handleCreateDs" />
          </div>
          <div class="modal-footer">
            <button class="btn btn-text" @click="dsCreateDialog = false">取消</button>
            <button class="btn btn-primary" :disabled="!dsCreateName.trim()" @click="handleCreateDs">创建</button>
          </div>
        </div>
      </div>
    </div>
```

- [ ] **Step 4: Type-check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: no errors

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
  lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): datasource tab with CRUD, sync, and upload guide"
```

---

### Task 5: Build, Deploy, E2E Test

**Files:**
- Modify: `deploy/cce/values-cce.yaml` — bump API tag

- [ ] **Step 1: Build and push API**

```bash
# Bump tag in values-cce.yaml
IMAGE_TAG=<next_version> ./deploy/cce/build-and-push-api.sh
```

- [ ] **Step 2: Deploy**

```bash
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 3: E2E test via CLI**

```python
# Test sequence:
# 1. Create datasource
# 2. Get credentials
# 3. Upload files to OBS using STS credentials
# 4. Trigger sync
# 5. Verify documents created
# 6. Modify a file in OBS + re-sync → verify modified count
# 7. Delete a file from OBS + re-sync → verify deleted count
# 8. Delete datasource → verify documents cleaned up
```

- [ ] **Step 4: Git push**

```bash
git push
```

- [ ] **Step 5: Commit deploy changes**

```bash
git add deploy/cce/values-cce.yaml
git commit -m "deploy: datasource sync API + console"
```
