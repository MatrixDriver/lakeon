# Branch Independent Compute Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade from single-compute-per-database to independent compute per branch, with on-demand startup and auto-suspend.

**Architecture:** Each branch manages its own compute pod lifecycle. Neon Proxy's `wake_compute` callback resolves `endpointish` (dbname or dbname--branchname) to a branch, starts compute if needed, and returns its address. ComputeLifecycleService scans branches (not databases) for auto-suspend.

**Tech Stack:** Spring Boot 3.3.5, Java 17, Fabric8 K8s client, Neon Proxy callback protocol

**Spec:** `docs/superpowers/specs/2026-03-18-branch-independent-compute-design.md`

---

## Chunk 1: Backend — ComputePodManager + BranchEntity changes

### Task 1: Add suspendTimeout to BranchEntity

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/model/entity/BranchEntity.java`

- [ ] **Step 1: Add suspendTimeout field**

After the `computePort` field, add:

```java
@Column(name = "suspend_timeout", length = 16)
private String suspendTimeout;

@Column(name = "suspended_at")
private Instant suspendedAt;
```

Add getters/setters for both fields.

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/entity/BranchEntity.java
git commit -m "feat: add suspendTimeout and suspendedAt to BranchEntity"
```

---

### Task 2: Add createComputePodForBranch to ComputePodManager

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/k8s/ComputePodManager.java`

- [ ] **Step 1: Add new method**

Add `createComputePodForBranch(DatabaseEntity db, BranchEntity branch)`:

```java
/**
 * Create a compute pod for a specific branch.
 * Pod name: compute-{branch_id} (underscores replaced with dashes)
 * Config uses branch's neonTimelineId, all other settings from db.
 */
public String createComputePodForBranch(DatabaseEntity db, BranchEntity branch) {
    // Build a transient DatabaseEntity with branch's timeline
    // so existing generateComputeConfig() works without changes
    DatabaseEntity proxy = new DatabaseEntity();
    proxy.setId(branch.getId());
    proxy.setName(db.getName());
    proxy.setTenantId(db.getTenantId());
    proxy.setNeonTenantId(db.getNeonTenantId());
    proxy.setNeonTimelineId(branch.getNeonTimelineId());
    proxy.setDbUser(db.getDbUser());
    proxy.setDbPassword(db.getDbPassword());
    proxy.setComputeSize(db.getComputeSize());
    proxy.setSuspendTimeout(branch.getSuspendTimeout() != null
        ? branch.getSuspendTimeout() : db.getSuspendTimeout());

    String result = createComputePod(proxy);

    // Copy compute fields back to branch entity
    branch.setComputePodName(proxy.getComputePodName());
    branch.setComputeHost(proxy.getComputeHost());
    branch.setComputePort(proxy.getComputePort());

    return result;
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/k8s/ComputePodManager.java \
  lakeon-api/src/main/java/com/lakeon/model/entity/BranchEntity.java
git commit -m "feat: add createComputePodForBranch to ComputePodManager"
```

---

## Chunk 2: Backend — ProxyAdapterController branch routing

### Task 3: Refactor ProxyAdapterController for branch-level routing

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/ProxyAdapterController.java`

- [ ] **Step 1: Refactor wake_compute to route to branches**

The current `wakeCompute()` resolves `endpointish` to a database and returns the database's compute address. Change it to:

1. Parse `endpointish`: split on `--` → dbName + optional branchName
2. Find database by name
3. Find branch: if branchName given → find by name, else → find default branch (`is_default=true`)
4. If branch has running compute → return address
5. If branch has suspended compute (pod exists) → warm wake
6. Else → cold start: create compute pod for branch

Key changes to `wakeCompute()`:
- Replace `DatabaseEntity` compute field reads with `BranchEntity` compute field reads
- Call `computePodManager.createComputePodForBranch(db, branch)` for cold start
- Return branch's compute address
- Update `branch.setComputeStatus(RUNNING)` and `branch.setLastActiveAt()`
- Save branch entity after changes

Also update `getEndpointAccessControl()` to resolve branch the same way (for allowed IPs).

- [ ] **Step 2: Inject BranchRepository**

Add `BranchRepository` to the controller's constructor injection.

- [ ] **Step 3: Implement branch resolution helper**

```java
private BranchEntity resolveBranch(DatabaseEntity db, String endpointish) {
    String branchName = null;
    if (endpointish.contains("--")) {
        branchName = endpointish.split("--", 2)[1];
    }
    if (branchName != null) {
        return branchRepository.findByDatabaseIdAndName(db.getId(), branchName)
            .orElseThrow(() -> new NotFoundException("Branch not found: " + branchName));
    }
    return branchRepository.findByDatabaseIdAndIsDefaultTrue(db.getId())
        .orElseThrow(() -> new IllegalStateException("No default branch for database " + db.getName()));
}
```

- [ ] **Step 4: Rewrite wakeCompute() to use branch**

```java
@GetMapping("/wake_compute")
public Map<String, Object> wakeCompute(@RequestParam("endpointish") String endpointish, ...) {
    String dbName = endpointish.contains("--") ? endpointish.split("--", 2)[0] : endpointish;
    DatabaseEntity db = databaseRepository.findByName(dbName)...;

    BranchEntity branch = resolveBranch(db, endpointish);

    String address;
    String coldStartInfo;

    if (branch.getComputeStatus() == ComputeStatus.RUNNING
            && branch.getComputeHost() != null
            && computePodManager.isPodReady(branch.getComputePodName())) {
        // Hot path: already running
        address = branch.getComputeHost() + ":" + branch.getComputePort();
        coldStartInfo = "warm";
        branch.setLastActiveAt(Instant.now());
    } else if (branch.getComputePodName() != null
            && computePodManager.isPodReady(branch.getComputePodName())) {
        // Warm path: pod retained, just update status
        String ip = computePodManager.getPodIp(branch.getComputePodName());
        branch.setComputeHost(ip);
        branch.setComputePort(55433);
        branch.setComputeStatus(ComputeStatus.RUNNING);
        branch.setLastActiveAt(Instant.now());
        branch.setSuspendedAt(null);
        address = ip + ":55433";
        coldStartInfo = "warm";
    } else {
        // Cold path: create new compute pod
        address = computePodManager.createComputePodForBranch(db, branch);
        computePodManager.waitForPodReady(branch.getComputePodName(), 120_000);
        branch.setComputeStatus(ComputeStatus.RUNNING);
        branch.setLastActiveAt(Instant.now());
        branch.setSuspendedAt(null);
        coldStartInfo = "pool_miss";
    }
    branchRepository.save(branch);

    // Also update database status for default branch (backward compat)
    if (branch.getIsDefault()) {
        db.setStatus(DatabaseStatus.RUNNING);
        db.setSuspendedAt(null);
        databaseRepository.save(db);
    }

    return Map.of("address", address, "aux", Map.of(
        "endpoint_id", db.getId(),
        "project_id", db.getId(),
        "branch_id", branch.getId(),
        "compute_id", branch.getComputePodName(),
        "cold_start_info", coldStartInfo
    ));
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/ProxyAdapterController.java
git commit -m "feat: ProxyAdapterController routes to branch-level compute"
```

---

## Chunk 3: Backend — ComputeLifecycleService + DatabaseService migration

### Task 4: Refactor ComputeLifecycleService to scan branches

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java`

- [ ] **Step 1: Add branch scanning to checkAutoSuspend**

Change the scheduled `checkAutoSuspend()` to also scan branches with RUNNING compute:

```java
// After existing database suspend logic, add branch suspend:
List<BranchEntity> runningBranches = branchRepository.findByComputeStatus(ComputeStatus.RUNNING);
for (BranchEntity branch : runningBranches) {
    String timeout = branch.getSuspendTimeout();
    if (timeout == null) {
        // Inherit from database
        DatabaseEntity db = databaseRepository.findById(branch.getDatabaseId()).orElse(null);
        if (db != null) timeout = db.getSuspendTimeout();
    }
    Duration suspendAfter = parseSuspendTimeout(timeout);
    Instant lastActive = branch.getLastActiveAt() != null ? branch.getLastActiveAt() : branch.getCreatedAt();
    if (Instant.now().isAfter(lastActive.plus(suspendAfter))) {
        // Check no active connections
        if (branch.getComputePodName() != null
                && !computePodManager.hasActiveConnections(branch.getComputePodName())) {
            log.info("Auto-suspending branch {} compute (idle since {})", branch.getName(), lastActive);
            branch.setComputeStatus(ComputeStatus.SUSPENDED);
            branch.setSuspendedAt(Instant.now());
            branchRepository.save(branch);
        }
    }
}
```

- [ ] **Step 2: Add branch pod cleanup to doCleanupExpiredPods**

After existing database pod cleanup, add:

```java
List<BranchEntity> suspendedBranches = branchRepository.findByComputeStatus(ComputeStatus.SUSPENDED);
for (BranchEntity branch : suspendedBranches) {
    if (branch.getComputePodName() == null) continue;
    if (branch.getSuspendedAt() != null
            && branch.getSuspendedAt().plus(Duration.ofMinutes(podRetainMinutes)).isBefore(Instant.now())) {
        log.info("Cleaning up expired branch Pod: {}", branch.getComputePodName());
        computePodManager.deleteComputePod(branch.getComputePodName());
        branch.setComputePodName(null);
        branch.setComputeHost(null);
        branch.setComputePort(null);
        branchRepository.save(branch);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java
git commit -m "feat: ComputeLifecycleService auto-suspends branch computes"
```

---

### Task 5: Migrate DatabaseService.create() to bind compute to default branch

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`

- [ ] **Step 1: Change create() to assign compute to default branch**

In the `create()` method, after creating the compute pod and the default branch entity, copy compute fields to the branch:

```java
// After compute pod is created and database entity saved:
// Find the default branch (just created above)
BranchEntity defaultBranch = branchRepository.findByDatabaseIdAndIsDefaultTrue(entity.getId())
    .orElseThrow();
defaultBranch.setComputePodName(entity.getComputePodName());
defaultBranch.setComputeHost(entity.getComputeHost());
defaultBranch.setComputePort(entity.getComputePort());
defaultBranch.setComputeStatus(ComputeStatus.RUNNING);
defaultBranch.setSuspendTimeout(entity.getSuspendTimeout());
defaultBranch.setLastActiveAt(Instant.now());
branchRepository.save(defaultBranch);
```

This ensures new databases have compute on the branch level from the start.

- [ ] **Step 2: Add startup migration for existing databases**

Create a `@PostConstruct` migration in `DatabaseService` or a new `SchemaMigration` block:

```java
// For each database that has compute fields set but default branch doesn't:
// Copy compute fields from database to default branch
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java
git commit -m "feat: bind compute to default branch on database create + migration"
```

---

### Task 6: Update BranchService — remove switchActive, simplify promote

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/BranchService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/BranchController.java`

- [ ] **Step 1: Remove switchActive() method from BranchService**

Delete the `switchActive()` method entirely. It's no longer needed — users connect to branches directly.

- [ ] **Step 2: Remove activate endpoint from BranchController**

Remove the `POST /{branchId}/activate` endpoint.

- [ ] **Step 3: Simplify promote() — no compute rebuild**

Change `promote()` to only swap `is_default` flags, without any compute pod operations:

```java
public BranchResponse promote(TenantEntity tenant, String dbId, String branchId) {
    DatabaseEntity db = databaseRepository.findByIdAndTenantIdForUpdate(dbId, tenant.getId())
        .orElseThrow(() -> new NotFoundException("Database not found"));
    BranchEntity target = branchRepository.findByIdAndDatabaseId(branchId, dbId)
        .orElseThrow(() -> new NotFoundException("Branch not found"));
    if (target.getIsDefault()) {
        throw new BadRequestException("Branch is already the default");
    }
    BranchEntity currentDefault = branchRepository.findByDatabaseIdAndIsDefaultTrue(dbId)
        .orElseThrow();
    // Just swap the flags — no compute rebuild needed
    currentDefault.setIsDefault(false);
    currentDefault.setName(currentDefault.getName() + "-before-promote-" + Instant.now().getEpochSecond());
    currentDefault.setBranchType(BranchType.BACKUP);
    branchRepository.save(currentDefault);

    target.setIsDefault(true);
    branchRepository.save(target);

    // Update database's timeline ref to match new default
    db.setNeonTimelineId(target.getNeonTimelineId());
    databaseRepository.save(db);

    return toBranchResponse(target);
}
```

- [ ] **Step 4: Update branch delete to clean up compute**

In `delete()`, also delete the branch's compute pod if it has one:

```java
if (branch.getComputePodName() != null) {
    computePodManager.deleteComputePod(branch.getComputePodName());
}
```

- [ ] **Step 5: Update branch create to generate connection URI with branch name**

In `create()`, set the branch's `connectionUri` to include `--branchName`:

```java
String baseUri = db.getConnectionUri(); // e.g., postgres://user@host:port/dbname
branch.setConnectionUri(baseUri.replace("/" + db.getName(), "/" + db.getName() + "--" + branch.getName()));
```

- [ ] **Step 6: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 7: Run existing tests**

Run: `cd lakeon-api && mvn test -q`
Fix any broken tests due to switchActive removal.

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/BranchService.java \
  lakeon-api/src/main/java/com/lakeon/controller/BranchController.java
git commit -m "feat: remove switchActive, simplify promote, branches own compute"
```

---

## Chunk 4: Frontend — remove switch, show branch compute status

### Task 7: Update TimeTravelView — remove switch, show compute status + connection URI

**Files:**
- Modify: `lakeon-console/src/views/timetravel/TimeTravelView.vue`
- Modify: `lakeon-console/src/api/branch.ts`

- [ ] **Step 1: Remove "切换" button from branch list**

Remove the "切换" button and `handleActivateBranch` function.

- [ ] **Step 2: Show compute status per branch**

Each branch in the list should show its compute status (运行中/已挂起/未启动) with a colored dot:
- Green: RUNNING
- Yellow: SUSPENDED
- Gray: no compute (null)

Replace the current "当前" tag with the compute status.

- [ ] **Step 3: Show branch connection URI**

Below each branch name, show its connection string (truncated, with copy button).

- [ ] **Step 4: Remove activate from branch API**

In `branch.ts`, remove the `activate` method.

- [ ] **Step 5: Build**

Run: `cd lakeon-console && npm run build`

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/timetravel/TimeTravelView.vue \
  lakeon-console/src/api/branch.ts
git commit -m "feat: remove switch, show branch compute status and connection URI"
```

---

## Chunk 5: Database migration + integration testing

### Task 8: Add database schema migration for new branch columns

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/SchemaMigration.java` (or create if needed)

- [ ] **Step 1: Add SQL migration for branch columns**

Ensure `suspend_timeout` and `suspended_at` columns exist on `branches` table:

```sql
ALTER TABLE branches ADD COLUMN IF NOT EXISTS suspend_timeout VARCHAR(16);
ALTER TABLE branches ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP WITH TIME ZONE;
```

Run this on CCE RDS (same pattern as the `branch_type` migration).

- [ ] **Step 2: Add startup migration to copy compute from database to default branch**

In application startup, for each database that has `compute_pod_name` set:
- Find its default branch
- If default branch's `compute_pod_name` is null, copy from database
- Set branch's `compute_status` to database's status

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: schema migration for branch compute fields"
```

---

### Task 9: Update integration tests

**Files:**
- Modify: `deploy/local/integration-test.sh`

- [ ] **Step 1: Update branch tests**

- Remove tests for `POST /branches/{id}/activate`
- Add test: connect to `dbname--branchname` via proxy → verify it works
- Add test: create branch, connect to it (triggers auto-compute), verify response

- [ ] **Step 2: Run tests locally**

Run: `./deploy/local/integration-test.sh`

- [ ] **Step 3: Commit**

```bash
git add deploy/local/integration-test.sh
git commit -m "test: update integration tests for branch-independent compute"
```

---

### Task 10: Deploy and verify

- [ ] **Step 1: Run full test suite**

```bash
cd lakeon-api && mvn test -q
cd lakeon-console && npm run build && npm test -- --run
```

- [ ] **Step 2: Apply schema migration on CCE RDS**

```bash
# Via temporary psql pod
ALTER TABLE branches ADD COLUMN IF NOT EXISTS suspend_timeout VARCHAR(16);
ALTER TABLE branches ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP WITH TIME ZONE;
```

- [ ] **Step 3: Build and deploy**

```bash
IMAGE_TAG=0.7.0 ./deploy/cce/build-and-push-api.sh
# Update values-cce.yaml tag to 0.7.0
./deploy/cce/deploy.sh
```

- [ ] **Step 4: Verify**

- Connect to `mydb` → should route to default branch
- Connect to `mydb--dev` → should auto-start dev branch compute
- Wait 5 minutes idle → branch compute should auto-suspend
- Connect again → should auto-wake

- [ ] **Step 5: Commit tag bump**

```bash
git add deploy/cce/sites/hwstaff/values.yaml
git commit -m "deploy: bump API to 0.7.0 (branch independent compute)"
git push origin main
```
