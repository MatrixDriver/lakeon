# DBay 数据湖 Backend 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `lakeon-api` 中新增 `com.lakeon.datalake` 包，实现 Serverless Ray/Python/微调任务的提交、状态追踪、SSE 日志流和取消功能。

**Architecture:** 扩展现有 Spring Boot 单体（lakeon-api），新增独立的 `datalake_jobs` 表和 `com.lakeon.datalake` 包。Python 任务创建 K8s Job，Ray/微调任务创建 KubeRay RayJob CRD，均通过 VK 调度到 CCI。定时任务轮询 K8s 状态同步到 RDS。

**Tech Stack:** Spring Boot 3.3.5, Java 17, Fabric8 K8s client 6.13.4, Flyway, JUnit 5, MockMvc

**Spec:** `docs/superpowers/specs/2026-03-20-datalake-mvp-design.md`

**前置条件（POC，代码开发前必须完成）：**
- P0: CCE 安装 Virtual Kubelet HA × 2 + VPCEP 开通，验证 Pod 调度到 CCI
- P1: Python Job 单 Pod 端到端 + SSE 日志验证（确认 VK log API 可通）
- P2: KubeRay Operator 安装 + RayJob CRD 端到端验证
- P3: 预置镜像（python:3.11-slim, ray:2.10-py311, ray:2.10-py311-gpu）推送到 SWR

---

## 文件结构

### 新建文件

```
lakeon-api/src/main/resources/db/migration/
  V16__create_datalake_jobs.sql

lakeon-api/src/main/java/com/lakeon/datalake/
  DatalakeJobStatus.java          # enum: PENDING|STARTING|RUNNING|SUCCEEDED|FAILED|CANCELLED
  DatalakeJobType.java            # enum: PYTHON|RAY|FINETUNE
  DatalakeJobEntity.java          # JPA entity → datalake_jobs 表
  DatalakeJobRepository.java      # Spring Data JPA
  DatalakeSubmitRequest.java      # record: 提交请求（带 type 分发）
  DatalakeController.java         # REST endpoints /api/v1/datalake/**
  DatalakeService.java            # 任务生命周期状态机
  PythonJobRunner.java            # 创建 K8s Job → VK → CCI 单 Pod
  RayJobRunner.java               # 创建 RayJob CRD → KubeRay → VK → CCI
  FinetuneJobRunner.java          # 注入内置模板 → 调 RayJobRunner
  DatalakeStatusPoller.java       # @Scheduled: 轮询 K8s 状态同步到 RDS
  DatalakeLogService.java         # SSE 日志流 + OBS 持久化

lakeon-api/src/test/java/com/lakeon/datalake/
  DatalakeControllerTest.java     # MockMvc controller 测试
  DatalakeServiceTest.java        # service 单元测试（mock runners）
  PythonJobRunnerTest.java        # Fabric8 mock client 测试
  RayJobRunnerTest.java           # Fabric8 mock client 测试
```

### 修改文件

```
lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java
  + DatalakeConfig 内部类（namespace, obs prefix, preset images, poll interval）

lakeon-api/src/main/resources/application.yml
  + lakeon.datalake.* 配置节

deploy/helm/lakeon/templates/configmap-api.yaml
  + LAKEON_DATALAKE_* 环境变量

deploy/helm/lakeon/values.yaml
  + datalake 配置节

deploy/cce/sites/hwstaff/values.yaml
  + datalake CCE 环境配置
```

---

## Task 1: DB Migration + Properties 配置

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V16__create_datalake_jobs.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: 创建 Flyway 迁移脚本**

```sql
-- V16__create_datalake_jobs.sql
CREATE TABLE datalake_jobs (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    spec          TEXT         NOT NULL,
    cci_namespace VARCHAR(64),
    ray_job_name  VARCHAR(128),
    k8s_job_name  VARCHAR(128),
    base_image    VARCHAR(256),
    log_obs_path  VARCHAR(512),
    started_at    TIMESTAMP WITH TIME ZONE,
    finished_at   TIMESTAMP WITH TIME ZONE,
    core_hours    DECIMAL(10,4),
    gpu_hours     DECIMAL(10,4),
    error_message TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_datalake_jobs_tenant_id ON datalake_jobs(tenant_id);
CREATE INDEX idx_datalake_jobs_status    ON datalake_jobs(status);
```

- [ ] **Step 2: 在 `LakeonProperties` 中新增 `DatalakeConfig` 内部类**

在 `LakeonProperties.java` 中（参考现有 `K8sConfig` 的写法），添加：

```java
// 在 LakeonProperties 类内部
private DatalakeConfig datalake = new DatalakeConfig();
public DatalakeConfig getDatalake() { return datalake; }
public void setDatalake(DatalakeConfig datalake) { this.datalake = datalake; }

public static class DatalakeConfig {
    private String namespace = "lakeon-datalake";
    private String obsPrefix = "datalake";            // OBS prefix: {obsPrefix}/{tenantId}/jobs/{jobId}/
    private String pollIntervalMs = "10000";          // @Scheduled fixedDelayString 单位毫秒
    // 必须用 new HashMap<>()，不能用 Map.of()——Spring ConfigurationProperties 绑定需要可变 Map
    private Map<String, String> presetImages = new HashMap<>(Map.of(
        "python-slim",   "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-slim",
        "python-data",   "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-data",
        "ray",           "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311",
        "ray-gpu",       "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311-gpu"
    ));
    // getters + setters（pollIntervalMs 对应 poll-interval-ms 属性）
}
```

- [ ] **Step 3: 在 `application.yml` 中添加默认配置**

```yaml
lakeon:
  datalake:
    namespace: ${LAKEON_DATALAKE_NAMESPACE:lakeon-datalake}
    obs-prefix: ${LAKEON_DATALAKE_OBS_PREFIX:datalake}
    poll-interval-ms: ${LAKEON_DATALAKE_POLL_INTERVAL_MS:10000}
```

- [ ] **Step 4: 验证 Flyway 迁移**

```bash
cd lakeon-api
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local" &
sleep 15
curl -s http://localhost:8090/actuator/health | grep -q '"status":"UP"' && echo "Migration OK"
kill %1
```

期望输出：`Migration OK`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V16__create_datalake_jobs.sql \
        lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
        lakeon-api/src/main/resources/application.yml
git commit -m "feat(datalake): add datalake_jobs migration and properties config"
```

---

## Task 2: Domain 层（Entity、Enum、Repository）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRepository.java`

- [ ] **Step 1: 创建状态枚举**

```java
// DatalakeJobStatus.java
package com.lakeon.datalake;

public enum DatalakeJobStatus {
    PENDING,    // 已入队，等待资源
    STARTING,   // CCI Pod 启动中（Ray: Head + Workers 就绪前）
    RUNNING,    // 正在执行
    SUCCEEDED,  // 成功完成
    FAILED,     // 失败
    CANCELLED   // 用户取消
}
```

```java
// DatalakeJobType.java
package com.lakeon.datalake;

public enum DatalakeJobType {
    PYTHON,    // 单容器，python:3.11-slim，无 Ray
    RAY,       // Ray 集群（Head + N Workers），用户自写 Ray 代码
    FINETUNE   // 无代码，内置 Ray Train 模板，GPU
}
```

- [ ] **Step 2: 创建 JPA Entity**

```java
// DatalakeJobEntity.java
package com.lakeon.datalake;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datalake_jobs", indexes = {
    @Index(name = "idx_datalake_jobs_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_datalake_jobs_status",    columnList = "status")
})
public class DatalakeJobEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private DatalakeJobType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DatalakeJobStatus status;

    @Column(name = "spec", nullable = false, columnDefinition = "TEXT")
    private String spec;  // 完整请求 JSON

    @Column(name = "cci_namespace", length = 64)
    private String cciNamespace;

    @Column(name = "ray_job_name", length = 128)
    private String rayJobName;

    @Column(name = "k8s_job_name", length = 128)
    private String k8sJobName;

    @Column(name = "base_image", length = 256)
    private String baseImage;

    @Column(name = "log_obs_path", length = 512)
    private String logObsPath;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "core_hours", precision = 10, scale = 4)
    private BigDecimal coreHours;

    @Column(name = "gpu_hours", precision = 10, scale = 4)
    private BigDecimal gpuHours;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "dlj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // getters + setters（全部生成）
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DatalakeJobType getType() { return type; }
    public void setType(DatalakeJobType type) { this.type = type; }
    public DatalakeJobStatus getStatus() { return status; }
    public void setStatus(DatalakeJobStatus status) { this.status = status; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getCciNamespace() { return cciNamespace; }
    public void setCciNamespace(String cciNamespace) { this.cciNamespace = cciNamespace; }
    public String getRayJobName() { return rayJobName; }
    public void setRayJobName(String rayJobName) { this.rayJobName = rayJobName; }
    public String getK8sJobName() { return k8sJobName; }
    public void setK8sJobName(String k8sJobName) { this.k8sJobName = k8sJobName; }
    public String getBaseImage() { return baseImage; }
    public void setBaseImage(String baseImage) { this.baseImage = baseImage; }
    public String getLogObsPath() { return logObsPath; }
    public void setLogObsPath(String logObsPath) { this.logObsPath = logObsPath; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public BigDecimal getCoreHours() { return coreHours; }
    public void setCoreHours(BigDecimal coreHours) { this.coreHours = coreHours; }
    public BigDecimal getGpuHours() { return gpuHours; }
    public void setGpuHours(BigDecimal gpuHours) { this.gpuHours = gpuHours; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 创建 Repository**

```java
// DatalakeJobRepository.java
package com.lakeon.datalake;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DatalakeJobRepository extends JpaRepository<DatalakeJobEntity, String> {
    List<DatalakeJobEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DatalakeJobEntity> findAllByStatusIn(List<DatalakeJobStatus> statuses);
}
```

- [ ] **Step 4: 编译验证**

```bash
cd lakeon-api && ./mvnw compile -q
```

期望：无编译错误。

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/
git commit -m "feat(datalake): add domain layer — entity, enums, repository"
```

---

## Task 3: DatalakeController + DatalakeService（CRUD 骨架）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/DatalakeControllerTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/DatalakeServiceTest.java`

- [ ] **Step 1: 先写 Controller 测试（失败状态）**

```java
// DatalakeControllerTest.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatalakeController.class)
@Import(ApiKeyFilter.class)
@DisplayName("DatalakeController API 测试")
class DatalakeControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean DatalakeService datalakeService;
    @MockBean TenantService tenantService;
    @MockBean LakeonProperties lakeonProperties;

    private static final String API_KEY = "Bearer test-api-key-valid-32chars!!!";
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity();
        tenant.setId("tn_test001");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
            .thenReturn(tenant);
    }

    @Nested
    @DisplayName("列出任务 — GET /api/v1/datalake/jobs")
    class ListJobs {

        @Test
        @DisplayName("DL-001: 正常列出，返回 200 + 数组")
        void list_returns200() throws Exception {
            var job = buildJobEntity("dlj_abc123", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            when(datalakeService.listJobs("tn_test001")).thenReturn(List.of(job));

            mockMvc.perform(get("/api/v1/datalake/jobs").header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("dlj_abc123"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].type").value("PYTHON"));
        }

        @Test
        @DisplayName("DL-002: 无 API Key 返回 401")
        void list_noAuth_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/datalake/jobs"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("获取任务详情 — GET /api/v1/datalake/jobs/{id}")
    class GetJob {

        @Test
        @DisplayName("DL-003: 存在的任务返回 200")
        void get_exists_returns200() throws Exception {
            var job = buildJobEntity("dlj_abc123", DatalakeJobType.RAY, DatalakeJobStatus.SUCCEEDED);
            when(datalakeService.getJob("tn_test001", "dlj_abc123")).thenReturn(job);

            mockMvc.perform(get("/api/v1/datalake/jobs/dlj_abc123").header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("dlj_abc123"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        }

        @Test
        @DisplayName("DL-004: 不存在的任务返回 404")
        void get_notFound_returns404() throws Exception {
            when(datalakeService.getJob("tn_test001", "dlj_missing"))
                .thenThrow(new com.lakeon.service.exception.NotFoundException("not found"));

            mockMvc.perform(get("/api/v1/datalake/jobs/dlj_missing").header("Authorization", API_KEY))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("取消任务 — DELETE /api/v1/datalake/jobs/{id}")
    class CancelJob {

        @Test
        @DisplayName("DL-005: 取消运行中的任务返回 204")
        void cancel_running_returns204() throws Exception {
            doNothing().when(datalakeService).cancelJob("tn_test001", "dlj_abc123");

            mockMvc.perform(delete("/api/v1/datalake/jobs/dlj_abc123").header("Authorization", API_KEY))
                .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("提交任务 — POST /api/v1/datalake/jobs")
    class SubmitJob {

        @Test
        @DisplayName("DL-006: 提交 Python 任务返回 201")
        void submit_python_returns201() throws Exception {
            var body = Map.of(
                "name", "my-etl",
                "type", "PYTHON",
                "entrypoint", "python main.py",
                "baseImage", "python-slim",
                "resources", Map.of("cpu", "2", "memory", "4Gi"),
                "timeoutSeconds", 3600
            );
            var job = buildJobEntity("dlj_new001", DatalakeJobType.PYTHON, DatalakeJobStatus.PENDING);
            when(datalakeService.submitJob(eq(tenant), any())).thenReturn(job);

            mockMvc.perform(post("/api/v1/datalake/jobs")
                    .header("Authorization", API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("dlj_new001"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        }
    }

    @Nested
    @DisplayName("日志流 — GET /api/v1/datalake/jobs/{id}/logs")
    class StreamLogs {

        @Test
        @DisplayName("DL-007: 任务不存在返回 404")
        void logs_notFound_returns404() throws Exception {
            when(datalakeService.getJob("tn_test001", "dlj_missing"))
                .thenThrow(new com.lakeon.service.exception.NotFoundException("not found"));

            mockMvc.perform(get("/api/v1/datalake/jobs/dlj_missing/logs")
                    .header("Authorization", API_KEY))
                .andExpect(status().isNotFound());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private DatalakeJobEntity buildJobEntity(String id, DatalakeJobType type, DatalakeJobStatus status) {
        var job = new DatalakeJobEntity();
        job.setId(id);
        job.setTenantId("tn_test001");
        job.setName("test-job");
        job.setType(type);
        job.setStatus(status);
        job.setSpec("{}");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return job;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd lakeon-api
./mvnw test -Dtest=DatalakeControllerTest -q 2>&1 | tail -5
```

期望：`COMPILATION ERROR` 或 `ClassNotFoundException`（DatalakeController 还不存在）

- [ ] **Step 3: 实现 DatalakeController**

```java
// DatalakeController.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datalake")
public class DatalakeController {

    private final DatalakeService datalakeService;
    private final DatalakeLogService datalakeLogService;

    public DatalakeController(DatalakeService datalakeService, DatalakeLogService datalakeLogService) {
        this.datalakeService = datalakeService;
        this.datalakeLogService = datalakeLogService;
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(HttpServletRequest req) {
        TenantEntity tenant = getTenant(req);
        return datalakeService.listJobs(tenant.getId()).stream()
            .map(this::toResponse)
            .toList();
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> getJob(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return toResponse(datalakeService.getJob(tenant.getId(), id));
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitJob(HttpServletRequest req,
                                         @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        return toResponse(datalakeService.submitJob(tenant, body));
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelJob(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        datalakeService.cancelJob(tenant.getId(), id);
    }

    @GetMapping(value = "/jobs/{id}/logs", produces = "text/event-stream")
    public SseEmitter streamLogs(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        datalakeService.getJob(tenant.getId(), id);  // 验证存在且属于本租户
        return datalakeLogService.streamLogs(id);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toResponse(DatalakeJobEntity job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          job.getId());
        m.put("name",        job.getName());
        m.put("type",        job.getType().name());
        m.put("status",      job.getStatus().name());
        m.put("base_image",  job.getBaseImage());
        m.put("cci_namespace", job.getCciNamespace());
        m.put("started_at",  job.getStartedAt());
        m.put("finished_at", job.getFinishedAt());
        m.put("core_hours",  job.getCoreHours());
        m.put("gpu_hours",   job.getGpuHours());
        m.put("error_message", job.getErrorMessage());
        m.put("created_at",  job.getCreatedAt());
        return m;
    }
}
```

- [ ] **Step 4: 实现 DatalakeService 骨架**

```java
// DatalakeService.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DatalakeService {
    private static final Logger log = LoggerFactory.getLogger(DatalakeService.class);
    private static final Set<DatalakeJobStatus> TERMINAL =
        Set.of(DatalakeJobStatus.SUCCEEDED, DatalakeJobStatus.FAILED, DatalakeJobStatus.CANCELLED);

    private final DatalakeJobRepository repo;
    private final PythonJobRunner pythonRunner;
    private final RayJobRunner rayRunner;
    private final FinetuneJobRunner finetuneRunner;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public DatalakeService(DatalakeJobRepository repo,
                           PythonJobRunner pythonRunner,
                           RayJobRunner rayRunner,
                           FinetuneJobRunner finetuneRunner,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.pythonRunner = pythonRunner;
        this.rayRunner = rayRunner;
        this.finetuneRunner = finetuneRunner;
        this.objectMapper = objectMapper;
    }

    public List<DatalakeJobEntity> listJobs(String tenantId) {
        return repo.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public DatalakeJobEntity getJob(String tenantId, String jobId) {
        DatalakeJobEntity job = repo.findById(jobId)
            .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
        if (!job.getTenantId().equals(tenantId)) {
            throw new NotFoundException("Job not found: " + jobId);  // 403 → 404 隐藏存在性
        }
        return job;
    }

    @Transactional
    public DatalakeJobEntity submitJob(TenantEntity tenant, Map<String, Object> body) {
        String typeStr = (String) body.get("type");
        if (typeStr == null) throw new com.lakeon.service.exception.BadRequestException("type is required");
        DatalakeJobType type;
        try {
            type = DatalakeJobType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.lakeon.service.exception.BadRequestException("Invalid type: " + typeStr);
        }

        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new com.lakeon.service.exception.BadRequestException("name is required");

        DatalakeJobEntity job = new DatalakeJobEntity();
        job.setTenantId(tenant.getId());
        job.setName(name);
        job.setType(type);
        job.setStatus(DatalakeJobStatus.PENDING);
        job.setCciNamespace("datalake-" + tenant.getId());

        try {
            job.setSpec(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize spec", e);
        }

        repo.save(job);
        log.info("Submitted datalake job {} ({}) for tenant {}", job.getId(), type, tenant.getId());

        String jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(() -> launchJob(jobId));
            }
        });

        return job;
    }

    @Transactional
    public void cancelJob(String tenantId, String jobId) {
        DatalakeJobEntity job = getJob(tenantId, jobId);
        if (TERMINAL.contains(job.getStatus())) return;  // 幂等
        // 取消 K8s 资源
        try {
            switch (job.getType()) {
                case PYTHON   -> pythonRunner.cancel(job);
                case RAY      -> rayRunner.cancel(job);
                case FINETUNE -> rayRunner.cancel(job);
            }
        } catch (Exception e) {
            log.warn("Failed to cancel K8s resource for job {}: {}", jobId, e.getMessage());
        }
        job.setStatus(DatalakeJobStatus.CANCELLED);
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    private void launchJob(String jobId) {
        try {
            DatalakeJobEntity job = repo.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
            if (job.getStatus() != DatalakeJobStatus.PENDING) return;

            job.setStatus(DatalakeJobStatus.STARTING);
            repo.save(job);

            switch (job.getType()) {
                case PYTHON   -> pythonRunner.launch(job);
                case RAY      -> rayRunner.launch(job);
                case FINETUNE -> finetuneRunner.launch(job);
            }
            log.info("Launched datalake job {}", jobId);
        } catch (Exception e) {
            log.error("Failed to launch datalake job {}: {}", jobId, e.getMessage(), e);
            repo.findById(jobId).ifPresent(job -> {
                job.setStatus(DatalakeJobStatus.FAILED);
                job.setErrorMessage("Launch failed: " + e.getMessage());
                job.setFinishedAt(Instant.now());
                repo.save(job);
            });
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd lakeon-api && ./mvnw test -Dtest=DatalakeControllerTest -q 2>&1 | tail -10
```

期望：`Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeController.java \
        lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java \
        lakeon-api/src/test/java/com/lakeon/datalake/DatalakeControllerTest.java
git commit -m "feat(datalake): add controller and service skeleton with CRUD tests"
```

---

## Task 4: PythonJobRunner（K8s Job → VK → CCI 单 Pod）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java`

- [ ] **Step 1: 先写失败测试**

```java
// PythonJobRunnerTest.java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
@DisplayName("PythonJobRunner 单元测试")
class PythonJobRunnerTest {

    KubernetesClient client;       // 由 @EnableKubernetesMockClient 注入

    PythonJobRunner runner;
    LakeonProperties props;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setNamespace("lakeon-datalake");
        props.getDatalake().getPresetImages().put(
            "python-slim", "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-slim");
        runner = new PythonJobRunner(client, props);
    }

    @Test
    @DisplayName("launch() 创建 K8s Job 并设置正确的镜像和 namespace")
    void launch_createsBatchJob() {
        DatalakeJobEntity job = buildJob(DatalakeJobType.PYTHON,
            "{\"baseImage\":\"python-slim\",\"entrypoint\":\"python main.py\",\"resources\":{\"cpu\":\"2\",\"memory\":\"4Gi\"}}");

        runner.launch(job);

        Job created = client.batch().v1().jobs()
            .inNamespace("lakeon-datalake")
            .withName(job.getK8sJobName())
            .get();
        assertThat(created).isNotNull();
        assertThat(created.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())
            .contains("python:3.11-slim");
    }

    @Test
    @DisplayName("cancel() 删除 K8s Job")
    void cancel_deletesJob() {
        DatalakeJobEntity job = buildJob(DatalakeJobType.PYTHON, "{}");
        runner.launch(job);
        runner.cancel(job);

        Job remaining = client.batch().v1().jobs()
            .inNamespace("lakeon-datalake")
            .withName(job.getK8sJobName())
            .get();
        assertThat(remaining).isNull();
    }

    private DatalakeJobEntity buildJob(DatalakeJobType type, String spec) {
        var j = new DatalakeJobEntity();
        j.setId("dlj_test001");
        j.setTenantId("tn_test001");
        j.setName("test-job");
        j.setType(type);
        j.setStatus(DatalakeJobStatus.STARTING);
        j.setSpec(spec);
        j.setCciNamespace("datalake-tn_test001");
        j.setCreatedAt(Instant.now());
        j.setUpdatedAt(Instant.now());
        return j;
    }
}
```

- [ ] **Step 2: 确认测试失败**

```bash
cd lakeon-api && ./mvnw test -Dtest=PythonJobRunnerTest -q 2>&1 | tail -5
```

期望：`ClassNotFoundException` 或编译错误

- [ ] **Step 3: 实现 PythonJobRunner**

```java
// PythonJobRunner.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PythonJobRunner {
    private static final Logger log = LoggerFactory.getLogger(PythonJobRunner.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    public PythonJobRunner(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    public void launch(DatalakeJobEntity job) {
        Map<String, Object> spec = parseSpec(job.getSpec());
        String imageKey  = (String) spec.getOrDefault("baseImage", "python-slim");
        String image     = props.getDatalake().getPresetImages().getOrDefault(imageKey, imageKey);
        String entrypoint = (String) spec.getOrDefault("entrypoint", "python main.py");
        Map<?, ?> resources = (Map<?, ?>) spec.getOrDefault("resources", Map.of("cpu", "1", "memory", "2Gi"));

        String jobName = "py-" + job.getId().toLowerCase();
        job.setK8sJobName(jobName);
        job.setBaseImage(image);

        String ns = props.getDatalake().getNamespace();

        Job k8sJob = new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(ns)
                .addToLabels("app", "datalake-python")
                .addToLabels("datalake-job-id", job.getId())
                .addToLabels("tenant-id", job.getTenantId())
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(0)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", "datalake-python")
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        // VK node selector — Pod 调度到 Virtual Kubelet 节点，透明转发到 CCI
                        .addToNodeSelector("type", "virtual-kubelet")
                        .withTolerations(new TolerationBuilder()
                            .withKey("virtual-kubelet.io/provider")
                            .withOperator("Exists")
                            .build())
                        .withContainers(new ContainerBuilder()
                            .withName("main")
                            .withImage(image)
                            .withCommand("sh", "-c", entrypoint)
                            .withResources(new ResourceRequirementsBuilder()
                                .addToRequests("cpu",    new Quantity((String) resources.get("cpu")))
                                .addToRequests("memory", new Quantity((String) resources.get("memory")))
                                .addToLimits("cpu",      new Quantity((String) resources.get("cpu")))
                                .addToLimits("memory",   new Quantity((String) resources.get("memory")))
                                .build())
                            .withEnv(
                                new EnvVarBuilder().withName("TENANT_ID").withValue(job.getTenantId()).build(),
                                new EnvVarBuilder().withName("JOB_ID").withValue(job.getId()).build()
                            )
                            .build())
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();

        k8sClient.batch().v1().jobs().inNamespace(ns).resource(k8sJob).create();
        log.info("Created K8s Job {} for datalake job {}", jobName, job.getId());
    }

    public void cancel(DatalakeJobEntity job) {
        if (job.getK8sJobName() == null) return;
        String ns = props.getDatalake().getNamespace();
        k8sClient.batch().v1().jobs().inNamespace(ns).withName(job.getK8sJobName())
            .withPropagationPolicy("Background").delete();
        log.info("Deleted K8s Job {} for datalake job {}", job.getK8sJobName(), job.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSpec(String specJson) {
        try {
            return new ObjectMapper().readValue(specJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd lakeon-api && ./mvnw test -Dtest=PythonJobRunnerTest -q 2>&1 | tail -5
```

期望：`Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java \
        lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java
git commit -m "feat(datalake): add PythonJobRunner — K8s Job → VK → CCI"
```

---

## Task 5: RayJobRunner（RayJob CRD → KubeRay → VK → CCI）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/RayJobRunner.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/RayJobRunnerTest.java`

- [ ] **Step 1: 先写失败测试**

```java
// RayJobRunnerTest.java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
@DisplayName("RayJobRunner 单元测试")
class RayJobRunnerTest {

    KubernetesClient client;

    RayJobRunner runner;
    LakeonProperties props;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setNamespace("lakeon-datalake");
        props.getDatalake().getPresetImages().put(
            "ray", "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311");
        runner = new RayJobRunner(client, props);
    }

    @Test
    @DisplayName("launch() 创建 RayJob 自定义资源")
    void launch_createsRayJobCrd() {
        DatalakeJobEntity job = buildJob("{\"baseImage\":\"ray\",\"entrypoint\":\"python train.py\"," +
            "\"head\":{\"cpu\":\"2\",\"memory\":\"4Gi\"},\"workers\":{\"count\":2,\"cpu\":\"4\",\"memory\":\"8Gi\"}}");

        runner.launch(job);

        assertThat(job.getRayJobName()).isNotNull().startsWith("ray-");
        // 验证 CRD 资源在 mock server 中存在
        var resource = client.genericKubernetesResources(RayJobRunner.RAY_JOB_CONTEXT)
            .inNamespace("lakeon-datalake")
            .withName(job.getRayJobName())
            .get();
        assertThat(resource).isNotNull();
    }

    @Test
    @DisplayName("cancel() 删除 RayJob CRD")
    void cancel_deletesRayJob() {
        DatalakeJobEntity job = buildJob("{\"baseImage\":\"ray\",\"entrypoint\":\"python train.py\"," +
            "\"head\":{\"cpu\":\"2\",\"memory\":\"4Gi\"},\"workers\":{\"count\":1,\"cpu\":\"2\",\"memory\":\"4Gi\"}}");
        runner.launch(job);
        runner.cancel(job);

        var resource = client.genericKubernetesResources(RayJobRunner.RAY_JOB_CONTEXT)
            .inNamespace("lakeon-datalake")
            .withName(job.getRayJobName())
            .get();
        assertThat(resource).isNull();
    }

    private DatalakeJobEntity buildJob(String spec) {
        var j = new DatalakeJobEntity();
        j.setId("dlj_ray001");
        j.setTenantId("tn_test001");
        j.setName("test-ray");
        j.setType(DatalakeJobType.RAY);
        j.setStatus(DatalakeJobStatus.STARTING);
        j.setSpec(spec);
        j.setCciNamespace("datalake-tn_test001");
        j.setCreatedAt(Instant.now());
        j.setUpdatedAt(Instant.now());
        return j;
    }
}
```

- [ ] **Step 2: 实现 RayJobRunner**

```java
// RayJobRunner.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RayJobRunner {
    private static final Logger log = LoggerFactory.getLogger(RayJobRunner.class);

    // KubeRay RayJob CRD 元信息
    public static final CustomResourceDefinitionContext RAY_JOB_CONTEXT =
        new CustomResourceDefinitionContext.Builder()
            .withGroup("ray.io")
            .withVersion("v1")
            .withScope("Namespaced")
            .withPlural("rayjobs")
            .withKind("RayJob")
            .build();

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    public RayJobRunner(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    public void launch(DatalakeJobEntity job) {
        Map<String, Object> spec = parseSpec(job.getSpec());
        String imageKey  = (String) spec.getOrDefault("baseImage", "ray");
        String image     = props.getDatalake().getPresetImages().getOrDefault(imageKey, imageKey);
        String entrypoint = (String) spec.getOrDefault("entrypoint", "python main.py");
        Map<?, ?> head    = (Map<?, ?>) spec.getOrDefault("head", Map.of("cpu", "2", "memory", "4Gi"));
        Map<?, ?> workers = (Map<?, ?>) spec.getOrDefault("workers", Map.of("count", 1, "cpu", "2", "memory", "4Gi"));
        int workerCount = ((Number) workers.getOrDefault("count", 1)).intValue();

        String rayJobName = "ray-" + job.getId().toLowerCase();
        job.setRayJobName(rayJobName);
        job.setBaseImage(image);

        String ns = props.getDatalake().getNamespace();

        // RayJob CRD spec — 对应 KubeRay v1 schema
        Map<String, Object> rayJobSpec = new LinkedHashMap<>();
        rayJobSpec.put("entrypoint", entrypoint);
        rayJobSpec.put("shutdownAfterJobFinishes", true);
        rayJobSpec.put("ttlSecondsAfterFinished", 300);

        // RayClusterSpec
        Map<String, Object> clusterSpec = new LinkedHashMap<>();

        // Head group
        Map<String, Object> headGroup = new LinkedHashMap<>();
        headGroup.put("rayStartParams", Map.of());
        headGroup.put("template", podTemplate(image, head, job.getTenantId(), job.getId(), true));
        clusterSpec.put("headGroupSpec", headGroup);

        // Worker group
        Map<String, Object> workerGroup = new LinkedHashMap<>();
        workerGroup.put("groupName", "worker-group");
        workerGroup.put("replicas", workerCount);
        workerGroup.put("minReplicas", workerCount);
        workerGroup.put("maxReplicas", workerCount);
        workerGroup.put("rayStartParams", Map.of());
        workerGroup.put("template", podTemplate(image, workers, job.getTenantId(), job.getId(), false));
        clusterSpec.put("workerGroupSpecs", List.of(workerGroup));

        rayJobSpec.put("rayClusterSpec", clusterSpec);

        GenericKubernetesResource rayJob = new GenericKubernetesResourceBuilder()
            .withApiVersion("ray.io/v1")
            .withKind("RayJob")
            .withNewMetadata()
                .withName(rayJobName)
                .withNamespace(ns)
                .addToLabels("datalake-job-id", job.getId())
                .addToLabels("tenant-id", job.getTenantId())
            .endMetadata()
            .build();
        rayJob.setAdditionalProperties(Map.of("spec", rayJobSpec));

        k8sClient.genericKubernetesResources(RAY_JOB_CONTEXT)
            .inNamespace(ns).resource(rayJob).create();
        log.info("Created RayJob {} for datalake job {}", rayJobName, job.getId());
    }

    public void cancel(DatalakeJobEntity job) {
        if (job.getRayJobName() == null) return;
        String ns = props.getDatalake().getNamespace();
        k8sClient.genericKubernetesResources(RAY_JOB_CONTEXT)
            .inNamespace(ns).withName(job.getRayJobName())
            .withPropagationPolicy("Background").delete();
        log.info("Deleted RayJob {} for datalake job {}", job.getRayJobName(), job.getId());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    protected Map<String, Object> podTemplate(String image, Map<?, ?> res,
                                               String tenantId, String jobId, boolean isHead) {
        Map<String, Object> requests = Map.of(
            "cpu",    res.getOrDefault("cpu", "2"),
            "memory", res.getOrDefault("memory", "4Gi")
        );
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", isHead ? "ray-head" : "ray-worker");
        container.put("image", image);
        container.put("resources", Map.of("requests", requests, "limits", requests));
        container.put("env", List.of(
            Map.of("name", "TENANT_ID", "value", tenantId),
            Map.of("name", "JOB_ID",    "value", jobId)
        ));

        return Map.of("spec", Map.of(
            // VK node selector — 调度到 CCI
            "nodeSelector",  Map.of("type", "virtual-kubelet"),
            "tolerations", List.of(Map.of(
                "key", "virtual-kubelet.io/provider", "operator", "Exists")),
            "containers", List.of(container)
        ));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseSpec(String specJson) {
        try {
            return new ObjectMapper().readValue(specJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-api && ./mvnw test -Dtest=RayJobRunnerTest -q 2>&1 | tail -5
```

期望：`Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/RayJobRunner.java \
        lakeon-api/src/test/java/com/lakeon/datalake/RayJobRunnerTest.java
git commit -m "feat(datalake): add RayJobRunner — RayJob CRD via KubeRay"
```

---

## Task 6: FinetuneJobRunner（内置 Ray Train 模板）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/FinetuneJobRunner.java`

- [ ] **Step 1: 实现 FinetuneJobRunner**

FinetuneJobRunner 将表单参数转为 Ray Train 启动命令，复用 `RayJobRunner.launch()`：

```java
// FinetuneJobRunner.java
package com.lakeon.datalake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FinetuneJobRunner {
    private static final Logger log = LoggerFactory.getLogger(FinetuneJobRunner.class);

    // 支持的基础模型 → Hugging Face 模型 ID
    private static final Map<String, String> BASE_MODELS = Map.of(
        "Qwen2.5-7B",  "Qwen/Qwen2.5-7B-Instruct",
        "LLaMA3-8B",   "meta-llama/Meta-Llama-3-8B-Instruct"
    );

    private final RayJobRunner rayJobRunner;

    public FinetuneJobRunner(RayJobRunner rayJobRunner) {
        this.rayJobRunner = rayJobRunner;
    }

    public void launch(DatalakeJobEntity job) {
        Map<String, Object> userSpec = rayJobRunner.parseSpec(job.getSpec());
        Map<String, Object> raySpec  = buildRaySpec(userSpec, job);

        // 暂存原始 spec（用户填写的 finetune 参数），启动后恢复
        // 原因：RayJobRunner.launch() 会读 job.spec 构建 Ray 集群，需要传入转换后的 raySpec
        // 但 datalake_jobs.spec 列存的应是用户原始请求，便于审计和重试
        String originalSpec = job.getSpec();
        try {
            job.setSpec(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raySpec));
            rayJobRunner.launch(job);
        } catch (Exception e) {
            throw new RuntimeException("Failed to launch finetune job", e);
        } finally {
            job.setSpec(originalSpec);  // 无论成功失败都恢复原始 spec
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> buildRaySpec(Map<String, Object> userSpec, DatalakeJobEntity job) {
        String modelKey    = (String) userSpec.getOrDefault("baseModel", "Qwen2.5-7B");
        String modelId     = BASE_MODELS.getOrDefault(modelKey, modelKey);
        String datasetPath = (String) userSpec.getOrDefault("datasetPath", "");
        String outputPath  = (String) userSpec.getOrDefault("outputPath", "");
        Map<?, ?> hp       = (Map<?, ?>) userSpec.getOrDefault("hyperparams", Map.of());
        Map<?, ?> gpu      = (Map<?, ?>) userSpec.getOrDefault("gpu", Map.of("type", "V100", "count", 1));

        int gpuCount = ((Number) gpu.getOrDefault("count", 1)).intValue();

        // 生成 Ray Train 启动命令（调用内置脚本 /app/finetune.py）
        String entrypoint = String.format(
            "python /app/finetune.py " +
            "--model-id %s " +
            "--dataset-path %s " +
            "--output-path %s " +
            "--epochs %s " +
            "--batch-size %s " +
            "--learning-rate %s " +
            "--lora-rank %s " +
            "--num-workers %d",
            modelId, datasetPath, outputPath,
            hp.getOrDefault("epochs", 3),
            hp.getOrDefault("batchSize", 4),
            hp.getOrDefault("learningRate", "2e-4"),
            hp.getOrDefault("loraRank", 16),
            gpuCount
        );

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("baseImage",    "ray-gpu");
        spec.put("entrypoint",   entrypoint);
        spec.put("head",         Map.of("cpu", "4", "memory", "8Gi"));
        spec.put("workers",      Map.of(
            "count",  gpuCount,
            "cpu",    "8",
            "memory", "32Gi",
            "gpu",    Map.of("nvidia.com/gpu", "1")
        ));

        log.info("Finetune job {}: model={}, dataset={}", job.getId(), modelId, datasetPath);
        return spec;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd lakeon-api && ./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/FinetuneJobRunner.java
git commit -m "feat(datalake): add FinetuneJobRunner — no-code Ray Train template"
```

---

## Task 7: DatalakeStatusPoller（定时状态同步）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeStatusPoller.java`

状态同步逻辑：轮询非终态的 datalake 任务，查询对应的 K8s Job / RayJob 状态，同步到 RDS。

- [ ] **Step 1: 实现 DatalakeStatusPoller**

```java
// DatalakeStatusPoller.java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class DatalakeStatusPoller {
    private static final Logger log = LoggerFactory.getLogger(DatalakeStatusPoller.class);

    private final DatalakeJobRepository repo;
    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    public DatalakeStatusPoller(DatalakeJobRepository repo,
                                KubernetesClient k8sClient,
                                LakeonProperties props) {
        this.repo = repo;
        this.k8sClient = k8sClient;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${lakeon.datalake.poll-interval-ms:10000}")  // 与 DatalakeConfig.pollIntervalMs 一致
    @Transactional
    public void poll() {
        List<DatalakeJobEntity> activeJobs = repo.findAllByStatusIn(
            List.of(DatalakeJobStatus.STARTING, DatalakeJobStatus.RUNNING));

        for (DatalakeJobEntity job : activeJobs) {
            try {
                syncStatus(job);
            } catch (Exception e) {
                log.warn("Failed to sync status for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    private void syncStatus(DatalakeJobEntity job) {
        String ns = props.getDatalake().getNamespace();

        switch (job.getType()) {
            case PYTHON -> syncK8sJob(job, ns);
            case RAY, FINETUNE -> syncRayJob(job, ns);
        }
    }

    private void syncK8sJob(DatalakeJobEntity job, String ns) {
        if (job.getK8sJobName() == null) return;
        Job k8sJob = k8sClient.batch().v1().jobs()
            .inNamespace(ns).withName(job.getK8sJobName()).get();
        if (k8sJob == null) return;

        var cond = k8sJob.getStatus();
        if (cond == null) return;

        if (cond.getActive() != null && cond.getActive() > 0) {
            if (job.getStatus() == DatalakeJobStatus.STARTING) {
                job.setStatus(DatalakeJobStatus.RUNNING);
                job.setStartedAt(Instant.now());
                repo.save(job);
            }
        } else if (cond.getSucceeded() != null && cond.getSucceeded() > 0) {
            markFinished(job, DatalakeJobStatus.SUCCEEDED, null);
        } else if (cond.getFailed() != null && cond.getFailed() > 0) {
            markFinished(job, DatalakeJobStatus.FAILED, "K8s Job failed");
        }
    }

    @SuppressWarnings("unchecked")
    private void syncRayJob(DatalakeJobEntity job, String ns) {
        if (job.getRayJobName() == null) return;
        GenericKubernetesResource rayJob = k8sClient
            .genericKubernetesResources(RayJobRunner.RAY_JOB_CONTEXT)
            .inNamespace(ns).withName(job.getRayJobName()).get();
        if (rayJob == null) return;

        Map<String, Object> status = (Map<String, Object>) rayJob.getAdditionalProperties()
            .getOrDefault("status", Map.of());
        String phase = (String) status.get("jobStatus");  // KubeRay: PENDING/RUNNING/SUCCEEDED/FAILED
        if (phase == null) return;

        switch (phase) {
            case "RUNNING" -> {
                if (job.getStatus() == DatalakeJobStatus.STARTING) {
                    job.setStatus(DatalakeJobStatus.RUNNING);
                    job.setStartedAt(Instant.now());
                    repo.save(job);
                }
            }
            case "SUCCEEDED" -> markFinished(job, DatalakeJobStatus.SUCCEEDED, null);
            case "FAILED"    -> markFinished(job, DatalakeJobStatus.FAILED, "RayJob failed");
        }
    }

    private void markFinished(DatalakeJobEntity job, DatalakeJobStatus status, String error) {
        job.setStatus(status);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(error);

        // 计算 core·hours
        if (job.getStartedAt() != null) {
            double hours = (Instant.now().toEpochMilli() - job.getStartedAt().toEpochMilli()) / 3_600_000.0;
            job.setCoreHours(BigDecimal.valueOf(hours).setScale(4, java.math.RoundingMode.HALF_UP));
        }

        repo.save(job);
        log.info("Datalake job {} → {}", job.getId(), status);
    }
}
```

- [ ] **Step 2: 确保 `@EnableScheduling` 已开启**

检查 `LakeonApplication.java`，确认有 `@EnableScheduling`（参考现有的 `JobScheduledTasks` 已在运行，应该已有）。如果没有，添加：

```bash
grep -r "EnableScheduling" lakeon-api/src/main/java/
```

如无结果，在 `LakeonApplication.java` 添加 `@EnableScheduling`。

- [ ] **Step 3: 编译并运行全量测试**

```bash
cd lakeon-api && ./mvnw test -q 2>&1 | tail -10
```

期望：所有既有测试通过，无新的失败。

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeStatusPoller.java
git commit -m "feat(datalake): add status poller — sync K8s Job/RayJob status to RDS"
```

---

## Task 8: DatalakeLogService（SSE 日志流）

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeLogService.java`

- [ ] **Step 1: 实现 DatalakeLogService**

```java
// DatalakeLogService.java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DatalakeLogService {
    private static final Logger log = LoggerFactory.getLogger(DatalakeLogService.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 分钟

    private final DatalakeJobRepository repo;
    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public DatalakeLogService(DatalakeJobRepository repo,
                               KubernetesClient k8sClient,
                               LakeonProperties props) {
        this.repo = repo;
        this.k8sClient = k8sClient;
        this.props = props;
    }

    /**
     * 返回 SSE emitter，后台线程流式推送日志。
     * - 运行中：通过 K8s log API（via VK）实时推送
     * - 已完成：从 OBS log_obs_path 读取（TODO: Phase 2，当前返回空）
     * - STARTING：轮询等待 Pod 就绪后再推
     */
    public SseEmitter streamLogs(String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        executor.submit(() -> {
            try {
                DatalakeJobEntity job = repo.findById(jobId).orElseThrow();
                String ns = props.getDatalake().getNamespace();

                switch (job.getStatus()) {
                    case PENDING, STARTING -> {
                        emitter.send(SseEmitter.event().data("[Waiting for job to start...]"));
                        // 等待最多 5 分钟
                        for (int i = 0; i < 60; i++) {
                            Thread.sleep(5000);
                            job = repo.findById(jobId).orElseThrow();
                            if (job.getStatus() == DatalakeJobStatus.RUNNING) break;
                            if (isTerminal(job.getStatus())) {
                                emitter.send(SseEmitter.event().data("[Job finished: " + job.getStatus() + "]"));
                                emitter.complete();
                                return;
                            }
                        }
                        streamRunningLogs(job, ns, emitter);
                    }
                    case RUNNING -> streamRunningLogs(job, ns, emitter);
                    default -> {
                        emitter.send(SseEmitter.event().data("[Job is " + job.getStatus() + "]"));
                        emitter.complete();
                    }
                }
            } catch (Exception e) {
                log.warn("Log stream error for job {}: {}", jobId, e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void streamRunningLogs(DatalakeJobEntity job, String ns, SseEmitter emitter) throws Exception {
        // 找到对应的 Pod（K8s Job: label datalake-job-id; RayJob: head pod）
        String labelSelector = "datalake-job-id=" + job.getId();
        var pods = k8sClient.pods().inNamespace(ns).withLabel("datalake-job-id", job.getId()).list().getItems();

        if (pods.isEmpty()) {
            emitter.send(SseEmitter.event().data("[No pods found yet]"));
            emitter.complete();
            return;
        }

        for (var pod : pods) {
            String podName = pod.getMetadata().getName();
            emitter.send(SseEmitter.event().data("=== Pod: " + podName + " ==="));
            try (InputStream logStream = k8sClient.pods().inNamespace(ns).withName(podName)
                    .sinceSeconds(0).watchLog().getOutput()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = logStream.read(buf)) != -1) {
                    emitter.send(SseEmitter.event().data(new String(buf, 0, n)));
                }
            } catch (Exception e) {
                emitter.send(SseEmitter.event().data("[Log error for pod " + podName + ": " + e.getMessage() + "]"));
            }
        }

        emitter.complete();
    }

    private boolean isTerminal(DatalakeJobStatus s) {
        return s == DatalakeJobStatus.SUCCEEDED
            || s == DatalakeJobStatus.FAILED
            || s == DatalakeJobStatus.CANCELLED;
    }
}
```

- [ ] **Step 2: 将 DatalakeLogService 注入 DatalakeController**

确认 `DatalakeController.java` 构造函数已包含 `DatalakeLogService`（Task 3 中已写入）。

- [ ] **Step 3: 全量编译测试**

```bash
cd lakeon-api && ./mvnw test -q 2>&1 | tail -10
```

期望：全部通过。

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeLogService.java
git commit -m "feat(datalake): add SSE log streaming service"
```

---

## Task 9: Helm 配置

**Files:**
- Modify: `deploy/helm/lakeon/templates/configmap-api.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`
- Modify: `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: 在 `values.yaml` 添加 datalake 配置节**

```yaml
# deploy/helm/lakeon/values.yaml 中添加：
datalake:
  namespace: lakeon-datalake
  obsPrefix: datalake
  pollIntervalMs: "10000"
  presetImages:
    pythonSlim: "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-slim"
    pythonData: "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-data"
    ray:        "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311"
    rayGpu:     "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311-gpu"
```

- [ ] **Step 2: 在 `configmap-api.yaml` 添加环境变量**

```yaml
# deploy/helm/lakeon/templates/configmap-api.yaml 中的 data 节添加：
LAKEON_DATALAKE_NAMESPACE:        "{{ .Values.datalake.namespace }}"
LAKEON_DATALAKE_OBS_PREFIX:       "{{ .Values.datalake.obsPrefix }}"
LAKEON_DATALAKE_POLL_INTERVAL_MS: "{{ .Values.datalake.pollIntervalMs }}"
LAKEON_DATALAKE_IMAGE_PYTHON_SLIM: "{{ .Values.datalake.presetImages.pythonSlim }}"
LAKEON_DATALAKE_IMAGE_PYTHON_DATA: "{{ .Values.datalake.presetImages.pythonData }}"
LAKEON_DATALAKE_IMAGE_RAY:         "{{ .Values.datalake.presetImages.ray }}"
LAKEON_DATALAKE_IMAGE_RAY_GPU:     "{{ .Values.datalake.presetImages.rayGpu }}"
```

- [ ] **Step 3: 在 `LakeonProperties.DatalakeConfig` 中读取环境变量**

在 `application.yml` 中更新（确认 `LakeonProperties` 对应字段映射）：

```yaml
# 注意：所有属性名必须与 LakeonProperties.DatalakeConfig 的字段名一致
# pollIntervalMs → poll-interval-ms，presetImages → preset-images
lakeon:
  datalake:
    namespace:          ${LAKEON_DATALAKE_NAMESPACE:lakeon-datalake}
    obs-prefix:         ${LAKEON_DATALAKE_OBS_PREFIX:datalake}
    poll-interval-ms:   ${LAKEON_DATALAKE_POLL_INTERVAL_MS:10000}
    preset-images:
      python-slim:      ${LAKEON_DATALAKE_IMAGE_PYTHON_SLIM:python:3.11-slim}
      python-data:      ${LAKEON_DATALAKE_IMAGE_PYTHON_DATA:python:3.11-data}
      ray:              ${LAKEON_DATALAKE_IMAGE_RAY:ray:2.10-py311}
      ray-gpu:          ${LAKEON_DATALAKE_IMAGE_RAY_GPU:ray:2.10-py311-gpu}
```

- [ ] **Step 4: 全量测试 + 编译**

```bash
cd lakeon-api && ./mvnw test -q 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add deploy/helm/lakeon/templates/configmap-api.yaml \
        deploy/helm/lakeon/values.yaml \
        lakeon-api/src/main/resources/application.yml
git commit -m "feat(datalake): add Helm config for datalake namespace and preset images"
```

---

## Task 10: DatalakeService 单元测试

**Files:**
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/DatalakeServiceTest.java`

- [ ] **Step 1: 写 service 测试**

```java
// DatalakeServiceTest.java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatalakeService 单元测试")
class DatalakeServiceTest {

    @Mock DatalakeJobRepository repo;
    @Mock PythonJobRunner pythonRunner;
    @Mock RayJobRunner rayRunner;
    @Mock FinetuneJobRunner finetuneRunner;

    private DatalakeService service;  // 手动构造，避免与 @InjectMocks 冲突
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new DatalakeService(repo, pythonRunner, rayRunner, finetuneRunner, objectMapper);
    }

    @Test
    @DisplayName("getJob() — 跨租户访问返回 404")
    void getJob_wrongTenant_throws404() {
        var job = buildJob("dlj_001", "tn_owner");
        when(repo.findById("dlj_001")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.getJob("tn_attacker", "dlj_001"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("submitJob() — 无效 type 抛出 BadRequest")
    void submitJob_invalidType_throwsBadRequest() {
        var tenant = buildTenant("tn_001");
        assertThatThrownBy(() -> service.submitJob(tenant, Map.of("name", "test", "type", "INVALID")))
            .isInstanceOf(com.lakeon.service.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("cancelJob() — 已完成的任务幂等忽略")
    void cancelJob_alreadySucceeded_isIdempotent() {
        var job = buildJob("dlj_001", "tn_001");
        job.setStatus(DatalakeJobStatus.SUCCEEDED);
        when(repo.findById("dlj_001")).thenReturn(Optional.of(job));

        assertThatCode(() -> service.cancelJob("tn_001", "dlj_001")).doesNotThrowAnyException();
        verifyNoInteractions(pythonRunner, rayRunner);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private DatalakeJobEntity buildJob(String id, String tenantId) {
        var j = new DatalakeJobEntity();
        j.setId(id);
        j.setTenantId(tenantId);
        j.setName("test");
        j.setType(DatalakeJobType.PYTHON);
        j.setStatus(DatalakeJobStatus.RUNNING);
        j.setSpec("{}");
        j.setCreatedAt(Instant.now());
        j.setUpdatedAt(Instant.now());
        return j;
    }

    private TenantEntity buildTenant(String id) {
        var t = new TenantEntity();
        t.setId(id);
        return t;
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd lakeon-api && ./mvnw test -Dtest=DatalakeServiceTest -q 2>&1 | tail -5
```

期望：`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 3: 运行全量测试**

```bash
cd lakeon-api && ./mvnw test -q 2>&1 | tail -10
```

期望：所有测试通过。

- [ ] **Step 4: 最终 Commit**

```bash
git add lakeon-api/src/test/java/com/lakeon/datalake/DatalakeServiceTest.java
git commit -m "test(datalake): add DatalakeService unit tests — tenant isolation, cancel idempotency"
```

---

## 验收标准

Backend 实现完成后，以下场景应通过手动验证（在 CCE 集群 POC 通过后）：

1. `POST /api/v1/datalake/jobs` 提交 Python 任务 → CCE 创建 K8s Job → VK 调度到 CCI
2. `GET /api/v1/datalake/jobs/{id}` 状态从 STARTING → RUNNING（轮询器生效）
3. Job 完成后状态变为 SUCCEEDED，`finished_at` 和 `core_hours` 已填充
4. `GET /api/v1/datalake/jobs/{id}/logs` SSE 流返回 Pod 日志
5. `DELETE /api/v1/datalake/jobs/{id}` 取消运行中任务，K8s 资源已删除
6. 跨租户访问返回 404
7. Ray 任务：提交后 CCI 中出现 Head Pod + Worker Pods

**下一步（本计划完成后）：**
- Console 计划：`docs/superpowers/plans/2026-03-20-datalake-console.md`
- CCE 基础设施计划：KubeRay Operator + VK 安装 + Spec Normalizer
