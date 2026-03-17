# Version & Branch Management Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add version snapshots, branch promote/restore, schema diff, and data diff to Lakeon's database platform.

**Architecture:** Branch-centric model where each branch has independent version history. Versions are LSN bookmarks materialized as Neon snapshot timelines. Promote swaps the default branch. Restore creates a new timeline from a target LSN. Diff connects two computes and compares pg_catalog/data.

**Tech Stack:** Spring Boot 3.3.5, Java 17, JPA/Hibernate, Neon pageserver API, Vue 3 + TypeScript, Axios

**Spec:** `docs/superpowers/specs/2026-03-17-version-branch-management-design.md`

**Deferred P1 items (not in this plan):**
- Data Diff (P1) — requires temp compute for version-based queries
- AI Diff Summary (P1) — depends on Data Diff
- at_timestamp version creation (P1) — requires timestamp-to-LSN resolution via compute SQL
- Version-based diff target (P1) — requires temp compute lifecycle management

**Key implementation decisions:**
- LSN stored as `BIGINT` (parsed from Neon hex format) for correct comparison and range queries
- Controllers use `@ResponseStatus` + direct return (matching existing BranchController pattern)
- Promote/Restore use pessimistic locking (`SELECT FOR UPDATE` on DatabaseEntity)
- `created_by` derived from API key type (user vs agent)

---

## Chunk 1: Backend — VersionEntity + Version CRUD

### Task 1: Add branch_type to BranchEntity

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/model/entity/BranchEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/BranchType.java`

- [ ] **Step 1: Create BranchType enum**

```java
// lakeon-api/src/main/java/com/lakeon/model/enums/BranchType.java
package com.lakeon.model.enums;

public enum BranchType {
    USER,      // User-created branch
    BACKUP,    // System-created backup (from Restore/Promote)
    SNAPSHOT   // System-created for version snapshot timeline (not shown in UI branch list)
}
```

- [ ] **Step 2: Add branch_type field to BranchEntity**

Add to `BranchEntity.java` after the `isDefault` field:

```java
@Enumerated(EnumType.STRING)
@Column(name = "branch_type", nullable = false)
private BranchType branchType = BranchType.USER;
```

Add getter/setter. In `@PrePersist`, add:
```java
if (branchType == null) {
    branchType = BranchType.USER;
}
```

- [ ] **Step 3: Add branch_type to BranchResponse DTO**

Add to `BranchResponse.java`:
```java
@JsonProperty("branch_type")
private String branchType;
```
Add to Builder and constructor.

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/enums/BranchType.java \
  lakeon-api/src/main/java/com/lakeon/model/entity/BranchEntity.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/BranchResponse.java
git commit -m "feat: add branch_type enum to BranchEntity (USER/BACKUP)"
```

---

### Task 2: Create LsnUtil + VersionEntity + Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/util/LsnUtil.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/VersionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/VersionRepository.java`

- [ ] **Step 0: Create LsnUtil for hex LSN ↔ long conversion**

Neon LSN format is `segment/offset` hex (e.g., `0/1A2B3C0`). String comparison is lexicographically incorrect for hex values. Store as BIGINT for correct ordering and range queries.

```java
// lakeon-api/src/main/java/com/lakeon/util/LsnUtil.java
package com.lakeon.util;

public final class LsnUtil {
    private LsnUtil() {}

    /** Parse Neon LSN "segment/offset" hex string to long. E.g., "0/1A2B3C0" → 27542464 */
    public static long parse(String lsn) {
        if (lsn == null || lsn.isBlank()) throw new IllegalArgumentException("LSN is blank");
        String[] parts = lsn.split("/");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid LSN format: " + lsn);
        long segment = Long.parseUnsignedLong(parts[0], 16);
        long offset = Long.parseUnsignedLong(parts[1], 16);
        return (segment << 32) | offset;
    }

    /** Convert long back to Neon LSN hex string. E.g., 27542464 → "0/1A2B3C0" */
    public static String format(long lsn) {
        long segment = (lsn >>> 32) & 0xFFFFFFFFL;
        long offset = lsn & 0xFFFFFFFFL;
        return String.format("%X/%X", segment, offset);
    }
}
```

- [ ] **Step 1: Create VersionEntity**

```java
// lakeon-api/src/main/java/com/lakeon/model/entity/VersionEntity.java
package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "name"}))
public class VersionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "lsn", nullable = false)
    private long lsn;  // Stored as BIGINT, parsed from Neon hex LSN via LsnUtil

    @Column(name = "lsn_hex", nullable = false, length = 32)
    private String lsnHex;  // Original hex string for display (e.g., "0/1A2B3C0")

    @Column(name = "snapshot_timeline_id", length = 64)
    private String snapshotTimelineId;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "ver_" + UUID.randomUUID().toString().substring(0, 8);
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

    // Getters and setters for all fields
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getLsn() { return lsn; }
    public void setLsn(long lsn) { this.lsn = lsn; }
    public String getLsnHex() { return lsnHex; }
    public void setLsnHex(String lsnHex) { this.lsnHex = lsnHex; }
    public String getSnapshotTimelineId() { return snapshotTimelineId; }
    public void setSnapshotTimelineId(String snapshotTimelineId) { this.snapshotTimelineId = snapshotTimelineId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: Create VersionRepository**

```java
// lakeon-api/src/main/java/com/lakeon/repository/VersionRepository.java
package com.lakeon.repository;

import com.lakeon.model.entity.VersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VersionRepository extends JpaRepository<VersionEntity, String> {
    List<VersionEntity> findAllByBranchIdOrderByLsnAsc(String branchId);
    Optional<VersionEntity> findByIdAndBranchId(String id, String branchId);
    Optional<VersionEntity> findByBranchIdAndName(String branchId, String name);
    // BIGINT comparison — correct numeric ordering for LSN values
    List<VersionEntity> findAllByBranchIdAndLsnBetweenOrderByLsnAsc(String branchId, long fromLsn, long toLsn);
    void deleteAllByBranchId(String branchId);
    long countByBranchId(String branchId);
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/entity/VersionEntity.java \
  lakeon-api/src/main/java/com/lakeon/repository/VersionRepository.java
git commit -m "feat: add VersionEntity and VersionRepository"
```

---

### Task 3: Create Version DTOs

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/VersionResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/CreateVersionRequest.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/SquashVersionsRequest.java`

- [ ] **Step 1: Create CreateVersionRequest**

```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateVersionRequest(
    @NotBlank String name,
    String description,
    // "current" (default) or omit for current LSN
    String at,
    // Explicit LSN, used when at is omitted or null
    @JsonProperty("at_lsn") String atLsn
) {}
```

- [ ] **Step 2: Create VersionResponse**

Follow the `BranchResponse` builder pattern. Fields:
- `id`, `branch_id`, `name`, `description`, `lsn` (hex string for display, from `lsnHex`), `snapshot_timeline_id`, `created_by`, `created_at`

Use `@JsonProperty` for snake_case. Include Builder inner class. The `lsn` field in the response is always the hex string (e.g., `"0/1A2B3C0"`) for human readability.

- [ ] **Step 3: Create SquashVersionsRequest**

```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record SquashVersionsRequest(
    @NotBlank @JsonProperty("from_version_id") String fromVersionId,
    @NotBlank @JsonProperty("to_version_id") String toVersionId
) {}
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/CreateVersionRequest.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/VersionResponse.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/SquashVersionsRequest.java
git commit -m "feat: add Version DTOs (request/response/squash)"
```

---

### Task 4: Implement VersionService

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/VersionService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/service/VersionServiceTest.java`

- [ ] **Step 1: Write VersionService tests**

Test class with `@ExtendWith(MockitoExtension.class)`. Test cases:
1. `create_currentLsn_createsSnapshotTimeline` — mocks NeonApiClient.getTimeline() to return lastRecordLsn, creates timeline, saves entity
2. `create_explicitLsn_usesProvidedLsn` — uses at_lsn from request
3. `create_duplicateName_throwsConflict` — findByBranchIdAndName returns existing
4. `list_returnsVersionsOrderedByLsn`
5. `get_returnsVersion`
6. `get_notFound_throwsNotFound`
7. `delete_deletesEntityAndTimeline`
8. `squash_deletesMiddleVersions` — from v1 to v3, v2 gets deleted

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -pl . -Dtest=VersionServiceTest -q`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement VersionService**

```java
package com.lakeon.service;

@Service
@Transactional
public class VersionService {

    private final VersionRepository versionRepository;
    private final BranchRepository branchRepository;
    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;

    // Constructor injection

    public VersionResponse create(TenantEntity tenant, String dbId, String branchId,
                                   CreateVersionRequest request) {
        // 1. Validate database belongs to tenant
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found"));
        // 2. Validate branch belongs to database
        BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found"));
        // 3. Check duplicate name
        versionRepository.findByBranchIdAndName(branchId, request.name())
            .ifPresent(v -> { throw new ConflictException("Version name already exists"); });
        // 4. Resolve LSN
        String lsnHex;
        if (request.atLsn() != null && !request.atLsn().isBlank()) {
            lsnHex = request.atLsn();
        } else {
            NeonTimeline timeline = neonApiClient.getTimeline(db.getNeonTenantId(), branch.getNeonTimelineId());
            lsnHex = timeline.getLastRecordLsn();
        }
        long lsn = LsnUtil.parse(lsnHex);
        // 5. Create snapshot timeline on pageserver
        String snapshotTimelineId = generateHexId();
        neonApiClient.createTimeline(db.getNeonTenantId(),
            CreateTimelineRequest.forBranchAtLsn(snapshotTimelineId, branch.getNeonTimelineId(), lsnHex));
        // 6. Save entity
        VersionEntity entity = new VersionEntity();
        entity.setBranchId(branchId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setLsn(lsn);
        entity.setLsnHex(lsnHex);
        entity.setSnapshotTimelineId(snapshotTimelineId);
        // Derive created_by from tenant's API key type
        entity.setCreatedBy(tenant.getApiKeyType() != null ? tenant.getApiKeyType() : "user");
        versionRepository.save(entity);
        // 7. Return response
        return toResponse(entity);
    }

    public List<VersionResponse> list(TenantEntity tenant, String dbId, String branchId) {
        // Validate ownership chain
        validateBranchAccess(tenant, dbId, branchId);
        return versionRepository.findAllByBranchIdOrderByLsnAsc(branchId)
            .stream().map(this::toResponse).toList();
    }

    public VersionResponse get(TenantEntity tenant, String dbId, String branchId, String versionId) {
        validateBranchAccess(tenant, dbId, branchId);
        VersionEntity entity = versionRepository.findByIdAndBranchId(versionId, branchId)
            .orElseThrow(() -> new NotFoundException("Version not found"));
        return toResponse(entity);
    }

    public void delete(TenantEntity tenant, String dbId, String branchId, String versionId) {
        DatabaseEntity db = validateBranchAccess(tenant, dbId, branchId);
        VersionEntity entity = versionRepository.findByIdAndBranchId(versionId, branchId)
            .orElseThrow(() -> new NotFoundException("Version not found"));
        // Delete snapshot timeline from pageserver
        neonApiClient.deleteTimeline(db.getNeonTenantId(), entity.getSnapshotTimelineId());
        versionRepository.delete(entity);
    }

    public List<VersionResponse> squash(TenantEntity tenant, String dbId, String branchId,
                                         SquashVersionsRequest request) {
        DatabaseEntity db = validateBranchAccess(tenant, dbId, branchId);
        VersionEntity fromVer = versionRepository.findByIdAndBranchId(request.fromVersionId(), branchId)
            .orElseThrow(() -> new NotFoundException("From version not found"));
        VersionEntity toVer = versionRepository.findByIdAndBranchId(request.toVersionId(), branchId)
            .orElseThrow(() -> new NotFoundException("To version not found"));
        // Get versions in range (exclusive of from and to)
        List<VersionEntity> allInRange = versionRepository
            .findAllByBranchIdAndLsnBetweenOrderByLsnAsc(branchId, fromVer.getLsn(), toVer.getLsn());
        List<VersionEntity> middle = allInRange.stream()
            .filter(v -> !v.getId().equals(fromVer.getId()) && !v.getId().equals(toVer.getId()))
            .toList();
        // Check no snapshot timeline has children (pageserver would reject delete)
        // For now, attempt delete and handle 409 from pageserver
        for (VersionEntity v : middle) {
            neonApiClient.deleteTimeline(db.getNeonTenantId(), v.getSnapshotTimelineId());
            versionRepository.delete(v);
        }
        return list(tenant, dbId, branchId);
    }

    // Helper: validate tenant -> db -> branch chain, return DatabaseEntity
    private DatabaseEntity validateBranchAccess(TenantEntity tenant, String dbId, String branchId) { ... }
    private VersionResponse toResponse(VersionEntity entity) { ... }
    private String generateHexId() { /* same as BranchService */ }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd lakeon-api && mvn test -pl . -Dtest=VersionServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/VersionService.java \
  lakeon-api/src/test/java/com/lakeon/service/VersionServiceTest.java
git commit -m "feat: implement VersionService with CRUD and squash"
```

---

### Task 5: Implement VersionController

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/VersionController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/controller/VersionControllerTest.java`

- [ ] **Step 1: Write VersionController tests**

`@WebMvcTest(VersionController.class)` with `@MockBean VersionService`. Test:
1. `POST /databases/{dbId}/branches/{branchId}/versions` → 201
2. `GET /databases/{dbId}/branches/{branchId}/versions` → 200 list
3. `GET /databases/{dbId}/branches/{branchId}/versions/{versionId}` → 200
4. `DELETE /databases/{dbId}/branches/{branchId}/versions/{versionId}` → 204
5. `POST /databases/{dbId}/branches/{branchId}/versions/squash` → 200
6. Missing auth → 401

Follow `BranchControllerTest` patterns: mock ApiKeyFilter, use `mockMvc.perform()`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -pl . -Dtest=VersionControllerTest -q`

- [ ] **Step 3: Implement VersionController**

```java
@RestController
@RequestMapping("/api/v1/databases/{dbId}/branches/{branchId}/versions")
public class VersionController {

    private final VersionService versionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @Valid @RequestBody CreateVersionRequest request) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.create(tenant, dbId, branchId, request);
    }

    @GetMapping
    public List<VersionResponse> list(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.list(tenant, dbId, branchId);
    }

    @GetMapping("/{versionId}")
    public VersionResponse get(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @PathVariable String versionId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.get(tenant, dbId, branchId, versionId);
    }

    @DeleteMapping("/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @PathVariable String versionId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        versionService.delete(tenant, dbId, branchId, versionId);
    }

    @PostMapping("/squash")
    public List<VersionResponse> squash(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @PathVariable String branchId,
            @Valid @RequestBody SquashVersionsRequest request) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return versionService.squash(tenant, dbId, branchId, request);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd lakeon-api && mvn test -pl . -Dtest=VersionControllerTest -q`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/VersionController.java \
  lakeon-api/src/test/java/com/lakeon/controller/VersionControllerTest.java
git commit -m "feat: add VersionController REST endpoints"
```

---

## Chunk 2: Backend — Promote + Restore

### Task 6: Implement Promote

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/BranchService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/BranchController.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/service/BranchServiceTest.java`

- [ ] **Step 1: Write promote test in BranchServiceTest**

Test `promote_swapsDefaultBranch`:
- Setup: main branch (is_default=true), feature branch (is_default=false)
- Call promote(tenant, dbId, featureBranchId)
- Verify: old main → is_default=false, name renamed, branch_type=BACKUP
- Verify: feature branch → is_default=true
- Verify: database.neonTimelineId updated to feature branch's timeline
- Verify: computePodManager.deleteComputePod() and createComputePod() called

Test `promote_defaultBranch_throwsBadRequest`:
- Cannot promote the branch that's already default

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -pl . -Dtest=BranchServiceTest#promote* -q`

- [ ] **Step 3: Add pessimistic lock method to DatabaseRepository**

```java
// Add to DatabaseRepository.java:
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM DatabaseEntity d WHERE d.id = :id AND d.tenantId = :tenantId")
Optional<DatabaseEntity> findByIdAndTenantIdForUpdate(@Param("id") String id, @Param("tenantId") String tenantId);
```

- [ ] **Step 4: Implement promote in BranchService**

```java
public BranchResponse promote(TenantEntity tenant, String dbId, String branchId) {
    // Pessimistic lock prevents concurrent Promote/Restore
    DatabaseEntity db = databaseRepository.findByIdAndTenantIdForUpdate(dbId, tenant.getId())
        .orElseThrow(() -> new NotFoundException("Database not found"));
    BranchEntity target = branchRepository.findByIdAndDatabaseId(branchId, dbId)
        .orElseThrow(() -> new NotFoundException("Branch not found"));

    if (target.getIsDefault()) {
        throw new BadRequestException("Branch is already the default");
    }

    // Find current default
    BranchEntity currentDefault = branchRepository.findByDatabaseIdAndIsDefaultTrue(dbId)
        .orElseThrow(() -> new IllegalStateException("No default branch found"));

    // Demote current default
    currentDefault.setIsDefault(false);
    currentDefault.setName(currentDefault.getName() + "-before-promote-" + Instant.now().getEpochSecond());
    currentDefault.setBranchType(BranchType.BACKUP);
    branchRepository.save(currentDefault);

    // Promote target
    target.setIsDefault(true);
    branchRepository.save(target);

    // Update database active timeline
    db.setNeonTimelineId(target.getNeonTimelineId());

    // Rebuild compute pod
    if (db.getComputePodName() != null) {
        computePodManager.deleteComputePod(db.getComputePodName(), true);
    }
    db.setComputePodName(null);
    db.setComputeHost(null);
    db.setComputePort(null);
    databaseRepository.save(db);
    computePodManager.createComputePod(db);
    databaseRepository.save(db);

    return toBranchResponse(target);
}
```

- [ ] **Step 5: Add promote endpoint to BranchController**

```java
@PostMapping("/{branchId}/promote")
@ResponseStatus(HttpStatus.OK)
public BranchResponse promote(
        HttpServletRequest httpRequest,
        @PathVariable String dbId,
        @PathVariable String branchId) {
    TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
    return branchService.promote(tenant, dbId, branchId);
}
```

- [ ] **Step 5: Run tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest=BranchServiceTest -q`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/BranchService.java \
  lakeon-api/src/main/java/com/lakeon/controller/BranchController.java \
  lakeon-api/src/test/java/com/lakeon/service/BranchServiceTest.java
git commit -m "feat: implement branch promote (swap default branch)"
```

---

### Task 7: Implement Restore

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/BranchService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/BranchController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/RestoreBranchRequest.java`

- [ ] **Step 1: Create RestoreBranchRequest DTO**

```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RestoreBranchRequest(
    @JsonProperty("target_version_id") String targetVersionId,
    @JsonProperty("target_lsn") String targetLsn
) {}
```

- [ ] **Step 2: Write restore tests**

Test `restore_byVersionId_createsBackupAndNewTimeline`:
- Setup: main branch with v1, v2, v3
- Call restore(tenant, dbId, branchId, {target_version_id: v1.id})
- Verify: backup branch created with old timeline, type=BACKUP
- Verify: new timeline created from v1's snapshot timeline
- Verify: main branch's neonTimelineId updated to new timeline
- Verify: v2, v3 moved to backup branch (branchId updated)
- Verify: compute pod rebuilt

Test `restore_byLsn_works`:
- Uses target_lsn instead of version id

- [ ] **Step 3: Implement restore in BranchService**

```java
public BranchResponse restore(TenantEntity tenant, String dbId, String branchId,
                               RestoreBranchRequest request) {
    // Pessimistic lock prevents concurrent Promote/Restore
    DatabaseEntity db = databaseRepository.findByIdAndTenantIdForUpdate(dbId, tenant.getId())
        .orElseThrow(() -> new NotFoundException("Database not found"));
    BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
        .orElseThrow(() -> new NotFoundException("Branch not found"));

    // Resolve target LSN
    String targetLsnHex;
    long targetLsn;
    String ancestorTimelineId;
    if (request.targetVersionId() != null) {
        VersionEntity targetVersion = versionRepository.findByIdAndBranchId(request.targetVersionId(), branchId)
            .orElseThrow(() -> new NotFoundException("Version not found"));
        targetLsn = targetVersion.getLsn();
        targetLsnHex = targetVersion.getLsnHex();
        ancestorTimelineId = targetVersion.getSnapshotTimelineId();
    } else if (request.targetLsn() != null) {
        targetLsnHex = request.targetLsn();
        targetLsn = LsnUtil.parse(targetLsnHex);
        ancestorTimelineId = branch.getNeonTimelineId();
    } else {
        throw new BadRequestException("Either target_version_id or target_lsn is required");
    }

    String oldTimelineId = branch.getNeonTimelineId();

    // 1. Create backup branch (keeps old timeline)
    BranchEntity backup = new BranchEntity();
    backup.setName(branch.getName() + "-backup-" + Instant.now().getEpochSecond());
    backup.setDatabaseId(dbId);
    backup.setNeonTimelineId(oldTimelineId);
    backup.setParentBranchId(branch.getParentBranchId());
    backup.setParentBranchName(branch.getParentBranchName());
    backup.setIsDefault(false);
    backup.setStatus(BranchStatus.ACTIVE);
    backup.setBranchType(BranchType.BACKUP);
    branchRepository.save(backup);

    // 2. Create new timeline from target point
    String newTimelineId = generateHexId();
    neonApiClient.createTimeline(db.getNeonTenantId(),
        CreateTimelineRequest.forBranchAtLsn(newTimelineId, ancestorTimelineId, targetLsnHex));

    // 3. Update branch to new timeline
    branch.setNeonTimelineId(newTimelineId);
    branchRepository.save(branch);

    // 4. Move versions after target to backup branch
    List<VersionEntity> allVersions = versionRepository.findAllByBranchIdOrderByLsnAsc(branchId);
    for (VersionEntity v : allVersions) {
        if (v.getLsn() > targetLsn) {
            v.setBranchId(backup.getId());
            versionRepository.save(v);
        }
    }

    // 5. Update database active timeline if this is default branch
    if (branch.getIsDefault()) {
        db.setNeonTimelineId(newTimelineId);
        if (db.getComputePodName() != null) {
            computePodManager.deleteComputePod(db.getComputePodName(), true);
        }
        db.setComputePodName(null);
        db.setComputeHost(null);
        db.setComputePort(null);
        databaseRepository.save(db);
        computePodManager.createComputePod(db);
        databaseRepository.save(db);
    }

    return toBranchResponse(branch);
}
```

- [ ] **Step 4: Add restore endpoint to BranchController**

```java
@PostMapping("/{branchId}/restore")
public BranchResponse restore(
        HttpServletRequest httpRequest,
        @PathVariable String dbId,
        @PathVariable String branchId,
        @Valid @RequestBody RestoreBranchRequest request) {
    TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
    return branchService.restore(tenant, dbId, branchId, request);
}
```

- [ ] **Step 5: Run all branch tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest=BranchServiceTest -q`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/BranchService.java \
  lakeon-api/src/main/java/com/lakeon/controller/BranchController.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/RestoreBranchRequest.java \
  lakeon-api/src/test/java/com/lakeon/service/BranchServiceTest.java
git commit -m "feat: implement branch restore with auto-backup"
```

---

## Chunk 3: Backend — Schema Diff

### Task 8: Implement DiffService (Schema Diff)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/DiffService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/SchemaDiffResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/SchemaDiffResponse.java` (nested classes for table/column/index diffs)
- Create: `lakeon-api/src/test/java/com/lakeon/service/DiffServiceTest.java`

- [ ] **Step 1: Create SchemaDiffResponse DTO**

```java
package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SchemaDiffResponse(
    @JsonProperty("tables") TableDiffs tables,
    @JsonProperty("indexes") IndexDiffs indexes
) {
    public record TableDiffs(
        List<TableInfo> added,
        List<TableInfo> removed,
        List<TableModification> modified
    ) {}

    public record TableInfo(
        String name,
        String schema,
        List<ColumnInfo> columns
    ) {}

    public record ColumnInfo(
        String name,
        @JsonProperty("data_type") String dataType,
        @JsonProperty("is_nullable") boolean isNullable,
        @JsonProperty("column_default") String columnDefault
    ) {}

    public record TableModification(
        String name,
        String schema,
        ColumnDiffs columns
    ) {}

    public record ColumnDiffs(
        List<ColumnInfo> added,
        List<ColumnInfo> removed,
        List<ColumnModification> modified
    ) {}

    public record ColumnModification(
        String name,
        @JsonProperty("old_type") String oldType,
        @JsonProperty("new_type") String newType,
        @JsonProperty("old_nullable") Boolean oldNullable,
        @JsonProperty("new_nullable") Boolean newNullable,
        @JsonProperty("old_default") String oldDefault,
        @JsonProperty("new_default") String newDefault
    ) {}

    public record IndexDiffs(
        List<IndexInfo> added,
        List<IndexInfo> removed
    ) {}

    public record IndexInfo(
        String name,
        @JsonProperty("table_name") String tableName,
        String definition
    ) {}
}
```

- [ ] **Step 2: Implement DiffService**

The service needs to:
1. Resolve source/target to a JDBC connection (branch → use compute, version → start temp compute)
2. Query `information_schema.tables`, `information_schema.columns`, `pg_indexes` on both sides
3. Compute the diff

For the initial implementation, only support diff between two branches (both have compute).
Version-based diff (requiring temp compute) will be P1.

```java
@Service
public class DiffService {

    private final BranchRepository branchRepository;
    private final VersionRepository versionRepository;
    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;

    // Resolve a diff target to a JDBC URL
    private String resolveJdbcUrl(DatabaseEntity db, String type, String id) {
        if ("branch".equals(type)) {
            BranchEntity branch = branchRepository.findByIdAndDatabaseId(id, db.getId())
                .orElseThrow(() -> new NotFoundException("Branch not found"));
            if (branch.getComputeHost() == null) {
                throw new BadRequestException("Branch has no running compute. Start compute first.");
            }
            return String.format("jdbc:postgresql://%s:%d/%s?user=%s&password=%s",
                branch.getComputeHost(), branch.getComputePort(),
                "neondb", db.getDbUser(), db.getDbPassword());
        }
        // "version" type — needs temp compute, throw for now
        throw new BadRequestException("Version-based diff requires temp compute (not yet implemented). Use branch diff.");
    }

    public SchemaDiffResponse schemaDiff(TenantEntity tenant, String dbId,
                                          String sourceType, String sourceId,
                                          String targetType, String targetId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found"));

        String sourceUrl = resolveJdbcUrl(db, sourceType, sourceId);
        String targetUrl = resolveJdbcUrl(db, targetType, targetId);

        Map<String, List<ColumnInfo>> sourceTables = querySchema(sourceUrl);
        Map<String, List<ColumnInfo>> targetTables = querySchema(targetUrl);
        List<IndexInfo> sourceIndexes = queryIndexes(sourceUrl);
        List<IndexInfo> targetIndexes = queryIndexes(targetUrl);

        // Compute diffs...
        return computeSchemaDiff(sourceTables, targetTables, sourceIndexes, targetIndexes);
    }

    private Map<String, List<ColumnInfo>> querySchema(String jdbcUrl) {
        // Connect via JDBC, query information_schema.columns
        // WHERE table_schema NOT IN ('pg_catalog', 'information_schema', '_lakeon', 'neon')
        // Group by table_name
    }

    private List<IndexInfo> queryIndexes(String jdbcUrl) {
        // Query pg_indexes WHERE schemaname = 'public'
    }
}
```

- [ ] **Step 3: Write unit tests for diff computation logic**

Test the pure diff computation function with mock schema data — no JDBC needed:
1. `schemaDiff_newTable_showsAsAdded`
2. `schemaDiff_removedTable_showsAsRemoved`
3. `schemaDiff_modifiedColumn_showsTypeChange`
4. `schemaDiff_newIndex_showsAsAdded`

- [ ] **Step 4: Run tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest=DiffServiceTest -q`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/DiffService.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/SchemaDiffResponse.java \
  lakeon-api/src/test/java/com/lakeon/service/DiffServiceTest.java
git commit -m "feat: implement schema diff service"
```

---

### Task 9: Implement DiffController

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/DiffController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/controller/DiffControllerTest.java`

- [ ] **Step 1: Write controller tests**

Test:
1. `GET /databases/{dbId}/diff/schema?source_type=branch&source_id=br_1&target_type=branch&target_id=br_2` → 200
2. Missing params → 400
3. No auth → 401

- [ ] **Step 2: Implement DiffController**

```java
@RestController
@RequestMapping("/api/v1/databases/{dbId}/diff")
public class DiffController {

    private final DiffService diffService;

    @GetMapping("/schema")
    public SchemaDiffResponse schemaDiff(
            HttpServletRequest httpRequest,
            @PathVariable String dbId,
            @RequestParam("source_type") String sourceType,
            @RequestParam("source_id") String sourceId,
            @RequestParam("target_type") String targetType,
            @RequestParam("target_id") String targetId) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return diffService.schemaDiff(tenant, dbId, sourceType, sourceId, targetType, targetId);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest=DiffControllerTest -q`

- [ ] **Step 4: Run full test suite**

Run: `cd lakeon-api && mvn test -q`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/DiffController.java \
  lakeon-api/src/test/java/com/lakeon/controller/DiffControllerTest.java
git commit -m "feat: add schema diff REST endpoint"
```

---

## Chunk 4: Frontend — Branch Tab + Version Timeline

### Task 10: Create Version API client

**Files:**
- Create: `lakeon-console/src/api/version.ts`

- [ ] **Step 1: Create version API client**

```typescript
// lakeon-console/src/api/version.ts
import client from './client'

export interface Version {
  id: string
  branch_id: string
  name: string
  description: string | null
  lsn: string
  snapshot_timeline_id: string
  created_by: string
  created_at: string
}

export interface CreateVersionParams {
  name: string
  description?: string
  at?: string
  at_lsn?: string
}

export interface SquashParams {
  from_version_id: string
  to_version_id: string
}

export const versionApi = {
  list: (dbId: string, branchId: string) =>
    client.get<Version[]>(`/databases/${dbId}/branches/${branchId}/versions`),
  create: (dbId: string, branchId: string, data: CreateVersionParams) =>
    client.post<Version>(`/databases/${dbId}/branches/${branchId}/versions`, data),
  get: (dbId: string, branchId: string, versionId: string) =>
    client.get<Version>(`/databases/${dbId}/branches/${branchId}/versions/${versionId}`),
  delete: (dbId: string, branchId: string, versionId: string) =>
    client.delete(`/databases/${dbId}/branches/${branchId}/versions/${versionId}`),
  squash: (dbId: string, branchId: string, data: SquashParams) =>
    client.post<Version[]>(`/databases/${dbId}/branches/${branchId}/versions/squash`, data),
}
```

- [ ] **Step 2: Add promote/restore to branch API**

In `lakeon-console/src/api/branch.ts`, add:
```typescript
export interface RestoreParams {
  target_version_id?: string
  target_lsn?: string
}

// Add to branchApi object:
promote: (dbId: string, branchId: string) =>
  client.post<Branch>(`/databases/${dbId}/branches/${branchId}/promote`),
restore: (dbId: string, branchId: string, data: RestoreParams) =>
  client.post<Branch>(`/databases/${dbId}/branches/${branchId}/restore`, data),
```

- [ ] **Step 3: Add diff API client**

Create `lakeon-console/src/api/diff.ts`:
```typescript
import client from './client'

export interface SchemaDiffResponse {
  tables: {
    added: TableInfo[]
    removed: TableInfo[]
    modified: TableModification[]
  }
  indexes: {
    added: IndexInfo[]
    removed: IndexInfo[]
  }
}
// ... full type definitions

export const diffApi = {
  schema: (dbId: string, sourceType: string, sourceId: string, targetType: string, targetId: string) =>
    client.get<SchemaDiffResponse>(`/databases/${dbId}/diff/schema`, {
      params: { source_type: sourceType, source_id: sourceId, target_type: targetType, target_id: targetId }
    }),
}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/version.ts lakeon-console/src/api/branch.ts lakeon-console/src/api/diff.ts
git commit -m "feat: add version, promote, restore, diff API clients"
```

---

### Task 11: Add Branch Tab to DatabaseDetail

**Files:**
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue`

- [ ] **Step 1: Add tab navigation to DatabaseDetail**

Add tabs to the existing DatabaseDetail page: **概览 | 分支 | 连接信息 | 设置**

The "分支" tab contains:
- Left panel: branch list (reuse existing BranchTreeView or a simpler list)
- Right panel: version timeline for selected branch

This is the largest frontend change. Follow the existing DatabaseDetail structure (Huawei Cloud style).

- [ ] **Step 2: Implement branch list panel**

Left panel shows branches as a list (not the SVG tree — too complex for the sidebar):
- Each branch: name, status dot, "main" badge for default
- Click to select → right panel shows version timeline
- "New branch" button at bottom
- Selected branch highlighted

- [ ] **Step 3: Implement version timeline**

Right panel for selected branch:
- Header: branch name + "Create Version" button
- Version list in reverse chronological order (newest first)
- Each version: name, LSN (monospace), time ago, created_by
- Click version → expand action buttons (Diff, Restore, Create Branch, Delete)

- [ ] **Step 4: Verify it renders**

Run: `cd lakeon-console && npm run dev`
Open browser, navigate to database detail page, click "分支" tab.

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add branch tab with version timeline to database detail"
```

---

### Task 12: Create Version Dialog + Delete Confirmation

**Files:**
- Create: `lakeon-console/src/views/database/CreateVersionDialog.vue`

- [ ] **Step 1: Create the version dialog component**

Follow the dialog pattern from DatabaseList.vue:
- Overlay + centered box
- Form fields: name (required), description (optional)
- Cancel + Create buttons
- On submit: call `versionApi.create()`, emit 'created' event

- [ ] **Step 2: Wire dialog into the branch tab**

"Create Version" button opens the dialog. On success, refresh version list.

- [ ] **Step 3: Add delete confirmation**

Simple confirm dialog when clicking delete on a version.

- [ ] **Step 4: Verify interactions**

Test creating and deleting a version in the browser.

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/database/CreateVersionDialog.vue \
  lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add create version dialog and delete confirmation"
```

---

## Chunk 5: Frontend — Promote, Restore, Diff UI

### Task 13: Promote and Restore UI

**Files:**
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue`

- [ ] **Step 1: Add Promote button to branch actions**

In the branch list panel, for non-default branches, show a "Promote" button.
On click → confirm dialog: "将 {branch.name} 提升为主干？当前主干将降为普通分支。"
On confirm → call `branchApi.promote()` → refresh branch list.

- [ ] **Step 2: Add Restore button to version actions**

In the version timeline, each version's expanded actions include "回滚到此版本".
On click → confirm dialog: "将 {branch.name} 回滚到 {version.name}？回滚前的状态将自动保存为备份分支。"
On confirm → call `branchApi.restore()` → refresh branch and version list.

- [ ] **Step 3: Verify both operations in browser**

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add promote and restore UI with confirmation dialogs"
```

---

### Task 14: Schema Diff View

**Files:**
- Create: `lakeon-console/src/components/SchemaDiffView.vue`
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue`

- [ ] **Step 1: Create SchemaDiffView component**

Props: `dbId`, `sourceType`, `sourceId`, `targetType`, `targetId`

On mount, call `diffApi.schema()`. Display:
1. Change stats badges (+N added, N modified, -N removed)
2. For each added table: green block with table name + columns
3. For each removed table: red block
4. For each modified table: yellow block with column changes
5. Index changes

Follow the mockup from brainstorming (green/red/yellow color coding).

- [ ] **Step 2: Wire Diff button in version actions**

Clicking "Diff" on a version opens a diff view comparing that version to the current branch state.
Use an overlay/modal or inline expansion. Source = version, target = branch (current).

- [ ] **Step 3: Add branch-to-branch diff**

In the branch list, add a "Diff vs main" button for non-default branches.
Source = branch, target = default branch.

- [ ] **Step 4: Verify in browser**

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/components/SchemaDiffView.vue \
  lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add schema diff view component"
```

---

### Task 15: Squash UI

**Files:**
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue`

- [ ] **Step 1: Add squash mode to version timeline**

Add a "Squash" button in the branch header. On click:
- Enter squash selection mode
- User clicks first version (from), then second version (to)
- Middle versions show strikethrough style
- Confirm bar appears at bottom with "Confirm Squash" button

- [ ] **Step 2: Implement squash confirmation**

On confirm → call `versionApi.squash()` → exit squash mode → refresh version list.

- [ ] **Step 3: Verify in browser**

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add squash UI for version range merging"
```

---

## Chunk 6: Integration Testing + Polish

### Task 16: Update integration tests

**Files:**
- Modify: `deploy/local/integration-test.sh`

- [ ] **Step 1: Add version CRUD tests**

Add to integration test script:
1. Create version on default branch → 201
2. List versions → contains created version
3. Get version → matches
4. Create second version → 201
5. Delete first version → 204
6. List versions → only second version

- [ ] **Step 2: Add promote test**

1. Create branch from default
2. Promote branch → 200
3. Get branches → new branch is default, old renamed to backup

- [ ] **Step 3: Add schema diff test**

1. Create branch from default, run DDL on branch (CREATE TABLE)
2. GET `/databases/{dbId}/diff/schema?source_type=branch&source_id={defaultBranchId}&target_type=branch&target_id={newBranchId}`
3. Verify response contains the new table in `tables.added`

- [ ] **Step 4: Add restore test**

1. Create two versions on default branch
2. Restore to first version → 200
3. Get branches → backup branch created
4. List versions on default → only first version

- [ ] **Step 4: Run integration tests locally**

Run: `./deploy/local/integration-test.sh`
Expected: All tests pass (existing + new)

- [ ] **Step 5: Commit**

```bash
git add deploy/local/integration-test.sh
git commit -m "test: add version, promote, restore integration tests"
```

---

### Task 17: Update memory and clean up

- [ ] **Step 1: Update project memory**

Update `project_version_branch_strategy.md` to reflect implementation status.

- [ ] **Step 2: Verify full test suite**

Run: `cd lakeon-api && mvn test -q`
Expected: All unit tests pass

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: version & branch management — complete implementation"
```
