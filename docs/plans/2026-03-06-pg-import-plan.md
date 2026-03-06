# PG Data Import Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to import data from external PostgreSQL databases into Lakeon via a Job Pod running pg_dump/pg_restore, with per-table progress tracking, pause/resume/cancel/retry.

**Architecture:** Console UI wizard collects source DB info and table selection. lakeon-api creates an import Job Pod in lakeon-compute namespace. The Job Pod runs a shell script that executes pg_dump|pg_restore per table, calling back to lakeon-api to update progress. State is persisted in two RDS tables (import_tasks, import_table_tasks).

**Tech Stack:** Java 17 / Spring Boot 3.3 / JPA / Fabric8 K8s client / Vue 3 / TypeScript / pg_dump / pg_restore

**Design doc:** `docs/plans/2026-03-06-pg-import-design.md`

---

## Task 1: Backend Enums

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/ImportTaskStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/ImportMode.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/ConflictStrategy.java`

**Step 1: Create enums**

```java
// ImportTaskStatus.java
package com.lakeon.model.enums;
public enum ImportTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, PARTIAL, CANCELLED, PAUSED
}
```

```java
// ImportMode.java
package com.lakeon.model.enums;
public enum ImportMode {
    FULL, SELECTIVE
}
```

```java
// ConflictStrategy.java
package com.lakeon.model.enums;
public enum ConflictStrategy {
    APPEND, REPLACE
}
```

**Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/enums/Import*.java lakeon-api/src/main/java/com/lakeon/model/enums/ConflictStrategy.java
git commit -m "feat(import): add enums for import task status, mode, conflict strategy"
```

---

## Task 2: Backend Entities

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTableTaskEntity.java`

**Step 1: Create ImportTaskEntity**

Follow the existing pattern from `DatabaseEntity` and `OperationLogEntity`. ID prefix: `"imp_"`.

```java
package com.lakeon.model.entity;

import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_tasks",
       indexes = {
           @Index(name = "idx_import_tasks_tenant_id", columnList = "tenant_id"),
           @Index(name = "idx_import_tasks_database_id", columnList = "database_id")
       })
public class ImportTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "database_id", nullable = false, length = 64)
    private String databaseId;

    @Column(name = "source_host", nullable = false, length = 256)
    private String sourceHost;

    @Column(name = "source_port", nullable = false)
    private Integer sourcePort;

    @Column(name = "source_dbname", nullable = false, length = 128)
    private String sourceDbname;

    @Column(name = "source_user", nullable = false, length = 128)
    private String sourceUser;

    @Column(name = "source_password", nullable = false, length = 256)
    private String sourcePassword;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private ImportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_strategy", nullable = false, length = 16)
    private ConflictStrategy conflictStrategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImportTaskStatus status;

    @Column(name = "total_tables")
    private Integer totalTables;

    @Column(name = "completed_tables")
    private Integer completedTables;

    @Column(name = "job_pod_name", length = 128)
    private String jobPodName;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "imp_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters for all fields (generate all)
}
```

**Step 2: Create ImportTableTaskEntity**

ID prefix: `"itb_"`.

```java
package com.lakeon.model.entity;

import com.lakeon.model.enums.ImportTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_table_tasks",
       indexes = {
           @Index(name = "idx_import_table_tasks_task_id", columnList = "import_task_id")
       })
public class ImportTableTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "import_task_id", nullable = false, length = 64)
    private String importTaskId;

    @Column(name = "schema_name", nullable = false, length = 128)
    private String schemaName;

    @Column(name = "table_name", nullable = false, length = 128)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImportTaskStatus status;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "itb_" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    // Getters and setters for all fields (generate all)
}
```

**Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

**Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/entity/Import*.java
git commit -m "feat(import): add ImportTaskEntity and ImportTableTaskEntity"
```

---

## Task 3: Backend Repositories

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/repository/ImportTaskRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/ImportTableTaskRepository.java`

**Step 1: Create repositories**

```java
// ImportTaskRepository.java
package com.lakeon.repository;

import com.lakeon.model.entity.ImportTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ImportTaskRepository extends JpaRepository<ImportTaskEntity, String> {
    List<ImportTaskEntity> findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc(String databaseId, String tenantId);
    Optional<ImportTaskEntity> findByIdAndTenantId(String id, String tenantId);
}
```

```java
// ImportTableTaskRepository.java
package com.lakeon.repository;

import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.enums.ImportTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImportTableTaskRepository extends JpaRepository<ImportTableTaskEntity, String> {
    List<ImportTableTaskEntity> findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(String importTaskId);
    List<ImportTableTaskEntity> findAllByImportTaskIdAndStatus(String importTaskId, ImportTaskStatus status);
    long countByImportTaskIdAndStatus(String importTaskId, ImportTaskStatus status);
}
```

**Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/repository/Import*.java
git commit -m "feat(import): add import task repositories"
```

---

## Task 4: Backend DTOs

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/TestConnectionRequest.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/SourceTableInfo.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/ImportTableTaskResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/ImportCallbackRequest.java`

**Step 1: Create all DTOs**

```java
// TestConnectionRequest.java
package com.lakeon.model.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TestConnectionRequest(
    @NotBlank String host,
    @NotNull Integer port,
    @NotBlank String dbname,
    @NotBlank String user,
    @NotBlank String password
) {}
```

```java
// SourceTableInfo.java
package com.lakeon.model.dto;

public record SourceTableInfo(
    String schema,
    String table,
    long estimatedRows
) {}
```

```java
// CreateImportRequest.java
package com.lakeon.model.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateImportRequest(
    @NotBlank String sourceHost,
    @NotNull Integer sourcePort,
    @NotBlank String sourceDbname,
    @NotBlank String sourceUser,
    @NotBlank String sourcePassword,
    @NotBlank String mode,              // "full" or "selective"
    @NotBlank String conflictStrategy,  // "append" or "replace"
    List<String> tables                 // null for full mode, "schema.table" list for selective
) {}
```

```java
// ImportTaskResponse.java
package com.lakeon.model.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportTaskResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    @JsonProperty("source_host") String sourceHost,
    @JsonProperty("source_port") Integer sourcePort,
    @JsonProperty("source_dbname") String sourceDbname,
    String mode,
    @JsonProperty("conflict_strategy") String conflictStrategy,
    String status,
    @JsonProperty("total_tables") Integer totalTables,
    @JsonProperty("completed_tables") Integer completedTables,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("started_at") Instant startedAt,
    @JsonProperty("finished_at") Instant finishedAt,
    List<ImportTableTaskResponse> tables
) {}
```

```java
// ImportTableTaskResponse.java
package com.lakeon.model.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportTableTaskResponse(
    String id,
    @JsonProperty("schema_name") String schemaName,
    @JsonProperty("table_name") String tableName,
    String status,
    @JsonProperty("row_count") Long rowCount,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("started_at") Instant startedAt,
    @JsonProperty("finished_at") Instant finishedAt
) {}
```

```java
// ImportCallbackRequest.java
package com.lakeon.model.dto;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ImportCallbackRequest(
    @JsonProperty("table_task_id") String tableTaskId,
    String status,           // "running", "completed", "failed"
    @JsonProperty("row_count") Long rowCount,
    @JsonProperty("error_message") String errorMessage
) {}
```

**Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

**Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/TestConnectionRequest.java \
        lakeon-api/src/main/java/com/lakeon/model/dto/SourceTableInfo.java \
        lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java \
        lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java \
        lakeon-api/src/main/java/com/lakeon/model/dto/ImportTableTaskResponse.java \
        lakeon-api/src/main/java/com/lakeon/model/dto/ImportCallbackRequest.java
git commit -m "feat(import): add DTOs for import feature"
```

---

## Task 5: Backend ImportService

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/ImportService.java`

This is the core service. Key methods:

1. `testConnection(request)` — JDBC connect to source, return success/error
2. `listSourceTables(request)` — connect and query `information_schema.tables`
3. `createImport(tenant, dbId, request)` — create task + table tasks + launch Job Pod
4. `getImport(tenant, dbId, taskId)` — return task with table details
5. `listImports(tenant, dbId)` — list tasks for a database
6. `pauseImport(tenant, dbId, taskId)` — delete Job Pod, mark paused
7. `resumeImport(tenant, dbId, taskId)` — launch new Job Pod with remaining tables
8. `cancelImport(tenant, dbId, taskId)` — delete Job Pod, mark cancelled
9. `retryImport(tenant, dbId, taskId)` — reset failed tables to pending, launch Job Pod
10. `handleCallback(taskId, request)` — update table task status, recalculate totals

**Step 1: Implement ImportService**

The service should:
- Inject `ImportTaskRepository`, `ImportTableTaskRepository`, `DatabaseRepository`, `ImportJobPodManager` (Task 6), `LakeonProperties`
- `testConnection`: use `DriverManager.getConnection()` with 5s timeout, return boolean
- `listSourceTables`: connect, query `SELECT table_schema, table_name, COALESCE(c.reltuples, 0) FROM information_schema.tables t LEFT JOIN pg_class c ON c.relname = t.table_name WHERE t.table_type = 'BASE TABLE' AND t.table_schema NOT IN ('pg_catalog', 'information_schema')`
- `createImport`: validate database belongs to tenant, create `ImportTaskEntity` (status=PENDING), create `ImportTableTaskEntity` for each table (status=PENDING), call `importJobPodManager.launchJobPod(task, tableTasks)`
- `handleCallback`: find table task by ID, update status/rowCount/error, increment parent `completed_tables` if completed, check if all tables done to set parent terminal status (COMPLETED/PARTIAL/FAILED)
- Pause/Resume/Cancel: validate status transitions, call `importJobPodManager.deleteJobPod()` for pause/cancel, call `launchJobPod()` for resume (passing only non-completed tables)

**Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

**Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/ImportService.java
git commit -m "feat(import): add ImportService with full lifecycle management"
```

---

## Task 6: Backend ImportJobPodManager

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/k8s/ImportJobPodManager.java`

Follow `ComputePodManager` patterns. This creates a Job Pod that runs the import shell script.

**Step 1: Implement ImportJobPodManager**

Key design decisions:
- Pod name: `"import-" + task.getId().replace("_", "-")`
- Image: same compute-node-v17 image (has pg_dump/pg_restore)
- ConfigMap: task config JSON (table list, strategies, target DB connection info)
- Secret: source DB password
- Command: `sh /scripts/import.sh`
- Scripts ConfigMap: mount the import shell script (from Task 7)
- Namespace: `lakeon-compute` (same as compute pods)
- RestartPolicy: Never

The pod needs:
- Source DB connection info (from ConfigMap + Secret)
- Target DB connection info: compute pod IP:55433, user=cloud_admin, password=cloud-admin-internal
- API callback URL: `http://lakeon-api.lakeon.svc.cluster.local:8080/api/v1/import/callback/{taskId}`
- Table list with task IDs (for callback)

Methods:
- `launchJobPod(ImportTaskEntity task, List<ImportTableTaskEntity> tableTasks, DatabaseEntity targetDb)`
- `deleteJobPod(String podName)` — delete pod + configmap + secret

**Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

**Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/k8s/ImportJobPodManager.java
git commit -m "feat(import): add ImportJobPodManager for K8s Job Pod lifecycle"
```

---

## Task 7: Import Shell Script

**Files:**
- Create: `deploy/helm/lakeon/scripts/import.sh`

This script runs inside the Job Pod. It reads config from `/config/task.json` and executes pg_dump|pg_restore per table.

**Step 1: Write the import script**

```bash
#!/bin/bash
# PG Import Script — runs inside Job Pod
# Reads /config/task.json for table list and connection info
# Calls back to lakeon-api after each table

set -euo pipefail

CONFIG=/config/task.json
API_URL=$(jq -r '.callback_url' $CONFIG)
SOURCE_HOST=$(jq -r '.source.host' $CONFIG)
SOURCE_PORT=$(jq -r '.source.port' $CONFIG)
SOURCE_DB=$(jq -r '.source.dbname' $CONFIG)
SOURCE_USER=$(jq -r '.source.user' $CONFIG)
TARGET_HOST=$(jq -r '.target.host' $CONFIG)
TARGET_PORT=$(jq -r '.target.port' $CONFIG)
TARGET_DB=$(jq -r '.target.dbname' $CONFIG)
TARGET_USER=$(jq -r '.target.user' $CONFIG)
TARGET_PASSWORD=$(jq -r '.target.password' $CONFIG)
CONFLICT=$(jq -r '.conflict_strategy' $CONFIG)
TABLE_COUNT=$(jq '.tables | length' $CONFIG)

export PGPASSWORD_SOURCE=$(cat /secrets/source-password)
export PGPASSWORD="$TARGET_PASSWORD"

callback() {
  local table_task_id=$1 status=$2 row_count=${3:-0} error=${4:-}
  curl -sf -X PUT "$API_URL" \
    -H "Content-Type: application/json" \
    -d "{\"table_task_id\":\"$table_task_id\",\"status\":\"$status\",\"row_count\":$row_count,\"error_message\":\"$error\"}" \
    || echo "WARN: callback failed for $table_task_id"
}

for i in $(seq 0 $((TABLE_COUNT - 1))); do
  TASK_ID=$(jq -r ".tables[$i].id" $CONFIG)
  SCHEMA=$(jq -r ".tables[$i].schema" $CONFIG)
  TABLE=$(jq -r ".tables[$i].table" $CONFIG)

  echo "=== Importing $SCHEMA.$TABLE ($((i+1))/$TABLE_COUNT) ==="
  callback "$TASK_ID" "running"

  DUMP_ARGS="-h $SOURCE_HOST -p $SOURCE_PORT -U $SOURCE_USER -d $SOURCE_DB -t $SCHEMA.$TABLE"
  RESTORE_ARGS="-h $TARGET_HOST -p $TARGET_PORT -U $TARGET_USER -d $TARGET_DB"

  if [ "$CONFLICT" = "replace" ]; then
    DUMP_ARGS="$DUMP_ARGS --clean --if-exists"
  else
    DUMP_ARGS="$DUMP_ARGS --data-only"
  fi

  if PGPASSWORD="$PGPASSWORD_SOURCE" pg_dump $DUMP_ARGS -Fc 2>/tmp/dump_err | \
     pg_restore $RESTORE_ARGS --no-owner --no-acl 2>/tmp/restore_err; then
    # Count rows in target
    ROW_COUNT=$(PGPASSWORD="$TARGET_PASSWORD" psql -h "$TARGET_HOST" -p "$TARGET_PORT" -U "$TARGET_USER" -d "$TARGET_DB" \
      -t -A -c "SELECT count(*) FROM $SCHEMA.$TABLE" 2>/dev/null || echo 0)
    callback "$TASK_ID" "completed" "$ROW_COUNT"
    echo "  OK: $ROW_COUNT rows"
  else
    ERR=$(cat /tmp/dump_err /tmp/restore_err 2>/dev/null | tail -5 | tr '"' "'" | tr '\n' ' ')
    callback "$TASK_ID" "failed" 0 "$ERR"
    echo "  FAILED: $ERR"
  fi
done

echo "=== Import complete ==="
```

**Step 2: Commit**

```bash
git add deploy/helm/lakeon/scripts/import.sh
git commit -m "feat(import): add pg_dump/pg_restore import shell script for Job Pod"
```

---

## Task 8: Helm Template for Import Script ConfigMap

**Files:**
- Create: `deploy/helm/lakeon/templates/configmap-import-script.yaml`

**Step 1: Create Helm template**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: lakeon-import-script
  namespace: lakeon-compute
  labels:
    {{- include "lakeon.labels" . | nindent 4 }}
data:
  import.sh: |
{{ .Files.Get "scripts/import.sh" | indent 4 }}
```

**Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/configmap-import-script.yaml
git commit -m "feat(import): add Helm template for import script ConfigMap"
```

---

## Task 9: Backend Controller

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/ImportController.java`

**Step 1: Implement ImportController**

```java
package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.ImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ImportController {
    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import/test-connection")
    public Map<String, Object> testConnection(HttpServletRequest req,
                                               @Valid @RequestBody TestConnectionRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.testConnection(request);
    }

    @PostMapping("/import/source-tables")
    public List<SourceTableInfo> listSourceTables(HttpServletRequest req,
                                                   @Valid @RequestBody TestConnectionRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.listSourceTables(request);
    }

    @PostMapping("/databases/{dbId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportTaskResponse createImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @Valid @RequestBody CreateImportRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.createImport(tenant, dbId, request);
    }

    @GetMapping("/databases/{dbId}/import")
    public List<ImportTaskResponse> listImports(HttpServletRequest req,
                                                 @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.listImports(tenant, dbId);
    }

    @GetMapping("/databases/{dbId}/import/{taskId}")
    public ImportTaskResponse getImport(HttpServletRequest req,
                                         @PathVariable String dbId,
                                         @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.getImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/pause")
    public ImportTaskResponse pauseImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.pauseImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/resume")
    public ImportTaskResponse resumeImport(HttpServletRequest req,
                                            @PathVariable String dbId,
                                            @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.resumeImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/cancel")
    public ImportTaskResponse cancelImport(HttpServletRequest req,
                                            @PathVariable String dbId,
                                            @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.cancelImport(tenant, dbId, taskId);
    }

    @PostMapping("/databases/{dbId}/import/{taskId}/retry")
    public ImportTaskResponse retryImport(HttpServletRequest req,
                                           @PathVariable String dbId,
                                           @PathVariable String taskId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return importService.retryImport(tenant, dbId, taskId);
    }

    // Internal callback — no tenant auth required, validate by task existence
    @PutMapping("/import/callback/{taskId}")
    public void importCallback(@PathVariable String taskId,
                                @RequestBody ImportCallbackRequest request) {
        importService.handleCallback(taskId, request);
    }
}
```

**Step 2: Add callback path to ApiKeyFilter exclusion**

Modify: `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`

Add `/api/v1/import/callback/` to the list of paths that skip authentication (this is an internal-only endpoint called by Job Pods within the cluster).

**Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

**Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/ImportController.java \
        lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java
git commit -m "feat(import): add ImportController with all endpoints"
```

---

## Task 10: Frontend API Client

**Files:**
- Create: `lakeon-console/src/api/import.ts`

**Step 1: Create import API module**

```typescript
import client from './client'

export interface SourceTableInfo {
  schema: string
  table: string
  estimated_rows: number
}

export interface ImportTableTask {
  id: string
  schema_name: string
  table_name: string
  status: string
  row_count: number | null
  error_message: string | null
  started_at: string | null
  finished_at: string | null
}

export interface ImportTask {
  id: string
  database_id: string
  source_host: string
  source_port: number
  source_dbname: string
  mode: string
  conflict_strategy: string
  status: string
  total_tables: number
  completed_tables: number
  error_message: string | null
  created_at: string
  started_at: string | null
  finished_at: string | null
  tables: ImportTableTask[] | null
}

export const importApi = {
  testConnection: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<{ success: boolean; message: string }>('/import/test-connection', data),

  listSourceTables: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<SourceTableInfo[]>('/import/source-tables', data),

  create: (dbId: string, data: {
    sourceHost: string; sourcePort: number; sourceDbname: string;
    sourceUser: string; sourcePassword: string;
    mode: string; conflictStrategy: string; tables?: string[]
  }) => client.post<ImportTask>(`/databases/${dbId}/import`, {
    source_host: data.sourceHost, source_port: data.sourcePort,
    source_dbname: data.sourceDbname, source_user: data.sourceUser,
    source_password: data.sourcePassword,
    mode: data.mode, conflict_strategy: data.conflictStrategy,
    tables: data.tables,
  }),

  list: (dbId: string) =>
    client.get<ImportTask[]>(`/databases/${dbId}/import`),

  get: (dbId: string, taskId: string) =>
    client.get<ImportTask>(`/databases/${dbId}/import/${taskId}`),

  pause: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/pause`),

  resume: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/resume`),

  cancel: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/cancel`),

  retry: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/retry`),
}
```

**Step 2: Commit**

```bash
git add lakeon-console/src/api/import.ts
git commit -m "feat(import): add frontend import API client"
```

---

## Task 11: Frontend Import Wizard Component

**Files:**
- Create: `lakeon-console/src/views/database/ImportWizard.vue`

A multi-step wizard dialog:
- Step 1: Source DB connection form + test connection button
- Step 2: Table selection (full/selective with checkboxes)
- Step 3: Conflict strategy (append/replace) + confirm

**Step 1: Implement ImportWizard.vue**

The component should:
- Accept props: `dbId: string`, `visible: boolean`
- Emit: `close`, `created` (with task data)
- Step 1: form fields for host/port/dbname/user/password, "测试连接" button, green checkmark on success
- Step 2: after connection test passes, show "整库导入" toggle or table list with checkboxes, "加载表列表" triggers `importApi.listSourceTables()`
- Step 3: radio buttons for append/replace, summary, "开始导入" button
- Use existing CSS patterns from the codebase (`.dialog-overlay`, `.dialog-box`, `.form-input`, `.btn`)

**Step 2: Commit**

```bash
git add lakeon-console/src/views/database/ImportWizard.vue
git commit -m "feat(import): add ImportWizard component with 3-step flow"
```

---

## Task 12: Frontend Import Task List & Detail

**Files:**
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue` — add "导入" tab
- Create: `lakeon-console/src/views/database/ImportTaskDetail.vue` — task detail with table status

**Step 1: Add "导入" tab to DatabaseDetail.vue**

Add a new tab `{ key: 'import', label: '导入' }` to the tabs array. The tab content should show:
- "导入数据" button that opens ImportWizard
- Import task history list (importApi.list)
- Each task shows: status badge, source DB, progress (completed/total), created time
- Click task row opens ImportTaskDetail

**Step 2: Create ImportTaskDetail.vue**

This is a sub-view within the import tab content (not a separate route). Shows:
- Task summary: source DB, mode, strategy, status, created/started/finished times
- Action buttons based on status:
  - `running` → "暂停" button
  - `paused` → "恢复" button
  - `failed`/`partial` → "重试" button
  - `running`/`pending` → "取消" button
- Table list: schema.table, status badge, row count, error message (expandable)
- Auto-poll every 3s while status is `running` or `pending`

**Step 3: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue \
        lakeon-console/src/views/database/ImportTaskDetail.vue
git commit -m "feat(import): add import tab and task detail UI in console"
```

---

## Task 13: Build & Deploy

**Step 1: Build and verify locally**

```bash
cd lakeon-api && mvn package -DskipTests -q
cd lakeon-console && npm run build
```

**Step 2: Build and push images**

```bash
# API
IMAGE_TAG=0.2.0
docker build -t swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-api:$IMAGE_TAG \
  -f lakeon-api/target/Dockerfile.build lakeon-api/target/
docker push swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-api:$IMAGE_TAG

# Console
docker build -t swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-console:$IMAGE_TAG lakeon-console/
docker push swr.cn-north-4.myhuaweicloud.com/lakeon/lakeon-console:$IMAGE_TAG
```

**Step 3: Update values-cce.yaml**

Update `api.image.tag` and `console.image.tag` to new version.

**Step 4: Helm deploy**

```bash
source deploy/cce/.env.cce
KUBECONFIG=~/.kube/cce-lakeon-config helm upgrade --install lakeon deploy/helm/lakeon \
  -f deploy/cce/values-cce.yaml \
  --set obs.accessKey=$OBS_AK --set obs.secretKey=$OBS_SK \
  --set metadataDb.host=$RDS_PRIVATE_IP --set metadataDb.password=$RDS_PASSWORD \
  -n lakeon --timeout 5m --no-hooks
```

**Step 5: Verify import script ConfigMap deployed**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get configmap lakeon-import-script -n lakeon-compute
```

**Step 6: Commit final version bump**

```bash
git add deploy/cce/values-cce.yaml
git commit -m "chore: bump API to 0.2.0 and console to 0.2.0 for import feature"
git push origin main
```

---

## Task Dependencies

```
Task 1 (Enums) ──┐
                  ├── Task 2 (Entities) ── Task 3 (Repos) ── Task 4 (DTOs) ──┐
                  │                                                            ├── Task 5 (Service) ── Task 9 (Controller) ──┐
Task 7 (Script) ── Task 8 (Helm) ── Task 6 (PodManager) ─────────────────────┘                                              ├── Task 13 (Deploy)
Task 10 (Frontend API) ── Task 11 (Wizard) ── Task 12 (Task List/Detail) ───────────────────────────────────────────────────┘
```

**Parallelizable groups:**
- Group A (backend model): Tasks 1→2→3→4
- Group B (infra): Tasks 7→8→6
- Group C (frontend): Tasks 10→11→12
- Group D (backend logic): Task 5→9 (depends on A + B)
- Final: Task 13 (depends on all)
