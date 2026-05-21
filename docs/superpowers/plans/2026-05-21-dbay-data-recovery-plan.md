# dbay 数据恢复工具集实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 dbay 加 PITR + OBS manifest 双写 + dbay-rescue Go binary，覆盖 L1-L5 故障的数据恢复能力

**Architecture:** lakeon-api 内新增 `RecoveryService`（PITR endpoint）+ `ManifestWriter`（AFTER_COMMIT 写 OBS）；lakeon-console 加 Restore 按钮；dbay-cli 加 `db pitr` 子命令；新建独立 Go binary `dbay-rescue` 供 SRE 灾难时使用。所有写权威仍是 RDS，OBS manifest 是派生副本，最终一致。

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / Maven · Vue 3 / TypeScript / Vite / Playwright · Python 3.11 / Typer / pytest · Go 1.21 / cobra / 华为云 OBS Go SDK

**Spec:** `docs/superpowers/specs/2026-05-21-dbay-data-recovery-design.md`

**Out-of-scope:** L6 (OBS region 挂)、L7 (勒索/恶意删) —— 显式不做

---

## File Structure

### lakeon-api 新文件
```
lakeon-api/src/main/java/com/lakeon/
├── service/
│   ├── RecoveryService.java                    ← PITR 业务逻辑
│   ├── ManifestWriter.java                     ← AFTER_COMMIT hook
│   └── ManifestRetryScheduler.java             ← retry queue 处理
├── controller/
│   └── RecoveryController.java                 ← /pitr endpoints
├── neon/
│   └── NeonApiClient.java                      (修改：加 getLsnByTimestamp、createBranch)
├── obs/
│   ├── ObsClient.java                          ← 新增 PUT/GET/LIST 封装
│   └── ManifestObjects.java                    ← JSON schema POJO
├── model/
│   ├── event/
│   │   ├── TenantChangedEvent.java
│   │   ├── DatabaseChangedEvent.java
│   │   └── BranchChangedEvent.java
│   └── dto/
│       ├── PitrRequest.java
│       ├── PitrResponse.java
│       └── PitrWindow.java
```

### lakeon-api 修改文件
```
lakeon-api/src/main/java/com/lakeon/service/
├── TenantService.java     (发 TenantChangedEvent)
├── DatabaseService.java   (发 DatabaseChangedEvent)
└── BranchService.java     (发 BranchChangedEvent)

lakeon-api/src/main/resources/application.yml
└── 新增 lakeon.manifest.* 配置段
```

### lakeon-console 新/改文件
```
lakeon-console/src/
├── views/database/DatabaseDetail.vue                       (改：加 Restore 按钮)
├── components/database/RestoreDialog.vue                   ← 新
└── api/recovery.ts                                         ← 新

lakeon-console/e2e/
└── restore.spec.ts                                         ← 新
```

### dbay-cli 新/改文件
```
dbay-cli/dbay_cli/commands/db.py                            (改：加 pitr 子命令)
dbay-cli/tests/test_db_pitr.py                              ← 新
```

### dbay-rescue 全新 Go 项目
```
dbay-rescue/
├── go.mod
├── go.sum
├── cmd/dbay-rescue/
│   └── main.go                                             ← cobra root + 子命令注册
├── internal/
│   ├── obs/
│   │   ├── client.go                                       ← OBS PUT/GET/LIST
│   │   └── manifest.go                                     ← manifest schema
│   ├── neon/
│   │   └── pageserver.go                                   ← Pageserver HTTP client
│   ├── rds/
│   │   └── client.go                                       ← pgx 直连
│   ├── creds/
│   │   └── loader.go                                       ← ~/.dbay/rescue-credentials.yaml
│   └── commands/
│       ├── list_tenants.go
│       ├── owner_lookup.go
│       ├── pitr.go
│       ├── rebuild_metadata.go
│       └── emergency_mount.go
└── test/
    ├── e2e_test.go
    └── fixtures/
```

### 测试 / 文档
```
tests/e2e/
├── test_pitr.py                                            ← API E2E
├── test_manifest_writer.py                                 ← API E2E
└── test_dbay_rescue.py                                     ← rescue E2E

docs/sre/
└── runbook-dbay-rescue.md                                  ← 灾难恢复 runbook
```

---

## Phase 1.1: PITR API (lakeon-api)

### Task 1: NeonApiClient 加 `getLsnByTimestamp` 方法

Neon Pageserver mgmt API 提供 `GET /v1/tenant/{tenant_id}/timeline/{timeline_id}/get_lsn_by_timestamp?timestamp=<RFC3339>`，返回 `{"lsn": "0/A1B2C3D4"}`。

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java`
- Test: `lakeon-api/src/test/java/com/lakeon/neon/NeonApiClientTest.java`

- [ ] **Step 1: 写失败测试**

```java
// NeonApiClientTest.java
@ExtendWith(MockitoExtension.class)
class NeonApiClientTest {
    @Test
    void getLsnByTimestamp_callsCorrectEndpoint(@Mock RestTemplate restTemplate) {
        NeonApiClient client = new NeonApiClient(restTemplate, "http://pageserver:9898", "tok");
        when(restTemplate.exchange(
                eq("http://pageserver:9898/v1/tenant/tn1/timeline/tl1/get_lsn_by_timestamp?timestamp=2026-05-21T14:30:00Z"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(LsnByTimestampResponse.class)))
            .thenReturn(ResponseEntity.ok(new LsnByTimestampResponse("0/A1B2C3D4")));

        String lsn = client.getLsnByTimestamp("tn1", "tl1", Instant.parse("2026-05-21T14:30:00Z"));

        assertThat(lsn).isEqualTo("0/A1B2C3D4");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=NeonApiClientTest#getLsnByTimestamp_callsCorrectEndpoint test
```
Expected: FAIL — method not found

- [ ] **Step 3: 实现方法**

```java
// NeonApiClient.java (新增)
public record LsnByTimestampResponse(String lsn) {}

public String getLsnByTimestamp(String tenantId, String timelineId, Instant timestamp) {
    String iso = DateTimeFormatter.ISO_INSTANT.format(timestamp);
    String url = String.format("%s/v1/tenant/%s/timeline/%s/get_lsn_by_timestamp?timestamp=%s",
        pageserverUrl, tenantId, timelineId, iso);
    HttpHeaders headers = authHeaders();
    ResponseEntity<LsnByTimestampResponse> resp = restTemplate.exchange(
        url, HttpMethod.GET, new HttpEntity<>(headers), LsnByTimestampResponse.class);
    if (resp.getBody() == null) throw new IllegalStateException("empty response from pageserver");
    return resp.getBody().lsn();
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=NeonApiClientTest#getLsnByTimestamp_callsCorrectEndpoint test
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java \
        lakeon-api/src/test/java/com/lakeon/neon/NeonApiClientTest.java
git commit -m "feat(neon): NeonApiClient 支持 getLsnByTimestamp"
```

---

### Task 2: NeonApiClient 加 `createBranch` 方法

Neon `POST /v1/tenant/{tenant_id}/timeline` 接受 `{ancestor_timeline_id, ancestor_start_lsn, new_timeline_id}` 创建分支。

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java`
- Test: `lakeon-api/src/test/java/com/lakeon/neon/NeonApiClientTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void createBranch_postsCorrectBody(@Mock RestTemplate restTemplate) {
    NeonApiClient client = new NeonApiClient(restTemplate, "http://pageserver:9898", "tok");
    CreateBranchRequest req = new CreateBranchRequest("tl_parent", "0/AB12", "tl_new");

    ArgumentCaptor<HttpEntity> entityCap = ArgumentCaptor.forClass(HttpEntity.class);
    when(restTemplate.exchange(
            eq("http://pageserver:9898/v1/tenant/tn1/timeline"),
            eq(HttpMethod.POST),
            entityCap.capture(),
            eq(CreateBranchResponse.class)))
        .thenReturn(ResponseEntity.ok(new CreateBranchResponse("tl_new", "0/AB12")));

    CreateBranchResponse resp = client.createBranch("tn1", req);

    assertThat(resp.timelineId()).isEqualTo("tl_new");
    Map body = (Map) entityCap.getValue().getBody();
    assertThat(body).containsEntry("ancestor_timeline_id", "tl_parent");
    assertThat(body).containsEntry("ancestor_start_lsn", "0/AB12");
    assertThat(body).containsEntry("new_timeline_id", "tl_new");
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=NeonApiClientTest#createBranch_postsCorrectBody test
```
Expected: FAIL

- [ ] **Step 3: 实现方法**

```java
// NeonApiClient.java (新增)
public record CreateBranchRequest(String ancestorTimelineId, String ancestorStartLsn, String newTimelineId) {}
public record CreateBranchResponse(String timelineId, String lsn) {}

public CreateBranchResponse createBranch(String tenantId, CreateBranchRequest request) {
    String url = String.format("%s/v1/tenant/%s/timeline", pageserverUrl, tenantId);
    Map<String, String> body = Map.of(
        "ancestor_timeline_id", request.ancestorTimelineId(),
        "ancestor_start_lsn", request.ancestorStartLsn(),
        "new_timeline_id", request.newTimelineId()
    );
    HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, authHeaders());
    ResponseEntity<CreateBranchResponse> resp = restTemplate.exchange(
        url, HttpMethod.POST, entity, CreateBranchResponse.class);
    if (resp.getBody() == null) throw new IllegalStateException("empty createBranch response");
    return resp.getBody();
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=NeonApiClientTest#createBranch_postsCorrectBody test
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java \
        lakeon-api/src/test/java/com/lakeon/neon/NeonApiClientTest.java
git commit -m "feat(neon): NeonApiClient 支持 createBranch (Neon timeline branch API)"
```

---

### Task 3: 定义 PITR DTO

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/PitrRequest.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/PitrResponse.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/dto/PitrWindow.java`

- [ ] **Step 1: 写 PitrRequest**

```java
// PitrRequest.java
package com.lakeon.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record PitrRequest(
    @NotNull Instant targetTime,
    String newDbName  // 可选，为 null 时自动生成
) {}
```

- [ ] **Step 2: 写 PitrResponse**

```java
// PitrResponse.java
package com.lakeon.model.dto;

public record PitrResponse(
    String newDbId,
    String branchId,
    String lsn,
    String computeEndpoint,
    String status  // "ready" | "pending"
) {}
```

- [ ] **Step 3: 写 PitrWindow**

```java
// PitrWindow.java
package com.lakeon.model.dto;

import java.time.Instant;

public record PitrWindow(
    Instant earliest,
    Instant latest,
    String earliestLsn,
    String latestLsn
) {}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/Pitr*.java
git commit -m "feat(model): PITR request/response/window DTO"
```

---

### Task 4: RecoveryService.pitr() 单元测试 + 实现

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/RecoveryService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/service/RecoveryServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// RecoveryServiceTest.java
package com.lakeon.service;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {
    @Mock DatabaseRepository databaseRepository;
    @Mock NeonApiClient neonApiClient;
    @Mock DatabaseService databaseService;

    @Test
    void pitr_createsNewBranchAndDatabase() {
        DatabaseEntity src = new DatabaseEntity();
        src.setId("db_old");
        src.setName("mydb");
        src.setTenantId("tn1");
        src.setTimelineId("tl_old");
        when(databaseRepository.findById("db_old")).thenReturn(Optional.of(src));
        when(neonApiClient.getLsnByTimestamp(eq("tn1"), eq("tl_old"), any()))
            .thenReturn("0/AB12");
        when(neonApiClient.createBranch(eq("tn1"), any()))
            .thenReturn(new NeonApiClient.CreateBranchResponse("tl_new", "0/AB12"));
        when(databaseService.registerRecoveredDatabase(eq("tn1"), eq("tl_new"), eq("mydb_restored_20260521")))
            .thenReturn(new DatabaseEntity() {{ setId("db_new"); setName("mydb_restored_20260521"); }});

        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        PitrResponse resp = svc.pitr("db_old",
            new PitrRequest(Instant.parse("2026-05-21T14:30:00Z"), "mydb_restored_20260521"));

        assertThat(resp.newDbId()).isEqualTo("db_new");
        assertThat(resp.lsn()).isEqualTo("0/AB12");
        assertThat(resp.status()).isEqualTo("ready");
        verify(neonApiClient).createBranch(eq("tn1"),
            argThat(req -> req.ancestorTimelineId().equals("tl_old")
                        && req.ancestorStartLsn().equals("0/AB12")));
    }

    @Test
    void pitr_throwsWhenDatabaseNotFound() {
        when(databaseRepository.findById("nope")).thenReturn(Optional.empty());
        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        assertThatThrownBy(() -> svc.pitr("nope",
                new PitrRequest(Instant.now(), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database not found");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=RecoveryServiceTest test
```
Expected: FAIL — RecoveryService 不存在

- [ ] **Step 3: 实现 RecoveryService**

```java
// RecoveryService.java
package com.lakeon.service;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class RecoveryService {
    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final DatabaseService databaseService;

    public RecoveryService(DatabaseRepository databaseRepository,
                           NeonApiClient neonApiClient,
                           DatabaseService databaseService) {
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
        this.databaseService = databaseService;
    }

    public PitrResponse pitr(String dbId, PitrRequest request) {
        DatabaseEntity src = databaseRepository.findById(dbId)
            .orElseThrow(() -> new IllegalArgumentException("database not found: " + dbId));

        String lsn = neonApiClient.getLsnByTimestamp(
            src.getTenantId(), src.getTimelineId(), request.targetTime());

        String newTimelineId = "tl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        NeonApiClient.CreateBranchResponse branch = neonApiClient.createBranch(
            src.getTenantId(),
            new NeonApiClient.CreateBranchRequest(src.getTimelineId(), lsn, newTimelineId)
        );

        String newDbName = request.newDbName() != null
            ? request.newDbName()
            : src.getName() + "_restored_" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());

        DatabaseEntity recovered = databaseService.registerRecoveredDatabase(
            src.getTenantId(), branch.timelineId(), newDbName);

        return new PitrResponse(
            recovered.getId(),
            branch.timelineId(),
            lsn,
            null,  // computeEndpoint 由 caller 异步起 compute 后填
            "ready"
        );
    }
}
```

- [ ] **Step 4: 在 DatabaseService 加 `registerRecoveredDatabase` 方法**

```java
// DatabaseService.java (新增方法)
public DatabaseEntity registerRecoveredDatabase(String tenantId, String timelineId, String name) {
    DatabaseEntity db = new DatabaseEntity();
    db.setId("db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    db.setTenantId(tenantId);
    db.setTimelineId(timelineId);
    db.setName(name);
    db.setStatus("ACTIVE");
    db.setRecoveredFromPitr(true);   // 新增字段标记
    db.setCreatedAt(Instant.now());
    return databaseRepository.save(db);
}
```

DatabaseEntity 需加字段：
```java
// DatabaseEntity.java (加字段)
@Column(name = "recovered_from_pitr")
private boolean recoveredFromPitr;
```

加 Flyway migration:
```sql
-- lakeon-api/src/main/resources/db/migration/V42__add_recovered_from_pitr.sql
ALTER TABLE databases ADD COLUMN recovered_from_pitr BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=RecoveryServiceTest test
```
Expected: PASS (2/2)

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/RecoveryService.java \
        lakeon-api/src/test/java/com/lakeon/service/RecoveryServiceTest.java \
        lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java \
        lakeon-api/src/main/java/com/lakeon/model/entity/DatabaseEntity.java \
        lakeon-api/src/main/resources/db/migration/V42__add_recovered_from_pitr.sql
git commit -m "feat(recovery): RecoveryService + PITR 核心逻辑 (new-branch 语义)"
```

---

### Task 5: RecoveryService.getPitrWindow() 方法

返回该 database 可恢复的时间窗。最早 = created_at；最晚 = Neon Pageserver 当前 head LSN 对应的 timestamp。

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/RecoveryService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/service/RecoveryServiceTest.java`

- [ ] **Step 1: 给 NeonApiClient 加 getTimelineInfo**

```java
// NeonApiClient.java (新增)
public record TimelineInfo(
    String timelineId,
    String lastRecordLsn,
    String diskConsistentLsn,
    Instant latestGcCutoff
) {}

public TimelineInfo getTimelineInfo(String tenantId, String timelineId) {
    String url = String.format("%s/v1/tenant/%s/timeline/%s",
        pageserverUrl, tenantId, timelineId);
    ResponseEntity<TimelineInfo> resp = restTemplate.exchange(
        url, HttpMethod.GET, new HttpEntity<>(authHeaders()), TimelineInfo.class);
    if (resp.getBody() == null) throw new IllegalStateException("empty getTimelineInfo response");
    return resp.getBody();
}
```

- [ ] **Step 2: 写 RecoveryService.getPitrWindow() 测试**

```java
@Test
void getPitrWindow_returnsCreatedAtAndHeadLsnTimestamp() {
    DatabaseEntity db = new DatabaseEntity();
    db.setId("db1");
    db.setTenantId("tn1");
    db.setTimelineId("tl1");
    db.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
    when(databaseRepository.findById("db1")).thenReturn(Optional.of(db));
    when(neonApiClient.getTimelineInfo("tn1", "tl1"))
        .thenReturn(new NeonApiClient.TimelineInfo("tl1", "0/FFFF", "0/FFFE",
            Instant.parse("2026-03-25T00:00:00Z")));

    RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
    PitrWindow window = svc.getPitrWindow("db1");

    // 最早 = max(db.createdAt, timeline.latestGcCutoff) — GC cutoff 之前的 LSN 已被回收
    assertThat(window.earliest()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    assertThat(window.latestLsn()).isEqualTo("0/FFFF");
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=RecoveryServiceTest#getPitrWindow_returnsCreatedAtAndHeadLsnTimestamp test
```
Expected: FAIL

- [ ] **Step 4: 实现 getPitrWindow**

```java
// RecoveryService.java (新增方法)
public PitrWindow getPitrWindow(String dbId) {
    DatabaseEntity db = databaseRepository.findById(dbId)
        .orElseThrow(() -> new IllegalArgumentException("database not found: " + dbId));
    NeonApiClient.TimelineInfo info = neonApiClient.getTimelineInfo(
        db.getTenantId(), db.getTimelineId());

    Instant earliest = db.getCreatedAt().isAfter(info.latestGcCutoff())
        ? db.getCreatedAt() : info.latestGcCutoff();
    return new PitrWindow(earliest, Instant.now(), null, info.lastRecordLsn());
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=RecoveryServiceTest test
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/neon/NeonApiClient.java \
        lakeon-api/src/main/java/com/lakeon/service/RecoveryService.java \
        lakeon-api/src/test/java/com/lakeon/service/RecoveryServiceTest.java
git commit -m "feat(recovery): getPitrWindow 返回 [createdAt|gcCutoff, now]"
```

---

### Task 6: RecoveryController REST endpoints

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/RecoveryController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/controller/RecoveryControllerTest.java`

- [ ] **Step 1: 写 MockMvc 测试**

```java
// RecoveryControllerTest.java
package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.dto.*;
import com.lakeon.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecoveryController.class)
class RecoveryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockBean RecoveryService recoveryService;

    @Test
    void pitr_returns200WithResponseBody() throws Exception {
        when(recoveryService.pitr(eq("db1"), any()))
            .thenReturn(new PitrResponse("db_new", "tl_new", "0/AB12", null, "ready"));

        mvc.perform(post("/api/v1/databases/db1/pitr")
                .contentType("application/json")
                .content("""
                    {"targetTime": "2026-05-21T14:30:00Z", "newDbName": "restored"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newDbId").value("db_new"))
            .andExpect(jsonPath("$.lsn").value("0/AB12"));
    }

    @Test
    void pitrWindow_returns200() throws Exception {
        when(recoveryService.getPitrWindow("db1"))
            .thenReturn(new PitrWindow(
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-05-21T15:00:00Z"),
                null, "0/FFFF"));
        mvc.perform(get("/api/v1/databases/db1/pitr-window"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestLsn").value("0/FFFF"));
    }

    @Test
    void pitr_returns400OnTargetTimeOutOfWindow() throws Exception {
        when(recoveryService.pitr(eq("db1"), any()))
            .thenThrow(new IllegalArgumentException("target_time out of window"));
        mvc.perform(post("/api/v1/databases/db1/pitr")
                .contentType("application/json")
                .content("""{"targetTime": "2020-01-01T00:00:00Z"}"""))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=RecoveryControllerTest test
```
Expected: FAIL

- [ ] **Step 3: 实现 Controller**

```java
// RecoveryController.java
package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.service.RecoveryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/databases")
public class RecoveryController {
    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{dbId}/pitr")
    public ResponseEntity<PitrResponse> pitr(@PathVariable String dbId,
                                             @Valid @RequestBody PitrRequest request) {
        return ResponseEntity.ok(recoveryService.pitr(dbId, request));
    }

    @GetMapping("/{dbId}/pitr-window")
    public ResponseEntity<PitrWindow> pitrWindow(@PathVariable String dbId) {
        return ResponseEntity.ok(recoveryService.getPitrWindow(dbId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=RecoveryControllerTest test
```
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/RecoveryController.java \
        lakeon-api/src/test/java/com/lakeon/controller/RecoveryControllerTest.java
git commit -m "feat(api): POST /databases/{id}/pitr + GET /pitr-window endpoints"
```

---

### Task 7: PITR E2E pytest

**Files:**
- Create: `tests/e2e/test_pitr.py`

- [ ] **Step 1: 写 E2E 测试**

```python
# tests/e2e/test_pitr.py
import time
from datetime import datetime, timezone, timedelta
import pytest
from conftest import DbayClient, run_psql


@pytest.fixture
def fresh_db(tenant_client: DbayClient):
    resp = tenant_client.post("/databases", json={"name": "pitr_test_db"})
    db = resp.json()
    yield db
    tenant_client.delete(f"/databases/{db['id']}")


def test_pitr_recovers_to_intermediate_state(tenant_client: DbayClient, fresh_db):
    db_id = fresh_db["id"]
    conn = fresh_db["compute_endpoint"]

    # 1. 写第一批数据
    run_psql(conn, "CREATE TABLE t (id int, val text); INSERT INTO t VALUES (1, 'first');")
    time.sleep(2)  # 让 LSN 推进
    midpoint = datetime.now(timezone.utc)
    time.sleep(2)

    # 2. 写第二批数据
    run_psql(conn, "INSERT INTO t VALUES (2, 'second');")
    time.sleep(2)

    # 3. PITR 恢复到 midpoint
    resp = tenant_client.post(f"/databases/{db_id}/pitr", json={
        "targetTime": midpoint.isoformat(),
        "newDbName": "pitr_test_db_restored"
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ready"
    new_db_id = body["newDbId"]

    # 4. 等新 compute 起来
    new_db = tenant_client.get(f"/databases/{new_db_id}").json()
    for _ in range(30):
        if new_db.get("compute_endpoint"):
            break
        time.sleep(2)
        new_db = tenant_client.get(f"/databases/{new_db_id}").json()
    assert new_db.get("compute_endpoint"), "new compute did not become ready"

    # 5. 断言新数据库只看到第一批数据
    rows = run_psql(new_db["compute_endpoint"], "SELECT val FROM t ORDER BY id;")
    assert "first" in rows
    assert "second" not in rows, "PITR 应该只恢复到 midpoint 之前的数据"

    # 6. 清理
    tenant_client.delete(f"/databases/{new_db_id}")


def test_pitr_rejects_out_of_window(tenant_client: DbayClient, fresh_db):
    resp = tenant_client.post(f"/databases/{fresh_db['id']}/pitr", json={
        "targetTime": "2020-01-01T00:00:00Z"
    })
    assert resp.status_code == 400


def test_pitr_window_endpoint(tenant_client: DbayClient, fresh_db):
    resp = tenant_client.get(f"/databases/{fresh_db['id']}/pitr-window")
    assert resp.status_code == 200
    window = resp.json()
    assert "earliest" in window
    assert "latestLsn" in window
```

- [ ] **Step 2: 跑 E2E**

```bash
python3 -m pytest tests/e2e/test_pitr.py -v
```
Expected: PASS (3/3)。若 FAIL，必须修复（项目规定不能 SKIPPED 蒙混）。

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_pitr.py
git commit -m "test(e2e): PITR API 完整业务流程测试"
```

---

## Phase 1.2: Console UI (lakeon-console)

### Task 8: API client 加 recovery 方法

**Files:**
- Create: `lakeon-console/src/api/recovery.ts`

- [ ] **Step 1: 写文件**

```typescript
// lakeon-console/src/api/recovery.ts
import { apiClient } from './client'

export interface PitrWindow {
  earliest: string
  latest: string
  earliestLsn: string | null
  latestLsn: string
}

export interface PitrRequest {
  targetTime: string
  newDbName?: string
}

export interface PitrResponse {
  newDbId: string
  branchId: string
  lsn: string
  computeEndpoint: string | null
  status: 'ready' | 'pending'
}

export async function getPitrWindow(dbId: string): Promise<PitrWindow> {
  const { data } = await apiClient.get(`/databases/${dbId}/pitr-window`)
  return data
}

export async function pitr(dbId: string, req: PitrRequest): Promise<PitrResponse> {
  const { data } = await apiClient.post(`/databases/${dbId}/pitr`, req)
  return data
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/recovery.ts
git commit -m "feat(console): recovery API client 封装"
```

---

### Task 9: RestoreDialog 组件

**Files:**
- Create: `lakeon-console/src/components/database/RestoreDialog.vue`

- [ ] **Step 1: 写组件**

```vue
<!-- lakeon-console/src/components/database/RestoreDialog.vue -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import { getPitrWindow, pitr, type PitrWindow } from '@/api/recovery'

const props = defineProps<{
  dbId: string
  dbName: string
  visible: boolean
}>()
const emit = defineEmits<{
  close: []
  restored: [newDbId: string]
}>()

const window = ref<PitrWindow | null>(null)
const targetTime = ref<string>('')
const newDbName = ref<string>('')
const loading = ref(false)
const error = ref<string | null>(null)

watch(() => props.visible, async (v) => {
  if (!v) return
  error.value = null
  try {
    window.value = await getPitrWindow(props.dbId)
    targetTime.value = window.value.latest
    newDbName.value = `${props.dbName}_restored_${Date.now()}`
  } catch (e: any) {
    error.value = e.message ?? 'failed to fetch PITR window'
  }
})

async function submit() {
  loading.value = true
  error.value = null
  try {
    const resp = await pitr(props.dbId, {
      targetTime: targetTime.value,
      newDbName: newDbName.value
    })
    emit('restored', resp.newDbId)
    emit('close')
  } catch (e: any) {
    error.value = e.response?.data ?? e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="visible" class="restore-dialog-mask" data-testid="restore-dialog">
    <div class="restore-dialog">
      <h3>Restore "{{ dbName }}" to a point in time</h3>
      <p v-if="window" class="window-info">
        Available window: {{ window.earliest }} → {{ window.latest }}
      </p>
      <label>
        Target time (UTC):
        <input v-model="targetTime" type="datetime-local" data-testid="target-time"
               :min="window?.earliest" :max="window?.latest" />
      </label>
      <label>
        New database name:
        <input v-model="newDbName" type="text" data-testid="new-db-name" />
      </label>
      <p v-if="error" class="error" data-testid="error">{{ error }}</p>
      <div class="actions">
        <button @click="emit('close')" :disabled="loading">Cancel</button>
        <button @click="submit" :disabled="loading || !targetTime || !newDbName"
                data-testid="confirm-restore">
          {{ loading ? 'Restoring…' : 'Restore' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.restore-dialog-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.45);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
}
.restore-dialog {
  background: #fff8ef; padding: 24px; border-radius: 8px; max-width: 480px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.2);
}
.restore-dialog label { display: block; margin: 12px 0; }
.restore-dialog input { width: 100%; padding: 8px; border: 1px solid #e0d4c4; border-radius: 4px; }
.window-info { color: #6b5a44; font-size: 13px; }
.error { color: #c0392b; }
.actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
button { padding: 8px 16px; border-radius: 4px; border: 1px solid #d4b896; background: #f4e0c4; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/components/database/RestoreDialog.vue
git commit -m "feat(console): RestoreDialog 组件 (时间窗 + 表单 + 提交)"
```

---

### Task 10: DatabaseDetail.vue 集成 Restore 按钮

**Files:**
- Modify: `lakeon-console/src/views/database/DatabaseDetail.vue`

- [ ] **Step 1: 在 DatabaseDetail.vue 加按钮和 dialog**

Read 现有文件结构后，在主操作区（如 header / toolbar）加按钮：

```vue
<!-- 在 <script setup> 区域添加 -->
<script setup lang="ts">
// ... 现有 import
import RestoreDialog from '@/components/database/RestoreDialog.vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const restoreDialogVisible = ref(false)
const router = useRouter()

function onRestored(newDbId: string) {
  router.push(`/databases/${newDbId}`)
}
</script>

<!-- 在 <template> 操作按钮区域添加 -->
<button class="action-btn" data-testid="open-restore-dialog"
        @click="restoreDialogVisible = true">
  Restore to time
</button>

<RestoreDialog v-if="database"
               :db-id="database.id"
               :db-name="database.name"
               :visible="restoreDialogVisible"
               @close="restoreDialogVisible = false"
               @restored="onRestored" />
```

- [ ] **Step 2: 本地验证**

```bash
cd lakeon-console && npm run dev
# 浏览器打开 localhost:5173 → 进入某个数据库详情 → 看到 Restore 按钮
```

- [ ] **Step 3: Type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/database/DatabaseDetail.vue
git commit -m "feat(console): DatabaseDetail 加 Restore to time 按钮"
```

---

### Task 11: Playwright E2E

**Files:**
- Create: `lakeon-console/e2e/restore.spec.ts`

- [ ] **Step 1: 写 E2E**

```typescript
// lakeon-console/e2e/restore.spec.ts
import { test, expect } from '@playwright/test'

test.describe('PITR Restore Flow', () => {
  test('user can restore a database to a past time', async ({ page, request }) => {
    // 1. 准备：调 API 创建测试 db，写一些数据
    const tenant = await createTestTenant(request)
    const db = await createDatabaseWithData(request, tenant)

    await page.goto(`/databases/${db.id}`)
    await expect(page.getByText(db.name)).toBeVisible()

    // 2. 点 Restore 按钮
    await page.getByTestId('open-restore-dialog').click()
    await expect(page.getByTestId('restore-dialog')).toBeVisible()

    // 3. 填表单
    await page.getByTestId('new-db-name').fill(`${db.name}_restored_playwright`)
    // target-time 用 dialog 打开时填的默认值

    // 4. 提交
    await page.getByTestId('confirm-restore').click()

    // 5. 应该跳转到新数据库详情页
    await expect(page).toHaveURL(/\/databases\/db_[a-z0-9]+/, { timeout: 30000 })
    await expect(page.getByText(`${db.name}_restored_playwright`)).toBeVisible()

    // 6. 清理
    await cleanupTenant(request, tenant)
  })

  test('shows error when target_time is outside window', async ({ page, request }) => {
    const tenant = await createTestTenant(request)
    const db = await createDatabaseWithData(request, tenant)

    await page.goto(`/databases/${db.id}`)
    await page.getByTestId('open-restore-dialog').click()

    // 手工填一个无效时间
    await page.getByTestId('target-time').fill('2020-01-01T00:00')
    await page.getByTestId('new-db-name').fill('should_fail')
    await page.getByTestId('confirm-restore').click()

    await expect(page.getByTestId('error')).toBeVisible()
    await cleanupTenant(request, tenant)
  })
})

// helpers
const ADMIN_TOKEN = process.env.LAKEON_ADMIN_TOKEN ?? 'lakeon-sre-2026'
const API_BASE = process.env.LAKEON_API_BASE ?? 'http://localhost:8080/api/v1'

async function createTestTenant(request: any) {
  const r = await request.post(`${API_BASE}/tenants`, {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}` },
    data: { name: `pw_test_${Date.now()}`, ownerEmail: 'pw-test@local.test' }
  })
  return await r.json()
}

async function createDatabaseWithData(request: any, tenant: any) {
  const r = await request.post(`${API_BASE}/databases`, {
    headers: { Authorization: `Bearer ${tenant.apiKey}` },
    data: { name: `pw_db_${Date.now()}` }
  })
  const db = await r.json()
  // 等 compute_endpoint 就绪 + 写测试数据
  // 实际项目里可能需要 polling，简化用一个 sleep
  await new Promise(r => setTimeout(r, 3000))
  return db
}

async function cleanupTenant(request: any, tenant: any) {
  await request.delete(`${API_BASE}/tenants/${tenant.id}`, {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}` }
  })
}
```

- [ ] **Step 2: 跑 E2E**

```bash
cd lakeon-console && npm run test:e2e -- restore.spec.ts
```
Expected: PASS (2/2)

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/e2e/restore.spec.ts
git commit -m "test(e2e): Restore to time UI 流程 Playwright 测试"
```

---

## Phase 1.3: dbay-cli (Python Typer)

### Task 12: dbay db pitr 子命令

**Files:**
- Modify: `dbay-cli/dbay_cli/commands/db.py`
- Create: `dbay-cli/tests/test_db_pitr.py`

- [ ] **Step 1: 写失败测试**

```python
# dbay-cli/tests/test_db_pitr.py
from typer.testing import CliRunner
from unittest.mock import MagicMock, patch
from dbay_cli.main import app

runner = CliRunner()


@patch('dbay_cli.commands.db.DbayClient')
def test_pitr_calls_api_with_iso_timestamp(MockClient):
    client = MockClient.return_value
    client.post.return_value = MagicMock(
        status_code=200,
        json=lambda: {"newDbId": "db_new", "lsn": "0/AB12", "status": "ready"}
    )

    result = runner.invoke(app, [
        "db", "pitr", "db_old",
        "--time", "2026-05-21T14:30:00Z",
        "--new-name", "restored"
    ])

    assert result.exit_code == 0
    assert "db_new" in result.stdout
    client.post.assert_called_once_with(
        "/databases/db_old/pitr",
        json={"targetTime": "2026-05-21T14:30:00Z", "newDbName": "restored"}
    )


@patch('dbay_cli.commands.db.DbayClient')
def test_pitr_supports_relative_time(MockClient):
    client = MockClient.return_value
    client.post.return_value = MagicMock(
        status_code=200,
        json=lambda: {"newDbId": "db_new", "lsn": "0/AB12", "status": "ready"}
    )

    result = runner.invoke(app, ["db", "pitr", "db_old", "--time", "5min ago"])
    assert result.exit_code == 0
    # 验证传给 API 的是 ISO 时间戳
    call = client.post.call_args
    assert "T" in call.kwargs["json"]["targetTime"]
    assert call.kwargs["json"]["targetTime"].endswith("Z")
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd dbay-cli && python -m pytest tests/test_db_pitr.py -v
```
Expected: FAIL — pitr 子命令不存在

- [ ] **Step 3: 实现子命令**

```python
# dbay-cli/dbay_cli/commands/db.py (在现有 app 上加子命令)
import re
from datetime import datetime, timezone, timedelta
import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import load_config

# 复用已有的 app = typer.Typer()


def _parse_time(s: str) -> str:
    """Parse '5min ago' / ISO 8601 → ISO 8601 UTC."""
    s = s.strip()
    m = re.fullmatch(r"(\d+)\s*(min|minutes?|h|hours?|d|days?|s|seconds?)\s*ago", s, re.I)
    if m:
        n = int(m.group(1))
        unit = m.group(2).lower()
        if unit.startswith("s"): delta = timedelta(seconds=n)
        elif unit.startswith("min"): delta = timedelta(minutes=n)
        elif unit.startswith("h"): delta = timedelta(hours=n)
        elif unit.startswith("d"): delta = timedelta(days=n)
        else: raise typer.BadParameter(f"unknown time unit: {unit}")
        return (datetime.now(timezone.utc) - delta).strftime("%Y-%m-%dT%H:%M:%SZ")
    # 当作 ISO 8601
    return datetime.fromisoformat(s.replace("Z", "+00:00")) \
        .astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@app.command()
def pitr(
    db_id: str = typer.Argument(..., help="数据库 ID"),
    time: str = typer.Option(..., "--time", "-t",
        help="目标时间：ISO 8601 或 '5min ago' 风格"),
    new_name: str = typer.Option(None, "--new-name",
        help="新数据库名（默认自动生成）"),
):
    """从历史时间点恢复数据库到一个新的 branch。原数据库不变。"""
    cfg = load_config()
    client = DbayClient(endpoint=cfg.endpoint, api_key=cfg.api_key)
    iso_time = _parse_time(time)
    payload = {"targetTime": iso_time}
    if new_name:
        payload["newDbName"] = new_name
    resp = client.post(f"/databases/{db_id}/pitr", json=payload)
    if resp.status_code != 200:
        typer.echo(f"PITR failed: {resp.status_code} {resp.text}", err=True)
        raise typer.Exit(1)
    body = resp.json()
    typer.echo(f"✓ Restored to new database: {body['newDbId']}")
    typer.echo(f"  LSN: {body['lsn']}")
    typer.echo(f"  Status: {body['status']}")
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd dbay-cli && python -m pytest tests/test_db_pitr.py -v
```
Expected: PASS (2/2)

- [ ] **Step 5: Commit**

```bash
git add dbay-cli/dbay_cli/commands/db.py dbay-cli/tests/test_db_pitr.py
git commit -m "feat(cli): dbay db pitr 子命令 (支持 ISO 和 '5min ago' 时间)"
```

---

## Phase 1.4: ManifestWriter (lakeon-api)

### Task 13: OBS Client (PUT/GET/LIST 封装)

现有 `ObsStsService` 只管 STS 凭据。需要新增直接 PUT/GET/LIST 的客户端。

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/obs/ObsClient.java`
- Create: `lakeon-api/src/test/java/com/lakeon/obs/ObsClientTest.java`

- [ ] **Step 1: 写测试（用 Mock 华为云 OBS SDK）**

```java
// ObsClientTest.java
package com.lakeon.obs;

import com.obs.services.ObsClient;
import com.obs.services.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObsClientTest {
    @Test
    void putObject_withIfMatch_setsETagHeader(@Mock ObsClient obs) {
        com.lakeon.obs.LakeonObsClient lc = new com.lakeon.obs.LakeonObsClient(obs, "test-bucket");
        ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(obs.putObject(cap.capture())).thenReturn(new PutObjectResult() {{ setEtag("new-etag"); }});

        String etag = lc.putObject("path/k.json", "{\"v\":1}", "old-etag");

        assertThat(etag).isEqualTo("new-etag");
        assertThat(cap.getValue().getBucketName()).isEqualTo("test-bucket");
        assertThat(cap.getValue().getObjectKey()).isEqualTo("path/k.json");
        // ObsClient SDK 用 metadata 携带 If-Match
        assertThat(cap.getValue().getMetadata().getUserMetadata().get("if-match")).isEqualTo("old-etag");
    }

    @Test
    void getObject_returnsContentAndEtag(@Mock ObsClient obs) {
        com.lakeon.obs.LakeonObsClient lc = new com.lakeon.obs.LakeonObsClient(obs, "test-bucket");
        com.obs.services.model.ObsObject obj = mock(com.obs.services.model.ObsObject.class);
        ObjectMetadata md = new ObjectMetadata(); md.setEtag("e1");
        when(obj.getMetadata()).thenReturn(md);
        when(obj.getObjectContent()).thenReturn(new java.io.ByteArrayInputStream("{\"v\":1}".getBytes()));
        when(obs.getObject("test-bucket", "k.json")).thenReturn(obj);

        com.lakeon.obs.LakeonObsClient.ObsGetResult r = lc.getObject("k.json");

        assertThat(r.content()).isEqualTo("{\"v\":1}");
        assertThat(r.etag()).isEqualTo("e1");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=ObsClientTest test
```
Expected: FAIL — LakeonObsClient 不存在

- [ ] **Step 3: 实现 LakeonObsClient**

```java
// lakeon-api/src/main/java/com/lakeon/obs/LakeonObsClient.java
package com.lakeon.obs;

import com.obs.services.ObsClient;
import com.obs.services.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class LakeonObsClient {
    private final ObsClient obs;
    private final String bucket;

    public LakeonObsClient(ObsClient obs,
                           @Value("${lakeon.obs.bucket}") String bucket) {
        this.obs = obs;
        this.bucket = bucket;
    }

    public record ObsGetResult(String content, String etag) {}
    public record ObsListItem(String key, long size, String etag) {}

    public String putObject(String key, String content, String ifMatchETag) {
        PutObjectRequest req = new PutObjectRequest(bucket, key);
        req.setInput(new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        ObjectMetadata md = new ObjectMetadata();
        md.setContentLength((long) content.getBytes(StandardCharsets.UTF_8).length);
        md.setContentType("application/json");
        if (ifMatchETag != null) {
            md.addUserMetadata("if-match", ifMatchETag);
        }
        req.setMetadata(md);
        return obs.putObject(req).getEtag();
    }

    public ObsGetResult getObject(String key) {
        ObsObject obj = obs.getObject(bucket, key);
        try (InputStream is = obj.getObjectContent()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new ObsGetResult(content, obj.getMetadata().getEtag());
        } catch (IOException e) {
            throw new RuntimeException("failed to read OBS object " + key, e);
        }
    }

    public boolean exists(String key) {
        try {
            obs.getObjectMetadata(bucket, key);
            return true;
        } catch (com.obs.services.exception.ObsException e) {
            if (e.getResponseCode() == 404) return false;
            throw e;
        }
    }

    public List<ObsListItem> listPrefix(String prefix) {
        List<ObsListItem> out = new ArrayList<>();
        ListObjectsRequest req = new ListObjectsRequest(bucket);
        req.setPrefix(prefix);
        ObjectListing listing;
        do {
            listing = obs.listObjects(req);
            for (ObsObject o : listing.getObjects()) {
                out.add(new ObsListItem(o.getObjectKey(),
                    o.getMetadata().getContentLength(), o.getMetadata().getEtag()));
            }
            req.setMarker(listing.getNextMarker());
        } while (listing.isTruncated());
        return out;
    }
}
```

- [ ] **Step 4: 注册 OBS Client Bean**

```java
// lakeon-api/src/main/java/com/lakeon/config/ObsConfig.java (新建或修改)
package com.lakeon.config;

import com.obs.services.ObsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObsConfig {
    @Bean(destroyMethod = "close")
    public ObsClient obsClient(
        @Value("${lakeon.obs.endpoint}") String endpoint,
        @Value("${lakeon.obs.access-key}") String accessKey,
        @Value("${lakeon.obs.secret-key}") String secretKey
    ) {
        return new ObsClient(accessKey, secretKey, endpoint);
    }
}
```

- [ ] **Step 5: 跑测试**

```bash
cd lakeon-api && mvn -Dtest=ObsClientTest test
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/obs/LakeonObsClient.java \
        lakeon-api/src/main/java/com/lakeon/config/ObsConfig.java \
        lakeon-api/src/test/java/com/lakeon/obs/ObsClientTest.java
git commit -m "feat(obs): LakeonObsClient (PUT/GET/LIST + If-Match)"
```

---

### Task 14: Manifest JSON schema POJO

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/obs/ManifestObjects.java`

- [ ] **Step 1: 写 POJO**

```java
// lakeon-api/src/main/java/com/lakeon/obs/ManifestObjects.java
package com.lakeon.obs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ManifestObjects {
    public record BranchEntry(
        String branchId,
        String parent,
        String lsn
    ) {}

    public record DatabaseEntry(
        String dbId,
        String name,
        String timelineId,
        Instant createdAt,
        Instant deletedAt,
        List<BranchEntry> branches
    ) {}

    public record TenantManifest(
        int manifestVersion,
        String tenantId,
        String ownerEmail,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<DatabaseEntry> databases
    ) {}

    public record OwnersIndex(
        int indexVersion,
        Instant updatedAt,
        Map<String, List<String>> owners  // email → [tenantId,...]
    ) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/obs/ManifestObjects.java
git commit -m "feat(obs): TenantManifest / OwnersIndex POJO"
```

---

### Task 15: Spring Event 定义

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/event/TenantChangedEvent.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/event/DatabaseChangedEvent.java`
- Create: `lakeon-api/src/main/java/com/lakeon/model/event/BranchChangedEvent.java`

- [ ] **Step 1: 写 events**

```java
// TenantChangedEvent.java
package com.lakeon.model.event;

public record TenantChangedEvent(String tenantId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}

// DatabaseChangedEvent.java
package com.lakeon.model.event;

public record DatabaseChangedEvent(String tenantId, String dbId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}

// BranchChangedEvent.java
package com.lakeon.model.event;

public record BranchChangedEvent(String tenantId, String dbId, String branchId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/event/*.java
git commit -m "feat(event): 定义 Tenant/Database/Branch ChangedEvent"
```

---

### Task 16: ManifestWriter 实现

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/ManifestWriter.java`
- Create: `lakeon-api/src/test/java/com/lakeon/service/ManifestWriterTest.java`

- [ ] **Step 1: 写失败测试**

```java
// ManifestWriterTest.java
package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lakeon.model.entity.*;
import com.lakeon.model.event.*;
import com.lakeon.obs.LakeonObsClient;
import com.lakeon.obs.ManifestObjects;
import com.lakeon.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManifestWriterTest {
    @Mock TenantRepository tenantRepo;
    @Mock DatabaseRepository dbRepo;
    @Mock BranchRepository branchRepo;
    @Mock LakeonObsClient obs;

    @Test
    void onDatabaseChanged_writesTenantManifestToObs() throws Exception {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn1");
        tenant.setOwnerEmail("alice@example.com");
        tenant.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        when(tenantRepo.findById("tn1")).thenReturn(Optional.of(tenant));

        DatabaseEntity db = new DatabaseEntity();
        db.setId("db1"); db.setName("mydb"); db.setTenantId("tn1");
        db.setTimelineId("tl1"); db.setCreatedAt(Instant.parse("2026-04-05T10:00:00Z"));
        when(dbRepo.findByTenantId("tn1")).thenReturn(List.of(db));
        when(branchRepo.findByDatabaseId("db1")).thenReturn(List.of());
        when(obs.exists("tenants/tn1/_manifest.json")).thenReturn(false);
        when(obs.putObject(eq("tenants/tn1/_manifest.json"), anyString(), isNull()))
            .thenReturn("etag-1");

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ManifestWriter writer = new ManifestWriter(tenantRepo, dbRepo, branchRepo, obs, om, null);

        writer.onDatabaseChanged(new DatabaseChangedEvent("tn1", "db1",
            DatabaseChangedEvent.ChangeType.CREATED));

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(obs).putObject(eq("tenants/tn1/_manifest.json"), bodyCap.capture(), isNull());
        ManifestObjects.TenantManifest written = om.readValue(bodyCap.getValue(),
            ManifestObjects.TenantManifest.class);
        assertThat(written.tenantId()).isEqualTo("tn1");
        assertThat(written.ownerEmail()).isEqualTo("alice@example.com");
        assertThat(written.databases()).hasSize(1);
        assertThat(written.databases().get(0).dbId()).isEqualTo("db1");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd lakeon-api && mvn -Dtest=ManifestWriterTest test
```
Expected: FAIL

- [ ] **Step 3: 实现 ManifestWriter**

```java
// ManifestWriter.java
package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.*;
import com.lakeon.model.event.*;
import com.lakeon.obs.LakeonObsClient;
import com.lakeon.obs.ManifestObjects;
import com.lakeon.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ManifestWriter {
    private static final Logger log = LoggerFactory.getLogger(ManifestWriter.class);
    private final TenantRepository tenantRepo;
    private final DatabaseRepository dbRepo;
    private final BranchRepository branchRepo;
    private final LakeonObsClient obs;
    private final ObjectMapper om;
    private final ManifestRetryQueue retryQueue;

    public ManifestWriter(TenantRepository tenantRepo,
                          DatabaseRepository dbRepo,
                          BranchRepository branchRepo,
                          LakeonObsClient obs,
                          ObjectMapper om,
                          ManifestRetryQueue retryQueue) {
        this.tenantRepo = tenantRepo;
        this.dbRepo = dbRepo;
        this.branchRepo = branchRepo;
        this.obs = obs;
        this.om = om;
        this.retryQueue = retryQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTenantChanged(TenantChangedEvent e) {
        writeManifestForTenant(e.tenantId());
        updateOwnersIndexForTenant(e.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDatabaseChanged(DatabaseChangedEvent e) {
        writeManifestForTenant(e.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBranchChanged(BranchChangedEvent e) {
        writeManifestForTenant(e.tenantId());
    }

    void writeManifestForTenant(String tenantId) {
        try {
            TenantEntity tenant = tenantRepo.findById(tenantId).orElse(null);
            if (tenant == null) return;
            ManifestObjects.TenantManifest manifest = buildManifest(tenant);
            String body = om.writeValueAsString(manifest);

            String key = "tenants/" + tenantId + "/_manifest.json";
            String ifMatch = obs.exists(key) ? obs.getObject(key).etag() : null;
            obs.putObject(key, body, ifMatch);
        } catch (Exception ex) {
            log.error("ManifestWriter failed for tenant {}, enqueue retry", tenantId, ex);
            if (retryQueue != null) {
                retryQueue.enqueue(tenantId, "tenant_manifest", ex.getMessage());
            }
        }
    }

    private ManifestObjects.TenantManifest buildManifest(TenantEntity tenant) {
        List<DatabaseEntity> dbs = dbRepo.findByTenantId(tenant.getId());
        List<ManifestObjects.DatabaseEntry> dbEntries = dbs.stream().map(db -> {
            List<ManifestObjects.BranchEntry> branches = branchRepo.findByDatabaseId(db.getId())
                .stream().map(b -> new ManifestObjects.BranchEntry(
                    b.getId(),
                    b.getParentBranchId(),
                    b.getStartLsn()))
                .collect(Collectors.toList());
            return new ManifestObjects.DatabaseEntry(
                db.getId(), db.getName(), db.getTimelineId(),
                db.getCreatedAt(), db.getDeletedAt(), branches);
        }).collect(Collectors.toList());

        long version = System.currentTimeMillis();
        return new ManifestObjects.TenantManifest(
            1, tenant.getId(), tenant.getOwnerEmail(),
            tenant.getCreatedAt(), Instant.now(), version, dbEntries);
    }

    void updateOwnersIndexForTenant(String tenantId) {
        try {
            TenantEntity tenant = tenantRepo.findById(tenantId).orElse(null);
            if (tenant == null || tenant.getOwnerEmail() == null) return;
            String email = tenant.getOwnerEmail();
            String shard = emailShard(email);
            String key = "_global/owners/" + shard + ".idx";

            String oldEtag = null;
            Map<String, List<String>> owners = new HashMap<>();
            if (obs.exists(key)) {
                LakeonObsClient.ObsGetResult r = obs.getObject(key);
                oldEtag = r.etag();
                ManifestObjects.OwnersIndex idx =
                    om.readValue(r.content(), ManifestObjects.OwnersIndex.class);
                owners = new HashMap<>(idx.owners());
            }
            owners.computeIfAbsent(email, k -> new ArrayList<>());
            if (!owners.get(email).contains(tenantId)) {
                owners.get(email).add(tenantId);
            }
            ManifestObjects.OwnersIndex newIdx =
                new ManifestObjects.OwnersIndex(1, Instant.now(), owners);
            obs.putObject(key, om.writeValueAsString(newIdx), oldEtag);
        } catch (Exception ex) {
            log.error("OwnersIndex update failed for tenant {}", tenantId, ex);
            if (retryQueue != null) {
                retryQueue.enqueue(tenantId, "owners_index", ex.getMessage());
            }
        }
    }

    static String emailShard(String email) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                .digest(email.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x", h[0] & 0xff);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd lakeon-api && mvn -Dtest=ManifestWriterTest test
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/ManifestWriter.java \
        lakeon-api/src/test/java/com/lakeon/service/ManifestWriterTest.java
git commit -m "feat(manifest): ManifestWriter AFTER_COMMIT 写 OBS (manifest + owners.idx)"
```

---

### Task 17: ManifestRetryQueue + Scheduler

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/ManifestRetryQueue.java`
- Create: `lakeon-api/src/main/java/com/lakeon/service/ManifestRetryScheduler.java`

- [ ] **Step 1: 写 RetryQueue（写 OBS retry queue 路径）**

```java
// ManifestRetryQueue.java
package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.obs.LakeonObsClient;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ManifestRetryQueue {
    private final LakeonObsClient obs;
    private final ObjectMapper om;

    public ManifestRetryQueue(LakeonObsClient obs, ObjectMapper om) {
        this.obs = obs;
        this.om = om;
    }

    public record RetryEntry(String tenantId, String kind, String reason, Instant enqueuedAt) {}

    public void enqueue(String tenantId, String kind, String reason) {
        try {
            String key = "_retry_queue/" + Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".json";
            String body = om.writeValueAsString(
                new RetryEntry(tenantId, kind, reason, Instant.now()));
            obs.putObject(key, body, null);
        } catch (Exception e) {
            // 退化路径：retry queue 都写不进去时，记日志 + Prometheus 告警
            org.slf4j.LoggerFactory.getLogger(ManifestRetryQueue.class)
                .error("CRITICAL: retry queue write failed", e);
        }
    }
}
```

- [ ] **Step 2: 写 Scheduler 扫 retry queue**

```java
// ManifestRetryScheduler.java
package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.obs.LakeonObsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ManifestRetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ManifestRetryScheduler.class);
    private final LakeonObsClient obs;
    private final ObjectMapper om;
    private final ManifestWriter writer;

    public ManifestRetryScheduler(LakeonObsClient obs, ObjectMapper om, ManifestWriter writer) {
        this.obs = obs;
        this.om = om;
        this.writer = writer;
    }

    @Scheduled(fixedDelay = 60000)  // 每分钟
    public void retryPending() {
        var items = obs.listPrefix("_retry_queue/");
        for (var item : items) {
            try {
                var content = obs.getObject(item.key()).content();
                var entry = om.readValue(content, ManifestRetryQueue.RetryEntry.class);
                if ("tenant_manifest".equals(entry.kind())) {
                    writer.writeManifestForTenant(entry.tenantId());
                } else if ("owners_index".equals(entry.kind())) {
                    writer.updateOwnersIndexForTenant(entry.tenantId());
                }
                obs.putObject(item.key() + ".done", "{}", null);  // 标记完成
                // 实际删除走 lifecycle 或单独命令
            } catch (Exception e) {
                log.warn("retry {} still failing", item.key(), e);
            }
        }
    }
}
```

- [ ] **Step 3: 在 application.yml 启用 @Scheduled / @Async**

```yaml
# lakeon-api/src/main/resources/application.yml (新增段)
spring:
  task:
    execution:
      pool:
        core-size: 4
        max-size: 16
      thread-name-prefix: manifest-

lakeon:
  manifest:
    retry-cron-fixed-delay: 60000
```

主 Application 类加：
```java
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class LakeonApiApplication { /* ... */ }
```
（如果还没加 `@EnableAsync` / `@EnableScheduling`）

- [ ] **Step 4: 编译验证**

```bash
cd lakeon-api && mvn compile
```
Expected: 无错误

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/ManifestRetryQueue.java \
        lakeon-api/src/main/java/com/lakeon/service/ManifestRetryScheduler.java \
        lakeon-api/src/main/resources/application.yml \
        lakeon-api/src/main/java/com/lakeon/LakeonApiApplication.java
git commit -m "feat(manifest): RetryQueue + Scheduler (失败后端补偿)"
```

---

### Task 18: TenantService / DatabaseService / BranchService 发 Event

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/TenantService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/BranchService.java`

- [ ] **Step 1: 在 TenantService 注入 ApplicationEventPublisher 并发事件**

```java
// TenantService.java (修改)
import org.springframework.context.ApplicationEventPublisher;
import com.lakeon.model.event.TenantChangedEvent;

private final ApplicationEventPublisher events;
// 构造器注入

@Transactional
public TenantResponse create(CreateTenantRequest request) {
    // ... 现有逻辑保存 entity
    TenantEntity saved = tenantRepository.save(entity);
    events.publishEvent(new TenantChangedEvent(saved.getId(), TenantChangedEvent.ChangeType.CREATED));
    return toResponse(saved);
}

@Transactional
public void delete(String tenantId) {
    // ... 现有逻辑
    events.publishEvent(new TenantChangedEvent(tenantId, TenantChangedEvent.ChangeType.DELETED));
}
```

- [ ] **Step 2: 同样模式改 DatabaseService**

```java
// DatabaseService.java (在创建/更新/删除点发 event)
private final ApplicationEventPublisher events;

@Transactional
public DatabaseResponse create(String tenantId, CreateDatabaseRequest req) {
    DatabaseEntity saved = databaseRepository.save(/* ... */);
    events.publishEvent(new DatabaseChangedEvent(tenantId, saved.getId(),
        DatabaseChangedEvent.ChangeType.CREATED));
    return toResponse(saved);
}
// 类似在 update / delete / registerRecoveredDatabase 加 event
```

- [ ] **Step 3: 同样模式改 BranchService**

```java
@Transactional
public void createBranch(String dbId, /* ... */) {
    // ... 保存
    events.publishEvent(new BranchChangedEvent(tenantId, dbId, branchId,
        BranchChangedEvent.ChangeType.CREATED));
}
```

- [ ] **Step 4: 跑现有单元测试确保没破坏**

```bash
cd lakeon-api && mvn test
```
Expected: 全 PASS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/TenantService.java \
        lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java \
        lakeon-api/src/main/java/com/lakeon/service/BranchService.java
git commit -m "feat(service): Tenant/Database/Branch 变更后发 ApplicationEvent"
```

---

### Task 19: ManifestWriter E2E pytest

**Files:**
- Create: `tests/e2e/test_manifest_writer.py`

- [ ] **Step 1: 写 E2E**

```python
# tests/e2e/test_manifest_writer.py
import json
import time
import pytest
import boto3  # 或华为云 obs SDK
from conftest import DbayClient

# 假设 conftest 提供 obs_client fixture 直接访问 OBS bucket


def test_create_tenant_writes_manifest_to_obs(admin_client: DbayClient, obs_client):
    resp = admin_client.post("/tenants", json={
        "name": "manifest_test_tenant",
        "ownerEmail": "writer-test@example.com"
    })
    assert resp.status_code == 200
    tenant_id = resp.json()["id"]

    # 等 AFTER_COMMIT + async 写完成
    for _ in range(20):
        try:
            obj = obs_client.get_object(
                Bucket="lakeon-test", Key=f"tenants/{tenant_id}/_manifest.json")
            manifest = json.loads(obj["Body"].read())
            break
        except obs_client.exceptions.NoSuchKey:
            time.sleep(1)
    else:
        pytest.fail("manifest 未在 20s 内写入 OBS")

    assert manifest["tenantId"] == tenant_id
    assert manifest["ownerEmail"] == "writer-test@example.com"

    # 验证 owners.idx 也更新了
    import hashlib
    shard = hashlib.sha256(b"writer-test@example.com").hexdigest()[:2]
    idx_obj = obs_client.get_object(
        Bucket="lakeon-test", Key=f"_global/owners/{shard}.idx")
    idx = json.loads(idx_obj["Body"].read())
    assert tenant_id in idx["owners"]["writer-test@example.com"]

    admin_client.delete(f"/tenants/{tenant_id}")


def test_create_database_updates_tenant_manifest(admin_client, tenant_client, obs_client):
    resp = tenant_client.post("/databases", json={"name": "mfw_test_db"})
    db_id = resp.json()["id"]
    tenant_id = tenant_client.tenant_id

    for _ in range(20):
        obj = obs_client.get_object(
            Bucket="lakeon-test", Key=f"tenants/{tenant_id}/_manifest.json")
        manifest = json.loads(obj["Body"].read())
        if any(d["dbId"] == db_id for d in manifest["databases"]):
            break
        time.sleep(1)
    else:
        pytest.fail("manifest 未在 20s 内反映新数据库")

    tenant_client.delete(f"/databases/{db_id}")
```

- [ ] **Step 2: 跑 E2E**

```bash
python3 -m pytest tests/e2e/test_manifest_writer.py -v
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add tests/e2e/test_manifest_writer.py
git commit -m "test(e2e): ManifestWriter 写 OBS manifest + owners.idx 验证"
```

---

## Phase 1.5: dbay-rescue (Go binary)

### Task 20: Go 项目脚手架

**Files:**
- Create: `dbay-rescue/go.mod`
- Create: `dbay-rescue/cmd/dbay-rescue/main.go`
- Create: `dbay-rescue/internal/creds/loader.go`

- [ ] **Step 1: 初始化 go module**

```bash
mkdir -p /Users/jacky/code/lakeon/dbay-rescue && cd /Users/jacky/code/lakeon/dbay-rescue
go mod init github.com/dbay-cloud/dbay-rescue
go get github.com/spf13/cobra@latest
go get github.com/huaweicloud/huaweicloud-sdk-go-obs@latest
go get github.com/jackc/pgx/v5@latest
go get gopkg.in/yaml.v3@latest
```

- [ ] **Step 2: 写 creds loader**

```go
// internal/creds/loader.go
package creds

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

type Credentials struct {
	OBS struct {
		Endpoint  string `yaml:"endpoint"`
		AccessKey string `yaml:"access_key"`
		SecretKey string `yaml:"secret_key"`
		Bucket    string `yaml:"bucket"`
	} `yaml:"obs"`
	Pageserver struct {
		MgmtEndpoint string `yaml:"mgmt_endpoint"`
		Token        string `yaml:"token"`
	} `yaml:"pageserver"`
	RDS struct {
		DefaultDSN string `yaml:"default_dsn"`
	} `yaml:"rds"`
}

func Load(path string) (*Credentials, error) {
	if path == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil, err
		}
		path = filepath.Join(home, ".dbay", "rescue-credentials.yaml")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read creds %s: %w", path, err)
	}
	var c Credentials
	if err := yaml.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("parse creds: %w", err)
	}
	return &c, nil
}
```

- [ ] **Step 3: 写 main.go + cobra root**

```go
// cmd/dbay-rescue/main.go
package main

import (
	"fmt"
	"os"

	"github.com/dbay-cloud/dbay-rescue/internal/commands"
	"github.com/spf13/cobra"
)

var (
	credsPath string
)

func main() {
	root := &cobra.Command{
		Use:   "dbay-rescue",
		Short: "dbay 灾难恢复工具 (SRE)",
		Long: `dbay-rescue 是独立的 SRE 应急工具。在 lakeon-api / RDS 不可用时，
凭 OBS + SRE 离线凭据直接执行恢复操作。`,
	}
	root.PersistentFlags().StringVar(&credsPath, "creds", "",
		"凭据文件路径 (默认 ~/.dbay/rescue-credentials.yaml)")

	root.AddCommand(
		commands.NewListTenantsCmd(&credsPath),
		commands.NewOwnerLookupCmd(&credsPath),
		commands.NewPitrCmd(&credsPath),
		commands.NewRebuildMetadataCmd(&credsPath),
		commands.NewEmergencyMountCmd(&credsPath),
	)

	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
```

- [ ] **Step 4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-rescue/go.mod dbay-rescue/go.sum dbay-rescue/cmd dbay-rescue/internal/creds
git commit -m "feat(dbay-rescue): Go 项目脚手架 + creds loader"
```

---

### Task 21: dbay-rescue OBS client

**Files:**
- Create: `dbay-rescue/internal/obs/client.go`
- Create: `dbay-rescue/internal/obs/manifest.go`
- Create: `dbay-rescue/internal/obs/client_test.go`

- [ ] **Step 1: 写 manifest.go (Go 端 schema 对应)**

```go
// internal/obs/manifest.go
package obs

import "time"

type BranchEntry struct {
	BranchID string `json:"branchId"`
	Parent   string `json:"parent"`
	LSN      string `json:"lsn"`
}

type DatabaseEntry struct {
	DBID        string         `json:"dbId"`
	Name        string         `json:"name"`
	TimelineID  string         `json:"timelineId"`
	CreatedAt   time.Time      `json:"createdAt"`
	DeletedAt   *time.Time     `json:"deletedAt,omitempty"`
	Branches    []BranchEntry  `json:"branches"`
}

type TenantManifest struct {
	ManifestVersion int             `json:"manifestVersion"`
	TenantID        string          `json:"tenantId"`
	OwnerEmail      string          `json:"ownerEmail"`
	CreatedAt       time.Time       `json:"createdAt"`
	UpdatedAt       time.Time       `json:"updatedAt"`
	Version         int64           `json:"version"`
	Databases       []DatabaseEntry `json:"databases"`
}

type OwnersIndex struct {
	IndexVersion int                   `json:"indexVersion"`
	UpdatedAt    time.Time             `json:"updatedAt"`
	Owners       map[string][]string   `json:"owners"`
}
```

- [ ] **Step 2: 写 client.go**

```go
// internal/obs/client.go
package obs

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/huaweicloud/huaweicloud-sdk-go-obs/obs"
)

type Client struct {
	c      *obs.ObsClient
	bucket string
}

func New(endpoint, ak, sk, bucket string) (*Client, error) {
	c, err := obs.New(ak, sk, endpoint)
	if err != nil {
		return nil, err
	}
	return &Client{c: c, bucket: bucket}, nil
}

func (c *Client) Close() {
	c.c.Close()
}

func (c *Client) GetJSON(key string, out any) error {
	resp, err := c.c.GetObject(&obs.GetObjectInput{
		GetObjectMetadataInput: obs.GetObjectMetadataInput{
			Bucket: c.bucket, Key: key,
		},
	})
	if err != nil {
		return fmt.Errorf("get %s: %w", key, err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, out)
}

func (c *Client) PutJSON(key string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	_, err = c.c.PutObject(&obs.PutObjectInput{
		PutObjectBasicInput: obs.PutObjectBasicInput{
			ObjectOperationInput: obs.ObjectOperationInput{
				Bucket: c.bucket, Key: key,
			},
			ContentType: "application/json",
		},
		Body: strings.NewReader(string(b)),
	})
	return err
}

func (c *Client) DeleteKey(key string) error {
	_, err := c.c.DeleteObject(&obs.DeleteObjectInput{Bucket: c.bucket, Key: key})
	return err
}

func (c *Client) ListKeys(prefix string) ([]string, error) {
	var keys []string
	marker := ""
	for {
		out, err := c.c.ListObjects(&obs.ListObjectsInput{
			Bucket: c.bucket,
			ListObjsInput: obs.ListObjsInput{
				Prefix: prefix, Marker: marker, MaxKeys: 1000,
			},
		})
		if err != nil {
			return nil, err
		}
		for _, o := range out.Contents {
			keys = append(keys, o.Key)
		}
		if !out.IsTruncated {
			break
		}
		marker = out.NextMarker
	}
	return keys, nil
}
```

- [ ] **Step 3: 写 client_test.go**

```go
// internal/obs/client_test.go
package obs

import (
	"encoding/json"
	"testing"
	"time"
)

func TestTenantManifest_JSONRoundtrip(t *testing.T) {
	m := TenantManifest{
		ManifestVersion: 1,
		TenantID:        "tn1",
		OwnerEmail:      "alice@example.com",
		CreatedAt:       time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC),
		UpdatedAt:       time.Date(2026, 5, 21, 14, 0, 0, 0, time.UTC),
		Version:         42,
		Databases: []DatabaseEntry{{
			DBID:       "db1",
			Name:       "mydb",
			TimelineID: "tl1",
			CreatedAt:  time.Date(2026, 4, 5, 0, 0, 0, 0, time.UTC),
			Branches:   []BranchEntry{{BranchID: "br_main", Parent: "", LSN: "0/0"}},
		}},
	}
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var back TenantManifest
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if back.TenantID != "tn1" || len(back.Databases) != 1 {
		t.Errorf("roundtrip mismatch: %+v", back)
	}
}
```

- [ ] **Step 4: 跑测试**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go test ./...
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add dbay-rescue/internal/obs/
git commit -m "feat(dbay-rescue): OBS client + manifest schema"
```

---

### Task 22: dbay-rescue Pageserver HTTP client

**Files:**
- Create: `dbay-rescue/internal/neon/pageserver.go`

- [ ] **Step 1: 写 client**

```go
// internal/neon/pageserver.go
package neon

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

type Client struct {
	base   string
	token  string
	client *http.Client
}

func New(base, token string) *Client {
	return &Client{
		base:   base,
		token:  token,
		client: &http.Client{Timeout: 30 * time.Second},
	}
}

type LsnByTimestampResp struct {
	LSN string `json:"lsn"`
}

func (c *Client) GetLsnByTimestamp(tenantID, timelineID string, ts time.Time) (string, error) {
	u := fmt.Sprintf("%s/v1/tenant/%s/timeline/%s/get_lsn_by_timestamp?timestamp=%s",
		c.base, tenantID, timelineID, url.QueryEscape(ts.UTC().Format(time.RFC3339)))
	var out LsnByTimestampResp
	if err := c.do(http.MethodGet, u, nil, &out); err != nil {
		return "", err
	}
	return out.LSN, nil
}

type CreateBranchReq struct {
	AncestorTimelineID string `json:"ancestor_timeline_id"`
	AncestorStartLSN   string `json:"ancestor_start_lsn"`
	NewTimelineID      string `json:"new_timeline_id"`
}

type CreateBranchResp struct {
	TimelineID string `json:"timeline_id"`
}

func (c *Client) CreateBranch(tenantID string, req CreateBranchReq) (*CreateBranchResp, error) {
	u := fmt.Sprintf("%s/v1/tenant/%s/timeline", c.base, tenantID)
	var out CreateBranchResp
	if err := c.do(http.MethodPost, u, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) do(method, url string, body, out any) error {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, url, reader)
	if err != nil {
		return err
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return fmt.Errorf("pageserver %s %s → %d: %s", method, url, resp.StatusCode, string(rb))
	}
	if out != nil {
		return json.Unmarshal(rb, out)
	}
	return nil
}
```

- [ ] **Step 2: Commit**

```bash
git add dbay-rescue/internal/neon/
git commit -m "feat(dbay-rescue): Pageserver HTTP client"
```

---

### Task 23: `dbay-rescue list-tenants` 子命令

**Files:**
- Create: `dbay-rescue/internal/commands/list_tenants.go`
- Create: `dbay-rescue/internal/commands/list_tenants_test.go`

- [ ] **Step 1: 写命令**

```go
// internal/commands/list_tenants.go
package commands

import (
	"fmt"
	"strings"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

func NewListTenantsCmd(credsPath *string) *cobra.Command {
	var fromOBS bool
	cmd := &cobra.Command{
		Use:   "list-tenants",
		Short: "列出所有 tenant (从 OBS manifest 读，不依赖 RDS)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			client, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer client.Close()

			keys, err := client.ListKeys("tenants/")
			if err != nil {
				return err
			}
			fmt.Printf("%-24s  %-32s  %s\n", "TENANT_ID", "OWNER_EMAIL", "DBS")
			for _, k := range keys {
				if !strings.HasSuffix(k, "/_manifest.json") {
					continue
				}
				var m obs.TenantManifest
				if err := client.GetJSON(k, &m); err != nil {
					fmt.Printf("  ! skip %s: %v\n", k, err)
					continue
				}
				fmt.Printf("%-24s  %-32s  %d\n", m.TenantID, m.OwnerEmail, len(m.Databases))
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&fromOBS, "from", true, "数据源 (目前只支持 obs)")
	return cmd
}
```

- [ ] **Step 2: 简单单元测试（不调真实 OBS，只测命令注册）**

```go
// internal/commands/list_tenants_test.go
package commands

import (
	"strings"
	"testing"
)

func TestListTenantsCmd_HasCorrectName(t *testing.T) {
	cmd := NewListTenantsCmd(new(string))
	if cmd.Use != "list-tenants" {
		t.Errorf("expected list-tenants, got %s", cmd.Use)
	}
	if !strings.Contains(cmd.Short, "tenant") {
		t.Errorf("missing description")
	}
}
```

- [ ] **Step 3: 跑测试 + 编译**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go test ./internal/commands/...
go build ./cmd/dbay-rescue
```
Expected: PASS + 编译出 binary

- [ ] **Step 4: Commit**

```bash
git add dbay-rescue/internal/commands/list_tenants.go \
        dbay-rescue/internal/commands/list_tenants_test.go
git commit -m "feat(dbay-rescue): list-tenants 子命令"
```

---

### Task 24: `dbay-rescue owner-lookup` 子命令

**Files:**
- Create: `dbay-rescue/internal/commands/owner_lookup.go`

- [ ] **Step 1: 写命令**

```go
// internal/commands/owner_lookup.go
package commands

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

func NewOwnerLookupCmd(credsPath *string) *cobra.Command {
	var email string
	cmd := &cobra.Command{
		Use:   "owner-lookup",
		Short: "用邮箱反查该用户拥有的 tenant",
		RunE: func(cmd *cobra.Command, args []string) error {
			if email == "" {
				return fmt.Errorf("--email is required")
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			client, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer client.Close()

			h := sha256.Sum256([]byte(email))
			shard := hex.EncodeToString(h[:1])
			key := fmt.Sprintf("_global/owners/%s.idx", shard)

			var idx obs.OwnersIndex
			if err := client.GetJSON(key, &idx); err != nil {
				return fmt.Errorf("owners index not found for shard %s: %w", shard, err)
			}
			tenants, ok := idx.Owners[email]
			if !ok || len(tenants) == 0 {
				fmt.Printf("no tenants found for %s\n", email)
				return nil
			}
			fmt.Printf("Tenants owned by %s:\n", email)
			for _, tid := range tenants {
				var m obs.TenantManifest
				key := fmt.Sprintf("tenants/%s/_manifest.json", tid)
				if err := client.GetJSON(key, &m); err != nil {
					fmt.Printf("  %s (manifest not found: %v)\n", tid, err)
					continue
				}
				fmt.Printf("  %s  created=%s  dbs=%d\n",
					tid, m.CreatedAt.Format("2006-01-02"), len(m.Databases))
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&email, "email", "e", "", "用户邮箱")
	return cmd
}
```

- [ ] **Step 2: 编译**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go build ./cmd/dbay-rescue
```
Expected: 成功

- [ ] **Step 3: Commit**

```bash
git add dbay-rescue/internal/commands/owner_lookup.go
git commit -m "feat(dbay-rescue): owner-lookup 子命令 (按 email shard)"
```

---

### Task 25: `dbay-rescue pitr` 子命令

**Files:**
- Create: `dbay-rescue/internal/commands/pitr.go`

- [ ] **Step 1: 写命令**

```go
// internal/commands/pitr.go
package commands

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/neon"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
)

func NewPitrCmd(credsPath *string) *cobra.Command {
	var dbID, timeArg, tenantHint string
	cmd := &cobra.Command{
		Use:   "pitr",
		Short: "绕过 lakeon-api，直接 PITR (用于 API 已挂场景)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if dbID == "" || timeArg == "" {
				return fmt.Errorf("--db and --time required")
			}
			ts, err := parseTime(timeArg)
			if err != nil {
				return err
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			tenantID, timelineID, err := locateTimeline(obsCli, dbID, tenantHint)
			if err != nil {
				return err
			}
			fmt.Printf("Located: tenant=%s timeline=%s\n", tenantID, timelineID)

			ps := neon.New(c.Pageserver.MgmtEndpoint, c.Pageserver.Token)
			lsn, err := ps.GetLsnByTimestamp(tenantID, timelineID, ts)
			if err != nil {
				return fmt.Errorf("get_lsn_by_timestamp: %w", err)
			}
			fmt.Printf("LSN at %s: %s\n", ts.Format(time.RFC3339), lsn)

			newTl := "tl_" + randHex(16)
			resp, err := ps.CreateBranch(tenantID, neon.CreateBranchReq{
				AncestorTimelineID: timelineID,
				AncestorStartLSN:   lsn,
				NewTimelineID:      newTl,
			})
			if err != nil {
				return fmt.Errorf("create_branch: %w", err)
			}
			fmt.Printf("\n✓ New timeline: %s\n", resp.TimelineID)
			fmt.Printf("  Ancestor: %s @ %s\n", timelineID, lsn)
			fmt.Printf("\n下一步:\n")
			fmt.Printf("  1. 用 emergency-mount %s 起 compute pod\n", tenantID)
			fmt.Printf("  2. lakeon-api 恢复后跑 rebuild-metadata 把这次新建落库\n")
			return nil
		},
	}
	cmd.Flags().StringVarP(&dbID, "db", "d", "", "数据库 ID")
	cmd.Flags().StringVarP(&timeArg, "time", "t", "", "目标时间 (ISO 8601 或 '5min ago')")
	cmd.Flags().StringVar(&tenantHint, "tenant", "", "可选: tenant_id (加速定位)")
	return cmd
}

func parseTime(s string) (time.Time, error) {
	s = strings.TrimSpace(s)
	re := regexp.MustCompile(`^(\d+)\s*(s|sec|seconds?|min|minutes?|h|hours?|d|days?)\s*ago$`)
	if m := re.FindStringSubmatch(strings.ToLower(s)); m != nil {
		n, _ := strconv.Atoi(m[1])
		var d time.Duration
		switch {
		case strings.HasPrefix(m[2], "s"):
			d = time.Duration(n) * time.Second
		case strings.HasPrefix(m[2], "min"):
			d = time.Duration(n) * time.Minute
		case strings.HasPrefix(m[2], "h"):
			d = time.Duration(n) * time.Hour
		case strings.HasPrefix(m[2], "d"):
			d = time.Duration(n) * 24 * time.Hour
		}
		return time.Now().UTC().Add(-d), nil
	}
	return time.Parse(time.RFC3339, s)
}

func locateTimeline(c *obs.Client, dbID, tenantHint string) (string, string, error) {
	if tenantHint != "" {
		var m obs.TenantManifest
		key := fmt.Sprintf("tenants/%s/_manifest.json", tenantHint)
		if err := c.GetJSON(key, &m); err != nil {
			return "", "", err
		}
		for _, db := range m.Databases {
			if db.DBID == dbID {
				return tenantHint, db.TimelineID, nil
			}
		}
		return "", "", fmt.Errorf("db %s not found in tenant %s", dbID, tenantHint)
	}
	// fallback: 扫所有 manifest
	keys, err := c.ListKeys("tenants/")
	if err != nil {
		return "", "", err
	}
	for _, k := range keys {
		if !strings.HasSuffix(k, "/_manifest.json") {
			continue
		}
		var m obs.TenantManifest
		if err := c.GetJSON(k, &m); err != nil {
			continue
		}
		for _, db := range m.Databases {
			if db.DBID == dbID {
				return m.TenantID, db.TimelineID, nil
			}
		}
	}
	return "", "", fmt.Errorf("db %s not found in any tenant manifest", dbID)
}

func randHex(n int) string {
	b := make([]byte, n/2)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
```

- [ ] **Step 2: 编译 + 简测**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go build ./cmd/dbay-rescue
go test ./internal/commands/...
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add dbay-rescue/internal/commands/pitr.go
git commit -m "feat(dbay-rescue): pitr 子命令 (绕过 lakeon-api 直连 Neon)"
```

---

### Task 26: `dbay-rescue rebuild-metadata` 子命令

**Files:**
- Create: `dbay-rescue/internal/rds/client.go`
- Create: `dbay-rescue/internal/commands/rebuild_metadata.go`

- [ ] **Step 1: 写 RDS client（用 pgx）**

```go
// internal/rds/client.go
package rds

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

type Conn struct {
	c *pgx.Conn
}

func Connect(ctx context.Context, dsn string) (*Conn, error) {
	c, err := pgx.Connect(ctx, dsn)
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

func (c *Conn) Close(ctx context.Context) error {
	return c.c.Close(ctx)
}

func (c *Conn) UpsertTenant(ctx context.Context, id, ownerEmail string, createdAt time.Time) error {
	_, err := c.c.Exec(ctx, `
		INSERT INTO tenants (id, owner_email, created_at)
		VALUES ($1, $2, $3)
		ON CONFLICT (id) DO UPDATE SET owner_email = EXCLUDED.owner_email
	`, id, ownerEmail, createdAt)
	return err
}

func (c *Conn) UpsertDatabase(ctx context.Context, id, tenantID, name, timelineID string,
	createdAt time.Time, deletedAt *time.Time) error {
	_, err := c.c.Exec(ctx, `
		INSERT INTO databases (id, tenant_id, name, timeline_id, created_at, deleted_at, status)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (id) DO UPDATE SET
		    name = EXCLUDED.name,
		    timeline_id = EXCLUDED.timeline_id,
		    deleted_at = EXCLUDED.deleted_at
	`, id, tenantID, name, timelineID, createdAt, deletedAt,
		map[bool]string{true: "DELETED", false: "ACTIVE"}[deletedAt != nil])
	return err
}

func (c *Conn) UpsertBranch(ctx context.Context, id, dbID, parentID, lsn string) error {
	_, err := c.c.Exec(ctx, `
		INSERT INTO branches (id, database_id, parent_branch_id, start_lsn)
		VALUES ($1, $2, NULLIF($3, ''), $4)
		ON CONFLICT (id) DO UPDATE SET start_lsn = EXCLUDED.start_lsn
	`, id, dbID, parentID, lsn)
	return err
}

func (c *Conn) Ping(ctx context.Context) error {
	return c.c.Ping(ctx)
}

func (c *Conn) Begin(ctx context.Context) (pgx.Tx, error) {
	return c.c.Begin(ctx)
}

func (c *Conn) Raw() *pgx.Conn { return c.c }

var _ = fmt.Sprintf
```

- [ ] **Step 2: 写 rebuild_metadata 命令**

```go
// internal/commands/rebuild_metadata.go
package commands

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/dbay-cloud/dbay-rescue/internal/rds"
	"github.com/spf13/cobra"
)

func NewRebuildMetadataCmd(credsPath *string) *cobra.Command {
	var dsn string
	var fromOBS bool
	cmd := &cobra.Command{
		Use:   "rebuild-metadata",
		Short: "从 OBS manifest 反向重建 RDS 元数据库",
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
			defer cancel()
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			if dsn == "" {
				dsn = c.RDS.DefaultDSN
			}
			if dsn == "" {
				return fmt.Errorf("--to DSN required (or set rds.default_dsn in creds)")
			}

			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			rdsConn, err := rds.Connect(ctx, dsn)
			if err != nil {
				return fmt.Errorf("connect rds: %w", err)
			}
			defer rdsConn.Close(ctx)
			if err := rdsConn.Ping(ctx); err != nil {
				return fmt.Errorf("ping rds: %w", err)
			}

			keys, err := obsCli.ListKeys("tenants/")
			if err != nil {
				return err
			}
			var ok, skipped, failed int
			for _, k := range keys {
				if !strings.HasSuffix(k, "/_manifest.json") {
					continue
				}
				var m obs.TenantManifest
				if err := obsCli.GetJSON(k, &m); err != nil {
					fmt.Printf("  ! parse %s: %v\n", k, err)
					failed++
					continue
				}
				if err := applyManifest(ctx, rdsConn, m); err != nil {
					fmt.Printf("  ! apply %s: %v\n", m.TenantID, err)
					failed++
					continue
				}
				ok++
				if ok%10 == 0 {
					fmt.Printf("  ✓ rebuilt %d tenants...\n", ok)
				}
			}
			fmt.Printf("\nDone. ok=%d skipped=%d failed=%d\n", ok, skipped, failed)
			if failed > 0 {
				return fmt.Errorf("%d tenants failed", failed)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&dsn, "to", "", "目标 RDS DSN")
	cmd.Flags().BoolVar(&fromOBS, "from-obs", true, "(目前只支持 obs)")
	return cmd
}

func applyManifest(ctx context.Context, conn *rds.Conn, m obs.TenantManifest) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if err := conn.UpsertTenant(ctx, m.TenantID, m.OwnerEmail, m.CreatedAt); err != nil {
		return err
	}
	for _, db := range m.Databases {
		if err := conn.UpsertDatabase(ctx, db.DBID, m.TenantID, db.Name,
			db.TimelineID, db.CreatedAt, db.DeletedAt); err != nil {
			return err
		}
		for _, br := range db.Branches {
			if err := conn.UpsertBranch(ctx, br.BranchID, db.DBID, br.Parent, br.LSN); err != nil {
				return err
			}
		}
	}
	return tx.Commit(ctx)
}
```

- [ ] **Step 3: 编译**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go build ./cmd/dbay-rescue
```
Expected: 成功

- [ ] **Step 4: Commit**

```bash
git add dbay-rescue/internal/rds dbay-rescue/internal/commands/rebuild_metadata.go
git commit -m "feat(dbay-rescue): rebuild-metadata 从 OBS manifest 反向重建 RDS"
```

---

### Task 27: `dbay-rescue emergency-mount` 子命令

**前提**：emergency-mount 假设 k8s API 可达（kubeconfig 已在 SRE 笔记本配好）。如果连 k8s API 都挂了，此命令也跑不了——这是 L5 的合理假设。

**Files:**
- Create: `dbay-rescue/internal/commands/emergency_mount.go`
- Modify: `dbay-rescue/go.mod` 加 `k8s.io/client-go`

- [ ] **Step 1: 装依赖**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue
go get k8s.io/client-go@latest k8s.io/api@latest k8s.io/apimachinery@latest
```

- [ ] **Step 2: 写命令**

```go
// internal/commands/emergency_mount.go
package commands

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/tools/remotecommand"
	"k8s.io/client-go/util/homedir"
)

func NewEmergencyMountCmd(credsPath *string) *cobra.Command {
	var tenantID, ownerEmail string
	var writable bool
	cmd := &cobra.Command{
		Use:   "emergency-mount",
		Short: "RDS 挂时给单 tenant 起 1h 临时 compute + 临时只读 PG ROLE",
		RunE: func(cmd *cobra.Command, args []string) error {
			if tenantID == "" || ownerEmail == "" {
				return fmt.Errorf("--tenant and --owner required")
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			var m obs.TenantManifest
			key := fmt.Sprintf("tenants/%s/_manifest.json", tenantID)
			if err := obsCli.GetJSON(key, &m); err != nil {
				return fmt.Errorf("tenant manifest not found: %w", err)
			}
			if !strings.EqualFold(m.OwnerEmail, ownerEmail) {
				return fmt.Errorf("ownerEmail mismatch: manifest says %s, you passed %s",
					m.OwnerEmail, ownerEmail)
			}
			if len(m.Databases) == 0 {
				return fmt.Errorf("tenant %s has no databases", tenantID)
			}
			db := m.Databases[0]
			roleName := "em_" + randAlnum(8)
			password := randAlnum(32)

			fmt.Printf("Mounting emergency compute for %s...\n", tenantID)
			fmt.Printf("  Database: %s (timeline=%s)\n", db.Name, db.TimelineID)

			// 1. 起 k8s pod (compute attached to existing timeline)
			podName, err := startEmergencyComputePod(tenantID, db.TimelineID, c.Pageserver.MgmtEndpoint)
			if err != nil {
				return fmt.Errorf("start compute pod: %w", err)
			}
			fmt.Printf("  Compute pod: %s\n", podName)

			// 2. 在 compute 上创建临时 ROLE
			grant := "GRANT pg_read_all_data"
			if writable {
				grant = "GRANT pg_write_all_data, pg_read_all_data"
			}
			sql := fmt.Sprintf(
				"CREATE ROLE %s WITH LOGIN PASSWORD '%s' VALID UNTIL '%s'; %s TO %s;",
				roleName, password,
				time.Now().Add(time.Hour).UTC().Format("2006-01-02 15:04:05"),
				grant, roleName)
			if err := execOnComputePod(podName, sql); err != nil {
				return fmt.Errorf("create temp role: %w", err)
			}

			// 3. 写 audit log
			audit := map[string]any{
				"timestamp":   time.Now().UTC().Format(time.RFC3339),
				"actor":       sreActor(),
				"tenant_id":   tenantID,
				"owner_email": ownerEmail,
				"role":        roleName,
				"writable":    writable,
			}
			ab, _ := json.Marshal(audit)
			auditKey := fmt.Sprintf("_audit/emergency_mount/%d-%s.json",
				time.Now().UnixMilli(), roleName)
			_ = obsCli.PutJSON(auditKey, json.RawMessage(ab))

			// 4. 输出连接串
			fmt.Printf("\n✓ Emergency mount ready (valid 1h):\n\n")
			fmt.Printf("  postgresql://%s:%s@%s:5432/%s?sslmode=require\n\n",
				roleName, password, podName, db.Name)
			fmt.Printf("  Audit: %s\n", auditKey)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantID, "tenant", "", "tenant ID (required)")
	cmd.Flags().StringVar(&ownerEmail, "owner", "", "user email (must match manifest)")
	cmd.Flags().BoolVar(&writable, "writable", false, "授予写权限 (默认只读)")
	return cmd
}

func startEmergencyComputePod(tenantID, timelineID, pageserverURL string) (string, error) {
	kubeconfig := filepath.Join(homedir.HomeDir(), ".kube", "config")
	cfg, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		return "", err
	}
	cli, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return "", err
	}
	podName := fmt.Sprintf("em-%s-%s", tenantID[:8], randAlnum(6))
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      podName,
			Namespace: "lakeon",
			Labels: map[string]string{
				"app":         "emergency-mount",
				"tenant-id":   tenantID,
				"timeline-id": timelineID,
				"expires-at":  fmt.Sprintf("%d", time.Now().Add(time.Hour).Unix()),
			},
		},
		Spec: corev1.PodSpec{
			Containers: []corev1.Container{{
				Name:  "compute",
				Image: "swr.cn-east-3.myhuaweicloud.com/lakeon/compute-ctl:latest",
				Env: []corev1.EnvVar{
					{Name: "TENANT_ID", Value: tenantID},
					{Name: "TIMELINE_ID", Value: timelineID},
					{Name: "PAGESERVER_URL", Value: pageserverURL},
				},
				Ports: []corev1.ContainerPort{{ContainerPort: 5432}},
			}},
		},
	}
	_, err = cli.CoreV1().Pods("lakeon").Create(context.Background(), pod, metav1.CreateOptions{})
	return podName, err
}

func execOnComputePod(podName, sql string) error {
	kubeconfig := filepath.Join(homedir.HomeDir(), ".kube", "config")
	cfg, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		return err
	}
	cli, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return err
	}

	// 等 pod ready (最多 90s)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	for {
		pod, err := cli.CoreV1().Pods("lakeon").Get(ctx, podName, metav1.GetOptions{})
		if err != nil {
			return fmt.Errorf("get pod: %w", err)
		}
		if pod.Status.Phase == corev1.PodRunning {
			ready := false
			for _, c := range pod.Status.ContainerStatuses {
				if c.Ready {
					ready = true
					break
				}
			}
			if ready {
				break
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("pod %s not ready within 90s", podName)
		case <-time.After(2 * time.Second):
		}
	}

	// SPDY exec
	req := cli.CoreV1().RESTClient().Post().
		Resource("pods").
		Name(podName).
		Namespace("lakeon").
		SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: "compute",
			Command:   []string{"psql", "-U", "postgres", "-c", sql},
			Stdout:    true,
			Stderr:    true,
		}, scheme.ParameterCodec)

	exec, err := remotecommand.NewSPDYExecutor(cfg, "POST", req.URL())
	if err != nil {
		return fmt.Errorf("new SPDY executor: %w", err)
	}
	var stdout, stderr strings.Builder
	if err := exec.StreamWithContext(ctx, remotecommand.StreamOptions{
		Stdout: &stdout,
		Stderr: &stderr,
	}); err != nil {
		return fmt.Errorf("exec failed: %w stderr=%s", err, stderr.String())
	}
	return nil
}

func sreActor() string {
	if u := os.Getenv("USER"); u != "" {
		return u
	}
	if u := os.Getenv("SRE_OPERATOR"); u != "" {
		return u
	}
	return "unknown"
}

func randAlnum(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	s := base64.RawURLEncoding.EncodeToString(b)
	return s[:n]
}
```

> **注意**：`execOnComputePod` 标了 TODO，因为 client-go 的 SPDY exec 实现较长。执行阶段需要补完整 `corev1exec.Exec` 流程。可以参考 `kubectl exec` 源码或用 `k8s.io/client-go/tools/remotecommand`。

- [ ] **Step 3: 编译**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && go build ./cmd/dbay-rescue
```
Expected: 成功

- [ ] **Step 4: 简单单元测试（不调 k8s）**

```go
// internal/commands/emergency_mount_test.go
package commands

import (
	"testing"
)

func TestRandAlnum_GeneratesCorrectLength(t *testing.T) {
	for _, n := range []int{8, 16, 32} {
		s := randAlnum(n)
		if len(s) != n {
			t.Errorf("randAlnum(%d) returned len %d", n, len(s))
		}
	}
}
```

跑：
```bash
go test ./internal/commands/...
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add dbay-rescue/internal/commands/emergency_mount.go \
        dbay-rescue/internal/commands/emergency_mount_test.go \
        dbay-rescue/go.mod dbay-rescue/go.sum
git commit -m "feat(dbay-rescue): emergency-mount 起临时 compute + 临时 PG ROLE + 审计"
```

---

### Task 28: dbay-rescue E2E 集成测试

**Files:**
- Create: `dbay-rescue/test/e2e_test.go`

- [ ] **Step 1: 写 E2E（用真实 OBS 测试 bucket + Pageserver staging）**

```go
// test/e2e_test.go
//go:build e2e

package test

import (
	"context"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/jackc/pgx/v5"
)

func TestE2E_ListTenants(t *testing.T) {
	cred := os.Getenv("DBAY_RESCUE_TEST_CREDS")
	if cred == "" {
		t.Skip("DBAY_RESCUE_TEST_CREDS not set")
	}
	cmd := exec.Command("../dbay-rescue", "list-tenants", "--creds", cred)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("list-tenants failed: %v\n%s", err, out)
	}
	if !strings.Contains(string(out), "TENANT_ID") {
		t.Errorf("expected header in output, got: %s", out)
	}
}

func TestE2E_RebuildMetadata_RoundTrip(t *testing.T) {
	cred := os.Getenv("DBAY_RESCUE_TEST_CREDS")
	rdsDSN := os.Getenv("DBAY_RESCUE_TEST_RDS")
	if cred == "" || rdsDSN == "" {
		t.Skip("E2E env not set")
	}

	// 1. 写一个 fake tenant manifest 到测试 OBS bucket
	bucket := os.Getenv("DBAY_RESCUE_TEST_BUCKET")
	c, _ := obs.New(os.Getenv("OBS_ENDPOINT"),
		os.Getenv("OBS_AK"), os.Getenv("OBS_SK"), bucket)
	defer c.Close()
	tenantID := "e2etest_" + time.Now().Format("20060102150405")
	m := obs.TenantManifest{
		ManifestVersion: 1, TenantID: tenantID,
		OwnerEmail: "e2e@test.local",
		CreatedAt:  time.Now(), UpdatedAt: time.Now(), Version: 1,
		Databases:  []obs.DatabaseEntry{{
			DBID: "db_e2e", Name: "edb", TimelineID: "tl_e2e",
			CreatedAt: time.Now(),
		}},
	}
	if err := c.PutJSON("tenants/"+tenantID+"/_manifest.json", m); err != nil {
		t.Fatalf("put fake manifest: %v", err)
	}
	defer func() {
		_ = c.DeleteKey("tenants/" + tenantID + "/_manifest.json")
	}()

	// 2. 跑 rebuild-metadata
	cmd := exec.Command("../dbay-rescue", "rebuild-metadata",
		"--creds", cred, "--to", rdsDSN)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("rebuild failed: %v\n%s", err, out)
	}

	// 3. 验证 RDS 有该 tenant
	ctx := context.Background()
	pgConn, err := pgx.Connect(ctx, rdsDSN)
	if err != nil {
		t.Fatalf("connect rds: %v", err)
	}
	defer pgConn.Close(ctx)

	var ownerEmail string
	err = pgConn.QueryRow(ctx,
		"SELECT owner_email FROM tenants WHERE id = $1", tenantID).Scan(&ownerEmail)
	if err != nil {
		t.Fatalf("query tenants: %v", err)
	}
	if ownerEmail != "e2e@test.local" {
		t.Errorf("expected e2e@test.local, got %s", ownerEmail)
	}

	// cleanup
	_, _ = pgConn.Exec(ctx, "DELETE FROM tenants WHERE id = $1", tenantID)
}
```

- [ ] **Step 2: 跑 E2E（需 staging 环境）**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue
DBAY_RESCUE_TEST_CREDS=/tmp/test-creds.yaml \
DBAY_RESCUE_TEST_RDS=postgres://test@localhost/lakeon_test \
go test -tags=e2e ./test/...
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add dbay-rescue/test/e2e_test.go
git commit -m "test(dbay-rescue): E2E list-tenants + rebuild-metadata roundtrip"
```

---

### Task 29: dbay-rescue cross-compile + release script

**Files:**
- Create: `dbay-rescue/Makefile`

- [ ] **Step 1: 写 Makefile**

```makefile
# dbay-rescue/Makefile
.PHONY: build build-all test clean release

VERSION := $(shell git describe --tags --always --dirty)
LDFLAGS := -X main.version=$(VERSION) -s -w

build:
	go build -ldflags "$(LDFLAGS)" -o dbay-rescue ./cmd/dbay-rescue

build-all: clean
	mkdir -p dist
	GOOS=linux   GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-linux-amd64 ./cmd/dbay-rescue
	GOOS=linux   GOARCH=arm64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-linux-arm64 ./cmd/dbay-rescue
	GOOS=darwin  GOARCH=arm64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-darwin-arm64 ./cmd/dbay-rescue
	GOOS=darwin  GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-darwin-amd64 ./cmd/dbay-rescue
	cd dist && shasum -a 256 dbay-rescue-* > SHA256SUMS

test:
	go test ./...

clean:
	rm -rf dist dbay-rescue
```

- [ ] **Step 2: 验证编译**

```bash
cd /Users/jacky/code/lakeon/dbay-rescue && make build-all
ls -lh dist/
```
Expected: 4 个 binary + SHA256SUMS

- [ ] **Step 3: Commit**

```bash
git add dbay-rescue/Makefile
git commit -m "build(dbay-rescue): Makefile 多平台交叉编译"
```

---

## Phase 1.6: Runbook 文档

### Task 30: 灾难恢复 runbook

**Files:**
- Create: `docs/sre/runbook-dbay-rescue.md`

- [ ] **Step 1: 写 runbook**

```markdown
# dbay-rescue 灾难恢复 Runbook

## 何时使用

| 故障 | 用什么 |
|---|---|
| API 短暂挂 | k8s rollout restart deployment/lakeon-api |
| API 镜像坏 | helm rollback |
| 用户误删数据 | 让用户走 Console "Restore" |
| **RDS 挂了** | `dbay-rescue rebuild-metadata` |
| **RDS 挂 + 用户急用** | `dbay-rescue emergency-mount` |

## 前置：拿到 SRE 凭据

凭据离线托管（YubiKey / 加密 USB / 公司保险箱）。

```bash
# 1. 解密拿到 rescue-credentials.yaml
gpg --decrypt rescue-creds.yaml.gpg > ~/.dbay/rescue-credentials.yaml
chmod 600 ~/.dbay/rescue-credentials.yaml

# 2. 下载 dbay-rescue binary (跨平台 release)
curl -L https://internal-release/dbay-rescue/latest/dbay-rescue-darwin-arm64 -o dbay-rescue
shasum -a 256 dbay-rescue  # 对比 SHA256SUMS
chmod +x dbay-rescue
```

## 场景 1: RDS 完全失效

### 步骤

```bash
# 1. 确认 RDS 真的挂了 (而不是网络抖动)
psql "$LAKEON_RDS_DSN" -c "SELECT 1"   # 失败
# 检查华为云 RDS 控制台

# 2. 先扫一下 OBS 看数据范围
./dbay-rescue list-tenants
# 输出类似：
# TENANT_ID                 OWNER_EMAIL                   DBS
# tn_abc123                 alice@example.com             3
# tn_def456                 bob@example.com               1

# 3. 拉一个新 RDS 实例（或恢复旧的）
# 通过华为云控制台创建空的 PostgreSQL RDS 实例

# 4. 在新 RDS 上跑 Flyway migration
cd lakeon-api && mvn flyway:migrate -Dflyway.url=jdbc:postgresql://<new-rds>:5432/lakeon

# 5. 跑 rebuild-metadata
./dbay-rescue rebuild-metadata --to postgresql://<new-rds>:5432/lakeon

# 6. 确认重建结果
psql "postgresql://<new-rds>:5432/lakeon" -c "SELECT COUNT(*) FROM tenants"
# 数字应该和 list-tenants 看到的一致

# 7. 更新 lakeon-api 配置指向新 RDS 重启
KUBECONFIG=~/.kube/cce-lakeon-config kubectl set env deployment/lakeon-api \
    -n lakeon LAKEON_RDS_DSN="postgresql://<new-rds>:5432/lakeon"
kubectl rollout restart deployment/lakeon-api -n lakeon

# 8. 验证服务恢复
curl https://api.dbay.cloud:8443/api/v1/health
```

### 预期 RTO

约 30 分钟（含 RDS 重建时间）。

## 场景 2: RDS 挂时用户急等

如果某个用户在等数据，等不及 rebuild-metadata 全量重建：

```bash
# 1. 先 owner-lookup 确认用户身份
./dbay-rescue owner-lookup --email alice@example.com
# 输出该用户的 tenant_id

# 2. 起临时直连
./dbay-rescue emergency-mount \
    --tenant tn_abc123 \
    --owner alice@example.com

# 输出形如:
# postgresql://em_ab12cd34:<pwd>@em-tn_abc12-x9y8z7:5432/mydb?sslmode=require

# 3. 把连接串发给用户（用安全渠道，如公司 IM 加密私聊）
# 4. 1h 后自动清理
```

### 注意

- `--owner` 必须和 manifest 中的 ownerEmail 完全匹配（防误操作）
- 默认只读。如用户需要写，加 `--writable` 并多记一条 audit
- 所有调用都写到 OBS audit bucket，事后必查

## 场景 3: lakeon-api 部署故障（RDS 还在）

```bash
# 1. 看 pod 状态
kubectl get pods -n lakeon -l app=lakeon-api

# 2. 看日志
kubectl logs -n lakeon -l app=lakeon-api --tail=200

# 3. 如果是镜像问题，rollback
helm rollback lakeon-api

# 4. 如果是配置问题，修配置后 rollout restart
```

## 场景 4: 演练（季度）

每季度在 staging 跑一次：

```bash
# 1. 准备一份 staging OBS bucket 的 read-only 凭据
# 2. 起一个空 RDS
# 3. 跑 rebuild-metadata
./dbay-rescue rebuild-metadata \
    --to postgresql://staging-rds:5432/lakeon_drill

# 4. 抽样 5 个 tenant 验证可访问性
# 5. 记录耗时 + 失败率到 SRE wiki
```

## 不在覆盖范围内的故障

- **OBS region 整挂**：当前不开 Cross-Region Replication。预案：等待华为云 OBS 恢复，期间业务降级
- **OBS 数据被勒索/删**：当前不开 Versioning/Object Lock。预案：依赖华为云事件审计 + 法律手段

后续若纳入这些场景，会另写设计文档。
```

- [ ] **Step 2: Commit**

```bash
git add docs/sre/runbook-dbay-rescue.md
git commit -m "docs(sre): dbay-rescue 灾难恢复 runbook"
```

---

## 最终验证 (Smoke Test)

### Task 31: 端到端冒烟测试

- [ ] **Step 1: lakeon-api 单元测试全过**

```bash
cd lakeon-api && mvn test
```
Expected: PASS

- [ ] **Step 2: lakeon-console type-check + 单测**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit && npm test
```
Expected: PASS

- [ ] **Step 3: API E2E**

```bash
python3 -m pytest tests/e2e/test_pitr.py tests/e2e/test_manifest_writer.py -v
```
Expected: 全 PASS（不能 SKIPPED）

- [ ] **Step 4: Console E2E**

```bash
cd lakeon-console && npm run test:e2e -- restore.spec.ts
```
Expected: PASS

- [ ] **Step 5: dbay-cli 单测**

```bash
cd dbay-cli && python -m pytest tests/test_db_pitr.py -v
```
Expected: PASS

- [ ] **Step 6: dbay-rescue 单测 + 编译**

```bash
cd dbay-rescue && go test ./... && make build-all
```
Expected: PASS + dist/ 4 binaries

- [ ] **Step 7: 手工灾难演练**

按 `docs/sre/runbook-dbay-rescue.md` 场景 4 在 staging 跑一遍 rebuild-metadata。

记录:
- 耗时
- 成功 / 失败 tenant 数
- 任何不顺的地方 → 回到对应 Task 修

- [ ] **Step 8: Final commit**

```bash
git status  # 确认无遗漏
git log --oneline  # 检查 commit 历史清晰
```

---

## 依赖关系图

```
Task 1 (getLsnByTimestamp)
    │
Task 2 (createBranch)
    │
Task 3 (DTOs)
    │
Task 4 (RecoveryService.pitr) ──── Task 5 (getPitrWindow)
    │                                      │
    └────────────── Task 6 (Controller) ───┤
                          │                │
                          └──── Task 7 (E2E pytest)

Phase 1.2 (Console)
    Task 8 (api/recovery.ts) → Task 9 (RestoreDialog) → Task 10 (DatabaseDetail) → Task 11 (Playwright)
    [依赖 Task 6 的 endpoint]

Phase 1.3 (CLI)
    Task 12 (dbay db pitr)
    [依赖 Task 6]

Phase 1.4 (ManifestWriter)
    Task 13 (LakeonObsClient) → Task 14 (ManifestObjects) → Task 15 (Events)
        │                                                       │
        └──────────────── Task 16 (ManifestWriter) ─────────────┤
                                  │                             │
                                  ├── Task 17 (RetryQueue/Scheduler)
                                  └── Task 18 (Service publishes events)
                                            │
                                            └── Task 19 (E2E)

Phase 1.5 (dbay-rescue)
    Task 20 (scaffold) → Task 21 (OBS client) → Task 22 (Pageserver client)
                              │                          │
        Task 23 (list-tenants)─┤                          │
        Task 24 (owner-lookup)─┤                          │
        Task 25 (pitr)─────────┴──────────────────────────┤
        Task 26 (rebuild-metadata) [+ rds client]         │
        Task 27 (emergency-mount) [+ k8s client]──────────┤
                                                          │
                                            Task 28 (E2E)
                                            Task 29 (Makefile)

Phase 1.6
    Task 30 (Runbook)  [依赖所有功能完成]

Task 31 (Smoke test)  [依赖所有]
```

**并行机会**：Phase 1.1 完成后，Phase 1.2 / 1.3 / 1.5 可并行（不同语言、不同模块），Phase 1.4 独立。

---

## 完成标准

- 所有 31 个 task 全部 `[x]`
- `mvn test` / `npm test` / `pytest` / `go test ./...` 全 PASS（不能 SKIPPED）
- Console UI 手工验证：能从浏览器走完 Restore 流程
- `dbay-rescue rebuild-metadata` 在 staging 演练成功
- runbook 文档已 commit

---

## 后续迭代（不在本计划）

- `dbay-sre-mcp` 适配（让 AI agent 调 dbay-rescue 能力）
- 自动 disaster_drill（定时跑 dry-run）
- L6 覆盖（OBS Cross-Region Replication）
- L7 覆盖（Object Lock + Versioning）
- Phase 3：hot standby compute（持续 WAL 重放）
