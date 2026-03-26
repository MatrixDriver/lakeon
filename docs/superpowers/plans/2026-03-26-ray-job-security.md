# Ray Job + Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Ray distributed jobs on CCI with multi-tenant security isolation (NetworkPolicy, ResourceQuota, STS, dataset tenant validation).

**Architecture:** Extract namespace provisioning into shared `DatalakeNamespaceManager` (NetworkPolicy + ResourceQuota + swr-secret). Fix dataset cross-tenant vulnerability. Refactor `RayJobRunner` to use `runtime_env` for pip dependencies and pyobsfs env vars. Add Ray resource picker UI. Install KubeRay Operator on CCE.

**Tech Stack:** Spring Boot / Fabric8 K8s client, KubeRay Operator v1.3, Ray 2.44, Vue 3, pytest E2E.

---

## File Map

**Backend — create:**
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeNamespaceManager.java` — shared namespace provisioning (create ns, NetworkPolicy, ResourceQuota, swr-secret)

**Backend — modify:**
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java` — fix findById → findByIdAndTenantId
- `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java` — extract namespace creation to DatalakeNamespaceManager
- `lakeon-api/src/main/java/com/lakeon/datalake/RayJobRunner.java` — runtime_env, ConfigMap, CCI constraints, namespace manager
- `lakeon-api/src/main/resources/application.yml` — update Ray image preset

**Frontend — modify:**
- `lakeon-console/src/views/datalake/components/DatalakeJobNewResources.vue` — Ray head/worker picker
- `lakeon-console/src/views/datalake/DatalakeJobNew.vue` — head/workers form fields
- `lakeon-console/src/api/datalake.ts` — head/workers in submit request type

**Infrastructure:**
- Install KubeRay Operator on CCE
- Push Ray + KubeRay images to SWR

**Tests:**
- `tests/e2e/test_datalake.py` — add Ray job E2E tests

---

## Task 1: Security fix — dataset cross-tenant validation

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java`

- [ ] **Step 1: Read DatalakeService.java, find the dataset lookup**

Read the file. Find line ~82 where `datasetRepository.findById(dsId)` is used without tenant validation.

- [ ] **Step 2: Fix — change findById to findByIdAndTenantId**

The method needs the tenantId. Read the surrounding code to find how tenantId is available (it should be from the `TenantEntity` or `entity.getTenantId()`).

Replace:
```java
DatasetEntity dataset = datasetRepository.findById(dsId)
        .orElseThrow(() -> new NotFoundException("Dataset not found: " + dsId));
```

With:
```java
DatasetEntity dataset = datasetRepository.findByIdAndTenantId(dsId, entity.getTenantId())
        .orElseThrow(() -> new NotFoundException("Dataset not found: " + dsId));
```

- [ ] **Step 3: Compile and test**
```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeService.java
git commit -m "security(datalake): validate dataset belongs to tenant before injecting DATASET_PATH"
```

---

## Task 2: Extract DatalakeNamespaceManager (NetworkPolicy + ResourceQuota + swr-secret)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeNamespaceManager.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java`

- [ ] **Step 1: Read PythonJobRunner.java namespace creation code**

Read lines 51-85 — the namespace creation + swr-secret copy logic. This will be extracted.

- [ ] **Step 2: Create DatalakeNamespaceManager.java**

Extract the namespace creation logic into a reusable component. Add NetworkPolicy and ResourceQuota creation.

```java
package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.networking.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatalakeNamespaceManager {

    private static final Logger log = LoggerFactory.getLogger(DatalakeNamespaceManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    public DatalakeNamespaceManager(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    /**
     * Ensure the tenant's datalake namespace exists with proper isolation:
     * - Namespace with tenant labels
     * - SWR image pull secret
     * - NetworkPolicy (allow same-ns ingress, deny cross-ns)
     * - ResourceQuota (limit CPU/memory/pods per tenant)
     */
    public String ensureNamespace(String tenantId) {
        String ns = props.getDatalake().getCciNamespacePrefix()
                + tenantId.replace("_", "-");

        if (k8sClient.namespaces().withName(ns).get() != null) {
            return ns;
        }

        // 1. Create namespace
        k8sClient.namespaces().resource(
            new NamespaceBuilder()
                .withNewMetadata().withName(ns)
                    .addToLabels("app", "datalake")
                    .addToLabels("lakeon.io/tenant-id", tenantId)
                .endMetadata()
                .build()
        ).create();
        log.info("Created namespace: {}", ns);

        // 2. Copy SWR image pull secret
        copySWRSecret(ns);

        // 3. NetworkPolicy — allow same-namespace ingress, deny cross-namespace
        createNetworkPolicy(ns);

        // 4. ResourceQuota — limit resources per tenant
        createResourceQuota(ns);

        return ns;
    }

    private void copySWRSecret(String ns) {
        String sourceNs = props.getK8s().getNamespace();
        try {
            var srcSecret = k8sClient.secrets().inNamespace(sourceNs).withName("swr-secret").get();
            if (srcSecret != null) {
                var newSecret = new SecretBuilder()
                    .withNewMetadata().withName("swr-secret").withNamespace(ns).endMetadata()
                    .withType(srcSecret.getType())
                    .withData(srcSecret.getData())
                    .build();
                k8sClient.secrets().inNamespace(ns).resource(newSecret).createOrReplace();
                // Patch default SA
                k8sClient.serviceAccounts().inNamespace(ns).withName("default")
                    .edit(sa -> new ServiceAccountBuilder(sa)
                        .withImagePullSecrets(
                            new LocalObjectReferenceBuilder().withName("swr-secret").build())
                        .build());
                log.info("Copied swr-secret to namespace: {}", ns);
            }
        } catch (Exception e) {
            log.warn("Failed to copy swr-secret to {}: {}", ns, e.getMessage());
        }
    }

    private void createNetworkPolicy(String ns) {
        try {
            NetworkPolicy policy = new NetworkPolicyBuilder()
                .withNewMetadata().withName("tenant-isolation").withNamespace(ns).endMetadata()
                .withNewSpec()
                    .withNewPodSelector().endPodSelector()  // applies to all pods
                    .withPolicyTypes("Ingress", "Egress")
                    .addNewIngress()
                        .addNewFrom()
                            .withNewPodSelector().endPodSelector()  // same namespace only
                        .endFrom()
                    .endIngress()
                    .addNewEgress()  // allow all egress (OBS, DNS, pip install)
                    .endEgress()
                .endSpec()
                .build();
            k8sClient.network().networkPolicies().inNamespace(ns).resource(policy).createOrReplace();
            log.info("Created NetworkPolicy in namespace: {}", ns);
        } catch (Exception e) {
            log.warn("Failed to create NetworkPolicy in {}: {}", ns, e.getMessage());
        }
    }

    private void createResourceQuota(String ns) {
        try {
            ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata().withName("tenant-quota").withNamespace(ns).endMetadata()
                .withNewSpec()
                    .addToHard("requests.cpu", new Quantity("20"))
                    .addToHard("requests.memory", new Quantity("40Gi"))
                    .addToHard("limits.cpu", new Quantity("20"))
                    .addToHard("limits.memory", new Quantity("40Gi"))
                    .addToHard("pods", new Quantity("20"))
                .endSpec()
                .build();
            k8sClient.resourceQuotas().inNamespace(ns).resource(quota).createOrReplace();
            log.info("Created ResourceQuota in namespace: {}", ns);
        } catch (Exception e) {
            log.warn("Failed to create ResourceQuota in {}: {}", ns, e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Update PythonJobRunner to use DatalakeNamespaceManager**

Replace the inline namespace creation code (lines 51-85) with:
```java
private final DatalakeNamespaceManager nsManager;
// Add to constructor

// In start(), replace namespace creation block with:
String ns = nsManager.ensureNamespace(job.getTenantId());
```

Remove the inline namespace creation + swr-secret copy code.

- [ ] **Step 4: Compile and test**
```bash
cd lakeon-api && mvn compile -q && mvn test -Dtest=PythonJobRunnerTest -q 2>&1 | tail -5
```

Update `PythonJobRunnerTest` to mock `DatalakeNamespaceManager`.

- [ ] **Step 5: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeNamespaceManager.java \
        lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java \
        lakeon-api/src/test/java/com/lakeon/datalake/PythonJobRunnerTest.java
git commit -m "feat(datalake): extract DatalakeNamespaceManager with NetworkPolicy + ResourceQuota + swr-secret"
```

---

## Task 3: Infrastructure — install KubeRay Operator + push Ray images

**This task is manual / CLI-based.**

- [ ] **Step 1: Push KubeRay Operator image to SWR**

```bash
no_proxy="*" docker pull quay.io/kuberay/operator:v1.3.0
no_proxy="*" docker tag quay.io/kuberay/operator:v1.3.0 swr.cn-north-4.myhuaweicloud.com/flex/kuberay-operator:v1.3.0
no_proxy="*" docker push swr.cn-north-4.myhuaweicloud.com/flex/kuberay-operator:v1.3.0
```

- [ ] **Step 2: Install KubeRay Operator via Helm**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" helm repo add kuberay https://ray-project.github.io/kuberay-helm/
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" helm install kuberay-operator kuberay/kuberay-operator \
  -n lakeon \
  --set image.repository=swr.cn-north-4.myhuaweicloud.com/flex/kuberay-operator \
  --set image.tag=v1.3.0 \
  --set nodeSelector."lakeon/role"='' \
  --set tolerations=[]
```

Note: `nodeSelector` must NOT select `lakeon/role=compute` (operator runs on fixed node, not CCI).

- [ ] **Step 3: Verify KubeRay Operator is running**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" kubectl get pods -n lakeon -l app.kubernetes.io/name=kuberay-operator
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" kubectl get crd rayjobs.ray.io
```

Expected: operator pod Running, CRD exists.

- [ ] **Step 4: Push Ray image to SWR**

```bash
no_proxy="*" docker pull rayproject/ray:2.44.1-py311
no_proxy="*" docker tag rayproject/ray:2.44.1-py311 swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311
no_proxy="*" docker push swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311
```

- [ ] **Step 5: Update values.yaml with correct Ray image**

In `deploy/cce/sites/hwstaff/values.yaml`, update the ray image preset:
```yaml
datalake:
  images:
    ray: "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311"
```

---

## Task 4: Refactor RayJobRunner — runtime_env, CCI constraints, namespace manager

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/RayJobRunner.java`

- [ ] **Step 1: Read the current RayJobRunner.java in full**

Understand the existing CRD building logic, env var injection, image resolution.

- [ ] **Step 2: Refactor RayJobRunner**

Key changes:
1. Add `DatalakeNamespaceManager` and `ObsStsService` to constructor
2. Use `nsManager.ensureNamespace()` instead of assuming namespace exists
3. Add `runtimeEnvYAML` with pip deps (from `req.getRequirements()`) + OBS env vars
4. Mount inline_script ConfigMap on head pod
5. Enforce CCI constraints: CPU >= 250m, requests = limits
6. Add `imagePullSecrets: [swr-secret]` to head and worker pod specs

The `runtimeEnvYAML` should include:
- `pip`: list from requirements (always include `pyobsfs`)
- `env_vars`: OBS STS credentials + DATASET_PATH + OUTPUT_PATH

For inline_script: create ConfigMap (same pattern as PythonJobRunner), mount on head pod via `volumes`/`volumeMounts` in the RayJob spec.

- [ ] **Step 3: Compile**
```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/RayJobRunner.java
git commit -m "feat(datalake): refactor RayJobRunner — runtime_env, CCI constraints, namespace manager"
```

---

## Task 5: Frontend — Ray resource picker

**Files:**
- Modify: `lakeon-console/src/views/datalake/components/DatalakeJobNewResources.vue`
- Modify: `lakeon-console/src/views/datalake/DatalakeJobNew.vue`
- Modify: `lakeon-console/src/api/datalake.ts`

- [ ] **Step 1: Update DatalakeJobSubmitRequest type in datalake.ts**

Ensure `head` and `workers` fields exist in the submit request type (they should already be there from the original spec).

- [ ] **Step 2: Add head/workers to DatalakeJobNew.vue form state**

Read the file. Add to the `form` ref:
```typescript
head: { cpu: '2', memory: '4Gi' },
workers: { replicas: 2, cpu: '2', memory: '4Gi' },
```

Pass these to `DatalakeJobNewResources` as props.

In `handleSubmit()`, include head/workers in the body when type is RAY:
```typescript
head: form.value.type === 'RAY' ? form.value.head : undefined,
workers: form.value.type === 'RAY' ? form.value.workers : undefined,
```

- [ ] **Step 3: Replace "coming soon" in DatalakeJobNewResources.vue with Ray picker**

Read the current file. Replace the `<template v-else>` block with:

```vue
<template v-else-if="type === 'RAY'">
  <div class="section-desc">配置 Ray 集群资源。Head 节点运行主程序，Worker 节点执行分布式任务。</div>

  <div class="subsection">
    <div class="subsection-title">Head 节点</div>
    <div class="resource-row">
      <div class="field-group">
        <label class="field-label">CPU</label>
        <select class="field-select" :value="head.cpu" @change="$emit('update:head', {...head, cpu: ($event.target as HTMLSelectElement).value})">
          <option value="1">1 核</option>
          <option value="2">2 核</option>
          <option value="4">4 核</option>
        </select>
      </div>
      <div class="field-group">
        <label class="field-label">内存</label>
        <select class="field-select" :value="head.memory" @change="$emit('update:head', {...head, memory: ($event.target as HTMLSelectElement).value})">
          <option value="2Gi">2 Gi</option>
          <option value="4Gi">4 Gi</option>
          <option value="8Gi">8 Gi</option>
        </select>
      </div>
    </div>
  </div>

  <div class="subsection">
    <div class="subsection-title">Worker 节点</div>
    <div class="resource-row">
      <div class="field-group">
        <label class="field-label">副本数</label>
        <select class="field-select" :value="String(workers.replicas)" @change="$emit('update:workers', {...workers, replicas: Number(($event.target as HTMLSelectElement).value)})">
          <option v-for="n in 8" :key="n" :value="String(n)">{{ n }}</option>
        </select>
      </div>
      <div class="field-group">
        <label class="field-label">CPU（每节点）</label>
        <select class="field-select" :value="workers.cpu" @change="$emit('update:workers', {...workers, cpu: ($event.target as HTMLSelectElement).value})">
          <option value="1">1 核</option>
          <option value="2">2 核</option>
          <option value="4">4 核</option>
        </select>
      </div>
      <div class="field-group">
        <label class="field-label">内存（每节点）</label>
        <select class="field-select" :value="workers.memory" @change="$emit('update:workers', {...workers, memory: ($event.target as HTMLSelectElement).value})">
          <option value="2Gi">2 Gi</option>
          <option value="4Gi">4 Gi</option>
          <option value="8Gi">8 Gi</option>
        </select>
      </div>
    </div>
  </div>
</template>

<!-- Finetune: still coming soon -->
<template v-else>
  <div class="coming-soon">
    <div class="coming-soon-icon">🚧</div>
    <div>GPU 资源配置即将推出</div>
  </div>
</template>
```

Update props to include `head` and `workers`. Add emits for `update:head` and `update:workers`.

- [ ] **Step 4: Add CSS for subsection**

```css
.subsection { margin-bottom: 16px; }
.subsection-title { font-size: 13px; font-weight: 600; color: #1e293b; margin-bottom: 8px; }
```

- [ ] **Step 5: Build and verify**
```bash
cd lakeon-console && npm run build 2>&1 | tail -10
```

- [ ] **Step 6: Commit**
```bash
git add lakeon-console/src/views/datalake/components/DatalakeJobNewResources.vue \
        lakeon-console/src/views/datalake/DatalakeJobNew.vue \
        lakeon-console/src/api/datalake.ts
git commit -m "feat(datalake): Ray head/worker resource picker UI"
```

---

## Task 6: Deploy + E2E tests

- [ ] **Step 1: Build and deploy API**

```bash
IMAGE_TAG=0.9.84 ./deploy/cce/build-and-push-api.sh
# Update values.yaml tag
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 2: Push console + restart**

```bash
git push origin main  # Railway auto-deploys
```

- [ ] **Step 3: Verify KubeRay is functional — submit test Ray job via API**

```bash
API_KEY="..."
no_proxy="*" curl -sk -X POST "https://api.dbay.cloud:8443/api/v1/datalake/jobs" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ray-test-basic",
    "type": "RAY",
    "inline_script": "import ray\nray.init()\nprint(\"Ray nodes:\", ray.nodes())\nprint(\"SUCCESS\")",
    "head": {"cpu": "1", "memory": "2Gi"},
    "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
    "timeout_seconds": 120
  }'
```

Poll until SUCCEEDED or FAILED. Check logs.

- [ ] **Step 4: Add Ray E2E tests to test_datalake.py**

Add a new test class `TestDatalakeRayJob`:

```python
@pytest.mark.skipif(os.environ.get("SKIP_RAY_TESTS", "1") == "1",
                    reason="SKIP_RAY_TESTS=1 — set to 0 to run")
class TestDatalakeRayJob:
    """Ray job E2E tests."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-{ts}", f"Ray@{ts}", f"Ray E2E {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    def test_ray_job_submits_and_succeeds(self, client):
        """Submit a simple Ray job and wait for SUCCEEDED."""
        job = client.submit_datalake_job({
            "name": f"e2e-ray-{int(time.time())}",
            "type": "RAY",
            "inline_script": "import ray; ray.init(); print('Ray nodes:', ray.nodes()); print('SUCCESS')",
            "head": {"cpu": "1", "memory": "2Gi"},
            "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
            "timeout_seconds": 180,
        })
        assert job["type"] == "RAY"

        final = _wait_terminal(client, job["id"], timeout=300)
        assert final == "SUCCEEDED", f"Ray job failed: {final}"
```

- [ ] **Step 5: Run E2E tests**

```bash
SKIP_RAY_TESTS=0 no_proxy="*" python -m pytest tests/e2e/test_datalake.py::TestDatalakeRayJob -v --tb=short
```

- [ ] **Step 6: Run full dataset E2E to verify security fix**

```bash
no_proxy="*" python -m pytest tests/e2e/test_dataset.py tests/e2e/test_dataset_extended.py -v --tb=short
```

- [ ] **Step 7: Verify NetworkPolicy and ResourceQuota exist**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" kubectl get networkpolicy -n datalake-tn-{tenantId}
KUBECONFIG=~/.kube/cce-lakeon-config no_proxy="*" kubectl get resourcequota -n datalake-tn-{tenantId}
```
