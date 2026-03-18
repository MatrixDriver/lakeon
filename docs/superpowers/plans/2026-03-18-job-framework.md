# Job Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a generic async Job execution framework that orchestrates Job Pods on CCE elastic node pool.

**Architecture:** New `com.lakeon.job` package parallel to existing Import system. JPA entity for tracking, Fabric8 K8s client for Pod/ConfigMap lifecycle, HTTP callback for status updates, scheduled orphan detection for fault tolerance.

**Tech Stack:** Spring Boot 3.3.5, Java 17, Fabric8 K8s client 6.13.4, JPA/Hibernate, PostgreSQL

**Spec:** `docs/superpowers/specs/2026-03-18-job-framework-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `src/main/java/com/lakeon/job/JobType.java` | Job type enum |
| Create: `src/main/java/com/lakeon/job/JobStatus.java` | Job status enum |
| Create: `src/main/java/com/lakeon/job/JobEntity.java` | JPA entity with @PrePersist ID + token gen |
| Create: `src/main/java/com/lakeon/job/JobRepository.java` | Spring Data repository |
| Create: `src/main/java/com/lakeon/job/JobPodManager.java` | K8s Pod + ConfigMap lifecycle |
| Create: `src/main/java/com/lakeon/job/JobService.java` | Submit/query/cancel + async Pod launch |
| Create: `src/main/java/com/lakeon/job/JobCallbackController.java` | Callback endpoint with token auth |
| Create: `src/main/java/com/lakeon/job/JobScheduledTasks.java` | Orphan detection + cleanup |
| Modify: `src/main/java/com/lakeon/config/LakeonProperties.java` | Add JobConfig nested class |
| Modify: `src/main/java/com/lakeon/config/ApiKeyFilter.java` | Bypass auth for callback path |
| Modify: `src/main/resources/application.yml` | Add `lakeon.job.*` config section |

All `src/main/java` paths are relative to `lakeon-api/`.

---

### Task 1: Enums — JobType and JobStatus

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobStatus.java`

- [ ] **Step 1: Create JobType enum**

```java
package com.lakeon.job;

public enum JobType {
    DOCUMENT_PARSE,
    EMBEDDING,
    EXPORT_PARQUET,
    TRAINING
}
```

- [ ] **Step 2: Create JobStatus enum**

```java
package com.lakeon.job;

public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
```

- [ ] **Step 3: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/
git commit -m "feat(job): add JobType and JobStatus enums"
```

---

### Task 2: JobEntity — JPA entity

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobEntity.java`

- [ ] **Step 1: Create JobEntity**

Follow `ImportTaskEntity` pattern. Key differences: `job_` + 12 char ID, `callbackToken` UUID, `@PreUpdate` for updatedAt, `error` as TEXT.

```java
package com.lakeon.job;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs", indexes = {
    @Index(name = "idx_jobs_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_jobs_status", columnList = "status"),
    @Index(name = "idx_jobs_type_status", columnList = "type, status")
})
public class JobEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(columnDefinition = "TEXT")
    private String params;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "callback_token", length = 64)
    private String callbackToken;

    @Column(name = "pod_name", length = 128)
    private String podName;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (callbackToken == null) {
            callbackToken = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters for all fields
    // (standard Java bean pattern, same as ImportTaskEntity)

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getCallbackToken() { return callbackToken; }
    public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
```

- [ ] **Step 2: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobEntity.java
git commit -m "feat(job): add JobEntity JPA entity"
```

---

### Task 3: JobRepository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobRepository.java`

- [ ] **Step 1: Create repository**

```java
package com.lakeon.job;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, String> {

    Optional<JobEntity> findByIdAndTenantId(String id, String tenantId);

    List<JobEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<JobEntity> findAllByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, JobType type);

    List<JobEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, JobStatus status);

    List<JobEntity> findAllByTenantIdAndTypeAndStatusOrderByCreatedAtDesc(String tenantId, JobType type, JobStatus status);

    List<JobEntity> findAllByStatusIn(List<JobStatus> statuses);
}
```

- [ ] **Step 2: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobRepository.java
git commit -m "feat(job): add JobRepository"
```

---

### Task 4: LakeonProperties — add JobConfig

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add JobConfig nested class to LakeonProperties**

Add inside `LakeonProperties`:

```java
private JobConfig job = new JobConfig();

public JobConfig getJob() { return job; }
public void setJob(JobConfig job) { this.job = job; }

public static class JobConfig {
    private int timeoutMinutes = 30;
    private int pendingTimeoutMinutes = 5;
    private long orphanCheckIntervalMs = 60000;
    private Map<String, JobTypeConfig> types = new HashMap<>();

    // getters/setters for all fields

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    public int getPendingTimeoutMinutes() { return pendingTimeoutMinutes; }
    public void setPendingTimeoutMinutes(int pendingTimeoutMinutes) { this.pendingTimeoutMinutes = pendingTimeoutMinutes; }

    public long getOrphanCheckIntervalMs() { return orphanCheckIntervalMs; }
    public void setOrphanCheckIntervalMs(long orphanCheckIntervalMs) { this.orphanCheckIntervalMs = orphanCheckIntervalMs; }

    public Map<String, JobTypeConfig> getTypes() { return types; }
    public void setTypes(Map<String, JobTypeConfig> types) { this.types = types; }
}

public static class JobTypeConfig {
    private String image;
    private String cpu = "2";
    private String memory = "4Gi";

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
}
```

Add import: `import java.util.HashMap;` and `import java.util.Map;` (if not already present).

- [ ] **Step 2: Add job config section to application.yml**

Under `lakeon:` section, add:

```yaml
  job:
    timeout-minutes: ${LAKEON_JOB_TIMEOUT_MINUTES:30}
    pending-timeout-minutes: ${LAKEON_JOB_PENDING_TIMEOUT_MINUTES:5}
    orphan-check-interval-ms: ${LAKEON_JOB_ORPHAN_CHECK_INTERVAL_MS:60000}
    types:
      document-parse:
        image: ${LAKEON_JOB_IMAGE_DOCUMENT_PARSE:rayproject/ray:2.44.1-py312}
        cpu: "2"
        memory: "4Gi"
      embedding:
        image: ${LAKEON_JOB_IMAGE_EMBEDDING:rayproject/ray:2.44.1-py312}
        cpu: "2"
        memory: "4Gi"
      export-parquet:
        image: ${LAKEON_JOB_IMAGE_EXPORT_PARQUET:rayproject/ray:2.44.1-py312}
        cpu: "2"
        memory: "4Gi"
      training:
        image: ${LAKEON_JOB_IMAGE_TRAINING:rayproject/ray:2.44.1-py312}
        cpu: "4"
        memory: "8Gi"
```

- [ ] **Step 3: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java
git add lakeon-api/src/main/resources/application.yml
git commit -m "feat(job): add JobConfig to LakeonProperties and application.yml"
```

---

### Task 5: JobPodManager — K8s Pod + ConfigMap lifecycle

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobPodManager.java`

- [ ] **Step 1: Create JobPodManager**

Follow `ImportJobPodManager` pattern. Creates ConfigMap (params JSON) + Pod (env vars + ConfigMap mount + /dev/shm).

```java
package com.lakeon.job;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class JobPodManager {

    private static final Logger log = LoggerFactory.getLogger(JobPodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    @Value("${server.port:8090}")
    private int serverPort;

    public JobPodManager(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    public String launchJobPod(JobEntity job) {
        String namespace = props.getK8s().getNamespace();
        String safePodId = job.getId().replace("_", "-");
        String podName = "job-" + safePodId;
        String configMapName = podName + "-params";

        // Resolve image and resources from per-type config
        String typeKey = job.getType().name().toLowerCase().replace("_", "-");
        LakeonProperties.JobTypeConfig typeConfig = props.getJob().getTypes().get(typeKey);
        if (typeConfig == null || typeConfig.getImage() == null) {
            throw new IllegalStateException("No image configured for job type: " + typeKey);
        }

        // 1. Create ConfigMap with params
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(namespace)
                    .withLabels(Map.of(
                        "app", "lakeon-job",
                        "lakeon.io/job-id", job.getId()))
                .endMetadata()
                .withData(Map.of("params.json", job.getParams() != null ? job.getParams() : "{}"))
                .build();
        k8sClient.configMaps().inNamespace(namespace).resource(configMap).serverSideApply();

        // 2. Build callback URL
        String callbackUrl = String.format(
                "http://lakeon-api.lakeon.svc.cluster.local:%d/api/v1/jobs/%s/callback",
                serverPort, job.getId());

        // 3. Build Pod
        Map<String, String> labels = Map.of(
                "app", "lakeon-job",
                "lakeon.io/job-id", job.getId(),
                "lakeon.io/tenant-id", job.getTenantId(),
                "lakeon.io/job-type", typeKey);

        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("JOB_ID").withValue(job.getId()).build(),
                new EnvVarBuilder().withName("JOB_TYPE").withValue(job.getType().name()).build(),
                new EnvVarBuilder().withName("JOB_CALLBACK_URL").withValue(callbackUrl).build(),
                new EnvVarBuilder().withName("JOB_CALLBACK_TOKEN").withValue(job.getCallbackToken()).build(),
                new EnvVarBuilder().withName("OBS_ENDPOINT")
                        .withValue("https://obs." + "cn-north-4" + ".myhuaweicloud.com").build(),
                new EnvVarBuilder().withName("OBS_ACCESS_KEY")
                        .withNewValueFrom().withNewSecretKeyRef("access-key", "obs-credentials", false).endValueFrom().build(),
                new EnvVarBuilder().withName("OBS_SECRET_KEY")
                        .withNewValueFrom().withNewSecretKeyRef("secret-key", "obs-credentials", false).endValueFrom().build()
        );

        // imagePullSecrets
        List<LocalObjectReference> pullSecrets = props.getK8s().getImagePullSecrets() != null
                ? props.getK8s().getImagePullSecrets().stream()
                    .map(s -> new LocalObjectReferenceBuilder().withName(s).build())
                    .toList()
                : List.of();

        // nodeSelector
        Map<String, String> nodeSelector = Map.of("lakeon/role", "compute");

        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withNodeSelector(nodeSelector)
                    .withRestartPolicy("Never")
                    .withImagePullSecrets(pullSecrets)
                    .addNewContainer()
                        .withName("job")
                        .withImage(typeConfig.getImage())
                        .withEnv(envVars)
                        .withNewResources()
                            .withRequests(Map.of(
                                "cpu", new Quantity(typeConfig.getCpu()),
                                "memory", new Quantity(typeConfig.getMemory())))
                            .withLimits(Map.of(
                                "cpu", new Quantity(typeConfig.getCpu()),
                                "memory", new Quantity(typeConfig.getMemory())))
                        .endResources()
                        .addNewVolumeMount().withName("dshm").withMountPath("/dev/shm").endVolumeMount()
                        .addNewVolumeMount().withName("job-params").withMountPath("/etc/job").withReadOnly(true).endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                        .withName("dshm")
                        .withNewEmptyDir().withMedium("Memory").withSizeLimit(new Quantity("2Gi")).endEmptyDir()
                    .endVolume()
                    .addNewVolume()
                        .withName("job-params")
                        .withNewConfigMap().withName(configMapName).endConfigMap()
                    .endVolume()
                .endSpec()
                .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created job pod {} for job {} (type={})", podName, job.getId(), job.getType());
        return podName;
    }

    public boolean isPodTerminated(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return true;
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }

    public boolean podExists(String podName) {
        String namespace = props.getK8s().getNamespace();
        return k8sClient.pods().inNamespace(namespace).withName(podName).get() != null;
    }

    public void deleteJobResources(String jobId) {
        String namespace = props.getK8s().getNamespace();
        String safePodId = jobId.replace("_", "-");
        String podName = "job-" + safePodId;
        String configMapName = podName + "-params";

        try {
            k8sClient.pods().inNamespace(namespace).withName(podName).delete();
        } catch (Exception e) {
            log.debug("Pod {} already deleted or not found", podName);
        }
        try {
            k8sClient.configMaps().inNamespace(namespace).withName(configMapName).delete();
        } catch (Exception e) {
            log.debug("ConfigMap {} already deleted or not found", configMapName);
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobPodManager.java
git commit -m "feat(job): add JobPodManager for K8s Pod lifecycle"
```

---

### Task 6: JobService — submit, query, cancel

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobService.java`

- [ ] **Step 1: Create JobService**

```java
package com.lakeon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final JobPodManager jobPodManager;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public JobService(JobRepository jobRepository, JobPodManager jobPodManager, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobPodManager = jobPodManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JobEntity submitJob(TenantEntity tenant, JobType type, Map<String, Object> params) {
        JobEntity job = new JobEntity();
        job.setTenantId(tenant.getId());
        job.setType(type);
        job.setStatus(JobStatus.PENDING);
        try {
            job.setParams(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize job params", e);
        }
        jobRepository.save(job);

        // Launch pod after transaction commits
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(() -> launchPod(job.getId()));
            }
        });

        return job;
    }

    private void launchPod(String jobId) {
        try {
            JobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.PENDING) return;

            String podName = jobPodManager.launchJobPod(job);
            job.setPodName(podName);
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Failed to launch pod for job {}", jobId, e);
            JobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus(JobStatus.FAILED);
                job.setError("Pod launch failed: " + e.getMessage());
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
            }
        }
    }

    public JobEntity getJob(String tenantId, String jobId) {
        return jobRepository.findByIdAndTenantId(jobId, tenantId).orElse(null);
    }

    public List<JobEntity> listJobs(String tenantId, JobType type, JobStatus status) {
        if (type != null && status != null) {
            return jobRepository.findAllByTenantIdAndTypeAndStatusOrderByCreatedAtDesc(tenantId, type, status);
        } else if (type != null) {
            return jobRepository.findAllByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
        } else if (status != null) {
            return jobRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        }
        return jobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public JobEntity cancelJob(String tenantId, String jobId) {
        JobEntity job = jobRepository.findByIdAndTenantId(jobId, tenantId).orElse(null);
        if (job == null) return null;
        if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED
                || job.getStatus() == JobStatus.CANCELLED) {
            return job; // already terminal
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        jobPodManager.deleteJobResources(job.getId());
        return job;
    }

    @Transactional
    public boolean handleCallback(String jobId, String token, String status, String resultJson, String error) {
        JobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return false;
        if (!job.getCallbackToken().equals(token)) {
            log.warn("Invalid callback token for job {}", jobId);
            return false;
        }

        if ("RUNNING".equals(status)) {
            // Progress update
            job.setResult(resultJson);
            jobRepository.save(job);
            return true;
        }

        if ("SUCCEEDED".equals(status)) {
            job.setStatus(JobStatus.SUCCEEDED);
            job.setResult(resultJson);
        } else if ("FAILED".equals(status)) {
            job.setStatus(JobStatus.FAILED);
            job.setError(error);
        }
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);

        // Clean up K8s resources
        jobPodManager.deleteJobResources(job.getId());
        return true;
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobService.java
git commit -m "feat(job): add JobService with submit/query/cancel/callback"
```

---

### Task 7: JobCallbackController + auth bypass

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobCallbackController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`

- [ ] **Step 1: Create JobCallbackController**

```java
package com.lakeon.job;

import com.lakeon.model.entity.TenantEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobCallbackController {

    private final JobService jobService;

    public JobCallbackController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitJob(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        String typeStr = (String) body.get("type");
        JobType type = JobType.valueOf(typeStr);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());

        JobEntity job = jobService.submitJob(tenant, type, params);
        return Map.of(
                "id", job.getId(),
                "type", job.getType().name(),
                "status", job.getStatus().name(),
                "createdAt", job.getCreatedAt().toString());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getJob(@PathVariable String id, HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        JobEntity job = jobService.getJob(tenant.getId(), id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jobToMap(job));
    }

    @GetMapping
    public List<Map<String, Object>> listJobs(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        JobType jType = type != null ? JobType.valueOf(type) : null;
        JobStatus jStatus = status != null ? JobStatus.valueOf(status) : null;
        return jobService.listJobs(tenant.getId(), jType, jStatus).stream()
                .map(this::jobToMap).toList();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String id, HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        JobEntity job = jobService.cancelJob(tenant.getId(), id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(jobToMap(job));
    }

    // Internal callback endpoint (auth bypassed in ApiKeyFilter)
    @PostMapping("/{id}/callback")
    public ResponseEntity<Map<String, String>> callback(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        String status = (String) body.get("status");
        String result = body.containsKey("result") ? body.get("result").toString() : null;
        String error = (String) body.get("error");

        // Serialize result map to JSON string if it's a Map
        if (body.get("result") instanceof Map) {
            try {
                result = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(body.get("result"));
            } catch (Exception ignored) {}
        }

        boolean ok = jobService.handleCallback(id, token, status, result, error);
        if (!ok) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private Map<String, Object> jobToMap(JobEntity job) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", job.getId());
        map.put("type", job.getType().name());
        map.put("status", job.getStatus().name());
        if (job.getParams() != null) map.put("params", job.getParams());
        if (job.getResult() != null) map.put("result", job.getResult());
        if (job.getError() != null) map.put("error", job.getError());
        map.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
        if (job.getStartedAt() != null) map.put("startedAt", job.getStartedAt().toString());
        if (job.getCompletedAt() != null) map.put("completedAt", job.getCompletedAt().toString());
        return map;
    }
}
```

- [ ] **Step 2: Add auth bypass for callback path in ApiKeyFilter**

In `ApiKeyFilter.java`, find the existing import callback bypass (around line 121):
```java
if (path.startsWith("/api/v1/import/callback/")) {
```

Add immediately after that block:
```java
if (path.matches("/api/v1/jobs/[^/]+/callback")) {
    chain.doFilter(req, res);
    return;
}
```

- [ ] **Step 3: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobCallbackController.java
git add lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java
git commit -m "feat(job): add JobCallbackController and auth bypass for callback"
```

---

### Task 8: JobScheduledTasks — orphan detection

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/job/JobScheduledTasks.java`

- [ ] **Step 1: Create JobScheduledTasks**

```java
package com.lakeon.job;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class JobScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(JobScheduledTasks.class);

    private final JobRepository jobRepository;
    private final JobPodManager jobPodManager;
    private final LakeonProperties props;

    public JobScheduledTasks(JobRepository jobRepository, JobPodManager jobPodManager, LakeonProperties props) {
        this.jobRepository = jobRepository;
        this.jobPodManager = jobPodManager;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${lakeon.job.orphan-check-interval-ms:60000}")
    public void detectOrphanedJobs() {
        List<JobEntity> activeJobs = jobRepository.findAllByStatusIn(
                List.of(JobStatus.PENDING, JobStatus.RUNNING));

        Instant now = Instant.now();
        int pendingTimeout = props.getJob().getPendingTimeoutMinutes();
        int runningTimeout = props.getJob().getTimeoutMinutes();

        for (JobEntity job : activeJobs) {
            if (job.getStatus() == JobStatus.PENDING) {
                // PENDING too long without a Pod
                if (job.getCreatedAt().plus(pendingTimeout, ChronoUnit.MINUTES).isBefore(now)) {
                    if (job.getPodName() == null || !jobPodManager.podExists(job.getPodName())) {
                        log.warn("Job {} stuck in PENDING for {}min, marking FAILED", job.getId(), pendingTimeout);
                        job.setStatus(JobStatus.FAILED);
                        job.setError("Timed out in PENDING state (no pod created within " + pendingTimeout + "min)");
                        job.setCompletedAt(now);
                        jobRepository.save(job);
                    }
                }
            } else if (job.getStatus() == JobStatus.RUNNING) {
                // RUNNING but Pod terminated without callback
                if (job.getPodName() != null && jobPodManager.isPodTerminated(job.getPodName())) {
                    log.warn("Job {} pod terminated without callback, marking FAILED", job.getId());
                    job.setStatus(JobStatus.FAILED);
                    job.setError("Pod terminated without callback");
                    job.setCompletedAt(now);
                    jobRepository.save(job);
                    jobPodManager.deleteJobResources(job.getId());
                }
                // RUNNING too long (timeout)
                else if (job.getStartedAt() != null
                        && job.getStartedAt().plus(runningTimeout, ChronoUnit.MINUTES).isBefore(now)) {
                    log.warn("Job {} timed out after {}min, marking FAILED", job.getId(), runningTimeout);
                    job.setStatus(JobStatus.FAILED);
                    job.setError("Job timed out after " + runningTimeout + " minutes");
                    job.setCompletedAt(now);
                    jobRepository.save(job);
                    jobPodManager.deleteJobResources(job.getId());
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/job/JobScheduledTasks.java
git commit -m "feat(job): add orphan detection and timeout cleanup"
```

---

### Task 9: Integration verification

- [ ] **Step 1: Full build**

Run: `cd lakeon-api && mvn clean package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify JPA schema generation**

Start the app locally (or check that Hibernate auto-DDL creates the `jobs` table). Verify with:

```bash
# If running locally with Docker K8s:
kubectl port-forward svc/metadata-db 15432:5432 -n lakeon &
PGPASSWORD=lakeon psql -h localhost -p 15432 -U lakeon -d lakeon -c "\d jobs"
```

Expected: Table `jobs` with all columns from the spec.

- [ ] **Step 3: Smoke test — submit a job via curl**

```bash
API_KEY="<your-api-key>"
curl -s -X POST http://localhost:8090/api/v1/jobs \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"type":"DOCUMENT_PARSE","params":{"test":"true"}}' | jq .
```

Expected: JSON response with `id`, `type`, `status: PENDING`. Pod creation will fail (no real image), which is expected — the orphan detector will mark it FAILED after 5min.

- [ ] **Step 4: Smoke test — list jobs**

```bash
curl -s http://localhost:8090/api/v1/jobs \
  -H "Authorization: Bearer $API_KEY" | jq .
```

Expected: Array with the job created above.

- [ ] **Step 5: Commit all together**

```bash
git add -A
git commit -m "feat(job): complete job framework — entity, service, pod manager, controller, scheduler"
```
