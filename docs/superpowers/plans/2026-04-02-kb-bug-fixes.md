# KB Pipeline Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 6 production bugs discovered via log analysis affecting KB ingestion reliability.

**Architecture:** Independent fixes to existing Java services — no new files, targeted edits with tests.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, JUnit 5 + Mockito

---

### Task 1: LogQueryService JDBC URL prefix

The `LogQueryService` passes the raw DSN (`postgres://...`) to `DriverManager.getConnection()`, which requires `jdbc:postgresql://`. This makes the SRE console log query feature completely broken.

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/LogQueryService.java:19-23`
- Create: `lakeon-api/src/test/java/com/lakeon/service/LogQueryServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lakeon.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogQueryServiceTest {

    @Test
    void normalizeJdbcUrl_convertsPostgresScheme() {
        assertEquals(
            "jdbc:postgresql://host:5432/db?sslmode=require",
            LogQueryService.normalizeJdbcUrl("postgres://host:5432/db?sslmode=require"));
    }

    @Test
    void normalizeJdbcUrl_convertsPostgresqlScheme() {
        assertEquals(
            "jdbc:postgresql://host:5432/db",
            LogQueryService.normalizeJdbcUrl("postgresql://host:5432/db"));
    }

    @Test
    void normalizeJdbcUrl_preservesJdbcPrefix() {
        assertEquals(
            "jdbc:postgresql://host:5432/db",
            LogQueryService.normalizeJdbcUrl("jdbc:postgresql://host:5432/db"));
    }

    @Test
    void normalizeJdbcUrl_handlesUserInfoInUri() {
        assertEquals(
            "jdbc:postgresql://user:pass@host:5432/db?sslmode=require",
            LogQueryService.normalizeJdbcUrl("postgres://user:pass@host:5432/db?sslmode=require"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -pl . -Dtest=LogQueryServiceTest -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: FAIL — `normalizeJdbcUrl` method does not exist yet.

- [ ] **Step 3: Implement the fix**

In `LogQueryService.java`, add a static helper method and use it in `query()`:

```java
// Add this static method (package-visible for testing)
static String normalizeJdbcUrl(String dsn) {
    if (dsn == null) return dsn;
    if (dsn.startsWith("jdbc:")) return dsn;
    if (dsn.startsWith("postgresql://")) return "jdbc:" + dsn;
    if (dsn.startsWith("postgres://")) return "jdbc:postgresql://" + dsn.substring("postgres://".length());
    return dsn;
}
```

In the `query()` method at line 23, change:
```java
// Before:
try (Connection conn = DriverManager.getConnection(logDbDsn);
// After:
try (Connection conn = DriverManager.getConnection(normalizeJdbcUrl(logDbDsn));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd lakeon-api && mvn test -pl . -Dtest=LogQueryServiceTest -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/LogQueryService.java \
        lakeon-api/src/test/java/com/lakeon/service/LogQueryServiceTest.java
git commit -m "fix: convert postgres:// to jdbc:postgresql:// in LogQueryService"
```

---

### Task 2: RATE_LIMIT exponential backoff with jitter

Currently all RATE_LIMIT retries use a fixed 5-minute delay, causing thundering herd when many tasks retry simultaneously.

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:280-284`
- Create: `lakeon-api/src/test/java/com/lakeon/knowledge/KbWriteQueueRetryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.lakeon.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KbWriteQueueRetryTest {

    @Test
    void rateLimitBackoff_firstRetry_around30s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(0);
        // Base 30s + up to 15s jitter = 30..45
        assertTrue(delay >= 30 && delay <= 45, "Expected 30-45, got " + delay);
    }

    @Test
    void rateLimitBackoff_secondRetry_around60s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(1);
        // Base 60s + up to 30s jitter = 60..90
        assertTrue(delay >= 60 && delay <= 90, "Expected 60-90, got " + delay);
    }

    @Test
    void rateLimitBackoff_thirdRetry_around120s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(2);
        // Base 120s + up to 60s jitter = 120..180
        assertTrue(delay >= 120 && delay <= 180, "Expected 120-180, got " + delay);
    }

    @Test
    void rateLimitBackoff_cappedAt240s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(10);
        // Base 240s (capped) + up to 120s jitter = 240..360
        assertTrue(delay >= 240 && delay <= 360, "Expected 240-360, got " + delay);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -pl . -Dtest=KbWriteQueueRetryTest -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: FAIL — `rateLimitDelaySeconds` does not exist.

- [ ] **Step 3: Implement the fix**

In `KbWriteQueue.java`, add a static method (package-visible for testing):

```java
/**
 * Exponential backoff with jitter for RATE_LIMIT retries.
 * Base: 30s * 2^retryCount, capped at 240s. Jitter: 0..50% of base.
 */
static long rateLimitDelaySeconds(int retryCount) {
    long base = Math.min(30L * (1L << retryCount), 240L);
    long jitter = (long) (base * 0.5 * Math.random());
    return base + jitter;
}
```

Then replace lines 280-284 in `onJobCompleted()`:

```java
// Before:
if ("RATE_LIMIT".equals(errorCategory)) {
    task.setNextRetryAt(Instant.now().plusSeconds(300)); // 5 minutes
} else {
    task.setNextRetryAt(null); // immediate retry
}

// After:
if ("RATE_LIMIT".equals(errorCategory)) {
    long delay = rateLimitDelaySeconds(task.getRetryCount());
    task.setNextRetryAt(Instant.now().plusSeconds(delay));
    log.info("RATE_LIMIT retry for task {} in {}s (attempt {})",
             task.getId(), delay, task.getRetryCount());
} else {
    task.setNextRetryAt(null); // immediate retry
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd lakeon-api && mvn test -pl . -Dtest=KbWriteQueueRetryTest -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/KbWriteQueueRetryTest.java
git commit -m "fix: use exponential backoff with jitter for RATE_LIMIT retries"
```

---

### Task 3: Database existence pre-check before task submission

Currently `batchProcessDocuments()` queues tasks without verifying the target database exists. With 2600 docs, this creates 130 tasks that all fail with "Database not found".

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java:528`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:223-241`

- [ ] **Step 1: Add database validation in KbWriteQueue.submit()**

In `KbWriteQueue.java`, add validation at the beginning of the `submit()` method (line 224, before creating the entity):

```java
public KbWriteTaskEntity submit(String tenantId, String kbId, String databaseId,
                                 KbWriteTaskType type, Map<String, Object> params) {
    // Validate database exists and is usable
    DatabaseEntity db = databaseRepository.findById(databaseId).orElse(null);
    if (db == null) {
        throw new NotFoundException("Database not found: " + databaseId
            + ". The knowledge base may reference a deleted database.");
    }

    KbWriteTaskEntity task = new KbWriteTaskEntity();
    // ... rest unchanged
```

- [ ] **Step 2: Also validate in batchProcessDocuments()**

In `KnowledgeService.java`, after line 528 (`databaseId = kb.getDatabaseId()`), add:

```java
databaseId = kb.getDatabaseId();
// Validate database exists before queuing tasks
if (databaseId == null) {
    throw new BadRequestException("Knowledge base " + kbId + " has no database assigned");
}
```

The `submit()` check in KbWriteQueue handles the case where the DB ID exists but the database was deleted.

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `cd lakeon-api && mvn test -pl . -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: All existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "fix: validate database exists before queuing kb-write tasks"
```

---

### Task 4: Pipeline version "latest" parameter

The endpoint `GET /pipelines/{id}/versions/{version}` uses `@PathVariable int version`, which throws when the frontend passes "latest".

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineController.java:60-66`
- Modify: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentController.java:60-62`
- Modify: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineService.java:77`
- Modify: `lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentService.java:86`
- Test: `lakeon-api/src/test/java/com/lakeon/pipeline/PipelineControllerTest.java`

- [ ] **Step 1: Add resolveVersion helper to PipelineService**

In `PipelineService.java`, add a method to resolve "latest":

```java
public PipelineVersionEntity getVersion(String tenantId, String pipelineId, String version) {
    PipelineEntity pipeline = get(tenantId, pipelineId);
    int versionNum;
    if ("latest".equalsIgnoreCase(version)) {
        versionNum = pipeline.getLatestVersion();
    } else {
        try {
            versionNum = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid version: " + version + ". Use a number or 'latest'.");
        }
    }
    return pipelineVersionRepository.findByPipelineIdAndVersion(pipelineId, versionNum)
            .orElseThrow(() -> new NotFoundException(
                    "Pipeline version not found: " + pipelineId + " v" + versionNum));
}
```

Keep the old `getVersion(String, String, int)` method for backwards compatibility with internal callers, but have it delegate:

```java
public PipelineVersionEntity getVersion(String tenantId, String pipelineId, int version) {
    return getVersion(tenantId, pipelineId, String.valueOf(version));
}
```

- [ ] **Step 2: Update PipelineController**

In `PipelineController.java`, change lines 60-66:

```java
// Before:
@GetMapping("/{id}/versions/{version}")
public Map<String, Object> getVersion(HttpServletRequest req,
                                       @PathVariable String id,
                                       @PathVariable int version) {
    TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
    return versionToResponse(pipelineService.getVersion(tenant.getId(), id, version));
}

// After:
@GetMapping("/{id}/versions/{version}")
public Map<String, Object> getVersion(HttpServletRequest req,
                                       @PathVariable String id,
                                       @PathVariable String version) {
    TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
    return versionToResponse(pipelineService.getVersion(tenant.getId(), id, version));
}
```

- [ ] **Step 3: Add resolveVersion helper to PipelineComponentService**

Same pattern in `PipelineComponentService.java`:

```java
public PipelineComponentVersionEntity getVersion(String componentId, String version) {
    PipelineComponentEntity component = componentRepository.findById(componentId)
            .orElseThrow(() -> new NotFoundException("Component not found: " + componentId));
    int versionNum;
    if ("latest".equalsIgnoreCase(version)) {
        versionNum = component.getLatestVersion();
    } else {
        try {
            versionNum = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid version: " + version);
        }
    }
    return componentVersionRepository.findByComponentIdAndVersion(componentId, versionNum)
            .orElseThrow(() -> new NotFoundException(
                    "Component version not found: " + componentId + " v" + versionNum));
}

public PipelineComponentVersionEntity getVersion(String componentId, int version) {
    return getVersion(componentId, String.valueOf(version));
}
```

- [ ] **Step 4: Update PipelineComponentController**

In `PipelineComponentController.java`, change lines 60-62:

```java
// Before:
@GetMapping("/{id}/versions/{version}")
public Map<String, Object> getVersion(@PathVariable String id, @PathVariable int version) {
    return versionToResponse(componentService.getVersion(id, version));
}

// After:
@GetMapping("/{id}/versions/{version}")
public Map<String, Object> getVersion(@PathVariable String id, @PathVariable String version) {
    return versionToResponse(componentService.getVersion(id, version));
}
```

- [ ] **Step 5: Add test for "latest" resolution**

Add to `PipelineControllerTest.java` (or PipelineServiceTest.java if controller tests need web context):

```java
@Test
void getVersion_latest_resolves() throws Exception {
    // Create a pipeline with a version
    // Then GET /pipelines/{id}/versions/latest should return the latest version
    // This test depends on existing test infrastructure — adapt to match existing patterns
}
```

Check existing test patterns in `PipelineControllerTest.java` and follow the same setup.

- [ ] **Step 6: Run tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest="PipelineControllerTest,PipelineServiceTest,PipelineComponentServiceTest" -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: All pass.

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/pipeline/PipelineController.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentController.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineService.java \
        lakeon-api/src/main/java/com/lakeon/pipeline/PipelineComponentService.java
git commit -m "fix: support 'latest' as version parameter in pipeline endpoints"
```

---

### Task 5: CHECKPOINT race condition — skip if pod is being deleted

After a pod is deleted, a concurrent CHECKPOINT request retries 15 times (45 seconds) on a non-existent pod. Fix: delete pod first, then clear the pod name in entity, and check pod name before issuing CHECKPOINT.

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java:264-269,299-304`

- [ ] **Step 1: Fix database pod cleanup**

In `ComputeLifecycleService.java`, change the database pod cleanup block (lines 264-274):

```java
// Before:
try {
    // Flush WAL before deleting to prevent data loss
    if (computePodManager.isPodReady(entity.getComputePodName())) {
        computePodManager.executeCheckpoint(entity.getComputePodName());
    }
    computePodManager.deleteComputePod(entity.getComputePodName());
    entity.setComputePodName(null);
    entity.setComputeHost(null);
    entity.setComputePort(null);
    databaseRepository.save(entity);
} catch (Exception e) {
    log.error("Failed to cleanup Pod for database {}: {}", entity.getId(), e.getMessage());
}

// After:
try {
    String podName = entity.getComputePodName();
    // Flush WAL before deleting to prevent data loss
    if (computePodManager.isPodReady(podName)) {
        computePodManager.executeCheckpoint(podName);
    }
    // Clear pod reference BEFORE deleting — prevents other threads from
    // issuing CHECKPOINT on a pod that's being deleted
    entity.setComputePodName(null);
    entity.setComputeHost(null);
    entity.setComputePort(null);
    databaseRepository.save(entity);
    computePodManager.deleteComputePod(podName);
} catch (Exception e) {
    log.error("Failed to cleanup Pod for database {}: {}", entity.getId(), e.getMessage());
}
```

- [ ] **Step 2: Fix branch pod cleanup**

Same pattern for branch pod cleanup (lines 299-311):

```java
// After:
try {
    String podName = branch.getComputePodName();
    if (computePodManager.isPodReady(podName)) {
        computePodManager.executeCheckpoint(podName);
    }
    // Clear pod reference BEFORE deleting
    branch.setComputePodName(null);
    branch.setComputeHost(null);
    branch.setComputePort(null);
    branchRepository.save(branch);
    computePodManager.deleteComputePod(podName);
} catch (Exception e) {
    log.error("Failed to cleanup Pod for branch {}: {}", branch.getId(), e.getMessage());
}
```

- [ ] **Step 3: Run existing ComputeLifecycleServiceTest**

Run: `cd lakeon-api && mvn test -pl . -Dtest=ComputeLifecycleServiceTest -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: All existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java
git commit -m "fix: clear pod reference before deletion to prevent CHECKPOINT race"
```

---

### Task 6: Atomic document_count increment

Concurrent batch completions cause "Row was updated or deleted by another transaction" on KnowledgeBaseEntity. Replace read-modify-write pattern with atomic SQL increment.

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:354-360,419-424`

- [ ] **Step 1: Add atomic increment query to KnowledgeBaseRepository**

```java
package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    Optional<KnowledgeBaseEntity> findByIdAndTenantId(String id, String tenantId);
    List<KnowledgeBaseEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.documentCount = COALESCE(kb.documentCount, 0) + :delta WHERE kb.id = :id")
    void incrementDocumentCount(String id, int delta);
}
```

- [ ] **Step 2: Replace read-modify-write in syncDocumentFromTask()**

In `KbWriteQueue.java`, replace lines 354-360:

```java
// Before:
if (doc.getKbId() != null) {
    knowledgeBaseRepository.findById(doc.getKbId()).ifPresent(kb -> {
        kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
        knowledgeBaseRepository.save(kb);
    });
}

// After:
if (doc.getKbId() != null) {
    knowledgeBaseRepository.incrementDocumentCount(doc.getKbId(), 1);
}
```

- [ ] **Step 3: Replace read-modify-write in syncBatchDocumentsFromTask()**

In `KbWriteQueue.java`, replace lines 419-424 (inside the for loop for each docId):

```java
// Before:
if (doc.getKbId() != null) {
    knowledgeBaseRepository.findById(doc.getKbId()).ifPresent(kb -> {
        kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
        knowledgeBaseRepository.save(kb);
    });
}

// After:
if (doc.getKbId() != null) {
    knowledgeBaseRepository.incrementDocumentCount(doc.getKbId(), 1);
}
```

Note: This still increments once per document in the loop. For efficiency, it could be a single `incrementDocumentCount(kbId, successCount)` call outside the loop, but the per-doc loop is needed for setting individual doc status/chunksCount. The atomic increment is safe regardless.

- [ ] **Step 4: Add @Transactional to the sync methods**

The `@Modifying` query requires an active transaction. The `onJobCompleted` caller may not have one. Add `@Transactional` to `onJobCompleted`:

Check if `onJobCompleted` already has `@Transactional`. If not, add it:

```java
@Transactional
public void onJobCompleted(String jobId, boolean success, String result, String error) {
```

- [ ] **Step 5: Run full test suite**

Run: `cd lakeon-api && mvn test -pl . -Dspring.profiles.active=test -q 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeBaseRepository.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java
git commit -m "fix: use atomic SQL increment for KB document_count to prevent optimistic lock errors"
```

---

### Task 7: Final verification

- [ ] **Step 1: Run full API test suite**

Run: `cd lakeon-api && mvn test -pl . -Dspring.profiles.active=test -q 2>&1 | tail -30`
Expected: All tests pass.

- [ ] **Step 2: TypeScript check for frontend**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -20`
Expected: No errors (this plan has no frontend changes).

- [ ] **Step 3: Commit summary**

Verify all 6 commits are clean:
```bash
git log --oneline -6
```
