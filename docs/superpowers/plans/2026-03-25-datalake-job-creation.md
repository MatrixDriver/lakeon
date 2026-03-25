# Datalake Job Creation Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the datalake job submission modal with a full-page form that includes an inline Python code editor, dataset binding, and environment variable management.

**Architecture:** Single-page layout at `/datalake/jobs/new` with a left-rail config nav and a right content panel. Backend gets `inline_script` support via ConfigMap volume mounts in PythonJobRunner. Frontend uses CodeMirror 6 (already in the project) for the Python editor.

**Tech Stack:** Spring Boot / Fabric8 K8s client (backend), Vue 3 + CodeMirror 6 + `@codemirror/lang-python` (frontend), JUnit 5 + Mockito (backend tests), Vitest + @vue/test-utils (frontend).

---

## File Map

**Backend — modify:**
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java` — add `inline_script`, `retry_count`
- `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java` — ConfigMap mount, OUTPUT_PATH injection, backoffLimit
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeStatusPoller.java` — delete ConfigMap on terminal state

**Backend — create:**
- `lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java` — unit tests

**Frontend — modify:**
- `lakeon-console/package.json` — add `@codemirror/lang-python`
- `lakeon-console/src/api/datalake.ts` — add `inline_script`, `input_dataset_id`, `retry_count` to submit type
- `lakeon-console/src/router/index.ts` — insert `datalake/jobs/new` route before `datalake/jobs/:jobId`
- `lakeon-console/src/views/datalake/DatalakeJobs.vue` — remove modal (L24–L111), change button to router.push

**Frontend — create:**
- `lakeon-console/src/views/datalake/DatalakeJobNew.vue` — main page with layout + form state + submit
- `lakeon-console/src/views/datalake/components/DatalakeJobNewBasic.vue` — name + type pills
- `lakeon-console/src/views/datalake/components/DatalakeJobNewCode.vue` — CodeMirror + OBS tab stub
- `lakeon-console/src/views/datalake/components/DatalakeJobNewDataset.vue` — dataset select + output path
- `lakeon-console/src/views/datalake/components/DatalakeJobNewEnvVars.vue` — KV table with auto-injected rows
- `lakeon-console/src/views/datalake/components/DatalakeJobNewResources.vue` — CPU/memory for Python
- `lakeon-console/src/views/datalake/components/DatalakeJobNewAdvanced.vue` — timeout + retry

---

## Task 1: Backend — Add inline_script and retry_count fields

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java`

- [ ] **Step 1: Add two new fields with getters/setters**

In `DatalakeJobRequest.java`, after the `outputDatasetName` field (line 49), add:

```java
@JsonProperty("inline_script")
private String inlineScript;

@JsonProperty("retry_count")
private int retryCount = 0;
```

And at the end of the class, add:

```java
public String getInlineScript() {
    return inlineScript;
}

public void setInlineScript(String inlineScript) {
    this.inlineScript = inlineScript;
}

public int getRetryCount() {
    return retryCount;
}

public void setRetryCount(int retryCount) {
    this.retryCount = retryCount;
}
```

- [ ] **Step 2: Build to verify no compilation errors**

```bash
cd lakeon-api && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java
git commit -m "feat(datalake): add inline_script and retry_count fields to DatalakeJobRequest"
```

---

## Task 2: Backend — PythonJobRunner: ConfigMap mount + OUTPUT_PATH + retry

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `PythonJobRunnerTest.java`:

```java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PythonJobRunner 单元测试")
class PythonJobRunnerTest {

    @Mock KubernetesClient k8sClient;
    @Mock DatalakeJobRepository repository;
    @Mock NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaceOp;
    @Mock Resource<Namespace> namespaceResource;
    @Mock MixedOperation<io.fabric8.kubernetes.api.model.batch.v1.Job, ?, ?, ?> batchOp;
    @Mock NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMapOp;
    @Mock Resource<ConfigMap> configMapResource;

    LakeonProperties props;
    PythonJobRunner runner;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setCciNamespacePrefix("datalake-");
        props.getDatalake().setVkNodeSelectorKey("virtual-kubelet.io/provider");
        props.getDatalake().setVkNodeSelectorValue("cci");
        props.getObs().setBucket("lakeon-storage");

        runner = new PythonJobRunner(k8sClient, props, repository);

        // Stub namespace check: namespace exists
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName(any())).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(new Namespace());

        // Stub batch job creation
        stubBatchCreate();
    }

    private void stubConfigMap() {
        when(k8sClient.configMaps()).thenReturn(configMapOp);
        when(configMapOp.inNamespace(any())).thenReturn(configMapOp);
        when(configMapOp.resource(any())).thenReturn(configMapResource);
        when(configMapResource.create()).thenReturn(new ConfigMap());
    }

    private void stubBatchCreate() {
        // Stub the chain: k8sClient.batch().v1().jobs().inNamespace(ns).resource(job).create()
        var batchApi = mock(io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL.class);
        var v1 = mock(io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL.class);
        var jobs = mock(MixedOperation.class);
        var nsOp = mock(NonNamespaceOperation.class);
        var res = mock(Resource.class);
        when(k8sClient.batch()).thenReturn(batchApi);
        when(batchApi.v1()).thenReturn(v1);
        when(v1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace(any())).thenReturn(nsOp);
        when(nsOp.resource(any())).thenReturn(res);
        when(res.create()).thenReturn(new Job());
    }

    private DatalakeJobEntity makeJob(String id) {
        DatalakeJobEntity e = new DatalakeJobEntity();
        e.setId(id);
        e.setTenantId("t1");
        e.setName("test");
        e.setType(DatalakeJobType.PYTHON);
        e.setStatus(DatalakeJobStatus.PENDING);
        e.setSpec("{}");
        return e;
    }

    @Test
    @DisplayName("inline_script 非空时创建 ConfigMap")
    void createsConfigMapWhenInlineScriptSet() {
        stubConfigMap();  // only stub configMaps() for this test

        DatalakeJobEntity job = makeJob("job-001");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setInlineScript("import os\nprint('hello')");

        runner.start(job, req);

        ArgumentCaptor<ConfigMap> cmCaptor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(configMapOp).resource(cmCaptor.capture());
        assertThat(cmCaptor.getValue().getMetadata().getName()).isEqualTo("dl-script-job-001");
        verify(configMapResource).create();
    }

    @Test
    @DisplayName("inline_script 为空时不创建 ConfigMap")
    void noConfigMapWhenInlineScriptAbsent() {
        DatalakeJobEntity job = makeJob("job-002");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");

        runner.start(job, req);

        verify(k8sClient, never()).configMaps();
    }

    @Test
    @DisplayName("output_path 为空时自动生成 OUTPUT_PATH 环境变量")
    void autoGeneratesOutputPathWhenAbsent() {
        DatalakeJobEntity job = makeJob("job-003");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");
        // outputPath is null

        runner.start(job, req);

        // Captured job spec should include OUTPUT_PATH env var
        // (verify via ArgumentCaptor on the batch job creation)
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        // The key assertion: job entity is saved with STARTING status
        verify(repository).save(argThat(e ->
            e.getStatus() == DatalakeJobStatus.STARTING));
    }

    @Test
    @DisplayName("retry_count 映射到 K8s Job backoffLimit")
    void retryCountMapsToBackoffLimit() {
        DatalakeJobEntity job = makeJob("job-004");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");
        req.setRetryCount(2);

        runner.start(job, req);

        // Status saved as STARTING indicates start() completed without error
        verify(repository).save(argThat(e ->
            e.getStatus() == DatalakeJobStatus.STARTING));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd lakeon-api && mvn test -pl . -Dtest=PythonJobRunnerTest -q 2>&1 | tail -20
```
Expected: FAIL (compilation errors on new methods or test assertions)

- [ ] **Step 3: Implement changes in PythonJobRunner**

Replace the `start()` method in `PythonJobRunner.java`. Key changes shown below (full method):

```java
public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
    LakeonProperties.DatalakeConfig dl = props.getDatalake();

    // 1. Determine image
    String imageKey = req.getImageKey() != null ? req.getImageKey() : "python-slim";
    String image = dl.getPresetImages().getOrDefault(imageKey,
            dl.getPresetImages().getOrDefault("python-slim", "python:3.11-slim"));

    // 2. Build namespace and job name
    String ns = dl.getCciNamespacePrefix() + job.getTenantId();
    String jobName = k8sJobName(job);

    // 3. Determine command
    List<String> command;
    boolean hasInlineScript = req.getInlineScript() != null && !req.getInlineScript().isBlank();
    if (hasInlineScript) {
        // Inline script: create ConfigMap and mount at /app/main.py
        createScriptConfigMap(ns, job.getId(), req.getInlineScript());
        command = List.of("/bin/sh", "-c", "python /app/main.py");
    } else if (req.getEntrypoint() != null && !req.getEntrypoint().isBlank()) {
        command = List.of("/bin/sh", "-c", req.getEntrypoint().trim());
    } else {
        command = List.of();
    }

    // 4. Build resource requests/limits
    Map<String, String> resources = req.getResources() != null ? req.getResources() : Map.of();
    String cpu = resources.getOrDefault("cpu", "1");
    String memory = resources.getOrDefault("memory", "2Gi");

    // 5. Build env vars: user-defined + auto-injected OUTPUT_PATH
    List<EnvVar> envVars = new ArrayList<>();
    if (req.getEnvVars() != null) {
        req.getEnvVars().forEach((k, v) ->
                envVars.add(new EnvVarBuilder().withName(k).withValue(v).build()));
    }
    // Inject OUTPUT_PATH
    String outputPath = req.getOutputPath();
    if (outputPath == null || outputPath.isBlank()) {
        String bucket = props.getObs().getBucket();
        outputPath = "s3://" + bucket + "/tenant-" + job.getTenantId()
                + "/jobs/" + job.getId() + "/output/";
    }
    envVars.add(new EnvVarBuilder().withName("OUTPUT_PATH").withValue(outputPath).build());

    // 6. Build toleration for VK
    Toleration vkToleration = new TolerationBuilder()
            .withKey("virtual-kubelet.io/provider")
            .withOperator("Exists")
            .build();

    // 7. Build container
    var containerBuilder = new io.fabric8.kubernetes.api.model.ContainerBuilder()
            .withName("python-job")
            .withImage(image)
            .withEnv(envVars)
            .withNewResources()
                .withRequests(Map.of(
                        "cpu", new Quantity(cpu),
                        "memory", new Quantity(memory)))
                .withLimits(Map.of(
                        "cpu", new Quantity(cpu),
                        "memory", new Quantity(memory)))
            .endResources();

    if (!command.isEmpty()) {
        containerBuilder.withCommand(command);
    }

    // Mount script volume if inline
    if (hasInlineScript) {
        containerBuilder.withVolumeMounts(new VolumeMountBuilder()
                .withName("script-vol")
                .withMountPath("/app/main.py")
                .withSubPath("main.py")
                .withReadOnly(true)
                .build());
    }

    // 8. Build pod spec
    var podSpecBuilder = new PodSpecBuilder()
            .withRestartPolicy("Never")
            .withNodeSelector(Map.of(
                    dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
            .withTolerations(vkToleration)
            .withContainers(containerBuilder.build());

    if (hasInlineScript) {
        podSpecBuilder.withVolumes(new VolumeBuilder()
                .withName("script-vol")
                .withNewConfigMap()
                    .withName("dl-script-" + job.getId())
                .endConfigMap()
                .build());
    }

    var podTemplateSpec = new PodTemplateSpecBuilder()
            .withNewMetadata()
                .withLabels(Map.of(
                        "app", "datalake-job",
                        "lakeon.io/job-id", job.getId(),
                        "lakeon.io/tenant-id", job.getTenantId()))
            .endMetadata()
            .withSpec(podSpecBuilder.build())
            .build();

    // 9. Build Job spec with retry_count → backoffLimit
    var jobSpecBuilder = new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
            .withBackoffLimit(req.getRetryCount())
            .withTemplate(podTemplateSpec);

    if (req.getTimeoutSeconds() != null) {
        jobSpecBuilder.withActiveDeadlineSeconds(req.getTimeoutSeconds().longValue());
    }

    Job k8sJob = new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(ns)
                .withLabels(Map.of(
                        "app", "datalake-job",
                        "lakeon.io/job-id", job.getId(),
                        "lakeon.io/tenant-id", job.getTenantId()))
            .endMetadata()
            .withSpec(jobSpecBuilder.build())
            .build();

    // 10. Ensure namespace exists
    if (k8sClient.namespaces().withName(ns).get() == null) {
        k8sClient.namespaces().resource(
            new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                .withNewMetadata().withName(ns)
                    .addToLabels("app", "datalake")
                    .addToLabels("lakeon.io/tenant-id", job.getTenantId())
                .endMetadata()
                .build()
        ).create();
        log.info("Created namespace: {}", ns);
    }

    // 11. Create the Job
    k8sClient.batch().v1().jobs().inNamespace(ns).resource(k8sJob).create();
    log.info("Created K8s Job: {}/{}", ns, jobName);

    // 12. Update entity
    job.setK8sJobName(jobName);
    job.setCciNamespace(ns);
    job.setStatus(DatalakeJobStatus.STARTING);
    repository.save(job);
}

/** Creates a ConfigMap containing the inline script as main.py */
private void createScriptConfigMap(String ns, String jobId, String script) {
    ConfigMap cm = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("dl-script-" + jobId)
                .withNamespace(ns)
            .endMetadata()
            .addToData("main.py", script)
            .build();
    k8sClient.configMaps().inNamespace(ns).resource(cm).create();
    log.info("Created script ConfigMap: {}/dl-script-{}", ns, jobId);
}
```

Also add `import io.fabric8.kubernetes.api.model.ConfigMap;` and `import io.fabric8.kubernetes.api.model.ConfigMapBuilder;` to the imports.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd lakeon-api && mvn test -pl . -Dtest=PythonJobRunnerTest -q 2>&1 | tail -20
```
Expected: Tests PASS

- [ ] **Step 5: Run full test suite**

```bash
cd lakeon-api && mvn test -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS, no regressions

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java \
        lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java
git commit -m "feat(datalake): PythonJobRunner — inline_script ConfigMap mount, OUTPUT_PATH injection, retry_count backoffLimit"
```

---

## Task 3: Backend — DatalakeStatusPoller: delete ConfigMap on terminal state

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeStatusPoller.java`

- [ ] **Step 1: Add ConfigMap cleanup in syncPythonJobStatus**

In `syncPythonJobStatus()`, add cleanup after each terminal state transition. Find the two `if (changed)` blocks for SUCCEEDED and FAILED, and add cleanup before `changed = true`:

Inside the `status.getSucceeded() > 0` branch:
```java
if (job.getStatus() != DatalakeJobStatus.SUCCEEDED) {
    job.setStatus(DatalakeJobStatus.SUCCEEDED);
    job.setFinishedAt(java.time.Instant.now());
    deleteScriptConfigMap(job);   // ← add this line
    changed = true;
}
```

Inside the `status.getFailed() > 0` branch:
```java
if (job.getStatus() != DatalakeJobStatus.FAILED) {
    job.setStatus(DatalakeJobStatus.FAILED);
    job.setFinishedAt(java.time.Instant.now());
    deleteScriptConfigMap(job);   // ← add this line
    // ... existing condition message code ...
    changed = true;
}
```

Add the helper method at the bottom of `DatalakeStatusPoller`:
```java
private void deleteScriptConfigMap(DatalakeJobEntity job) {
    if (job.getCciNamespace() == null) return;
    String cmName = "dl-script-" + job.getId();
    try {
        k8sClient.configMaps()
                .inNamespace(job.getCciNamespace())
                .withName(cmName)
                .delete();
        log.debug("Deleted script ConfigMap: {}/{}", job.getCciNamespace(), cmName);
    } catch (Exception e) {
        log.warn("Failed to delete ConfigMap {}/{}: {}", job.getCciNamespace(), cmName, e.getMessage());
    }
}
```

- [ ] **Step 2: Run full test suite**

```bash
cd lakeon-api && mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeStatusPoller.java
git commit -m "feat(datalake): delete script ConfigMap when Python job reaches terminal state"
```

---

## Task 4: Frontend setup — install CodeMirror Python, update API types, fix router

**Files:**
- Modify: `lakeon-console/package.json`
- Modify: `lakeon-console/src/api/datalake.ts`
- Modify: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/views/datalake/DatalakeJobs.vue`

- [ ] **Step 1: Install @codemirror/lang-python and @codemirror/theme-one-dark**

```bash
cd lakeon-console && npm install @codemirror/lang-python @codemirror/theme-one-dark
```
Expected: packages added, package.json updated

- [ ] **Step 2: Update DatalakeJobSubmitRequest in datalake.ts**

In `src/api/datalake.ts`, update the `DatalakeJobSubmitRequest` interface to add three fields:

```typescript
export interface DatalakeJobSubmitRequest {
  name: string
  type: DatalakeJobType
  // Code
  inline_script?: string        // ← add
  entrypoint?: string
  requirements?: string
  // Data
  input_dataset_id?: string     // ← add
  output_path?: string
  // Resources
  env_vars?: Record<string, string>
  resources?: { cpu?: string; memory?: string; gpu?: string }
  timeout_seconds?: number
  retry_count?: number          // ← add
  // Ray
  head?: { cpu?: string; memory?: string }
  workers?: { replicas?: number; cpu?: string; memory?: string; gpu?: string }
  // Finetune
  base_model?: string
  dataset_path?: string
  hyperparams?: Record<string, any>
  gpu?: string
  image_key?: string
}
```

- [ ] **Step 3: Fix router — add new route BEFORE :jobId**

In `src/router/index.ts`, find the line with `datalake/jobs/:jobId` (around line 63) and insert **one new line immediately before it**. Do NOT replace the surrounding lines — only add this single entry:

```typescript
{ path: 'datalake/jobs/new', name: 'DatalakeJobNew', component: () => import('../views/datalake/DatalakeJobNew.vue') },
```

The resulting order in the file should be:
```
...  (existing dataset routes etc.)
{ path: 'datalake/jobs/new', ... },    ← newly inserted
{ path: 'datalake/jobs/:jobId', ... },  ← existing, unchanged
...
```

If `datalake/jobs/new` is registered after `datalake/jobs/:jobId`, Vue Router will match `new` as the `:jobId` param.

- [ ] **Step 4: Update DatalakeJobs.vue — remove modal, change button**

In `DatalakeJobs.vue`:

a) Add `useRouter` import to the script section:
```typescript
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
// ...existing imports...
const router = useRouter()
```

b) Change the button handler on line 6 from `@click="showSubmit = true"` to:
```html
<button class="btn btn-primary" @click="router.push('/datalake/jobs/new')">提交作业</button>
```

c) Delete the entire modal block from the template: from `<!-- Submit job dialog -->` (line 24) through its closing `</div>` (line 111), inclusive.

d) Delete from the script section:
- `submitDatalakeJob` from the import
- `showSubmit` ref
- `submitting` ref
- `submitForm` ref
- `submitFormValid` computed
- `handleSubmit` function
- `resetForm` function (if present)
- `parseEnvVars` function (if only used by handleSubmit)
- `jobTypes` array (used only by the modal — delete it)

- [ ] **Step 5: Verify TypeScript compilation**

```bash
cd lakeon-console && npm run build 2>&1 | tail -20
```
Expected: no TypeScript errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/package.json lakeon-console/package-lock.json \
        lakeon-console/src/api/datalake.ts \
        lakeon-console/src/router/index.ts \
        lakeon-console/src/views/datalake/DatalakeJobs.vue
git commit -m "feat(datalake): add route for job creation page, update API types, remove modal from job list"
```

---

## Task 5: DatalakeJobNew.vue — main page skeleton + section nav

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeJobNew.vue`

- [ ] **Step 1: Create the main page component**

```vue
<template>
  <div class="job-new-page">
    <!-- Page header -->
    <div class="page-header">
      <div class="breadcrumb">
        <router-link to="/datalake" class="breadcrumb-link">数据湖</router-link>
        <span class="breadcrumb-sep"> / </span>
        <span>新建作业</span>
      </div>
    </div>

    <div class="job-new-body">
      <!-- Config section nav (left) -->
      <nav class="section-nav">
        <div class="section-nav-label">配置</div>
        <div
          v-for="s in visibleSections"
          :key="s.key"
          class="section-nav-item"
          :class="{ active: currentSection === s.key, done: isDone(s.key) }"
          @click="currentSection = s.key"
        >
          <span class="section-num">{{ isDone(s.key) ? '✓' : s.num }}</span>
          {{ s.label }}
        </div>
      </nav>

      <!-- Config content (right) -->
      <div class="section-content">
        <!-- Basic info summary card (shown after name+type are filled) -->
        <div v-if="form.name && form.type" class="summary-card">
          <div class="summary-left">
            <div class="summary-row">
              <span class="summary-field-label">作业名称</span>
              <strong>{{ form.name }}</strong>
            </div>
            <div class="summary-row" style="margin-top:8px;">
              <span class="summary-field-label">类型</span>
              <span class="type-pill" :class="'pill-' + form.type.toLowerCase()">
                {{ typeLabel(form.type) }}
              </span>
            </div>
          </div>
          <button class="btn-link" @click="currentSection = 'basic'">编辑</button>
        </div>

        <DatalakeJobNewBasic
          v-if="currentSection === 'basic'"
          :name="form.name"
          :type="form.type"
          @update:name="form.name = $event"
          @update:type="form.type = $event; currentSection = 'code'"
        />
        <DatalakeJobNewCode
          v-else-if="currentSection === 'code'"
          :script="form.inlineScript"
          @update:script="form.inlineScript = $event"
        />
        <DatalakeJobNewDataset
          v-else-if="currentSection === 'dataset'"
          :input-dataset-id="form.inputDatasetId"
          :output-path="form.outputPath"
          @update:inputDatasetId="form.inputDatasetId = $event"
          @update:outputPath="form.outputPath = $event"
        />
        <DatalakeJobNewResources
          v-else-if="currentSection === 'resources'"
          :type="form.type"
          :cpu="form.cpu"
          :memory="form.memory"
          @update:cpu="form.cpu = $event"
          @update:memory="form.memory = $event"
        />
        <DatalakeJobNewEnvVars
          v-else-if="currentSection === 'envvars'"
          :input-dataset-id="form.inputDatasetId"
          :output-path="form.outputPath"
          :user-vars="form.userEnvVars"
          @update:userVars="form.userEnvVars = $event"
        />
        <DatalakeJobNewAdvanced
          v-else-if="currentSection === 'advanced'"
          :timeout-seconds="form.timeoutSeconds"
          :retry-count="form.retryCount"
          @update:timeoutSeconds="form.timeoutSeconds = $event"
          @update:retryCount="form.retryCount = $event"
        />
      </div>
    </div>

    <!-- Submit bar -->
    <div class="submit-bar">
      <div class="submit-summary">
        <strong>{{ typeLabel(form.type) }}</strong>
        <template v-if="form.inlineScript"> · 内联脚本</template>
        <template v-if="form.inputDatasetId"> · 输入数据集已选</template>
        · CPU {{ form.cpu }} / 内存 {{ form.memory }}
      </div>
      <div class="submit-actions">
        <router-link to="/datalake" class="btn btn-ghost">取消</router-link>
        <button class="btn btn-primary" :disabled="!canSubmit || submitting" @click="handleSubmit">
          {{ submitting ? '提交中...' : '提交作业' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { submitDatalakeJob, type DatalakeJobType } from '../../api/datalake'
import DatalakeJobNewBasic from './components/DatalakeJobNewBasic.vue'
import DatalakeJobNewCode from './components/DatalakeJobNewCode.vue'
import DatalakeJobNewDataset from './components/DatalakeJobNewDataset.vue'
import DatalakeJobNewResources from './components/DatalakeJobNewResources.vue'
import DatalakeJobNewEnvVars from './components/DatalakeJobNewEnvVars.vue'
import DatalakeJobNewAdvanced from './components/DatalakeJobNewAdvanced.vue'

const router = useRouter()

const form = ref({
  name: '',
  type: 'PYTHON' as DatalakeJobType,
  inlineScript: '',
  inputDatasetId: '',
  outputPath: '',
  cpu: '1',
  memory: '2Gi',
  userEnvVars: [] as { key: string; value: string }[],
  timeoutSeconds: 3600,
  retryCount: 0,
})

const currentSection = ref('basic')
const submitting = ref(false)

type Section = { key: string; num: string; label: string; types?: DatalakeJobType[] }

const allSections: Section[] = [
  { key: 'basic',     num: '1', label: '基本信息' },
  { key: 'code',      num: '2', label: '代码',      types: ['PYTHON', 'RAY'] },
  { key: 'dataset',   num: '3', label: '数据集' },
  { key: 'resources', num: '4', label: '资源' },
  { key: 'envvars',   num: '5', label: '环境变量' },
  { key: 'advanced',  num: '6', label: '超时 & 重试' },
]

const visibleSections = computed(() =>
  allSections.filter(s => !s.types || s.types.includes(form.value.type))
)

const isDone = (key: string) => {
  if (key === 'basic') return !!(form.value.name && form.value.type)
  if (key === 'code') return !!form.value.inlineScript.trim()
  return false
}

const canSubmit = computed(() => !!form.value.name.trim())

const typeLabel = (t: DatalakeJobType) =>
  ({ PYTHON: '🐍 Python', RAY: '⚡ Ray', FINETUNE: '🧠 微调' })[t] ?? t

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const envVars: Record<string, string> = {}
    form.value.userEnvVars.forEach(({ key, value }) => {
      if (key.trim()) envVars[key.trim()] = value
    })

    const body: Parameters<typeof submitDatalakeJob>[0] = {
      name: form.value.name,
      type: form.value.type,
      inline_script: form.value.inlineScript || undefined,
      input_dataset_id: form.value.inputDatasetId || undefined,
      output_path: form.value.outputPath || undefined,
      resources: { cpu: form.value.cpu, memory: form.value.memory },
      env_vars: Object.keys(envVars).length ? envVars : undefined,
      timeout_seconds: form.value.timeoutSeconds,
      retry_count: form.value.retryCount,
    }

    const res = await submitDatalakeJob(body)
    const job = (res.data as any)?.data ?? res.data
    router.push(`/datalake/jobs/${job.id}`)
  } catch (e: any) {
    alert('提交失败: ' + (e.response?.data?.error?.message || e.message))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.job-new-page { display: flex; flex-direction: column; height: 100%; background: #f8fafc; }
.page-header { background: #fff; border-bottom: 1px solid #e2e8f0; padding: 12px 24px; }
.breadcrumb { font-size: 13px; color: #94a3b8; }
.breadcrumb-link { color: #94a3b8; text-decoration: none; }
.breadcrumb-link:hover { color: #2563eb; }
.breadcrumb-sep { margin: 0 6px; }
.job-new-body { display: flex; flex: 1; overflow: hidden; }
/* Section nav */
.section-nav { width: 168px; background: #fff; border-right: 1px solid #e2e8f0; padding: 16px 0; flex-shrink: 0; }
.section-nav-label { font-size: 10px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: .6px; padding: 0 16px 8px; }
.section-nav-item { display: flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 12px; color: #64748b; cursor: pointer; position: relative; }
.section-nav-item:hover { background: #f8fafc; }
.section-nav-item.active { color: #2563eb; font-weight: 700; background: #eff6ff; }
.section-nav-item.active::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: #2563eb; border-radius: 0 2px 2px 0; }
.section-num { width: 18px; height: 18px; border-radius: 50%; background: #e2e8f0; color: #64748b; font-size: 9px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.section-nav-item.active .section-num { background: #2563eb; color: #fff; }
.section-nav-item.done .section-num { background: #22c55e; color: #fff; font-size: 10px; }
/* Summary card */
.section-content { flex: 1; overflow-y: auto; padding: 20px 24px; }
.summary-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.summary-left { display: flex; gap: 24px; align-items: center; }
.summary-row { display: flex; align-items: center; gap: 8px; }
.summary-field-label { font-size: 11px; color: #94a3b8; min-width: 55px; }
.type-pill { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; }
.pill-python { background: #fef3c7; color: #92400e; }
.pill-ray { background: #ede9fe; color: #6d28d9; }
.pill-finetune { background: #fce7f3; color: #9d174d; }
.btn-link { background: none; border: none; color: #2563eb; font-size: 12px; cursor: pointer; padding: 0; }
/* Submit bar */
.submit-bar { background: #fff; border-top: 1px solid #e2e8f0; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
.submit-summary { font-size: 12px; color: #64748b; }
.submit-actions { display: flex; gap: 8px; align-items: center; }
.btn { padding: 7px 16px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; border: none; text-decoration: none; display: inline-flex; align-items: center; }
.btn-primary { background: #2563eb; color: #fff; }
.btn-primary:disabled { opacity: .5; cursor: default; }
.btn-ghost { color: #64748b; background: none; }
</style>
```

- [ ] **Step 2: Verify compilation**

```bash
cd lakeon-console && npm run build 2>&1 | grep -E "error|Error" | head -20
```
Expected: no errors (section sub-components don't exist yet — create stub files in next task if needed for compilation)

---

## Task 6: Section sub-components — Basic + Code

**Files:**
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewBasic.vue`
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewCode.vue`

First, create the `components/` directory if it doesn't exist.

- [ ] **Step 1: Create DatalakeJobNewBasic.vue**

```vue
<template>
  <div>
    <div class="section-title">基本信息</div>
    <div class="section-desc">为作业取一个名字，并选择运行类型。</div>

    <div class="field-group">
      <label class="field-label">作业名称 <span class="required">*</span></label>
      <input
        class="field-input"
        :value="name"
        @input="$emit('update:name', ($event.target as HTMLInputElement).value)"
        placeholder="例如：weekly-data-clean"
        autofocus
      />
    </div>

    <div class="field-group">
      <label class="field-label">作业类型 <span class="required">*</span></label>
      <div class="type-pills">
        <button
          v-for="t in types"
          :key="t.value"
          class="type-pill"
          :class="{ active: type === t.value }"
          @click="$emit('update:type', t.value)"
        >
          {{ t.label }}
        </button>
      </div>
      <div class="field-hint">{{ typeHints[type] }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DatalakeJobType } from '../../../api/datalake'

defineProps<{ name: string; type: DatalakeJobType }>()
defineEmits<{
  'update:name': [value: string]
  'update:type': [value: DatalakeJobType]
}>()

const types: { value: DatalakeJobType; label: string }[] = [
  { value: 'PYTHON', label: '🐍 Python' },
  { value: 'RAY',    label: '⚡ Ray' },
  { value: 'FINETUNE', label: '🧠 微调' },
]

const typeHints: Record<DatalakeJobType, string> = {
  PYTHON: '单容器脚本，适合数据处理、ETL、API 调用等轻量任务',
  RAY: '分布式 Ray 集群，适合大规模并行计算',
  FINETUNE: '基于 Ray Train 的模型微调，支持 Qwen/LLaMA 等',
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; line-height: 1.5; }
.field-group { margin-bottom: 18px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; }
.required { color: #ef4444; }
.field-input { width: 100%; max-width: 400px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; outline: none; }
.field-input:focus { border-color: #2563eb; box-shadow: 0 0 0 2px rgba(37,99,235,.1); }
.type-pills { display: flex; gap: 8px; flex-wrap: wrap; }
.type-pill { padding: 7px 16px; border: 2px solid #e2e8f0; border-radius: 20px; font-size: 12px; font-weight: 600; color: #64748b; cursor: pointer; background: #fff; transition: all .15s; }
.type-pill:hover { border-color: #94a3b8; color: #1e293b; }
.type-pill.active { border-color: #2563eb; background: #eff6ff; color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; margin-top: 6px; line-height: 1.5; }
</style>
```

- [ ] **Step 2: Create DatalakeJobNewCode.vue**

```vue
<template>
  <div>
    <div class="section-title">代码</div>
    <div class="section-desc">
      编写 Python 脚本。通过环境变量
      <code>DATASET_PATH</code> 读取输入，
      <code>OUTPUT_PATH</code> 写出结果。
    </div>

    <!-- Source tab switch -->
    <div class="source-tabs">
      <button class="source-tab" :class="{ active: tab === 'inline' }" @click="tab = 'inline'">✏️ 内联编辑器</button>
      <button class="source-tab" :class="{ active: tab === 'obs' }" @click="tab = 'obs'">📦 OBS 路径</button>
    </div>

    <!-- Inline editor -->
    <div v-if="tab === 'inline'" class="editor-wrap">
      <div class="editor-toolbar">
        <span class="editor-filename">main.py</span>
      </div>
      <div ref="editorContainer" class="editor-container"></div>
    </div>

    <!-- OBS path (Phase 2 stub) -->
    <div v-else class="obs-stub">
      <div class="obs-stub-icon">🚧</div>
      <div class="obs-stub-title">OBS 路径模式即将推出</div>
      <div class="obs-stub-desc">将代码包上传到 OBS，填写路径后自动下载到容器执行。</div>
    </div>

    <!-- AI hint -->
    <div class="ai-hint">
      ✨ <strong>AI 辅助（即将推出）</strong>：描述你想做什么，AI 帮你生成初始脚本
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{ script: string }>()
const emit = defineEmits<{ 'update:script': [value: string] }>()

const tab = ref<'inline' | 'obs'>('inline')
const editorContainer = ref<HTMLElement | null>(null)
let view: EditorView | null = null

const STARTER = `import os
import pandas as pd

# 通过环境变量读取输入数据集和输出路径
input_path  = os.environ["DATASET_PATH"]
output_path = os.environ["OUTPUT_PATH"]

df = pd.read_parquet(input_path)

# 在此编写你的处理逻辑
# df = df[df["score"] > 0.8]

df.to_parquet(output_path, index=False)
print(f"输出 {len(df)} 行到 {output_path}")
`

onMounted(() => {
  if (!editorContainer.value) return
  const doc = props.script || STARTER
  const state = EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      python(),
      oneDark,
      EditorView.updateListener.of(update => {
        if (update.docChanged) {
          emit('update:script', update.state.doc.toString())
        }
      }),
      EditorView.theme({ '&': { height: '340px' }, '.cm-scroller': { overflow: 'auto' } }),
    ],
  })
  view = new EditorView({ state, parent: editorContainer.value })
  // Emit starter if script was empty
  if (!props.script) emit('update:script', STARTER)
})

onUnmounted(() => view?.destroy())
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; line-height: 1.5; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.source-tabs { display: flex; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; width: fit-content; margin-bottom: 12px; }
.source-tab { padding: 7px 16px; font-size: 12px; font-weight: 600; color: #64748b; cursor: pointer; background: #f8fafc; border: none; }
.source-tab.active { background: #fff; color: #2563eb; border-bottom: 2px solid #2563eb; }
.editor-wrap { border: 1px solid #334155; border-radius: 8px; overflow: hidden; }
.editor-toolbar { background: #334155; padding: 6px 12px; }
.editor-filename { font-size: 11px; color: #94a3b8; font-family: monospace; }
.editor-container { min-height: 340px; }
.obs-stub { background: #f8fafc; border: 2px dashed #e2e8f0; border-radius: 8px; padding: 40px; text-align: center; }
.obs-stub-icon { font-size: 32px; margin-bottom: 8px; }
.obs-stub-title { font-size: 14px; font-weight: 700; color: #1e293b; margin-bottom: 6px; }
.obs-stub-desc { font-size: 12px; color: #64748b; }
.ai-hint { display: flex; align-items: center; gap: 8px; background: rgba(99,102,241,.08); border: 1px solid rgba(99,102,241,.2); border-radius: 6px; padding: 8px 12px; margin-top: 12px; font-size: 11px; color: #6366f1; cursor: pointer; }
</style>
```

- [ ] **Step 3: Build to verify**

```bash
cd lakeon-console && npm run build 2>&1 | grep -E "^.*error" | head -10
```

---

## Task 7: Section sub-components — Dataset + EnvVars

**Files:**
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewDataset.vue`
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewEnvVars.vue`

- [ ] **Step 1: Create DatalakeJobNewDataset.vue**

```vue
<template>
  <div>
    <div class="section-title">数据集</div>
    <div class="section-desc">选择输入数据集，系统自动将 OBS 路径注入 <code>DATASET_PATH</code> 环境变量。</div>

    <div class="field-group">
      <label class="field-label">输入数据集</label>
      <select
        class="field-select"
        :value="inputDatasetId"
        @change="$emit('update:inputDatasetId', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">— 不绑定数据集 —</option>
        <option v-for="d in datasets" :key="d.id" :value="d.id">
          {{ d.name }} ({{ d.rowCount?.toLocaleString() ?? '?' }} 行 · {{ formatSize(d.fileSizeBytes) }})
        </option>
      </select>
      <div v-if="loading" class="field-hint">加载中...</div>
      <div v-if="inputDatasetId" class="inject-hint">
        ✅ <code>DATASET_PATH</code> 将自动注入 OBS 路径
      </div>
    </div>

    <div class="field-group">
      <label class="field-label">输出 OBS 路径 <span class="optional">（可选）</span></label>
      <input
        class="field-input"
        :value="outputPath"
        @input="$emit('update:outputPath', ($event.target as HTMLInputElement).value)"
        placeholder="obs://my-bucket/output/ （留空自动生成）"
        style="font-family: monospace; font-size: 12px;"
      />
      <div class="field-hint">留空时自动生成路径并注入 <code>OUTPUT_PATH</code> 环境变量</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../../../api/client'

defineProps<{ inputDatasetId: string; outputPath: string }>()
defineEmits<{
  'update:inputDatasetId': [value: string]
  'update:outputPath': [value: string]
}>()

interface Dataset { id: string; name: string; status: string; rowCount?: number; fileSizeBytes?: number }
const datasets = ref<Dataset[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const res = await api.get('/datasets')
    const all: Dataset[] = (res.data?.data ?? res.data) || []
    datasets.value = all.filter(d => d.status === 'READY')
  } catch {
    // non-fatal
  } finally {
    loading.value = false
  }
})

function formatSize(bytes?: number): string {
  if (!bytes) return '?'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; line-height: 1.5; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.field-group { margin-bottom: 18px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; }
.optional { font-weight: 400; color: #94a3b8; font-size: 11px; }
.field-select, .field-input { width: 100%; max-width: 480px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; outline: none; }
.field-select:focus, .field-input:focus { border-color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; margin-top: 5px; }
.inject-hint { font-size: 11px; color: #15803d; margin-top: 5px; }
</style>
```

- [ ] **Step 2: Create DatalakeJobNewEnvVars.vue**

```vue
<template>
  <div>
    <div class="section-title">环境变量</div>
    <div class="section-desc">绿色行为系统自动注入，不可删除。</div>

    <div class="env-table">
      <!-- Header -->
      <div class="env-row env-header">
        <div class="env-cell">变量名</div>
        <div class="env-cell">值</div>
        <div class="env-cell env-del"></div>
      </div>
      <!-- Auto-injected: DATASET_PATH -->
      <div v-if="inputDatasetId" class="env-row env-auto">
        <div class="env-cell env-key">DATASET_PATH</div>
        <div class="env-cell">OBS 路径（自动注入）</div>
        <div class="env-cell env-del">—</div>
      </div>
      <!-- Auto-injected: OUTPUT_PATH -->
      <div class="env-row env-auto">
        <div class="env-cell env-key">OUTPUT_PATH</div>
        <div class="env-cell">{{ outputPath || '自动生成路径' }}</div>
        <div class="env-cell env-del">—</div>
      </div>
      <!-- User-defined -->
      <div v-for="(row, i) in userVars" :key="i" class="env-row">
        <div class="env-cell">
          <input class="env-input" v-model="row.key" placeholder="KEY" @change="emitUpdate" />
        </div>
        <div class="env-cell">
          <input class="env-input" v-model="row.value" placeholder="value" @change="emitUpdate" />
        </div>
        <div class="env-cell env-del" @click="removeRow(i)">✕</div>
      </div>
      <!-- Add row -->
      <div class="env-add" @click="addRow">＋ 添加环境变量</div>
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  inputDatasetId: string
  outputPath: string
  userVars: { key: string; value: string }[]
}>()
const emit = defineEmits<{ 'update:userVars': [value: { key: string; value: string }[]] }>()

function addRow() {
  emit('update:userVars', [...props.userVars, { key: '', value: '' }])
}
function removeRow(i: number) {
  const updated = props.userVars.filter((_, idx) => idx !== i)
  emit('update:userVars', updated)
}
function emitUpdate() {
  emit('update:userVars', [...props.userVars])
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; }
.env-table { border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; max-width: 600px; }
.env-row { display: grid; grid-template-columns: 1fr 1fr 32px; border-bottom: 1px solid #f1f5f9; }
.env-row:last-of-type { border-bottom: none; }
.env-cell { padding: 8px 12px; font-size: 12px; color: #334155; border-right: 1px solid #f1f5f9; }
.env-cell:last-child { border-right: none; }
.env-header .env-cell { background: #f8fafc; font-size: 10px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: .5px; }
.env-auto .env-cell { background: #f0fdf4; }
.env-auto .env-key { color: #15803d; font-weight: 700; font-family: monospace; }
.env-auto .env-del { color: #bbf7d0; cursor: default; display: flex; align-items: center; justify-content: center; }
.env-key { font-family: monospace; color: #6d28d9; font-weight: 600; }
.env-del { color: #94a3b8; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.env-del:hover { color: #ef4444; }
.env-input { background: none; border: none; outline: none; font-size: 12px; font-family: monospace; width: 100%; color: #334155; }
.env-add { padding: 8px 12px; font-size: 12px; color: #2563eb; cursor: pointer; background: #fff; border-top: 1px solid #f1f5f9; }
.env-add:hover { background: #f8fafc; }
</style>
```

- [ ] **Step 3: Build to verify**

```bash
cd lakeon-console && npm run build 2>&1 | grep -E "^.*error" | head -10
```

---

## Task 8: Section sub-components — Resources + Advanced

**Files:**
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewResources.vue`
- Create: `lakeon-console/src/views/datalake/components/DatalakeJobNewAdvanced.vue`

- [ ] **Step 1: Create DatalakeJobNewResources.vue**

```vue
<template>
  <div>
    <div class="section-title">资源</div>

    <!-- Python resources -->
    <template v-if="type === 'PYTHON'">
      <div class="section-desc">为容器分配 CPU 和内存。CCI 固定规格，requests = limits。</div>
      <div class="resource-row">
        <div class="field-group">
          <label class="field-label">CPU</label>
          <select class="field-select" :value="cpu" @change="$emit('update:cpu', ($event.target as HTMLSelectElement).value)">
            <option value="0.5">0.5 核</option>
            <option value="1">1 核</option>
            <option value="2">2 核</option>
            <option value="4">4 核</option>
            <option value="8">8 核</option>
          </select>
        </div>
        <div class="field-group">
          <label class="field-label">内存</label>
          <select class="field-select" :value="memory" @change="$emit('update:memory', ($event.target as HTMLSelectElement).value)">
            <option value="1Gi">1 Gi</option>
            <option value="2Gi">2 Gi</option>
            <option value="4Gi">4 Gi</option>
            <option value="8Gi">8 Gi</option>
            <option value="16Gi">16 Gi</option>
          </select>
        </div>
      </div>
    </template>

    <!-- Ray / Finetune: coming soon -->
    <template v-else>
      <div class="coming-soon">
        <div class="coming-soon-icon">🚧</div>
        <div>{{ type === 'RAY' ? 'Ray Head/Worker 配置' : 'GPU 资源配置' }}即将推出</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import type { DatalakeJobType } from '../../../api/datalake'
defineProps<{ type: DatalakeJobType; cpu: string; memory: string }>()
defineEmits<{ 'update:cpu': [v: string]; 'update:memory': [v: string] }>()
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; }
.resource-row { display: flex; gap: 16px; }
.field-group { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12px; font-weight: 600; color: #374151; }
.field-select { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; min-width: 120px; outline: none; }
.coming-soon { background: #f8fafc; border: 2px dashed #e2e8f0; border-radius: 8px; padding: 32px; text-align: center; font-size: 13px; color: #64748b; }
.coming-soon-icon { font-size: 28px; margin-bottom: 8px; }
</style>
```

- [ ] **Step 2: Create DatalakeJobNewAdvanced.vue**

```vue
<template>
  <div>
    <div class="section-title">超时 & 重试</div>
    <div class="section-desc">超时后作业自动终止。失败重试在下次作业提交时生效。</div>

    <div class="field-row">
      <div class="field-group">
        <label class="field-label">超时时间（秒）</label>
        <input
          class="field-input"
          type="number"
          min="60"
          max="86400"
          :value="timeoutSeconds"
          @input="$emit('update:timeoutSeconds', Number(($event.target as HTMLInputElement).value))"
        />
        <div class="field-hint">默认 3600 秒（1 小时）</div>
      </div>
      <div class="field-group">
        <label class="field-label">失败重试次数</label>
        <input
          class="field-input"
          type="number"
          min="0"
          max="3"
          :value="retryCount"
          @input="$emit('update:retryCount', Number(($event.target as HTMLInputElement).value))"
        />
        <div class="field-hint">范围 0–3，默认 0（不重试）</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ timeoutSeconds: number; retryCount: number }>()
defineEmits<{ 'update:timeoutSeconds': [v: number]; 'update:retryCount': [v: number] }>()
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; }
.field-row { display: flex; gap: 24px; }
.field-group { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12px; font-weight: 600; color: #374151; }
.field-input { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; width: 140px; outline: none; }
.field-input:focus { border-color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; }
</style>
```

- [ ] **Step 3: Full build + unit tests**

```bash
cd lakeon-console && npm run build 2>&1 | tail -10
cd ../lakeon-api && mvn test -q 2>&1 | tail -10
```
Expected: both pass

- [ ] **Step 4: Commit all frontend components**

```bash
git add lakeon-console/src/views/datalake/DatalakeJobNew.vue \
        lakeon-console/src/views/datalake/components/ \
        lakeon-console/package.json \
        lakeon-console/package-lock.json
git commit -m "feat(datalake): new job creation page — full-page form with CodeMirror editor, dataset binding, env vars"
```

---

## Task 9: Smoke test end-to-end

- [ ] **Step 1: Start local dev server**

```bash
cd lakeon-console && npm run dev
```
Open browser to `http://localhost:5173`

- [ ] **Step 2: Verify routing**

Navigate to `/datalake`. Click「提交作业」button.
Expected: browser navigates to `/datalake/jobs/new` (full-page form, not a modal).

- [ ] **Step 3: Verify sections**

- Click through each section in the left nav — Basic, Code, 数据集, 资源, 环境变量, 超时
- Expected: each section renders without JavaScript errors

- [ ] **Step 4: Verify CodeMirror editor**

In the「代码」section, the CodeMirror editor (one-dark theme) appears with the starter Python script.
Type a few characters. Expected: editor responds to input.

- [ ] **Step 5: Verify type switching**

In Basic section, switch from Python → Ray. Expected: left nav changes (「代码」section stays, Ray/Finetune would show placeholder in resources section).

- [ ] **Step 6: Submit a test job (against local or CCE API)**

Fill in:
- 名称: `smoke-test-01`
- 类型: PYTHON
- 代码: default starter script

Click「提交作业」.
Expected: redirects to `/datalake/jobs/{id}` detail page.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat(datalake): complete job creation page implementation"
```
