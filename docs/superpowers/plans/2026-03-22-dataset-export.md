# Dataset Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users export database tables to Parquet datasets on OBS, and integrate datasets with datalake jobs as inputs/outputs.

**Architecture:** New `DatasetEntity` + `DatasetService` + `DatasetController` in a `com.lakeon.dataset` package. Export jobs use the existing `com.lakeon.job` framework (`EXPORT_PARQUET` type). A new `export_parquet.py` script in `knowledge/job/` uses DuckDB `postgres_query` to scan user PG and write Parquet to OBS. Console gets a real dataset page replacing the placeholder, plus sidebar cleanup.

**Tech Stack:** Java 17 / Spring Boot 3.3 / JPA, DuckDB (Python), Vue 3, OBS (S3-compatible)

**Spec:** `docs/superpowers/specs/2026-03-22-dataset-export-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java` | JPA entity for `datasets` table |
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetStatus.java` | Enum: DRAFT, EXPORTING, READY, FAILED |
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java` | Enum: DB_EXPORT, JOB_OUTPUT |
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetRepository.java` | JPA repository |
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java` | Business logic: preview, create, export, list, get, delete |
| `lakeon-api/src/main/java/com/lakeon/dataset/DatasetController.java` | REST endpoints under `/api/v1/datasets` |
| `lakeon-api/src/test/java/com/lakeon/dataset/DatasetServiceTest.java` | Unit tests |
| `knowledge/job/export_parquet.py` | DuckDB export script |
| `lakeon-console/src/views/datalake/DatalakeDatasetNew.vue` | New dataset creation page |
| `lakeon-console/src/views/datalake/DatalakeDatasetDetail.vue` | Dataset detail page |

### Modified Files

| File | Change |
|------|--------|
| `knowledge/job/main.py` | Add `EXPORT_PARQUET` job type dispatch |
| `knowledge/job/requirements.txt` | Add `duckdb` dependency |
| `lakeon-console/src/layouts/ConsoleLayout.vue` | Remove Notebook/模型仓库 from sidebar |
| `lakeon-console/src/router/index.ts` | Remove notebook/models routes, add dataset routes |
| `lakeon-console/src/views/datalake/DatalakeDatasets.vue` | Replace placeholder with real dataset list |
| `lakeon-api/src/main/resources/application.yml` | Update export-parquet job config (memory: 2Gi) |
| `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java` | Add `inputDatasetId`, `outputDatasetName` |
| `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java` | Inject dataset env vars, auto-register output datasets |
| `lakeon-api/src/main/java/com/lakeon/job/JobService.java` | Add dataset completion listener hook |

---

## Task 1: DatasetEntity + Enums + Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetSourceType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetRepository.java`

- [ ] **Step 1: Create DatasetStatus enum**

```java
package com.lakeon.dataset;

public enum DatasetStatus {
    DRAFT, EXPORTING, READY, FAILED
}
```

- [ ] **Step 2: Create DatasetSourceType enum**

```java
package com.lakeon.dataset;

public enum DatasetSourceType {
    DB_EXPORT, JOB_OUTPUT
}
```

- [ ] **Step 3: Create DatasetEntity**

Follow `JobEntity` pattern: prefix-based UUID (`ds_` + 12 char), `@PrePersist`/`@PreUpdate` lifecycle hooks, `Instant` timestamps.

```java
package com.lakeon.dataset;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datasets", indexes = {
    @Index(name = "idx_datasets_tenant", columnList = "tenant_id"),
    @Index(name = "idx_datasets_status", columnList = "status")
})
public class DatasetEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private DatasetSourceType sourceType;

    @Column(name = "database_id", length = 64)
    private String databaseId;

    @Column(name = "source_sql", columnDefinition = "text")
    private String sourceSql;

    @Column(name = "source_tables", columnDefinition = "text")
    private String sourceTables;

    @Column(name = "obs_path", length = 512)
    private String obsPath;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DatasetStatus status;

    @Column(name = "job_id", length = 64)
    private String jobId;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters for all fields
}
```

- [ ] **Step 4: Create DatasetRepository**

```java
package com.lakeon.dataset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatasetRepository extends JpaRepository<DatasetEntity, String> {
    List<DatasetEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DatasetEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, DatasetStatus status);
    Optional<DatasetEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<DatasetEntity> findByJobId(String jobId);
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: no errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/
git commit -m "feat(dataset): DatasetEntity + enums + repository"
```

---

## Task 2: DatasetService

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/dataset/DatasetServiceTest.java`

- [ ] **Step 1: Write tests for create, list, get, delete**

```java
package com.lakeon.dataset;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatasetService 单元测试")
class DatasetServiceTest {

    @Mock private DatasetRepository datasetRepository;
    // Other mocks added as needed per test

    private DatasetService service;

    @BeforeEach
    void setUp() {
        // Construct with mocks — exact constructor depends on final implementation
    }

    @Nested @DisplayName("create")
    class Create {
        @Test @DisplayName("creates DRAFT dataset with TABLE_SELECT")
        void createTableSelect() {
            when(datasetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // Call service.create(...) with TABLE_SELECT params
            // Assert: status=DRAFT, sourceType=DB_EXPORT, sourceSql generated
        }

        @Test @DisplayName("rejects blank name")
        void rejectBlankName() {
            assertThatThrownBy(() -> service.create(/*blank name*/))
                .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested @DisplayName("get")
    class Get {
        @Test @DisplayName("returns dataset by id and tenant")
        void getByIdAndTenant() {
            DatasetEntity ds = new DatasetEntity();
            when(datasetRepository.findByIdAndTenantId("ds_test", "t1"))
                .thenReturn(Optional.of(ds));
            assertThat(service.getDataset("t1", "ds_test")).isEqualTo(ds);
        }

        @Test @DisplayName("throws NotFoundException for missing dataset")
        void notFound() {
            when(datasetRepository.findByIdAndTenantId(any(), any()))
                .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getDataset("t1", "ds_xxx"))
                .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested @DisplayName("list")
    class ListTests {
        @Test @DisplayName("lists datasets for tenant")
        void listAll() {
            when(datasetRepository.findAllByTenantIdOrderByCreatedAtDesc("t1"))
                .thenReturn(List.of());
            assertThat(service.listDatasets("t1", null)).isEmpty();
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -Dtest=DatasetServiceTest -q`
Expected: compilation error (DatasetService doesn't exist yet)

- [ ] **Step 3: Implement DatasetService**

Key methods:
- `create(tenantId, name, description, databaseId, queryMode, tables, sql)` — validates, generates SQL from table selection, saves as DRAFT
- `preview(tenantId, databaseId, queryMode, tables, sql)` — wakes compute, runs SELECT LIMIT 10 + COUNT(*)
- `triggerExport(tenantId, datasetId)` — wakes compute, constructs connstr, submits EXPORT_PARQUET job
- `listDatasets(tenantId, status)` — list with optional filter
- `getDataset(tenantId, datasetId)` — get single entity (tenant-scoped)
- `getDatasetResponse(tenantId, datasetId)` — returns detail Map with presigned download URL + code snippets
- `deleteDataset(tenantId, datasetId)` — delete entity + OBS file

**SQL injection validation** (CUSTOM_SQL mode):
```java
private void validateCustomSql(String sql) {
    if (sql == null || sql.isBlank()) throw new BadRequestException("SQL is required");
    String normalized = sql.trim().toLowerCase();
    if (!normalized.startsWith("select")) throw new BadRequestException("Only SELECT statements are allowed");
    if (normalized.contains(";")) throw new BadRequestException("Multiple statements not allowed");
}
```

**getDatasetResponse** implementation:
```java
public Map<String, Object> getDatasetResponse(String tenantId, String datasetId) {
    DatasetEntity ds = getDataset(tenantId, datasetId);
    Map<String, Object> resp = toMap(ds);  // basic fields
    if (ds.getStatus() == DatasetStatus.READY && ds.getObsPath() != null) {
        // Presigned download URL (valid 1 hour)
        String bucket = props.getNeon().getRemoteStorageBucket();
        String endpoint = props.getNeon().getRemoteStorageEndpoint();
        resp.put("download_url", generatePresignedUrl(bucket, ds.getObsPath(), endpoint));
        // Code snippets
        String s3Path = "s3://" + bucket + "/" + ds.getObsPath();
        Map<String, String> snippets = new LinkedHashMap<>();
        snippets.put("pandas", "import pandas as pd\ndf = pd.read_parquet('" + s3Path + "')");
        snippets.put("ray", "import ray.data\nds = ray.data.read_parquet('" + s3Path + "')");
        snippets.put("duckdb", "import duckdb\nduckdb.sql(\"SELECT * FROM '" + s3Path + "'\")");
        resp.put("code_snippets", snippets);
    }
    return resp;
}
```

Dependencies:
- `DatasetRepository`
- `ComputeLifecycleService` (wakeCompute)
- `DatabaseRepository` (get database entity for name/connstr)
- `DatabaseQueryService` (preview — executeQuery)
- `JobService` (submitJob)
- `LakeonProperties` (OBS config for presigned URLs)

SQL generation from table selection:
```java
private String generateSql(List<TableSelection> tables) {
    // Single table: SELECT col1, col2 FROM tablename
    // Multiple tables: UNION ALL of per-table SELECTs (or reject — keep simple for now)
    TableSelection t = tables.get(0);
    String cols = t.getColumns() != null && !t.getColumns().isEmpty()
        ? String.join(", ", t.getColumns().stream().map(c -> "\"" + c + "\"").toList())
        : "*";
    return "SELECT " + cols + " FROM \"" + t.getName() + "\"";
}
```

Connstr construction in triggerExport:
```java
String address = computeLifecycleService.wakeCompute(db.getId());
// address = "host:port"
String connstr = "postgresql://cloud_admin:cloud-admin-internal@" + address + "/" + db.getName() + "?sslmode=disable";
// Refresh lastActiveAt to prevent suspend during export
db.setLastActiveAt(Instant.now());
databaseRepository.save(db);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd lakeon-api && mvn test -Dtest=DatasetServiceTest -q`
Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java
git add lakeon-api/src/test/java/com/lakeon/dataset/DatasetServiceTest.java
git commit -m "feat(dataset): DatasetService with create/preview/export/list/get/delete"
```

---

## Task 3: DatasetController

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetController.java`

- [ ] **Step 1: Implement DatasetController**

Follow `KnowledgeController` pattern: extract tenant from `req.getAttribute("tenant")`, use `LinkedHashMap` responses.

```java
package com.lakeon.dataset;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.preview(tenant.getId(),
            (String) body.get("database_id"),
            (String) body.get("query_mode"),
            body.get("tables"),   // List<Map>
            (String) body.get("sql"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetEntity ds = datasetService.create(tenant.getId(),
            (String) body.get("name"),
            (String) body.get("description"),
            (String) body.get("database_id"),
            (String) body.get("query_mode"),
            body.get("tables"),
            (String) body.get("sql"));
        return toResponse(ds);
    }

    @PostMapping("/{id}/export")
    public Map<String, Object> triggerExport(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetEntity ds = datasetService.triggerExport(tenant.getId(), id);
        return toResponse(ds);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req,
                                          @RequestParam(required = false) String status) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetStatus ds = null;
        if (status != null && !status.isBlank()) {
            ds = DatasetStatus.valueOf(status.toUpperCase());
        }
        return datasetService.listDatasets(tenant.getId(), ds).stream()
            .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.getDatasetResponse(tenant.getId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        datasetService.deleteDataset(tenant.getId(), id);
    }

    private Map<String, Object> toResponse(DatasetEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("name", ds.getName());
        m.put("description", ds.getDescription());
        m.put("source_type", ds.getSourceType().name());
        m.put("database_id", ds.getDatabaseId());
        m.put("status", ds.getStatus().name());
        m.put("row_count", ds.getRowCount());
        m.put("file_size", ds.getFileSize());
        m.put("obs_path", ds.getObsPath());
        m.put("job_id", ds.getJobId());
        m.put("error", ds.getError());
        m.put("created_at", ds.getCreatedAt() != null ? ds.getCreatedAt().toString() : null);
        m.put("updated_at", ds.getUpdatedAt() != null ? ds.getUpdatedAt().toString() : null);
        return m;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetController.java
git commit -m "feat(dataset): DatasetController REST endpoints"
```

---

## Task 4: Export Job completion hook

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/job/JobService.java`

When an `EXPORT_PARQUET` job completes, update the associated DatasetEntity with results (row_count, file_size, obs_path, status).

- [ ] **Step 1: Add DatasetRepository dependency to JobService**

Add optional `@Autowired(required = false)` for `DatasetRepository` (same pattern as `KbWriteQueue`).

- [ ] **Step 2: In handleCallback(), after terminal status update, check if job type is EXPORT_PARQUET**

```java
// After existing terminal status handling (around line 226)
if (newStatus == JobStatus.SUCCEEDED && job.getType() == JobType.EXPORT_PARQUET) {
    updateDatasetFromExport(job, resultJson);
} else if (newStatus == JobStatus.FAILED && job.getType() == JobType.EXPORT_PARQUET) {
    failDatasetFromExport(job, error);
}
```

```java
private void updateDatasetFromExport(JobEntity job, String resultJson) {
    if (datasetRepository == null) return;
    datasetRepository.findByJobId(job.getId()).ifPresent(ds -> {
        ds.setStatus(DatasetStatus.READY);
        if (resultJson != null) {
            try {
                Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
                if (result.get("row_count") instanceof Number n) ds.setRowCount(n.longValue());
                if (result.get("file_size") instanceof Number n) ds.setFileSize(n.longValue());
                if (result.get("obs_path") instanceof String s) ds.setObsPath(s);
            } catch (Exception e) {
                log.warn("Failed to parse export result for job {}: {}", job.getId(), e.getMessage());
            }
        }
        datasetRepository.save(ds);
    });
}
```

- [ ] **Step 3: Verify compilation and run existing tests**

Run: `cd lakeon-api && mvn compile -q && mvn test -Dtest=DatasetServiceTest -q`
Expected: all pass

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobService.java
git commit -m "feat(dataset): export job completion updates DatasetEntity"
```

---

## Task 5: export_parquet.py

**Files:**
- Create: `knowledge/job/export_parquet.py`
- Modify: `knowledge/job/main.py`
- Modify: `knowledge/job/requirements.txt`

- [ ] **Step 1: Add duckdb to requirements.txt**

Append `duckdb` to `knowledge/job/requirements.txt`.

- [ ] **Step 2: Create export_parquet.py**

```python
"""Export data from user PG to Parquet on OBS via DuckDB."""
import json
import os
import logging

import boto3
import duckdb

from callback import report_success, report_failure, report_progress

logger = logging.getLogger("export-parquet")


def main():
    try:
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        connstr = params["database_connstr"]
        source_sql = params["source_sql"]
        obs_path = params["obs_output_path"]

        obs_endpoint = os.environ["OBS_ENDPOINT"]
        obs_ak = os.environ["OBS_ACCESS_KEY"]
        obs_sk = os.environ["OBS_SECRET_KEY"]
        obs_bucket = os.environ.get("OBS_BUCKET", "lakeon-storage")

        report_progress("Initializing DuckDB", 0.1)

        conn = duckdb.connect()
        conn.execute("INSTALL postgres; LOAD postgres;")
        conn.execute("INSTALL httpfs; LOAD httpfs;")

        # Configure S3 (OBS is S3-compatible)
        s3_host = obs_endpoint.replace("https://", "").replace("http://", "")
        conn.execute(f"""
            SET s3_endpoint = '{s3_host}';
            SET s3_access_key_id = '{obs_ak}';
            SET s3_secret_access_key = '{obs_sk}';
            SET s3_url_style = 'path';
            SET s3_use_ssl = true;
        """)

        report_progress("Counting rows", 0.2)

        # Escape single quotes in source_sql for DuckDB string embedding
        safe_sql = source_sql.replace("'", "''")

        # Count rows first
        count_result = conn.execute(
            f"SELECT COUNT(*) FROM postgres_query('{connstr}', '{safe_sql}')"
        ).fetchone()
        row_count = count_result[0]
        logger.info(f"Row count: {row_count}")

        report_progress(f"Exporting {row_count} rows to Parquet", 0.3)

        # Export to Parquet on OBS
        full_obs_path = f"s3://{obs_bucket}/{obs_path}"
        conn.execute(f"""
            COPY (
                SELECT * FROM postgres_query('{connstr}', '{safe_sql}')
            )
            TO '{full_obs_path}'
            (FORMAT PARQUET, ROW_GROUP_SIZE 100000, COMPRESSION ZSTD)
        """)

        report_progress("Getting file size", 0.9)

        # Get file size from OBS
        s3 = boto3.client("s3",
            endpoint_url=obs_endpoint,
            aws_access_key_id=obs_ak,
            aws_secret_access_key=obs_sk)
        head = s3.head_object(Bucket=obs_bucket, Key=obs_path)
        file_size = head["ContentLength"]

        logger.info(f"Export complete: {row_count} rows, {file_size} bytes -> {obs_path}")

        report_success({
            "row_count": row_count,
            "file_size": file_size,
            "obs_path": obs_path
        })

    except Exception as e:
        logger.error(f"Export failed: {e}", exc_info=True)
        report_failure(str(e))
```

- [ ] **Step 3: Add EXPORT_PARQUET dispatch to main.py**

At the top of `main()` in `knowledge/job/main.py`, before existing logic:

```python
def main():
    job_type = os.environ.get("JOB_TYPE", "DOCUMENT_PARSE")
    if job_type == "EXPORT_PARQUET":
        from export_parquet import main as export_main
        export_main()
        return

    # ... existing DOCUMENT_PARSE logic below ...
```

- [ ] **Step 4: Update application.yml export-parquet config**

Change memory from 512Mi to 2Gi, cpu from 500m to 1:

```yaml
      export-parquet:
        image: ${LAKEON_JOB_IMAGE_EXPORT_PARQUET:swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge-job:0.2.0}
        cpu: ${LAKEON_JOB_CPU_EXPORT_PARQUET:1}
        memory: ${LAKEON_JOB_MEMORY_EXPORT_PARQUET:2Gi}
```

- [ ] **Step 5: Commit**

```bash
git add knowledge/job/export_parquet.py knowledge/job/main.py knowledge/job/requirements.txt
git add lakeon-api/src/main/resources/application.yml
git commit -m "feat(dataset): export_parquet.py DuckDB job + main.py dispatch"
```

> **Note**: After this commit, a new job image must be built that includes `duckdb` and its extensions.
> The Dockerfile should pre-install DuckDB extensions at build time:
> ```dockerfile
> RUN pip install duckdb && python -c "import duckdb; duckdb.execute('INSTALL postgres; INSTALL httpfs;')"
> ```
> Bump the image tag (e.g., `0.2.0`) and update `application.yml` accordingly.

---

## Task 6: Console — sidebar cleanup + dataset routes

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Remove Notebook and 模型仓库 from sidebar**

In `ConsoleLayout.vue`, in the datalake nav group, remove the Notebook and 模型仓库 router-links. Keep only:
```vue
<router-link to="/datalake" class="nav-item" active-class="active" @click="sidebarOpen = false">作业管理</router-link>
<router-link to="/datalake/datasets" class="nav-item" active-class="active" @click="sidebarOpen = false">数据集</router-link>
```

- [ ] **Step 2: Update router**

Remove routes for `datalake/notebooks` and `datalake/models`. Add new routes:
```ts
{ path: 'datalake/datasets', name: 'DatalakeDatasets', component: () => import('../views/datalake/DatalakeDatasets.vue') },
{ path: 'datalake/datasets/new', name: 'DatalakeDatasetNew', component: () => import('../views/datalake/DatalakeDatasetNew.vue') },
{ path: 'datalake/datasets/:id', name: 'DatalakeDatasetDetail', component: () => import('../views/datalake/DatalakeDatasetDetail.vue') },
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/layouts/ConsoleLayout.vue lakeon-console/src/router/index.ts
git commit -m "feat(console): cleanup datalake sidebar, add dataset routes"
```

---

## Task 7: Console — dataset list page

**Files:**
- Modify: `lakeon-console/src/views/datalake/DatalakeDatasets.vue`

- [ ] **Step 1: Replace placeholder with real dataset list**

Follow `DatalakeJobs.vue` patterns: status tabs, table, auto-polling.

Key elements:
- Status tabs: 全部 / DRAFT / EXPORTING / READY / FAILED (with counts)
- Table columns: 名称 / 来源 / 行数 / 大小 / 状态 / 创建时间 / 操作
- Actions: 下载 (READY) / 删除
- Right-top: 「新建数据集」button → navigates to `/datalake/datasets/new`
- Auto-poll every 10s when any dataset is EXPORTING
- API calls: `GET /api/v1/datasets` with bearer token from localStorage

Helper functions:
- `formatSize(bytes)`: human-readable file size (KB/MB/GB)
- `statusColor(status)`: DRAFT→gray, EXPORTING→blue, READY→green, FAILED→red

- [ ] **Step 2: Verify it renders**

Run console dev server, navigate to 数据湖 → 数据集, verify empty state shows.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeDatasets.vue
git commit -m "feat(console): dataset list page with status filtering"
```

---

## Task 8: Console — new dataset page

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeDatasetNew.vue`

- [ ] **Step 1: Build the new dataset creation page**

Sections:
1. **名称** — text input
2. **选择数据库** — dropdown, calls `GET /api/v1/databases` to populate
3. **模式切换** — toggle between 「选择表」and「自定义 SQL」
4. **选择表模式** — on database select, calls `GET /api/v1/databases/{id}/schemas/public/tables` to list tables. Checkboxes for table selection.
5. **自定义 SQL 模式** — textarea / code editor
6. **预览** button — calls `POST /api/v1/datasets/preview`, shows 10-row table + total count
7. **导出** button — calls `POST /api/v1/datasets` then `POST /api/v1/datasets/{id}/export`, redirects to detail page

Support URL query params: `?database_id=xxx&table=yyy` for pre-filling from database page shortcut.

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeDatasetNew.vue
git commit -m "feat(console): new dataset page with table select + SQL + preview"
```

---

## Task 9: Console — dataset detail page

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeDatasetDetail.vue`

- [ ] **Step 1: Build dataset detail page**

Sections:
- **Header**: dataset name + status badge
- **Info card**: source type, database name, row count, file size, created at
- **Progress**: show when EXPORTING (poll every 5s)
- **Code snippets**: pandas / ray / duckdb tabs with copy button (only when READY)
- **Download**: button that opens presigned URL (only when READY)
- **Error**: show error message when FAILED
- **Delete**: danger button

API: `GET /api/v1/datasets/{id}` — response includes `code_snippets` and `download_url`.

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeDatasetDetail.vue
git commit -m "feat(console): dataset detail page with code snippets + download"
```

---

## Task 10: Database page — export shortcut

**Files:**
- Modify: `lakeon-console/src/views/database/` (the table list component)

- [ ] **Step 1: Add export link to DatabaseDetail.vue**

In `lakeon-console/src/views/database/DatabaseDetail.vue`, find the table list section. Add an "导出到数据集" action button/link for each table row.

- [ ] **Step 2: Add export link**

Add a router-link or button that navigates to:
```
/datalake/datasets/new?database_id=${dbId}&table=${tableName}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/database/
git commit -m "feat(console): database table list export-to-dataset shortcut"
```

---

## Task 11: Datalake job ↔ dataset integration

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java`

- [ ] **Step 1: Add fields to DatalakeJobRequest**

```java
@JsonProperty("input_dataset_id")
private String inputDatasetId;

@JsonProperty("output_dataset_name")
private String outputDatasetName;

// getters + setters
```

- [ ] **Step 2: In DatalakeService.submitJob(), handle dataset input**

Before starting the runner, if `inputDatasetId` is set:
- Look up DatasetEntity, verify it's READY
- Add `DATASET_PATH=s3://{bucket}/{obs_path}` to job's env vars

- [ ] **Step 3: In DatalakeService, handle output dataset on job completion**

When a datalake job with `outputDatasetName` completes successfully:
- Create DatasetEntity with source_type=JOB_OUTPUT, status=READY
- Set obs_path from the job's output convention

This can be done in `DatalakeStatusPoller` where job status is synced.

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java
git commit -m "feat(datalake): dataset input/output integration for jobs"
```

---

## Task 12: Final cleanup + delete unused files

**Files:**
- Delete: `lakeon-console/src/views/datalake/DatalakeNotebooks.vue`
- Delete: `lakeon-console/src/views/datalake/DatalakeModels.vue`

- [ ] **Step 1: Delete unused placeholder files**

```bash
rm lakeon-console/src/views/datalake/DatalakeNotebooks.vue
rm lakeon-console/src/views/datalake/DatalakeModels.vue
```

- [ ] **Step 2: Verify console builds**

Run: `cd lakeon-console && npm run build`
Expected: no errors (routes removed in Task 6, so no dangling imports)

- [ ] **Step 3: Run all API tests**

Run: `cd lakeon-api && mvn test -q`
Expected: all pass (except pre-existing BranchServiceTest failures)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: remove unused Notebook/Models placeholders"
```
