# Lakebase CDF and Lakeon-managed Iceberg Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Lakebase CDF into Lakeon-managed lakehouse tables with initial backfill, Lakebase-backed Iceberg REST Catalog, Lakebase-backed metadata/planning index, and lazy Iceberg manifest export.

**Architecture:** Lakeon API owns the REST Catalog, CDF streams, commit transactions, planning index, and export materializer. User Lakebase databases store `_lakeon_iceberg` system tables; OBS stores Parquet data/delete files and lazily materialized Iceberg metadata/manifest files. PostgreSQL clients continue to query Lakebase tables; Iceberg clients query managed Parquet through the Lakeon REST Catalog.

**Tech Stack:** Spring Boot 3.3.5 / Java 17, PostgreSQL logical decoding, Neon/Lakebase compute, Huawei OBS, Jackson JSON, Flyway for Lakeon metadata RDS, tenant Lakebase SQL bootstrap, pytest E2E, JUnit/Mockito.

---

## File Structure

### API Package

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogController.java`  
  Standard REST Catalog endpoints for config, namespace/table load, commit, and scan planning.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogService.java`  
  Tenant authorization, table lookup, load response, commit orchestration, and plan endpoint orchestration.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergMetadataJson.java`  
  Minimal Iceberg table metadata JSON builder/parser used by MVP. Keep it format-compatible and small.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCommitValidator.java`  
  Validates update requirements and CAS preconditions.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergPlanningService.java`  
  Converts REST scan requests into Lakebase planning-index SQL and returns FileScanTask-compatible JSON.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergWarehousePath.java`  
  Builds managed OBS prefixes and validates table paths.

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergExportMaterializer.java`  
  Converts Lakebase planning index rows into standard Iceberg metadata/manifest files in OBS.

### CDF Package

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfController.java`  
  API for creating/listing/pausing/resuming CDF streams.

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java`  
  Stream lifecycle, source table validation, and publication/slot naming.

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfWorker.java`  
  Scheduled worker that consumes logical decoding batches and commits lakehouse snapshots.

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseBackfillService.java`  
  Creates the initial lakehouse snapshot for a source table and records the LSN boundary for incremental CDF.

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/CdfBatch.java`  
  Immutable batch model containing source rows, LSN range, txid, and operation counts.

- Create: `lakeon-api/src/main/java/com/lakeon/cdf/CdfParquetWriter.java`  
  Writes batch rows to OBS Parquet. First implementation can write JSONL test files behind an interface, then swap to Parquet in the Parquet task.

### Tenant Lakebase Schema

- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergTenantSchemaManager.java`  
  Ensures `_lakeon_iceberg` schema exists in the target user Lakebase database/branch.

- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergTenantSchemaManagerTest.java`  
  Verifies DDL is idempotent and contains required indexes.

### Lakeon Metadata RDS

- Create: `lakeon-api/src/main/resources/db/migration/V45__lakebase_cdf_catalog_control.sql`  
  Minimal control-plane index for feature flags, stream registry mirror, export jobs, and admin status. Heavy catalog rows stay in user Lakebase.

- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/LakebaseCdfStreamEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/LakebaseCdfStreamRepository.java`

### Tests

- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCommitValidatorTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCatalogServiceTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergPlanningServiceTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfWorkerTest.java`
- Create: `tests/e2e/test_lakebase_cdf_iceberg.py`

### Console/Admin Later

- Modify: `lakeon-console/src/router/index.ts`
- Create: `lakeon-console/src/views/cdf/LakebaseCdfList.vue`
- Modify: `lakeon-console/src/api/*` with CDF/Catalog status methods.

Do not start console work until backend E2E passes.

---

## Milestone 1: Tenant Lakebase Catalog Schema

### Task 1: Add Tenant Schema DDL Builder

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergTenantSchemaManager.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergTenantSchemaManagerTest.java`

- [x] **Step 1: Write failing tests**

Create `IcebergTenantSchemaManagerTest`:

```java
package com.lakeon.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IcebergTenantSchemaManagerTest {
    @Test
    void schemaSqlCreatesCatalogAndPlanningTables() {
        String sql = IcebergTenantSchemaManager.schemaSql();

        assertThat(sql).contains("CREATE SCHEMA IF NOT EXISTS _lakeon_iceberg");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.tables");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.snapshots");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.data_files");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.delete_files");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_streams");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_offsets");
        assertThat(sql).contains("CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_table_branch_snapshot");
    }

    @Test
    void schemaSqlTracksLazyExportStatus() {
        String sql = IcebergTenantSchemaManager.schemaSql();

        assertThat(sql).contains("export_status TEXT NOT NULL DEFAULT 'NOT_MATERIALIZED'");
        assertThat(sql).contains("current_metadata_json JSONB NOT NULL");
        assertThat(sql).contains("lower_bounds_json JSONB");
        assertThat(sql).contains("upper_bounds_json JSONB");
    }
}
```

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
cd lakeon-api
./mvnw -Dtest=IcebergTenantSchemaManagerTest test
```

Expected: compilation fails because `IcebergTenantSchemaManager` does not exist.

- [x] **Step 3: Implement DDL builder**

Create `IcebergTenantSchemaManager.java`:

```java
package com.lakeon.iceberg;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class IcebergTenantSchemaManager {
    private static final String SCHEMA_SQL = """
            CREATE SCHEMA IF NOT EXISTS _lakeon_iceberg;

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.tables (
                table_id TEXT PRIMARY KEY,
                database_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                namespace TEXT NOT NULL,
                table_name TEXT NOT NULL,
                table_location TEXT NOT NULL,
                current_metadata_location TEXT NOT NULL,
                current_metadata_json JSONB NOT NULL,
                current_metadata_hash TEXT NOT NULL,
                current_snapshot_id BIGINT,
                metadata_version BIGINT NOT NULL DEFAULT 0,
                export_status TEXT NOT NULL DEFAULT 'NOT_MATERIALIZED',
                last_commit_lsn TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE (database_id, branch_id, namespace, table_name)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.snapshots (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                parent_snapshot_id BIGINT,
                sequence_number BIGINT NOT NULL,
                operation TEXT NOT NULL,
                committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                manifest_materialized BOOLEAN NOT NULL DEFAULT false,
                PRIMARY KEY (table_id, branch_id, snapshot_id)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.data_files (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                file_path TEXT NOT NULL,
                content_type TEXT NOT NULL DEFAULT 'DATA',
                partition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                record_count BIGINT NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                lower_bounds_json JSONB,
                upper_bounds_json JSONB,
                null_counts_json JSONB,
                value_counts_json JSONB,
                added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (table_id, branch_id, snapshot_id, file_path)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.delete_files (
                table_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                snapshot_id BIGINT NOT NULL,
                file_path TEXT NOT NULL,
                delete_type TEXT NOT NULL,
                partition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                record_count BIGINT NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (table_id, branch_id, snapshot_id, file_path)
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_streams (
                stream_id TEXT PRIMARY KEY,
                source_schema TEXT NOT NULL,
                source_table TEXT NOT NULL,
                target_namespace TEXT NOT NULL,
                target_table TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                backfill_status TEXT NOT NULL DEFAULT 'PENDING',
                backfill_lsn TEXT,
                slot_name TEXT NOT NULL,
                publication_name TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE TABLE IF NOT EXISTS _lakeon_iceberg.cdf_offsets (
                stream_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                last_commit_lsn TEXT NOT NULL,
                last_txid TEXT,
                last_snapshot_id BIGINT NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (stream_id, branch_id)
            );

            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_table_branch_snapshot
                ON _lakeon_iceberg.data_files(table_id, branch_id, snapshot_id);
            CREATE INDEX IF NOT EXISTS idx_lakeon_iceberg_data_files_partition
                ON _lakeon_iceberg.data_files USING gin(partition_json);
            """;

    public static String schemaSql() {
        return SCHEMA_SQL;
    }

    public void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA_SQL);
        }
    }
}
```

- [x] **Step 4: Run test**

Run:

```bash
cd lakeon-api
./mvnw -Dtest=IcebergTenantSchemaManagerTest test
```

Expected: test passes.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergTenantSchemaManager.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergTenantSchemaManagerTest.java
git commit -m "feat(iceberg): add tenant catalog schema bootstrap"
```

### Task 2: Add Lakeon RDS Control-plane Migration

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V45__lakebase_cdf_catalog_control.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/LakebaseCdfStreamEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/LakebaseCdfStreamRepository.java`

- [x] **Step 1: Add migration**

Create `V45__lakebase_cdf_catalog_control.sql`:

```sql
CREATE TABLE IF NOT EXISTS lakebase_cdf_streams (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    database_id VARCHAR(64) NOT NULL,
    branch_id VARCHAR(128) NOT NULL,
    source_schema VARCHAR(128) NOT NULL,
    source_table VARCHAR(128) NOT NULL,
    target_namespace VARCHAR(128) NOT NULL,
    target_table VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'APPEND_CHANGELOG',
    status VARCHAR(32) NOT NULL DEFAULT 'PAUSED',
    slot_name VARCHAR(128) NOT NULL,
    publication_name VARCHAR(128) NOT NULL,
    export_status VARCHAR(32) NOT NULL DEFAULT 'NOT_MATERIALIZED',
    backfill_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    backfill_lsn VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lakebase_cdf_streams_tenant_db
    ON lakebase_cdf_streams(tenant_id, database_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lakebase_cdf_stream_target
    ON lakebase_cdf_streams(tenant_id, database_id, branch_id, target_namespace, target_table);
```

- [x] **Step 2: Add entity**

Create `LakebaseCdfStreamEntity.java` with fields matching the migration. Use existing entity style from `ImportTaskEntity`.

- [x] **Step 3: Add repository**

Create `LakebaseCdfStreamRepository.java`:

```java
package com.lakeon.repository;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LakebaseCdfStreamRepository extends JpaRepository<LakebaseCdfStreamEntity, String> {
    List<LakebaseCdfStreamEntity> findByTenantIdAndDatabaseId(String tenantId, String databaseId);
    Optional<LakebaseCdfStreamEntity> findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
            String tenantId, String databaseId, String branchId, String targetNamespace, String targetTable);
}
```

- [x] **Step 4: Compile**

Run:

```bash
cd lakeon-api
./mvnw -DskipTests compile
```

Expected: compile succeeds.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V45__lakebase_cdf_catalog_control.sql \
        lakeon-api/src/main/java/com/lakeon/model/entity/LakebaseCdfStreamEntity.java \
        lakeon-api/src/main/java/com/lakeon/repository/LakebaseCdfStreamRepository.java
git commit -m "feat(cdf): add stream control-plane metadata"
```

---

## Milestone 2: REST Catalog Load And Commit Skeleton

### Task 3: Implement Minimal Iceberg Metadata JSON

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergMetadataJson.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergMetadataJsonTest.java`

- [x] **Step 1: Write failing test**

Test must assert generated JSON contains standard fields:

```java
assertThat(json.get("format-version").asInt()).isEqualTo(2);
assertThat(json.get("table-uuid").asText()).isEqualTo(tableId);
assertThat(json.get("location").asText()).startsWith("obs://");
assertThat(json.get("refs").get("main").get("type").asText()).isEqualTo("branch");
```

- [x] **Step 2: Implement builder**

Implement a static method:

```java
public static JsonNode initialMetadata(ObjectMapper mapper, String tableId, String location, JsonNode schema)
```

It must produce format-version 2 metadata with:

- table uuid
- location
- schemas
- current-schema-id
- partition-specs as empty unpartitioned spec
- sort-orders as unsorted
- snapshots empty
- refs containing `main` branch with snapshot id omitted until first commit
- properties containing `lakeon.managed=true`

- [x] **Step 3: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergMetadataJsonTest test
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergMetadataJson.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergMetadataJsonTest.java
git commit -m "feat(iceberg): build managed table metadata json"
```

### Task 4: Implement Commit Requirement Validation

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCommitValidator.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCommitValidatorTest.java`

- [x] **Step 1: Write tests**

Cover:

- create succeeds only when current metadata hash is absent
- update succeeds only when requirement metadata hash equals current hash
- stale update throws `ConflictException`
- unsupported requirement throws `BadRequestException`

- [x] **Step 2: Implement validator**

Implement:

```java
public void validateCreate(List<Map<String, Object>> requirements, String currentHash)
public void validateUpdate(List<Map<String, Object>> requirements, String currentHash)
```

Support these requirement actions first:

- `assert-create`
- `assert-current-schema-id`
- `assert-ref-snapshot-id`
- `assert-last-assigned-field-id`

For MVP, unknown requirement actions fail closed with `BadRequestException`.

- [x] **Step 3: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergCommitValidatorTest test
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCommitValidator.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCommitValidatorTest.java
git commit -m "feat(iceberg): validate REST catalog commit requirements"
```

### Task 5: Add REST Catalog Config And Load Table Endpoints

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCatalogServiceTest.java`

- [x] **Step 1: Write service tests**

Tests:

- `config()` returns defaults with `warehouse`, `scan-planning-mode=server`, and REST endpoints.
- `loadTable()` returns `metadata-location`, `metadata`, and scoped config.
- missing table throws `NotFoundException`.

- [x] **Step 2: Implement controller paths**

Create endpoints under:

```text
GET  /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/config
GET  /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/namespaces/{namespace}/tables/{table}
POST /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/namespaces/{namespace}/tables/{table}
```

Use `HttpServletRequest` tenant attribute, matching existing controllers.

- [x] **Step 3: Implement service load path**

Service opens user Lakebase connection through a database/branch-aware connection manager. If a branch-aware connection helper does not exist, create an interface:

```java
public interface LakebaseBranchConnectionProvider {
    Connection open(TenantEntity tenant, String databaseId, String branchId) throws SQLException;
}
```

The first implementation can route to the default branch and reject non-default branches with `BadRequestException` until branch compute connection support lands.

- [x] **Step 4: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergCatalogServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogController.java \
        lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogService.java \
        lakeon-api/src/main/java/com/lakeon/iceberg/LakebaseBranchConnectionProvider.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCatalogServiceTest.java
git commit -m "feat(iceberg): add REST catalog load endpoints"
```

---

## Milestone 3: CDF Stream Lifecycle

### Task 6: Add CDF Stream API

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java`

- [x] **Step 1: Write tests**

Tests:

- creating a stream generates deterministic names:
  - `lakeon_cdf_pub_<shortId>`
  - `lakeon_cdf_slot_<shortId>`
- duplicate target namespace/table is rejected.
- mode defaults to `APPEND_CHANGELOG`.
- status defaults to `PAUSED`.
- backfill status defaults to `PENDING`.
- stream is not advertised as readable until backfill status is `SUCCEEDED`.

- [x] **Step 2: Implement request/response records**

Inside `LakebaseCdfController`, define request body:

```java
record CreateCdfStreamRequest(
    String database_id,
    String branch_id,
    String source_schema,
    String source_table,
    String target_namespace,
    String target_table,
    String mode,
    Boolean initial_backfill
) {}
```

Response must include stream id, source, target, mode, status, backfill status, backfill LSN, slot, publication, and export status.

- [x] **Step 3: Implement service create/list**

Create:

```text
POST /api/v1/databases/{databaseId}/cdf-streams
GET  /api/v1/databases/{databaseId}/cdf-streams
POST /api/v1/databases/{databaseId}/cdf-streams/{streamId}/resume
POST /api/v1/databases/{databaseId}/cdf-streams/{streamId}/pause
```

- [x] **Step 4: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=LakebaseCdfServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfController.java \
        lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java
git commit -m "feat(cdf): add stream lifecycle API"
```

### Task 7: Create Publication And Logical Slot Setup

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java`

- [x] **Step 1: Add SQL generator tests**

Assert:

```sql
CREATE PUBLICATION lakeon_cdf_pub_x FOR TABLE "public"."orders"
SELECT * FROM pg_create_logical_replication_slot('lakeon_cdf_slot_x', 'pgoutput')
```

Quote identifiers with a helper; reject identifiers containing null bytes or empty strings.

- [x] **Step 2: Implement setup SQL**

Add methods:

```java
List<String> setupSql(LakebaseCdfStreamEntity stream)
List<String> teardownSql(LakebaseCdfStreamEntity stream)
```

Teardown must drop publication and slot with `IF EXISTS` and must not fail if slot is already gone.

- [x] **Step 3: Execute setup on resume**

When stream resumes, open branch connection, ensure `_lakeon_iceberg` schema, then execute setup SQL in order.

- [x] **Step 4: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=LakebaseCdfServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java
git commit -m "feat(cdf): configure logical decoding stream"
```

---

## Milestone 4: Initial Backfill

### Task 8: Add Initial Backfill Snapshot

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseBackfillService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseBackfillServiceTest.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java`

- [x] **Step 1: Write failing tests**

Cover:

- creating a stream with `initial_backfill=true` marks backfill `PENDING`.
- `runBackfill()` reads source table rows into one or more `CdfBatch` objects.
- backfill commits snapshot id `1` before stream status becomes `RUNNING`.
- backfill records `backfill_lsn` and initializes `_lakeon_iceberg.cdf_offsets` to that LSN.
- repeated `runBackfill()` for a succeeded stream is idempotent and does not create another initial snapshot.
- backfill failure marks stream `BACKFILL_FAILED` and keeps catalog load unavailable.

- [x] **Step 2: Implement backfill service API**

Create `LakebaseBackfillService`:

```java
package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import java.sql.Connection;

public class LakebaseBackfillService {
    public BackfillResult runBackfill(Connection connection, LakebaseCdfStreamEntity stream) {
        String backfillLsn = captureCurrentWalLsn(connection);
        long snapshotId = 1L;
        long rowCount = scanSourceInBatches(connection, stream, backfillLsn, snapshotId);
        markBackfillSucceeded(connection, stream, backfillLsn, snapshotId);
        return new BackfillResult("SUCCEEDED", backfillLsn, snapshotId, rowCount);
    }

    public record BackfillResult(String status, String backfillLsn, long snapshotId, long rowCount) {}
}
```

Implement private helpers in the same task:

- `captureCurrentWalLsn(Connection connection)` runs `SELECT pg_current_wal_lsn()` and returns the first column as a string.
- `scanSourceInBatches(Connection connection, LakebaseCdfStreamEntity stream, String backfillLsn, long snapshotId)` reads source rows in stable order, converts each page to a `CdfBatch`, calls `LakebaseCdfWorker.commitBatch(CdfBatch batch)`, and returns the total row count.
- `markBackfillSucceeded(Connection connection, LakebaseCdfStreamEntity stream, String backfillLsn, long snapshotId)` updates the control-plane stream row with `backfill_status='SUCCEEDED'`, `backfill_lsn`, and status `RUNNING`.

- [x] **Step 3: Capture a consistent LSN boundary**

Inside the same branch connection used for backfill, capture an LSN before the table scan:

```sql
SELECT pg_current_wal_lsn()
```

Use that LSN as `backfill_lsn`. The logical decoding stream must start from this boundary or skip changes at or below this boundary when applying incremental batches.

- [x] **Step 4: Scan source table deterministically**

Generate source scan SQL:

```sql
SELECT * FROM "source_schema"."source_table" ORDER BY <primary-key-columns>
```

Requirements:

- For `CURRENT_STATE`, reject tables without a primary key.
- For `APPEND_CHANGELOG`, allow tables without primary key but order by all columns only in tests; production should prefer primary key when present.
- Use pages/batches so large tables do not load fully into memory.

- [x] **Step 5: Commit initial snapshot**

Reuse `LakebaseCdfWorker.commitBatch(CdfBatch batch)` with:

```java
new CdfBatch(stream.getId(), stream.getBranchId(), backfillLsn, backfillLsn, "backfill", rows)
```

The first snapshot must:

- insert `_lakeon_iceberg.tables` if missing
- insert `_lakeon_iceberg.snapshots(snapshot_id = 1, operation = 'backfill')`
- insert `_lakeon_iceberg.data_files`
- set `_lakeon_iceberg.cdf_offsets.last_commit_lsn = backfillLsn`
- set stream `backfill_status = SUCCEEDED`

- [x] **Step 6: Wire resume flow**

When `resume` is called:

1. Ensure tenant schema.
2. Create publication and slot.
3. Run initial backfill if `backfill_status != SUCCEEDED`.
4. Set stream status `RUNNING` only after backfill succeeds.

- [x] **Step 7: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=LakebaseBackfillServiceTest,LakebaseCdfServiceTest test
```

Expected: tests pass.

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/cdf/LakebaseBackfillService.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseBackfillServiceTest.java \
        lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfService.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfServiceTest.java
git commit -m "feat(cdf): add initial backfill snapshot"
```

---

## Milestone 5: CDF Worker And Managed Commit

### Task 9: Add Batch Model And Idempotent Offset Logic

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/cdf/CdfBatch.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfWorkerTest.java`

- [x] **Step 1: Write tests**

Cover:

- empty batch does not advance snapshot.
- batch with LSN older than committed offset is ignored.
- batch with new LSN creates exactly one snapshot id.

- [x] **Step 2: Implement `CdfBatch`**

Use immutable record:

```java
public record CdfBatch(
    String streamId,
    String branchId,
    String startLsn,
    String endLsn,
    String txid,
    List<Map<String, Object>> rows
) {}
```

- [x] **Step 3: Implement worker offset checks**

Create `LakebaseCdfWorker` with a method:

```java
public CommitResult commitBatch(Connection connection, LakebaseCdfStreamEntity stream, CdfBatch batch)
```

It must read `_lakeon_iceberg.cdf_offsets` first and return `SKIPPED_OLD_LSN` when `endLsn` is already applied.

- [x] **Step 4: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=LakebaseCdfWorkerTest test
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/cdf/CdfBatch.java \
        lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfWorker.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfWorkerTest.java
git commit -m "feat(cdf): add idempotent batch commit skeleton"
```

### Task 10: Write Parquet Data Files Behind An Interface

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/cdf/CdfParquetWriter.java`
- Create: `lakeon-api/src/test/java/com/lakeon/cdf/CdfParquetWriterTest.java`

- [x] **Step 1: Define writer output**

Create:

```java
public record WrittenDataFile(
    String path,
    long recordCount,
    long fileSizeBytes,
    Map<String, Object> partition,
    Map<String, Object> lowerBounds,
    Map<String, Object> upperBounds
) {}
```

- [x] **Step 2: Add Parquet dependencies**

Add Apache Parquet dependencies to `lakeon-api/pom.xml`:

```xml
<dependency>
  <groupId>org.apache.parquet</groupId>
  <artifactId>parquet-avro</artifactId>
  <version>1.15.2</version>
</dependency>
<dependency>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro</artifactId>
  <version>1.12.0</version>
</dependency>
```

- [x] **Step 3: Implement real Parquet writer**

The interface must remain:

```java
List<WrittenDataFile> write(String warehousePrefix, String tableId, long snapshotId, CdfBatch batch)
```

Implementation requirements:

- infer an Avro schema from the first batch using stable field ordering
- write a `.parquet` object under `data/<tableId>/<snapshotId>/`
- return actual object path, record count, and byte size
- compute lower/upper bounds for primitive comparable fields present in the batch
- reject an empty batch before calling writer

- [x] **Step 4: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=CdfParquetWriterTest test
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/pom.xml \
        lakeon-api/src/main/java/com/lakeon/cdf/CdfParquetWriter.java \
        lakeon-api/src/test/java/com/lakeon/cdf/CdfParquetWriterTest.java
git commit -m "feat(cdf): write managed lakehouse data files"
```

### Task 11: Commit Snapshot, Planning Index, And Offset Atomically

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfWorker.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfWorkerTest.java`

- [x] **Step 1: Add tests**

Assert a commit transaction writes:

- `_lakeon_iceberg.snapshots`
- `_lakeon_iceberg.data_files`
- `_lakeon_iceberg.cdf_offsets`
- `_lakeon_iceberg.tables.current_snapshot_id`
- `_lakeon_iceberg.tables.export_status = 'NOT_MATERIALIZED'`

- [x] **Step 2: Implement transaction**

Use a single JDBC transaction:

```java
connection.setAutoCommit(false);
try {
    // read current metadata hash
    // insert snapshot
    // insert data file rows
    // upsert cdf offset
    // update table row by current hash
    connection.commit();
} catch (Exception e) {
    connection.rollback();
    throw e;
}
```

- [x] **Step 3: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=LakebaseCdfWorkerTest test
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/cdf/LakebaseCdfWorker.java \
        lakeon-api/src/test/java/com/lakeon/cdf/LakebaseCdfWorkerTest.java
git commit -m "feat(cdf): commit snapshot and offset atomically"
```

---

## Milestone 6: Server-side Scan Planning

### Task 12: Implement Planning Service

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergPlanningService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergPlanningServiceTest.java`

- [x] **Step 1: Write tests**

Tests:

- exact partition equality prunes unrelated files.
- snapshot id limits files to that snapshot and ancestors.
- selected columns are preserved in response.
- unsupported filter returns a full table plan, not wrong pruning.

- [x] **Step 2: Implement request model**

Accept REST Catalog request JSON fields:

- `select`
- `filter`
- `case-sensitive`
- `snapshot-id`
- `stats-fields`

- [x] **Step 3: Implement SQL planning**

Initial pruning:

```sql
SELECT file_path, record_count, file_size_bytes, partition_json, lower_bounds_json, upper_bounds_json
FROM _lakeon_iceberg.data_files
WHERE table_id = ?
  AND branch_id = ?
  AND snapshot_id <= ?
ORDER BY file_path
LIMIT ?
```

Apply safe partition equality in SQL only when the expression is unambiguous. Otherwise return full snapshot file set.

- [x] **Step 4: Return REST planning response**

Return JSON compatible with Iceberg REST `PlanTableScanResponse` shape:

```json
{
  "plan-id": "sync-20260624-0001",
  "plan-status": "COMPLETED",
  "file-scan-tasks": []
}
```

- [x] **Step 5: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergPlanningServiceTest test
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergPlanningService.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergPlanningServiceTest.java
git commit -m "feat(iceberg): plan scans from Lakebase index"
```

### Task 13: Wire REST Scan Planning Endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCatalogServiceTest.java`

- [x] **Step 1: Add endpoints**

```text
POST /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/namespaces/{namespace}/tables/{table}/plan
GET  /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/namespaces/{namespace}/tables/{table}/plan/{planId}
POST /api/v1/iceberg/catalog/{databaseId}/{branchId}/v1/namespaces/{namespace}/tables/{table}/tasks
```

- [x] **Step 2: Advertise server mode**

`LoadTableResponse.config` must include:

```json
{
  "scan-planning-mode": "server"
}
```

- [x] **Step 3: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergCatalogServiceTest,IcebergPlanningServiceTest test
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogController.java \
        lakeon-api/src/main/java/com/lakeon/iceberg/IcebergCatalogService.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergCatalogServiceTest.java
git commit -m "feat(iceberg): expose REST server-side scan planning"
```

---

## Milestone 7: Lazy Iceberg Export

### Task 14: Materialize Standard Iceberg Metadata And Manifests

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/iceberg/IcebergExportMaterializer.java`
- Create: `lakeon-api/src/test/java/com/lakeon/iceberg/IcebergExportMaterializerTest.java`

- [x] **Step 1: Write tests**

Tests:

- materializer reads table metadata and data file rows.
- writes metadata path under `export/metadata/`.
- updates `export_status` to `MATERIALIZED`.
- does not change current snapshot id.

- [x] **Step 2: Add Iceberg serialization dependency**

Add Apache Iceberg core dependency to `lakeon-api/pom.xml`:

```xml
<dependency>
  <groupId>org.apache.iceberg</groupId>
  <artifactId>iceberg-core</artifactId>
  <version>1.9.2</version>
</dependency>
```

- [x] **Step 3: Implement materializer**

Build standard Iceberg metadata JSON and manifest files from Lakebase rows. The materializer must:

- read `_lakeon_iceberg.tables`, `snapshots`, `data_files`, and `delete_files`
- build Iceberg `DataFile` entries using OBS paths, record counts, file sizes, partition values, and bounds
- write manifest Avro files under `export/metadata/`
- write manifest-list and metadata JSON under `export/metadata/`
- update `export_status` to `MATERIALIZED`
- store the latest exported metadata location in `_lakeon_iceberg.tables.current_metadata_location`
- leave `current_snapshot_id` unchanged

- [x] **Step 4: Add export API**

Add:

```text
POST /api/v1/databases/{databaseId}/cdf-streams/{streamId}/export
GET  /api/v1/databases/{databaseId}/cdf-streams/{streamId}/export
```

- [x] **Step 5: Run tests**

```bash
cd lakeon-api
./mvnw -Dtest=IcebergExportMaterializerTest test
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/pom.xml \
        lakeon-api/src/main/java/com/lakeon/iceberg/IcebergExportMaterializer.java \
        lakeon-api/src/test/java/com/lakeon/iceberg/IcebergExportMaterializerTest.java
git commit -m "feat(iceberg): add lazy manifest export"
```

---

## Milestone 8: End-to-end Verification

### Task 15: Add API E2E For CDF To Managed Table

**Files:**
- Create: `tests/e2e/test_lakebase_cdf_iceberg.py`

- [x] **Step 1: Write E2E scenario**

Scenario:

1. Create temporary tenant.
2. Create Lakebase database.
3. Create `public.orders(id text primary key, region text, amount numeric, status text)`.
4. Create CDF stream targeting `public.orders_cdf`.
5. Resume stream.
6. Poll stream until initial backfill status is `SUCCEEDED`.
7. Call Lakeon REST Catalog load table and verify initial rows are visible through planning.
8. Insert/update/delete source rows.
9. Poll stream status until a new incremental snapshot is visible.
10. Call plan endpoint with filter.
11. Request lazy export.
12. Verify export status is materialized.
13. Clean up tenant/database/stream.

- [ ] **Step 2: Run E2E**

Run:

```bash
python3 -m pytest tests/e2e/test_lakebase_cdf_iceberg.py -v
```

Expected: all tests pass.

Current status:

```bash
python3 -m py_compile tests/e2e/test_lakebase_cdf_iceberg.py
python3 -m pytest tests/e2e/test_lakebase_cdf_iceberg.py -v -s
```

Result: E2E scenario is implemented and syntax-valid, but it has not reached the CDF/Iceberg flow on the live API. The latest diagnostic run failed while creating the temporary Lakebase database: after 90 seconds the database was still `CREATING`, with status message `正在启动计算节点（如需扩容节点可能需要1~2分钟）...` and no `connection_uri`. A longer prior run also showed `POST /databases/{id}/resume` read timeouts before reaching CDF endpoints.

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_lakebase_cdf_iceberg.py
git commit -m "test(e2e): cover Lakebase CDF managed table flow"
```

### Task 16: Full Backend Verification

**Files:**
- No new files.

- [x] **Step 1: Run focused test suite**

```bash
cd lakeon-api
./mvnw -Dtest='Iceberg*Test,LakebaseCdf*Test' test
```

Expected: all focused tests pass.

Verified:

```bash
cd lakeon-api
mvn -Dtest='com.lakeon.iceberg.*Test,com.lakeon.cdf.*Test' test
```

Result: passed 85 focused CDF/Iceberg tests.

- [x] **Step 2: Run full backend tests**

```bash
cd lakeon-api
./mvnw test
```

Expected: all backend tests pass.

Verified:

```bash
cd lakeon-api
mvn test
```

Result: passed 418 backend tests, 0 failures, 0 errors, 0 skipped.

- [ ] **Step 3: Run API E2E**

```bash
python3 -m pytest tests/e2e -v
```

Expected: all E2E tests pass. If any existing unrelated test fails, record exact failing test and reason before continuing.

Current status: not complete. The focused CDF/Iceberg E2E is blocked before CDF setup by live database create/resume latency, so console work remains gated.

- [ ] **Step 4: Commit verification fixes**

Commit any fixes needed to make the above pass:

```bash
git status --short
git add <fixed files>
git commit -m "fix(cdf): stabilize managed lakehouse verification"
```

---

## Milestone 9: Console/Admin Surface

### Task 17: Add Minimal Console Status Page

**Files:**
- Create: `lakeon-console/src/api/cdf.ts`
- Create: `lakeon-console/src/views/cdf/LakebaseCdfList.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [x] **Step 1: Add API wrapper**

Implement list/create/pause/resume/export status methods against `/api/v1/databases/{databaseId}/cdf-streams`.

- [x] **Step 2: Add view**

View columns:

- source table
- target table
- mode
- status
- backfill status
- backfill LSN
- last commit LSN
- last snapshot id
- export status
- observed lag

Actions:

- pause
- resume
- materialize export

- [x] **Step 3: Run console typecheck**

```bash
cd lakeon-console
npx vue-tsc -b --noEmit
```

Expected: typecheck passes.

Verified:

```bash
cd lakeon-console
npm run test -- cdf-api ConsoleLayout
npx vue-tsc -b --noEmit
```

Result: CDF API/nav tests passed; console typecheck passed.

Additional verification:

```bash
cd lakeon-console
npm run test
npm run build
npx playwright test e2e/cdf.spec.ts --reporter=line
```

Result: 92 console unit tests passed; production build passed; CDF Playwright E2E passed.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/cdf.ts \
        lakeon-console/src/views/cdf/LakebaseCdfList.vue \
        lakeon-console/src/router/index.ts
git commit -m "feat(console): show Lakebase CDF streams"
```

---

## Self-review Checklist

- Spec coverage: schema, CDF lifecycle, REST Catalog, commit validation, planning index, lazy export, dbay-agent boundary, tests, and rollout are covered by tasks.
- Deferred-work scan: no task is allowed to stop at an unresolved future replacement; any intentionally staged item is represented as a later milestone with a concrete test.
- Type consistency: use `LakebaseCdfStreamEntity`, `IcebergCatalogService`, `IcebergPlanningService`, and `IcebergTenantSchemaManager` consistently.
- Execution boundary: do not start console work until backend E2E is passing.
