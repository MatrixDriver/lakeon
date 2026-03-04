# Lakeon Web Console Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Huawei Cloud-style web console for Lakeon Serverless PostgreSQL using TinyPro cloud console template.

**Architecture:** Independent Vue 3 frontend (`lakeon-console/`) calling existing `lakeon-api` REST endpoints. Backend adds operation log tracking and API key regeneration. Nginx reverse proxy handles API routing.

**Tech Stack:** Vue 3 + TypeScript + Vite + TinyPro + TinyVue + Pinia + Axios (frontend), Spring Boot 3.3.5 + JPA (backend additions)

**Design Doc:** `docs/plans/2026-03-04-web-console-design.md`

---

## Part 1: Backend — Operation Log

### Task 1: OperationLog Entity and Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/OperationLogEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/OperationType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/enums/OperationStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/OperationLogRepository.java`

**Step 1: Create OperationType enum**

```java
package com.lakeon.model.enums;

public enum OperationType {
    CREATE, SUSPEND, RESUME, DELETE, UPDATE
}
```

**Step 2: Create OperationStatus enum**

```java
package com.lakeon.model.enums;

public enum OperationStatus {
    IN_PROGRESS, SUCCESS, FAILED
}
```

**Step 3: Create OperationLogEntity**

```java
package com.lakeon.model.entity;

import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operation_logs", indexes = {
    @Index(name = "idx_oplog_database_id", columnList = "database_id"),
    @Index(name = "idx_oplog_tenant_id", columnList = "tenant_id")
})
public class OperationLogEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "database_id", length = 64, nullable = false)
    private String databaseId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "database_name")
    private String databaseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false)
    private OperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "op_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public OperationType getOperationType() { return operationType; }
    public void setOperationType(OperationType operationType) { this.operationType = operationType; }
    public OperationStatus getStatus() { return status; }
    public void setStatus(OperationStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

**Step 4: Create OperationLogRepository**

```java
package com.lakeon.repository;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLogEntity, String> {

    Page<OperationLogEntity> findByDatabaseIdAndTenantIdOrderByStartedAtDesc(
            String databaseId, String tenantId, Pageable pageable);

    Page<OperationLogEntity> findByDatabaseIdAndTenantIdAndOperationTypeOrderByStartedAtDesc(
            String databaseId, String tenantId, OperationType type, Pageable pageable);

    List<OperationLogEntity> findTop10ByTenantIdOrderByStartedAtDesc(String tenantId);
}
```

**Step 5: Run tests to verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/entity/OperationLogEntity.java \
        lakeon-api/src/main/java/com/lakeon/model/enums/OperationType.java \
        lakeon-api/src/main/java/com/lakeon/model/enums/OperationStatus.java \
        lakeon-api/src/main/java/com/lakeon/repository/OperationLogRepository.java
git commit -m "feat: add OperationLog entity, enums, and repository"
```

---

### Task 2: OperationLog Service

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/OperationLogService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/service/OperationLogServiceTest.java`

**Step 1: Write the failing test**

```java
package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperationLogService 单元测试")
class OperationLogServiceTest {

    @Mock
    private OperationLogRepository operationLogRepository;

    @InjectMocks
    private OperationLogService operationLogService;

    @Test
    @DisplayName("startOperation 应创建 IN_PROGRESS 状态的操作日志")
    void startOperation_shouldCreateInProgressLog() {
        when(operationLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OperationLogEntity log = operationLogService.startOperation(
                "db_test1", "tn_test1", "mydb", OperationType.RESUME);

        assertThat(log.getDatabaseId()).isEqualTo("db_test1");
        assertThat(log.getTenantId()).isEqualTo("tn_test1");
        assertThat(log.getDatabaseName()).isEqualTo("mydb");
        assertThat(log.getOperationType()).isEqualTo(OperationType.RESUME);
        assertThat(log.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(log.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("completeOperation 应更新状态为 SUCCESS 并计算耗时")
    void completeOperation_shouldSetSuccessAndDuration() {
        OperationLogEntity log = new OperationLogEntity();
        log.setStartedAt(java.time.Instant.now().minusMillis(500));
        log.setStatus(OperationStatus.IN_PROGRESS);

        when(operationLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        operationLogService.completeOperation(log, null);

        assertThat(log.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(log.getCompletedAt()).isNotNull();
        assertThat(log.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("completeOperation 传入错误信息应设置 FAILED 状态")
    void completeOperation_withError_shouldSetFailed() {
        OperationLogEntity log = new OperationLogEntity();
        log.setStartedAt(java.time.Instant.now());
        log.setStatus(OperationStatus.IN_PROGRESS);

        when(operationLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        operationLogService.completeOperation(log, "Timeout");

        assertThat(log.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(log.getErrorMessage()).isEqualTo("Timeout");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -pl . -Dtest=OperationLogServiceTest -q`
Expected: FAIL — `OperationLogService` not found

**Step 3: Write OperationLogService**

```java
package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OperationLogService {

    private final OperationLogRepository repository;

    public OperationLogService(OperationLogRepository repository) {
        this.repository = repository;
    }

    public OperationLogEntity startOperation(String databaseId, String tenantId,
                                              String databaseName, OperationType type) {
        OperationLogEntity log = new OperationLogEntity();
        log.setDatabaseId(databaseId);
        log.setTenantId(tenantId);
        log.setDatabaseName(databaseName);
        log.setOperationType(type);
        log.setStatus(OperationStatus.IN_PROGRESS);
        log.setStartedAt(Instant.now());
        return repository.save(log);
    }

    public void completeOperation(OperationLogEntity log, String errorMessage) {
        log.setCompletedAt(Instant.now());
        log.setDurationMs(log.getCompletedAt().toEpochMilli() - log.getStartedAt().toEpochMilli());
        if (errorMessage != null) {
            log.setStatus(OperationStatus.FAILED);
            log.setErrorMessage(errorMessage);
        } else {
            log.setStatus(OperationStatus.SUCCESS);
        }
        repository.save(log);
    }

    public Page<OperationLogEntity> getByDatabase(String databaseId, String tenantId,
                                                    OperationType type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        if (type != null) {
            return repository.findByDatabaseIdAndTenantIdAndOperationTypeOrderByStartedAtDesc(
                    databaseId, tenantId, type, pageable);
        }
        return repository.findByDatabaseIdAndTenantIdOrderByStartedAtDesc(
                databaseId, tenantId, pageable);
    }

    public List<OperationLogEntity> getRecent(String tenantId) {
        return repository.findTop10ByTenantIdOrderByStartedAtDesc(tenantId);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd lakeon-api && mvn test -pl . -Dtest=OperationLogServiceTest -q`
Expected: PASS — 3 tests

**Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/OperationLogService.java \
        lakeon-api/src/test/java/com/lakeon/service/OperationLogServiceTest.java
git commit -m "feat: add OperationLogService with start/complete tracking"
```

---

### Task 3: OperationLog Controller and DTOs

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/OperationLogResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/controller/OperationLogController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/controller/OperationLogControllerTest.java`

**Step 1: Create OperationLogResponse DTO**

```java
package com.lakeon.model.dto;

import com.lakeon.model.entity.OperationLogEntity;
import java.time.Instant;

public record OperationLogResponse(
    String id,
    String databaseId,
    String databaseName,
    String operationType,
    String status,
    Instant startedAt,
    Instant completedAt,
    Long durationMs,
    String errorMessage
) {
    public static OperationLogResponse from(OperationLogEntity entity) {
        return new OperationLogResponse(
            entity.getId(),
            entity.getDatabaseId(),
            entity.getDatabaseName(),
            entity.getOperationType().name(),
            entity.getStatus().name(),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            entity.getDurationMs(),
            entity.getErrorMessage()
        );
    }
}
```

**Step 2: Write the failing controller test**

```java
package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.service.OperationLogService;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationLogController.class)
@Import(ApiKeyFilter.class)
@DisplayName("OperationLogController 测试")
class OperationLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperationLogService operationLogService;

    @MockBean
    private TenantService tenantService;

    private TenantEntity mockTenant() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn_test1");
        tenant.setName("test-tenant");
        return tenant;
    }

    private OperationLogEntity mockLog() {
        OperationLogEntity log = new OperationLogEntity();
        log.setId("op_test1");
        log.setDatabaseId("db_test1");
        log.setDatabaseName("mydb");
        log.setTenantId("tn_test1");
        log.setOperationType(OperationType.RESUME);
        log.setStatus(OperationStatus.SUCCESS);
        log.setStartedAt(Instant.now().minusMillis(800));
        log.setCompletedAt(Instant.now());
        log.setDurationMs(800L);
        log.setCreatedAt(Instant.now());
        return log;
    }

    @Test
    @DisplayName("GET /api/v1/operations/recent 应返回最近操作列表")
    void getRecentOperations_shouldReturnList() throws Exception {
        TenantEntity tenant = mockTenant();
        when(tenantService.authenticateByApiKey("lk_testkey")).thenReturn(tenant);
        when(operationLogService.getRecent("tn_test1")).thenReturn(List.of(mockLog()));

        mockMvc.perform(get("/api/v1/operations/recent")
                .header("Authorization", "Bearer lk_testkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].databaseName").value("mydb"))
                .andExpect(jsonPath("$[0].operationType").value("RESUME"))
                .andExpect(jsonPath("$[0].durationMs").value(800));
    }
}
```

**Step 3: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -pl . -Dtest=OperationLogControllerTest -q`
Expected: FAIL — `OperationLogController` not found

**Step 4: Write OperationLogController**

```java
package com.lakeon.controller;

import com.lakeon.model.dto.OperationLogResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.OperationType;
import com.lakeon.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping("/databases/{dbId}/operations")
    public Page<OperationLogResponse> getDatabaseOperations(
            HttpServletRequest req,
            @PathVariable String dbId,
            @RequestParam(required = false) OperationType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return operationLogService.getByDatabase(dbId, tenant.getId(), type, page, size)
                .map(OperationLogResponse::from);
    }

    @GetMapping("/operations/recent")
    public List<OperationLogResponse> getRecentOperations(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return operationLogService.getRecent(tenant.getId()).stream()
                .map(OperationLogResponse::from)
                .toList();
    }
}
```

**Step 5: Run test to verify it passes**

Run: `cd lakeon-api && mvn test -pl . -Dtest=OperationLogControllerTest -q`
Expected: PASS

**Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/OperationLogResponse.java \
        lakeon-api/src/main/java/com/lakeon/controller/OperationLogController.java \
        lakeon-api/src/test/java/com/lakeon/controller/OperationLogControllerTest.java
git commit -m "feat: add operation log API endpoints"
```

---

### Task 4: Instrument DatabaseService with Operation Logging

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/service/DatabaseServiceTest.java`

**Step 1: Add OperationLogService dependency to DatabaseService**

Inject `OperationLogService` into `DatabaseService` constructor.

**Step 2: Wrap create/suspend/resume/delete methods with operation logging**

Pattern for each operation:

```java
OperationLogEntity opLog = operationLogService.startOperation(
        entity.getId(), entity.getTenantId(), entity.getName(), OperationType.RESUME);
try {
    // ... existing operation logic ...
    operationLogService.completeOperation(opLog, null);
} catch (Exception e) {
    operationLogService.completeOperation(opLog, e.getMessage());
    throw e;
}
```

Apply to: `create()`, `suspend()`, `resume()`, `delete()`, `update()`

**Step 3: Update existing tests to mock OperationLogService**

Add `@Mock OperationLogService operationLogService` to `DatabaseServiceTest` and update constructor injection.

**Step 4: Run all tests**

Run: `cd lakeon-api && mvn test -q`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java \
        lakeon-api/src/test/java/com/lakeon/service/DatabaseServiceTest.java
git commit -m "feat: instrument database operations with operation logging"
```

---

### Task 5: API Key Regeneration Endpoint

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/TenantService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/TenantController.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/service/TenantServiceTest.java`

**Step 1: Add regenerateApiKey to TenantService**

```java
public TenantEntity regenerateApiKey(String tenantId) {
    TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
    tenant.setApiKey("lk_" + generateHexString(64));
    return tenantRepository.save(tenant);
}
```

**Step 2: Add endpoint to TenantController**

```java
@PostMapping("/{tenantId}/regenerate-key")
public TenantResponse regenerateKey(HttpServletRequest req, @PathVariable String tenantId) {
    TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
    if (!tenant.getId().equals(tenantId)) {
        throw new ForbiddenException("Cannot regenerate key for another tenant");
    }
    TenantEntity updated = tenantService.regenerateApiKey(tenantId);
    return TenantResponse.from(updated);
}
```

**Step 3: Add test for regeneration**

**Step 4: Run tests**

Run: `cd lakeon-api && mvn test -q`
Expected: All PASS

**Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/TenantService.java \
        lakeon-api/src/main/java/com/lakeon/controller/TenantController.java \
        lakeon-api/src/test/java/com/lakeon/service/TenantServiceTest.java
git commit -m "feat: add API key regeneration endpoint"
```

---

## Part 2: Frontend — Console

### Task 6: Initialize TinyPro Project

**Files:**
- Create: `lakeon-console/` (entire directory via TinyPro CLI)

**Step 1: Install TinyPro CLI and create project**

```bash
cd /Users/jacky/code/lakeon
npx @opentiny/tiny-toolkit-pro init lakeon-console
# Select: Vue3, Vite, TypeScript, Cloud Console template
```

If CLI is unavailable, manually scaffold:

```bash
npm create vite@latest lakeon-console -- --template vue-ts
cd lakeon-console
npm install
npm install @opentiny/vue @opentiny/vue-icon axios pinia
```

**Step 2: Verify dev server starts**

```bash
cd lakeon-console && npm run dev
```
Expected: Dev server on http://localhost:5173

**Step 3: Add to .gitignore**

Append to `/Users/jacky/code/lakeon/.gitignore`:
```
# Frontend
lakeon-console/node_modules/
lakeon-console/dist/
```

**Step 4: Commit**

```bash
git add lakeon-console/ .gitignore
git commit -m "feat: initialize lakeon-console with Vue3 + TinyVue"
```

---

### Task 7: API Client and Auth Store

**Files:**
- Create: `lakeon-console/src/api/client.ts`
- Create: `lakeon-console/src/api/database.ts`
- Create: `lakeon-console/src/api/branch.ts`
- Create: `lakeon-console/src/api/tenant.ts`
- Create: `lakeon-console/src/api/operation.ts`
- Create: `lakeon-console/src/stores/auth.ts`

**Step 1: Create Axios client with interceptors**

`client.ts`:
```typescript
import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

client.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.apiKey) {
    config.headers.Authorization = `Bearer ${auth.apiKey}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      const auth = useAuthStore()
      auth.logout()
      router.push('/login')
    }
    return Promise.reject(error)
  }
)

export default client
```

**Step 2: Create API modules**

`database.ts`:
```typescript
import client from './client'

export interface Database {
  id: string
  name: string
  status: string
  connection_uri: string
  password?: string
  compute_size: string
  suspend_timeout: string
  storage_limit_gb: number
  storage_used_gb: number
  branches: BranchSummary[]
  created_at: string
}

export interface BranchSummary {
  id: string
  name: string
  is_default: boolean
  status: string
  compute_status: string
}

export const databaseApi = {
  list: () => client.get<Database[]>('/databases'),
  get: (id: string) => client.get<Database>(`/databases/${id}`),
  create: (data: { name: string; compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.post<Database>('/databases', data),
  update: (id: string, data: { compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.patch<Database>(`/databases/${id}`, data),
  delete: (id: string) => client.delete(`/databases/${id}`),
  suspend: (id: string) => client.post(`/databases/${id}/suspend`),
  resume: (id: string) => client.post(`/databases/${id}/resume`),
}
```

`operation.ts`:
```typescript
import client from './client'

export interface OperationLog {
  id: string
  databaseId: string
  databaseName: string
  operationType: string
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  errorMessage: string | null
}

export const operationApi = {
  getByDatabase: (dbId: string, params?: { type?: string; page?: number; size?: number }) =>
    client.get(`/databases/${dbId}/operations`, { params }),
  getRecent: () => client.get<OperationLog[]>('/operations/recent'),
}
```

`branch.ts`:
```typescript
import client from './client'

export interface Branch {
  id: string
  name: string
  parent_branch: string
  is_default: boolean
  status: string
  compute_status: string
  connection_uri: string
  created_at: string
}

export const branchApi = {
  list: (dbId: string) => client.get<Branch[]>(`/databases/${dbId}/branches`),
  create: (dbId: string, data: { name: string; start_compute?: boolean }) =>
    client.post<Branch>(`/databases/${dbId}/branches`, data),
  delete: (dbId: string, branchId: string) =>
    client.delete(`/databases/${dbId}/branches/${branchId}`),
}
```

`tenant.ts`:
```typescript
import client from './client'

export interface Tenant {
  id: string
  name: string
  api_key?: string
  created_at: string
}

export const tenantApi = {
  get: (id: string) => client.get<Tenant>(`/tenants/${id}`),
  regenerateKey: (id: string) => client.post<Tenant>(`/tenants/${id}/regenerate-key`),
}
```

**Step 3: Create auth store**

`stores/auth.ts`:
```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const apiKey = ref(localStorage.getItem('lakeon_api_key') || '')
  const tenantId = ref(localStorage.getItem('lakeon_tenant_id') || '')
  const tenantName = ref(localStorage.getItem('lakeon_tenant_name') || '')

  async function login(key: string): Promise<boolean> {
    try {
      const res = await client.get('/databases', {
        headers: { Authorization: `Bearer ${key}` },
      })
      // If we get 200, key is valid. Extract tenant info from a separate call
      apiKey.value = key
      localStorage.setItem('lakeon_api_key', key)
      return true
    } catch {
      return false
    }
  }

  function setTenant(id: string, name: string) {
    tenantId.value = id
    tenantName.value = name
    localStorage.setItem('lakeon_tenant_id', id)
    localStorage.setItem('lakeon_tenant_name', name)
  }

  function logout() {
    apiKey.value = ''
    tenantId.value = ''
    tenantName.value = ''
    localStorage.removeItem('lakeon_api_key')
    localStorage.removeItem('lakeon_tenant_id')
    localStorage.removeItem('lakeon_tenant_name')
  }

  return { apiKey, tenantId, tenantName, login, setTenant, logout }
})
```

**Step 4: Commit**

```bash
git add lakeon-console/src/api/ lakeon-console/src/stores/
git commit -m "feat: add API client, modules, and auth store"
```

---

### Task 8: Router and Layout

**Files:**
- Create: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/App.vue`
- Create: `lakeon-console/src/layouts/ConsoleLayout.vue`

**Step 1: Create router**

```typescript
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/login/LoginView.vue'), meta: { noAuth: true } },
  {
    path: '/',
    component: () => import('@/layouts/ConsoleLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/dashboard/DashboardView.vue') },
      { path: 'databases', name: 'DatabaseList', component: () => import('@/views/database/DatabaseList.vue') },
      { path: 'databases/:id', name: 'DatabaseDetail', component: () => import('@/views/database/DatabaseDetail.vue') },
      { path: 'apikey', name: 'ApiKey', component: () => import('@/views/apikey/ApiKeyView.vue') },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.noAuth && !auth.apiKey) {
    return '/login'
  }
})

export default router
```

**Step 2: Create ConsoleLayout with Huawei Cloud style**

Use TinyVue `<tiny-layout>`, `<tiny-nav-menu>` components:
- Black top bar with Lakeon branding and tenant name
- White left sidebar with menu items: 总览, 数据库, API Key
- Main content area with `<router-view>`

**Step 3: Update App.vue to use router**

**Step 4: Verify dev server works with routing**

**Step 5: Commit**

```bash
git add lakeon-console/src/router/ lakeon-console/src/layouts/ lakeon-console/src/App.vue
git commit -m "feat: add router and console layout with HuaweiCloud style"
```

---

### Task 9: Login Page

**Files:**
- Create: `lakeon-console/src/views/login/LoginView.vue`

**Step 1: Build login page**

- Centered card with Lakeon logo
- API Key input (password type, toggle visibility)
- Login button, loading state
- Error message display
- On success: redirect to /dashboard

**Step 2: Verify login flow works**

**Step 3: Commit**

```bash
git add lakeon-console/src/views/login/
git commit -m "feat: add login page with API key authentication"
```

---

### Task 10: Dashboard Page

**Files:**
- Create: `lakeon-console/src/views/dashboard/DashboardView.vue`

**Step 1: Build dashboard**

- 4 stat cards: total databases, running, suspended, error (use `<tiny-card>`)
- Recent operations table (use `<tiny-grid>`)
- Fetch data from `databaseApi.list()` and `operationApi.getRecent()`

**Step 2: Commit**

```bash
git add lakeon-console/src/views/dashboard/
git commit -m "feat: add dashboard with stats and recent operations"
```

---

### Task 11: Database List Page

**Files:**
- Create: `lakeon-console/src/views/database/DatabaseList.vue`

**Step 1: Build database list**

- Create button + search input
- Data table with columns: name, status (colored dot), compute_size, storage usage, created_at, actions
- Action buttons: suspend/resume (conditional), delete (with confirm dialog)
- Create database modal (name, compute_size, suspend_timeout, storage_limit_gb)
- Status polling after create/suspend/resume (every 2s until terminal state)

**Step 2: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseList.vue
git commit -m "feat: add database list page with CRUD operations"
```

---

### Task 12: Database Detail Page

**Files:**
- Create: `lakeon-console/src/views/database/DatabaseDetail.vue`

**Step 1: Build detail page**

- Resource summary card (name, ID, status, compute_size, connection_uri + copy button)
- 3 tabs: 基本信息, 分支, 操作历史
- Tab 1 (基本信息): connection info card, config info
- Tab 2 (分支): branch table with create/delete, uses `branchApi`
- Tab 3 (操作历史): operation log table with type filter, duration color coding
  - `durationMs` display: <1000ms green, 1000-5000ms orange, >5000ms red
  - Pagination

**Step 2: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat: add database detail page with branches and operation history"
```

---

### Task 13: API Key Management Page

**Files:**
- Create: `lakeon-console/src/views/apikey/ApiKeyView.vue`

**Step 1: Build API key page**

- Show masked key (`lk_a1b2...****`)
- Copy button (copies full key)
- Regenerate button with confirm dialog
- After regeneration: update auth store, show new key once

**Step 2: Commit**

```bash
git add lakeon-console/src/views/apikey/
git commit -m "feat: add API key management page"
```

---

## Part 3: Deployment

### Task 14: Vite Proxy Config (Dev)

**Files:**
- Modify: `lakeon-console/vite.config.ts`

**Step 1: Add dev proxy for API calls**

```typescript
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  // ... other config
})
```

**Step 2: Verify full stack works locally**

1. Start lakeon-api: `kubectl port-forward -n lakeon svc/lakeon-api 8080:8080`
2. Start console: `cd lakeon-console && npm run dev`
3. Open http://localhost:5173, login with API key, verify all pages work

**Step 3: Commit**

```bash
git add lakeon-console/vite.config.ts
git commit -m "feat: add vite dev proxy for API calls"
```

---

### Task 15: Docker and Helm Deployment

**Files:**
- Create: `lakeon-console/Dockerfile`
- Create: `lakeon-console/nginx.conf`
- Create: `deploy/helm/lakeon/templates/deployment-console.yaml`
- Create: `deploy/helm/lakeon/templates/service-console.yaml`
- Create: `deploy/helm/lakeon/templates/configmap-console.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`

**Step 1: Create nginx.conf**

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://lakeon-api:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

**Step 2: Create Dockerfile**

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

**Step 3: Create Helm templates**

`deployment-console.yaml`, `service-console.yaml`, `configmap-console.yaml`

**Step 4: Add console section to values.yaml**

```yaml
console:
  enabled: true
  image:
    repository: lakeon/lakeon-console
    tag: "0.1.0"
  replicas: 1
  port: 80
  serviceType: ClusterIP
  resources:
    requests:
      cpu: "100m"
      memory: "128Mi"
    limits:
      cpu: "200m"
      memory: "256Mi"
```

**Step 5: Build and test Docker image locally**

```bash
cd lakeon-console && docker build -t lakeon/lakeon-console:0.1.0 .
```

**Step 6: Commit**

```bash
git add lakeon-console/Dockerfile lakeon-console/nginx.conf \
        deploy/helm/lakeon/templates/deployment-console.yaml \
        deploy/helm/lakeon/templates/service-console.yaml \
        deploy/helm/lakeon/templates/configmap-console.yaml \
        deploy/helm/lakeon/values.yaml
git commit -m "feat: add console Docker build and Helm deployment"
```

---

### Task 16: Run All Backend Tests

**Step 1: Run full test suite**

```bash
cd lakeon-api && mvn clean test
```
Expected: All tests PASS (existing + new operation log tests)

**Step 2: Verify frontend builds**

```bash
cd lakeon-console && npm run build
```
Expected: Build succeeds, output in `dist/`

**Step 3: Final commit if any fixups needed**
