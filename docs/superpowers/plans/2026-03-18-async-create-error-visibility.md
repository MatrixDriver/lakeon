# Async Database Creation + Error Visibility

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make database creation async (non-blocking API) with step-by-step progress visibility and clear error reporting for both users and SRE.

**Architecture:** API returns immediately after saving entity as CREATING. A separate `DatabaseProvisioningService` with `@Async` performs Neon + K8s operations in a background thread, updating `statusMessage` at each step. On failure, status is set to ERROR with the error message. Neon resources are preserved (cleaned up on delete). On app restart, stuck CREATING databases are marked ERROR.

**Tech Stack:** Spring Boot @Async, JPA, Vue 3

---

### Task 1: DB Migration — Add status_message column

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V12__add_database_status_message.sql`

- [ ] **Step 1: Create migration file**

```sql
ALTER TABLE database_instances ADD COLUMN status_message VARCHAR(1024);
```

- [ ] **Step 2: Verify migration filename is sequential after V11**

Run: `ls lakeon-api/src/main/resources/db/migration/`
Expected: V12 follows V11 with no gaps

---

### Task 2: Add statusMessage to DatabaseEntity

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/model/entity/DatabaseEntity.java`

- [ ] **Step 1: Add statusMessage field and getter/setter**

After the `connectionUri` field (line ~58), add:

```java
@Column(name = "status_message", length = 1024)
private String statusMessage;
```

Add getter/setter after the existing `connectionUri` getter/setter:

```java
public String getStatusMessage() { return statusMessage; }
public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
```

---

### Task 3: Add status_message to DatabaseResponse + Builder

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/model/dto/DatabaseResponse.java`

- [ ] **Step 1: Add statusMessage field with JSON annotation**

After the `status` field (line ~12), add:

```java
@JsonProperty("status_message")
@JsonInclude(JsonInclude.Include.NON_NULL)
private String statusMessage;
```

- [ ] **Step 2: Add getter/setter**

```java
public String getStatusMessage() { return statusMessage; }
public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
```

- [ ] **Step 3: Add to Builder class**

Add field and method to Builder:

```java
private String statusMessage;

public Builder statusMessage(String statusMessage) { this.statusMessage = statusMessage; return this; }
```

In `Builder.build()`, after existing sets, add:

```java
r.setStatusMessage(statusMessage);
```

---

### Task 4: Wire statusMessage in DatabaseService.toResponse()

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java` (toResponse method, ~line 556)

- [ ] **Step 1: Add statusMessage to builder call**

In the `toResponse()` method's builder chain (around line 556-568), add `.statusMessage(entity.getStatusMessage())` after `.status(entity.getStatus())`:

```java
DatabaseResponse response = DatabaseResponse.builder()
    .id(entity.getId())
    .name(entity.getName())
    .status(entity.getStatus())
    .statusMessage(entity.getStatusMessage())
    // ... rest unchanged
```

---

### Task 5: Wire statusMessage in Admin dbToMap()

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java` (dbToMap method, ~line 420)

- [ ] **Step 1: Add status_message to admin map**

In `dbToMap()`, after `m.put("status", ...)` (line ~425), add:

```java
m.put("status_message", db.getStatusMessage());
```

---

### Task 6: Create AsyncConfig + DatabaseProvisioningService

**Why a separate service?** Spring `@Async` relies on proxy-based AOP. If `create()` calls `provisionAsync()` on the same bean (`this.method()`), the call bypasses the proxy and runs synchronously. A separate `@Service` class ensures the `@Async` annotation works correctly.

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/config/AsyncConfig.java`
- Create: `lakeon-api/src/main/java/com/lakeon/service/DatabaseProvisioningService.java`

- [ ] **Step 1: Create AsyncConfig**

```java
package com.lakeon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "databaseCreateExecutor")
    public Executor databaseCreateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("db-create-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

Note: `CallerRunsPolicy` ensures that if the thread pool is full, the caller thread runs the task synchronously as a fallback (creation still works, just blocks).

- [ ] **Step 2: Create DatabaseProvisioningService**

```java
package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class DatabaseProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseProvisioningService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final ComputePodManager computePodManager;
    private final OperationLogService operationLogService;
    private final DatabaseService databaseService;
    private final TransactionTemplate txTemplate;

    public DatabaseProvisioningService(DatabaseRepository databaseRepository,
                                        BranchRepository branchRepository,
                                        ComputePodManager computePodManager,
                                        OperationLogService operationLogService,
                                        DatabaseService databaseService,
                                        TransactionTemplate txTemplate) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.computePodManager = computePodManager;
        this.operationLogService = operationLogService;
        this.databaseService = databaseService;
        this.txTemplate = txTemplate;
    }

    @Async("databaseCreateExecutor")
    public void provisionAsync(String databaseId, String neonTimelineId,
                                String dbUser, String opLogId) {
        try {
            // Step 1: Create compute pod
            updateStatusMessage(databaseId, "正在启动计算节点...");
            DatabaseEntity entity = databaseRepository.findById(databaseId).orElseThrow();
            computePodManager.createComputePod(entity);
            boolean ready = computePodManager.waitForPodReady(entity.getComputePodName(), 60_000);
            if (!ready) {
                throw new RuntimeException("计算节点启动超时(60s)");
            }

            // Step 2: Enable extensions
            updateStatusMessage(databaseId, "正在配置默认扩展...");
            entity = databaseRepository.findById(databaseId).orElseThrow();
            databaseService.enableDefaultExtensions(entity);

            // Step 3: Finalize — set RUNNING
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(databaseId).orElseThrow();
                e.setConnectionUri(databaseService.buildConnectionUri(dbUser, e.getName()));
                e.setStatus(DatabaseStatus.RUNNING);
                e.setStatusMessage(null);
                e.setLastActiveAt(Instant.now());
                databaseRepository.save(e);

                BranchEntity mainBranch = new BranchEntity();
                mainBranch.setName("main");
                mainBranch.setDatabaseId(e.getId());
                mainBranch.setNeonTimelineId(neonTimelineId);
                mainBranch.setIsDefault(true);
                mainBranch.setStatus(BranchStatus.ACTIVE);
                mainBranch.setComputePodName(e.getComputePodName());
                mainBranch.setComputeHost(e.getComputeHost());
                mainBranch.setComputePort(e.getComputePort());
                mainBranch.setComputeStatus(ComputeStatus.RUNNING);
                mainBranch.setSuspendTimeout(e.getSuspendTimeout());
                mainBranch.setLastActiveAt(Instant.now());
                branchRepository.save(mainBranch);
            });

            // Complete operation log as success
            operationLogService.findById(opLogId).ifPresent(opLog ->
                operationLogService.completeOperation(opLog, null));
            log.info("Database {} provisioned successfully", databaseId);

        } catch (Exception e) {
            log.error("Failed to provision database {}: {}", databaseId, e.getMessage(), e);
            // Set ERROR status with message
            try {
                txTemplate.executeWithoutResult(status -> {
                    DatabaseEntity db = databaseRepository.findById(databaseId).orElse(null);
                    if (db != null) {
                        db.setStatus(DatabaseStatus.ERROR);
                        db.setStatusMessage(e.getMessage());
                        databaseRepository.save(db);
                    }
                });
            } catch (Exception updateEx) {
                log.error("Failed to update database {} status to ERROR: {}", databaseId, updateEx.getMessage());
            }
            // Complete operation log as failed
            try {
                operationLogService.findById(opLogId).ifPresent(opLog ->
                    operationLogService.completeOperation(opLog, e.getMessage()));
            } catch (Exception logEx) {
                log.error("Failed to complete operation log: {}", logEx.getMessage());
            }
        }
    }

    private void updateStatusMessage(String databaseId, String message) {
        txTemplate.executeWithoutResult(status -> {
            DatabaseEntity e = databaseRepository.findById(databaseId).orElseThrow();
            e.setStatusMessage(message);
            databaseRepository.save(e);
        });
    }

    /**
     * Called on app startup to mark any stuck CREATING databases as ERROR.
     * This handles the case where the app was restarted during provisioning.
     */
    public void recoverStuckCreatingDatabases() {
        var stuck = databaseRepository.findAllByStatus(DatabaseStatus.CREATING);
        for (DatabaseEntity db : stuck) {
            log.warn("Database {} stuck in CREATING status, marking as ERROR", db.getId());
            db.setStatus(DatabaseStatus.ERROR);
            db.setStatusMessage("创建过程被服务重启中断，请删除后重新创建");
            databaseRepository.save(db);
            // Also fail any IN_PROGRESS operation logs for this database
            operationLogService.failInProgressOperations(db.getId(), "服务重启中断");
        }
        if (!stuck.isEmpty()) {
            log.info("Recovered {} stuck CREATING databases", stuck.size());
        }
    }
}
```

---

### Task 7: Add helper methods and startup recovery

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/OperationLogService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java` (make `enableDefaultExtensions` and `buildConnectionUri` package-visible)
- Modify: `lakeon-api/src/main/java/com/lakeon/repository/DatabaseRepository.java` (add `findAllByStatus` if not present)

- [ ] **Step 1: Add failInProgressOperations to OperationLogService**

```java
public void failInProgressOperations(String databaseId, String errorMessage) {
    List<OperationLogEntity> inProgress = repository.findByDatabaseIdAndStatus(
        databaseId, OperationStatus.IN_PROGRESS);
    for (OperationLogEntity op : inProgress) {
        completeOperation(op, errorMessage);
    }
}
```

- [ ] **Step 2: Add findByDatabaseIdAndStatus to OperationLogRepository**

Check if it already exists. If not, add:

```java
List<OperationLogEntity> findByDatabaseIdAndStatus(String databaseId, OperationStatus status);
```

- [ ] **Step 3: Check DatabaseRepository for findAllByStatus**

Verify `findAllByStatus(DatabaseStatus status)` exists in `DatabaseRepository`. (It's already used in AdminController line 152.)

- [ ] **Step 4: Make enableDefaultExtensions package-visible**

In `DatabaseService.java`, change `private void enableDefaultExtensions` to package-private:

```java
void enableDefaultExtensions(DatabaseEntity entity) {
```

`buildConnectionUri` is already `public` (line 706).

- [ ] **Step 5: Register startup recovery**

Add `@PostConstruct` or `ApplicationRunner` to call `recoverStuckCreatingDatabases()`. Add to `DatabaseProvisioningService`:

```java
@jakarta.annotation.PostConstruct
public void onStartup() {
    recoverStuckCreatingDatabases();
}
```

---

### Task 8: Refactor DatabaseService.create() to be async

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`

- [ ] **Step 1: Inject DatabaseProvisioningService**

Add to constructor:

```java
private final DatabaseProvisioningService provisioningService;
```

Add parameter to constructor and assign it. Note: `DatabaseProvisioningService` injects `DatabaseService`, so this creates a circular dependency. Use `@Lazy` on the constructor parameter in `DatabaseService`:

```java
public DatabaseService(DatabaseRepository databaseRepository,
                       BranchRepository branchRepository,
                       NeonApiClient neonApiClient,
                       ComputePodManager computePodManager,
                       LakeonProperties props,
                       OperationLogService operationLogService,
                       MeterRegistry meterRegistry,
                       TransactionTemplate txTemplate,
                       @org.springframework.context.annotation.Lazy DatabaseProvisioningService provisioningService) {
    // ... existing assignments ...
    this.provisioningService = provisioningService;
}
```

- [ ] **Step 2: Replace the create() method**

Replace the entire `create()` method (lines 76-223) with:

```java
public DatabaseResponse create(TenantEntity tenant, CreateDatabaseRequest request) {
    // Generate credentials
    String dbUser = "user_" + UUID.randomUUID().toString().substring(0, 8);
    String rawPassword = generatePassword();
    String scramHash = ScramUtils.generateScramHash(rawPassword);

    // Transaction 1: validate + create Neon resources + save entity as CREATING
    record CreateResult(DatabaseEntity entity, NeonTimeline neonTimeline) {}
    CreateResult created = txTemplate.execute(status -> {
        // Check name uniqueness
        databaseRepository.findByTenantIdAndName(tenant.getId(), request.name()).ifPresent(existing -> {
            throw new ConflictException("Database '" + request.name() + "' already exists for this tenant");
        });

        // Check quota: database count
        int currentDbCount = databaseRepository.findAllByTenantId(tenant.getId()).size();
        if (tenant.getMaxDatabases() != null && currentDbCount >= tenant.getMaxDatabases()) {
            throw new QuotaExceededException(
                "Database quota exceeded: limit is " + tenant.getMaxDatabases() + ", current count is " + currentDbCount);
        }

        // Apply defaults
        String computeSize = request.computeSize() != null ? request.computeSize() : props.getDefaults().getComputeSize();
        String suspendTimeout = request.suspendTimeout() != null ? request.suspendTimeout() : props.getDefaults().getSuspendTimeout();
        int storageLimitGb = request.storageLimitGb() != null ? request.storageLimitGb() : props.getDefaults().getStorageLimitGb();

        // Check quota: compute size
        int requestedCu = parseComputeUnits(computeSize);
        if (tenant.getMaxComputeCu() != null && requestedCu > tenant.getMaxComputeCu()) {
            throw new QuotaExceededException(
                "Compute quota exceeded: requested " + computeSize + " but max allowed is " + tenant.getMaxComputeCu() + "cu");
        }

        // Create Neon tenant
        NeonTenant neonTenant;
        try {
            neonTenant = neonApiClient.createTenant(generateHexId());
        } catch (Exception e) {
            throw new ServiceException("Failed to create Neon tenant: " + e.getMessage(), e);
        }

        // Wait for tenant to become Active before creating timeline
        try {
            neonApiClient.waitForTenantActive(neonTenant.getId(), 30);
        } catch (Exception e) {
            try { neonApiClient.deleteTenant(neonTenant.getId()); } catch (Exception rollbackEx) {
                log.warn("Failed to rollback Neon tenant {}: {}", neonTenant.getId(), rollbackEx.getMessage());
            }
            throw new ServiceException("Tenant did not become active: " + e.getMessage(), e);
        }

        // Create Neon timeline
        NeonTimeline neonTimeline;
        try {
            neonTimeline = neonApiClient.createTimeline(neonTenant.getId(),
                CreateTimelineRequest.forNewTimeline(generateHexId(), 17));
        } catch (Exception e) {
            try { neonApiClient.deleteTenant(neonTenant.getId()); } catch (Exception rollbackEx) {
                log.warn("Failed to rollback Neon tenant {}: {}", neonTenant.getId(), rollbackEx.getMessage());
            }
            throw new ServiceException("Failed to create Neon timeline: " + e.getMessage(), e);
        }

        // Build entity
        DatabaseEntity entity = new DatabaseEntity();
        entity.setName(request.name());
        entity.setTenantId(tenant.getId());
        entity.setNeonTenantId(neonTenant.getId());
        entity.setNeonTimelineId(neonTimeline.getTimelineId());
        entity.setStatus(DatabaseStatus.CREATING);
        entity.setStatusMessage("正在准备存储资源...");
        entity.setComputeSize(computeSize);
        entity.setSuspendTimeout(suspendTimeout);
        entity.setStorageLimitGb(storageLimitGb);
        entity.setDbUser(dbUser);
        entity.setDbPassword(scramHash);

        String proxyHost = props.getProxy().getExternalHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            entity.setComputeHost(proxyHost);
        } else {
            entity.setComputeHost("proxy.lakeon.svc.cluster.local");
        }
        entity.setComputePort(props.getProxy().getExternalPort());

        DatabaseEntity saved = databaseRepository.save(entity);
        return new CreateResult(saved, neonTimeline);
    });

    DatabaseEntity entity = created.entity();
    NeonTimeline neonTimeline = created.neonTimeline();

    // Start operation log
    OperationLogEntity opLog = operationLogService.startOperation(
            entity.getId(), entity.getTenantId(), entity.getName(), OperationType.CREATE);

    // Launch async provisioning (via separate service to ensure @Async proxy works)
    provisioningService.provisionAsync(entity.getId(), neonTimeline.getTimelineId(), dbUser, opLog.getId());

    // Return immediately with CREATING status
    DatabaseResponse response = toResponse(entity, List.of());
    response.setPassword(rawPassword);
    return response;
}
```

---

### Task 9: Change API response status to 202 Accepted

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/DatabaseController.java`

- [ ] **Step 1: Change createDatabase response status**

Change line 27 from `HttpStatus.CREATED` to `HttpStatus.ACCEPTED`:

```java
@PostMapping
@ResponseStatus(HttpStatus.ACCEPTED)
public DatabaseResponse createDatabase(HttpServletRequest req,
                                       @Valid @RequestBody CreateDatabaseRequest request) {
```

---

### Task 10: User Console — update Database type and status display

**Files:**
- Modify: `lakeon-console/src/api/database.ts`
- Modify: `lakeon-console/src/views/database/DatabaseList.vue`

- [ ] **Step 1: Add status_message to Database type**

In `lakeon-console/src/api/database.ts`, add to the `Database` interface after `status`:

```typescript
status_message?: string | null
```

- [ ] **Step 2: Update status display in DatabaseList.vue**

In `DatabaseList.vue`, replace the status `<td>` (lines 51-54) with:

```html
<td>
  <div>
    <div>
      <span class="status-dot" :class="statusClass(db.status)"></span>
      {{ statusText(db.status) }}
    </div>
    <div v-if="db.status === 'CREATING' && db.status_message" class="status-message text-muted">
      {{ db.status_message }}
    </div>
    <div v-if="db.status === 'ERROR' && db.status_message" class="status-message text-error">
      {{ db.status_message }}
    </div>
  </div>
</td>
```

- [ ] **Step 3: Update statusText to handle ERROR**

```typescript
function statusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    case 'ERROR': return '创建失败'
    default: return '异常'
  }
}
```

- [ ] **Step 4: Update handleCreate to show toast**

Replace the `handleCreate` function:

```typescript
async function handleCreate() {
  if (!createForm.name.trim()) return
  createLoading.value = true
  try {
    const res = await databaseApi.create({
      name: createForm.name.trim(),
      compute_size: createForm.compute_size,
      suspend_timeout: createForm.suspend_timeout,
      storage_limit_gb: createForm.storage_limit_gb,
    })
    showCreateDialog.value = false
    createForm.name = ''
    createForm.compute_size = '1cu'
    createForm.suspend_timeout = '5m'
    createForm.storage_limit_gb = 10
    toast.success('数据库创建已提交，正在后台初始化...')
    await fetchDatabases()
    pollUntilReady(res.data.id)
  } catch (e) {
    const msg = (e as any)?.response?.data?.error?.message || (e as any)?.message || '未知错误'
    toast.error(`创建数据库失败: ${msg}`)
    console.error('Failed to create database', e)
  } finally {
    createLoading.value = false
  }
}
```

- [ ] **Step 5: Update pollUntilReady to refresh status messages**

Replace the `pollUntilReady` function to merge intermediate status into the list:

```typescript
async function pollUntilReady(id: string) {
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 2000))
    try {
      const res = await databaseApi.get(id)
      const db = res.data
      // Merge polled status into local list so user sees progress messages
      const idx = databases.value.findIndex(d => d.id === id)
      if (idx >= 0) {
        databases.value[idx] = db
      }
      if (['RUNNING', 'SUSPENDED', 'ERROR'].includes(db.status)) {
        break
      }
    } catch {
      break
    }
  }
  await fetchDatabases()
}
```

- [ ] **Step 6: Add CSS for status messages**

In the `<style scoped>` section, add:

```css
.status-message {
  font-size: 12px;
  margin-top: 2px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-error {
  color: #e53e3e;
}
```

---

### Task 11: SRE Console — show status_message column

**Files:**
- Modify: `lakeon-admin/src/views/databases/DatabaseList.vue`

- [ ] **Step 1: Add status_message to Database interface**

In the `Database` interface (line ~112), add:

```typescript
status_message?: string
```

- [ ] **Step 2: Add column header**

After the `状态` th (inside `<thead>`), add:

```html
<th>状态信息</th>
```

- [ ] **Step 3: Add column data**

After the status `<td>` (line ~61), add:

```html
<td class="error-cell">{{ db.status_message || '-' }}</td>
```

- [ ] **Step 4: Update colspan for empty state**

Change the empty state `colspan="9"` to `colspan="10"`.

- [ ] **Step 5: Add error-cell style**

In `<style scoped>`, add:

```css
.error-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #e53e3e;
  font-size: 13px;
}
```

---

### Task 12: Build and verify compilation

- [ ] **Step 1: Build Java API**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Build user console**

Run: `cd lakeon-console && npm run build`
Expected: no errors

- [ ] **Step 3: Build admin console**

Run: `cd lakeon-admin && npm run build`
Expected: no errors

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: async database creation with progress and error visibility

- Add statusMessage field to DatabaseEntity + V12 migration
- Extract DatabaseProvisioningService with @Async for background provisioning
- Refactor create() to return 202 immediately, provision in background thread
- Update statusMessage at each step: storage → compute → extensions → done
- On failure: set status=ERROR with error message (preserve Neon resources)
- On app restart: mark stuck CREATING databases as ERROR
- User console: show progress message under CREATING, error under ERROR
- SRE console: add status_message column to database list"
```
