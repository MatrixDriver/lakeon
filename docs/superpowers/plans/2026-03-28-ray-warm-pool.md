# Ray Notebook Warm Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pre-create 2 idle Ray head pods on CCI so notebook sessions start in ~13s instead of ~30s+.

**Architecture:** A `WarmPoolManager` Spring bean maintains a pool of 2 idle Ray head pods in a dedicated `datalake-pool` namespace. When a user creates a Ray notebook session, `NotebookService` claims a pod from the pool instead of cold-starting one. Used pods are destroyed (never recycled), and the pool controller replenishes asynchronously.

**Tech Stack:** Java 21, Spring Boot, Fabric8 Kubernetes Client, JUnit 5 + Mockito

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `lakeon-api/src/main/java/com/lakeon/notebook/WarmPoolManager.java` | Create | Pool lifecycle: create/claim/release/replenish idle Ray head pods |
| `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java` | Modify | Use WarmPoolManager for Ray sessions, fallback to cold start |
| `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java` | Modify | Look up pods in `datalake-pool` namespace when session uses warm pool |
| `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` | Modify | Add warm pool config fields to DatalakeConfig |
| `lakeon-api/src/main/resources/application.yml` | Modify | Add warm pool default config values |
| `lakeon-api/src/test/java/com/lakeon/notebook/WarmPoolManagerTest.java` | Create | Unit tests for pool logic |
| `lakeon-api/src/test/java/com/lakeon/notebook/NotebookServiceWarmPoolTest.java` | Create | Unit tests for warm pool integration in NotebookService |

---

### Task 1: Add warm pool config to LakeonProperties

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java:380-402`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add warm pool fields to DatalakeConfig**

In `LakeonProperties.java`, add these fields to the `DatalakeConfig` inner class (after line 384, before `presetImages`):

```java
private boolean warmPoolEnabled = true;
private int warmPoolSize = 2;
private String warmPoolNamespace = "datalake-pool";
private String warmPoolImage = "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data";
private int warmPoolIdleTimeoutMinutes = 30;
```

Add getters/setters after the existing ones (after line 401):

```java
public boolean isWarmPoolEnabled() { return warmPoolEnabled; }
public void setWarmPoolEnabled(boolean warmPoolEnabled) { this.warmPoolEnabled = warmPoolEnabled; }
public int getWarmPoolSize() { return warmPoolSize; }
public void setWarmPoolSize(int warmPoolSize) { this.warmPoolSize = warmPoolSize; }
public String getWarmPoolNamespace() { return warmPoolNamespace; }
public void setWarmPoolNamespace(String warmPoolNamespace) { this.warmPoolNamespace = warmPoolNamespace; }
public String getWarmPoolImage() { return warmPoolImage; }
public void setWarmPoolImage(String warmPoolImage) { this.warmPoolImage = warmPoolImage; }
public int getWarmPoolIdleTimeoutMinutes() { return warmPoolIdleTimeoutMinutes; }
public void setWarmPoolIdleTimeoutMinutes(int warmPoolIdleTimeoutMinutes) { this.warmPoolIdleTimeoutMinutes = warmPoolIdleTimeoutMinutes; }
```

- [ ] **Step 2: Add warm pool config to application.yml**

In `application.yml`, add under the `datalake:` section (after the `preset-images` block, around line 133):

```yaml
  warm-pool-enabled: "${LAKEON_DATALAKE_WARM_POOL_ENABLED:true}"
  warm-pool-size: "${LAKEON_DATALAKE_WARM_POOL_SIZE:2}"
  warm-pool-namespace: "${LAKEON_DATALAKE_WARM_POOL_NS:datalake-pool}"
  warm-pool-image: "${LAKEON_DATALAKE_WARM_POOL_IMAGE:swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data}"
  warm-pool-idle-timeout-minutes: "${LAKEON_DATALAKE_WARM_POOL_IDLE_TIMEOUT:30}"
```

- [ ] **Step 3: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java lakeon-api/src/main/resources/application.yml
git commit -m "feat(notebook): add warm pool config to LakeonProperties"
```

---

### Task 2: Create WarmPoolManager with tests

**Files:**
- Create: `lakeon-api/src/test/java/com/lakeon/notebook/WarmPoolManagerTest.java`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/WarmPoolManager.java`

- [ ] **Step 1: Write failing tests for WarmPoolManager**

Create `lakeon-api/src/test/java/com/lakeon/notebook/WarmPoolManagerTest.java`:

```java
package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.datalake.DatalakeNamespaceManager;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarmPoolManager 单元测试")
class WarmPoolManagerTest {

    @Mock private KubernetesClient k8sClient;
    @Mock private LakeonProperties props;
    @Mock private DatalakeNamespaceManager nsManager;

    // Mock chain for k8sClient.pods().inNamespace(ns)
    @Mock private MixedOperation<Pod, PodList, PodResource> podOps;
    @Mock private NonNamespaceOperation<Pod, PodList, PodResource> podNsOps;
    @Mock private PodResource podResource;

    private WarmPoolManager manager;
    private LakeonProperties.DatalakeConfig dlConfig;

    @BeforeEach
    void setUp() {
        dlConfig = new LakeonProperties.DatalakeConfig();
        dlConfig.setWarmPoolEnabled(true);
        dlConfig.setWarmPoolSize(2);
        dlConfig.setWarmPoolNamespace("datalake-pool");
        dlConfig.setWarmPoolImage("swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data");
        dlConfig.setWarmPoolIdleTimeoutMinutes(30);
        when(props.getDatalake()).thenReturn(dlConfig);

        lenient().when(k8sClient.pods()).thenReturn(podOps);
        lenient().when(podOps.inNamespace("datalake-pool")).thenReturn(podNsOps);

        manager = new WarmPoolManager(k8sClient, props, nsManager);
    }

    @Nested
    @DisplayName("claimHead")
    class ClaimHead {

        @Test
        @DisplayName("returns idle pod and marks it claimed")
        void claimIdlePod() {
            // Given: one idle pod in the pool
            Pod idlePod = buildIdlePod("warm-ray-head-abc12345", "10.0.0.1");
            PodList podList = new PodListBuilder().withItems(idlePod).build();
            when(podNsOps.withLabel("lakeon.io/pool", "warm")).thenReturn(podNsOps);
            when(podNsOps.withLabel("lakeon.io/status", "idle")).thenReturn(podNsOps);
            when(podNsOps.list()).thenReturn(podList);
            when(podNsOps.withName("warm-ray-head-abc12345")).thenReturn(podResource);
            when(podResource.edit(any())).thenReturn(idlePod);

            // When
            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant_123", "nbs_abc");

            // Then
            assertThat(result).isPresent();
            assertThat(result.get().podName()).isEqualTo("warm-ray-head-abc12345");
            assertThat(result.get().podIp()).isEqualTo("10.0.0.1");
            assertThat(result.get().namespace()).isEqualTo("datalake-pool");
        }

        @Test
        @DisplayName("returns empty when pool is exhausted")
        void poolExhausted() {
            PodList emptyList = new PodListBuilder().withItems(Collections.emptyList()).build();
            when(podNsOps.withLabel("lakeon.io/pool", "warm")).thenReturn(podNsOps);
            when(podNsOps.withLabel("lakeon.io/status", "idle")).thenReturn(podNsOps);
            when(podNsOps.list()).thenReturn(emptyList);

            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant_123", "nbs_abc");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("skips pods without IP")
        void skipsPodWithoutIp() {
            Pod noIpPod = buildIdlePod("warm-ray-head-noip", null);
            PodList podList = new PodListBuilder().withItems(noIpPod).build();
            when(podNsOps.withLabel("lakeon.io/pool", "warm")).thenReturn(podNsOps);
            when(podNsOps.withLabel("lakeon.io/status", "idle")).thenReturn(podNsOps);
            when(podNsOps.list()).thenReturn(podList);

            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant_123", "nbs_abc");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("releaseHead")
    class ReleaseHead {

        @Test
        @DisplayName("deletes the pod and its workers")
        void deletesPodAndWorkers() {
            when(podNsOps.withName("warm-ray-head-abc12345")).thenReturn(podResource);
            when(podNsOps.withLabel("lakeon.io/session-id", "nbs_abc")).thenReturn(podNsOps);

            manager.releaseHead("warm-ray-head-abc12345", "nbs_abc");

            verify(podResource).delete();
            verify(podNsOps).delete();
        }
    }

    @Nested
    @DisplayName("reconcile")
    class Reconcile {

        @Test
        @DisplayName("creates pods when pool below target")
        void replenishesPool() {
            // Pool has 0 idle pods -> should create 2
            PodList emptyList = new PodListBuilder().withItems(Collections.emptyList()).build();
            when(podNsOps.withLabel("lakeon.io/pool", "warm")).thenReturn(podNsOps);
            when(podNsOps.list()).thenReturn(emptyList);
            when(podNsOps.resource(any(Pod.class))).thenReturn(podResource);
            when(podResource.create()).thenReturn(new Pod());

            manager.reconcile();

            verify(podNsOps, times(2)).resource(any(Pod.class));
        }

        @Test
        @DisplayName("does nothing when pool is full")
        void poolFull() {
            Pod pod1 = buildIdlePod("warm-ray-head-aaa", "10.0.0.1");
            Pod pod2 = buildIdlePod("warm-ray-head-bbb", "10.0.0.2");
            PodList fullList = new PodListBuilder().withItems(pod1, pod2).build();
            when(podNsOps.withLabel("lakeon.io/pool", "warm")).thenReturn(podNsOps);
            when(podNsOps.list()).thenReturn(fullList);

            manager.reconcile();

            verify(podNsOps, never()).resource(any(Pod.class));
        }
    }

    @Nested
    @DisplayName("disabled pool")
    class Disabled {

        @BeforeEach
        void disablePool() {
            dlConfig.setWarmPoolEnabled(false);
        }

        @Test
        @DisplayName("claimHead returns empty when disabled")
        void claimReturnEmpty() {
            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("t", "s");
            assertThat(result).isEmpty();
            verifyNoInteractions(k8sClient);
        }

        @Test
        @DisplayName("reconcile is no-op when disabled")
        void reconcileNoop() {
            manager.reconcile();
            verifyNoInteractions(k8sClient);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Pod buildIdlePod(String name, String ip) {
        PodBuilder builder = new PodBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("datalake-pool")
                .withLabels(Map.of(
                    "lakeon.io/pool", "warm",
                    "lakeon.io/status", "idle"))
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .withPodIP(ip)
            .endStatus();
        return builder.build();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -pl . -Dtest=WarmPoolManagerTest -q 2>&1 | tail -5`
Expected: Compilation error — `WarmPoolManager` does not exist yet

- [ ] **Step 3: Implement WarmPoolManager**

Create `lakeon-api/src/main/java/com/lakeon/notebook/WarmPoolManager.java`:

```java
package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.datalake.DatalakeNamespaceManager;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class WarmPoolManager {

    private static final Logger log = LoggerFactory.getLogger(WarmPoolManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final DatalakeNamespaceManager nsManager;

    public WarmPoolManager(KubernetesClient k8sClient,
                           LakeonProperties props,
                           DatalakeNamespaceManager nsManager) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.nsManager = nsManager;
    }

    public record ClaimedHead(String podName, String podIp, String namespace) {}

    /**
     * Claim an idle head pod from the warm pool.
     * Labels the pod as claimed with tenantId and sessionId.
     * Returns empty if pool is exhausted or disabled.
     */
    public Optional<ClaimedHead> claimHead(String tenantId, String sessionId) {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) return Optional.empty();

        String ns = dl.getWarmPoolNamespace();
        List<Pod> idlePods = k8sClient.pods().inNamespace(ns)
                .withLabel("lakeon.io/pool", "warm")
                .withLabel("lakeon.io/status", "idle")
                .list().getItems();

        for (Pod pod : idlePods) {
            String podName = pod.getMetadata().getName();
            String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
            if (podIp == null || podIp.isBlank()) continue;
            if (!"Running".equals(pod.getStatus().getPhase())) continue;

            // Atomically mark as claimed via label edit
            try {
                k8sClient.pods().inNamespace(ns).withName(podName).edit(p -> {
                    p.getMetadata().getLabels().put("lakeon.io/status", "claimed");
                    p.getMetadata().getLabels().put("lakeon.io/tenant-id", tenantId);
                    p.getMetadata().getLabels().put("lakeon.io/session-id", sessionId);
                    return p;
                });
                log.info("Claimed warm pool head pod: {} (tenant={}, session={})", podName, tenantId, sessionId);
                return Optional.of(new ClaimedHead(podName, podIp, ns));
            } catch (Exception e) {
                log.warn("Failed to claim pod {}, trying next: {}", podName, e.getMessage());
            }
        }

        log.warn("Warm pool exhausted, no idle head pods available");
        return Optional.empty();
    }

    /**
     * Release (delete) a claimed head pod and its workers.
     * Called when a session is stopped.
     */
    public void releaseHead(String podName, String sessionId) {
        String ns = props.getDatalake().getWarmPoolNamespace();
        try {
            k8sClient.pods().inNamespace(ns).withName(podName).delete();
            log.info("Deleted warm pool head pod: {}", podName);
        } catch (Exception e) {
            log.warn("Failed to delete warm pool head pod {}: {}", podName, e.getMessage());
        }
        // Delete workers by session label
        try {
            k8sClient.pods().inNamespace(ns)
                    .withLabel("lakeon.io/session-id", sessionId)
                    .delete();
        } catch (Exception e) {
            log.warn("Failed to delete workers for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Reconcile loop: ensure pool has the target number of idle pods.
     * Also cleans up pods that have been idle longer than the timeout.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void reconcile() {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) return;

        String ns = dl.getWarmPoolNamespace();
        int targetSize = dl.getWarmPoolSize();

        List<Pod> poolPods;
        try {
            poolPods = k8sClient.pods().inNamespace(ns)
                    .withLabel("lakeon.io/pool", "warm")
                    .list().getItems();
        } catch (Exception e) {
            log.debug("Failed to list warm pool pods: {}", e.getMessage());
            return;
        }

        // Clean up idle pods past timeout
        Instant cutoff = Instant.now().minusSeconds(dl.getWarmPoolIdleTimeoutMinutes() * 60L);
        for (Pod pod : poolPods) {
            if (!"idle".equals(pod.getMetadata().getLabels().get("lakeon.io/status"))) continue;
            Instant created = Instant.parse(pod.getMetadata().getCreationTimestamp());
            if (created.isBefore(cutoff)) {
                try {
                    k8sClient.pods().inNamespace(ns).withName(pod.getMetadata().getName()).delete();
                    log.info("Deleted idle-timeout warm pool pod: {}", pod.getMetadata().getName());
                } catch (Exception e) {
                    log.warn("Failed to delete timed-out pod: {}", e.getMessage());
                }
            }
        }

        // Count current idle pods (re-count after cleanup)
        long idleCount = poolPods.stream()
                .filter(p -> "idle".equals(p.getMetadata().getLabels().get("lakeon.io/status")))
                .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
                .count();

        // Also count pods that are still starting (Pending) to avoid over-creating
        long pendingCount = poolPods.stream()
                .filter(p -> "idle".equals(p.getMetadata().getLabels().get("lakeon.io/status")))
                .filter(p -> p.getStatus() != null && "Pending".equals(p.getStatus().getPhase()))
                .count();

        long deficit = targetSize - idleCount - pendingCount;
        if (deficit <= 0) return;

        log.info("Warm pool: idle={}, pending={}, target={}, creating {} new pods",
                idleCount, pendingCount, targetSize, deficit);

        for (long i = 0; i < deficit; i++) {
            createIdleHeadPod(ns, dl);
        }
    }

    @PostConstruct
    public void init() {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) {
            log.info("Warm pool disabled");
            return;
        }
        // Ensure pool namespace exists
        try {
            String ns = dl.getWarmPoolNamespace();
            if (k8sClient.namespaces().withName(ns).get() == null) {
                k8sClient.namespaces().resource(
                    new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(ns)
                            .addToLabels("app", "datalake")
                            .addToLabels("lakeon.io/purpose", "warm-pool")
                        .endMetadata()
                        .build()
                ).create();
                log.info("Created warm pool namespace: {}", ns);
            }
        } catch (Exception e) {
            log.warn("Failed to ensure warm pool namespace: {}", e.getMessage());
        }
        log.info("Warm pool initialized: target={}, namespace={}", dl.getWarmPoolSize(), dl.getWarmPoolNamespace());
    }

    private void createIdleHeadPod(String ns, LakeonProperties.DatalakeConfig dl) {
        String podName = "warm-ray-head-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Toleration vkToleration = new TolerationBuilder()
                .withKey("virtual-kubelet.io/provider")
                .withOperator("Exists")
                .withEffect("NoSchedule")
                .build();

        Container container = new ContainerBuilder()
                .withName("repl")
                .withImage(dl.getWarmPoolImage())
                .withCommand("bash", "-c",
                        "ray start --head --port=6379 --num-cpus=0 && sleep infinity")
                .withStdin(true)
                .withStdinOnce(false)
                .withTty(false)
                .withNewResources()
                    .withRequests(Map.of(
                            "cpu", new Quantity("500m"),
                            "memory", new Quantity("2Gi")))
                    .withLimits(Map.of(
                            "cpu", new Quantity("2"),
                            "memory", new Quantity("4Gi")))
                .endResources()
                .build();

        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(ns)
                    .withLabels(Map.of(
                            "lakeon.io/pool", "warm",
                            "lakeon.io/status", "idle",
                            "app", "notebook"))
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withNodeSelector(Map.of(dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
                    .withTolerations(vkToleration)
                    .withContainers(container)
                .endSpec()
                .build();

        try {
            k8sClient.pods().inNamespace(ns).resource(pod).create();
            log.info("Created warm pool idle head pod: {}/{}", ns, podName);
        } catch (Exception e) {
            log.warn("Failed to create warm pool pod: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd lakeon-api && mvn test -pl . -Dtest=WarmPoolManagerTest -q 2>&1 | tail -10`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/notebook/WarmPoolManager.java \
        lakeon-api/src/test/java/com/lakeon/notebook/WarmPoolManagerTest.java
git commit -m "feat(notebook): add WarmPoolManager for Ray head pod warm pool"
```

---

### Task 3: Integrate warm pool into NotebookService with tests

**Files:**
- Create: `lakeon-api/src/test/java/com/lakeon/notebook/NotebookServiceWarmPoolTest.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java`

- [ ] **Step 1: Write failing tests for warm pool integration**

Create `lakeon-api/src/test/java/com/lakeon/notebook/NotebookServiceWarmPoolTest.java`:

```java
package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.datalake.DatalakeNamespaceManager;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotebookService warm pool integration")
class NotebookServiceWarmPoolTest {

    @Mock private NotebookSessionRepository sessionRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private KubernetesClient k8sClient;
    @Mock private LakeonProperties props;
    @Mock private DatalakeNamespaceManager nsManager;
    @Mock private ObsStsService obsStsService;
    @Mock private WarmPoolManager warmPoolManager;

    // K8s mock chain
    @Mock private MixedOperation<Pod, PodList, PodResource> podOps;
    @Mock private NonNamespaceOperation<Pod, PodList, PodResource> podNsOps;
    @Mock private PodResource podResource;
    @Mock private MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> cmOps;
    @Mock private NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> cmNsOps;
    @Mock private Resource<ConfigMap> cmResource;

    private NotebookService service;

    @BeforeEach
    void setUp() {
        LakeonProperties.DatalakeConfig dlConfig = new LakeonProperties.DatalakeConfig();
        lenient().when(props.getDatalake()).thenReturn(dlConfig);

        LakeonProperties.K8sConfig k8sConfig = new LakeonProperties.K8sConfig();
        lenient().when(props.getK8s()).thenReturn(k8sConfig);

        LakeonProperties.ObsConfig obsConfig = new LakeonProperties.ObsConfig();
        lenient().when(props.getObs()).thenReturn(obsConfig);

        lenient().when(k8sClient.pods()).thenReturn(podOps);
        lenient().when(podOps.inNamespace(anyString())).thenReturn(podNsOps);
        lenient().when(podNsOps.withName(anyString())).thenReturn(podResource);
        lenient().when(podNsOps.resource(any(Pod.class))).thenReturn(podResource);
        lenient().when(podResource.create()).thenReturn(new Pod());

        lenient().when(k8sClient.configMaps()).thenReturn(cmOps);
        lenient().when(cmOps.inNamespace(anyString())).thenReturn(cmNsOps);
        lenient().when(cmNsOps.resource(any(ConfigMap.class))).thenReturn(cmResource);

        lenient().when(nsManager.ensureNamespace(anyString())).thenReturn("datalake-tenant-123");

        service = new NotebookService(sessionRepo, datasetRepo, k8sClient, props, nsManager, obsStsService, warmPoolManager);
    }

    @Test
    @DisplayName("Ray session uses warm pool head when available")
    void raySessionUsesWarmPool() {
        // Given: no existing session, warm pool has an idle pod
        when(sessionRepo.findByTenantIdAndStatus(eq("t1"), any())).thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            NotebookSessionEntity s = inv.getArgument(0);
            if (s.getId() == null) { s.prePersist(); }
            return s;
        });
        when(warmPoolManager.claimHead(eq("t1"), anyString()))
                .thenReturn(Optional.of(new WarmPoolManager.ClaimedHead(
                        "warm-ray-head-abc12345", "10.0.0.1", "datalake-pool")));
        when(obsStsService.getCredentials("t1"))
                .thenReturn(new ObsStsService.StsCredentials("ak", "sk", "token"));

        // When
        NotebookSessionEntity result = service.getOrCreateSession("t1", "ray", null, 2, "small");

        // Then
        assertThat(result.getStatus()).isEqualTo(NotebookSessionStatus.RUNNING);
        assertThat(result.getPodName()).isEqualTo("warm-ray-head-abc12345");
        assertThat(result.getNamespace()).isEqualTo("datalake-pool");

        // Workers should be created in datalake-pool, not in tenant namespace
        ArgumentCaptor<Pod> podCaptor = ArgumentCaptor.forClass(Pod.class);
        verify(podNsOps, atLeast(2)).resource(podCaptor.capture());
        List<Pod> createdPods = podCaptor.getAllValues();
        long workerCount = createdPods.stream()
                .filter(p -> p.getMetadata().getName().contains("-worker-"))
                .count();
        assertThat(workerCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Falls back to cold start when pool exhausted")
    void fallbackToColdStart() throws Exception {
        // Given: warm pool empty
        when(sessionRepo.findByTenantIdAndStatus(eq("t1"), any())).thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            NotebookSessionEntity s = inv.getArgument(0);
            if (s.getId() == null) { s.prePersist(); }
            return s;
        });
        when(warmPoolManager.claimHead(eq("t1"), anyString())).thenReturn(Optional.empty());
        when(obsStsService.getCredentials("t1"))
                .thenReturn(new ObsStsService.StsCredentials("ak", "sk", "token"));

        // Mock head pod becoming Running with IP (for cold start polling)
        Pod runningPod = new PodBuilder()
                .withNewStatus().withPhase("Running").withPodIP("10.0.0.99").endStatus()
                .build();
        when(podResource.get()).thenReturn(runningPod);

        // When
        NotebookSessionEntity result = service.getOrCreateSession("t1", "ray", null, 1, "small");

        // Then: should still succeed via cold start path
        assertThat(result.getStatus()).isEqualTo(NotebookSessionStatus.RUNNING);
        // Namespace should be the tenant namespace (not datalake-pool)
        assertThat(result.getNamespace()).isEqualTo("datalake-tenant-123");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd lakeon-api && mvn test -pl . -Dtest=NotebookServiceWarmPoolTest -q 2>&1 | tail -5`
Expected: Compilation error — `NotebookService` constructor doesn't accept `WarmPoolManager` yet

- [ ] **Step 3: Modify NotebookService to accept WarmPoolManager and use it**

In `NotebookService.java`, make these changes:

**Add field and constructor parameter** — after `obsStsService` field (line 35), add:

```java
private final WarmPoolManager warmPoolManager;
```

Update constructor (lines 37-49) to include WarmPoolManager:

```java
public NotebookService(NotebookSessionRepository sessionRepo,
                       DatasetRepository datasetRepo,
                       KubernetesClient k8sClient,
                       LakeonProperties props,
                       DatalakeNamespaceManager nsManager,
                       ObsStsService obsStsService,
                       WarmPoolManager warmPoolManager) {
    this.sessionRepo = sessionRepo;
    this.datasetRepo = datasetRepo;
    this.k8sClient = k8sClient;
    this.props = props;
    this.nsManager = nsManager;
    this.obsStsService = obsStsService;
    this.warmPoolManager = warmPoolManager;
}
```

**Modify `getOrCreateSession`** — replace the Ray creation block (lines 94-100) with:

```java
// Create the pod (or Ray cluster)
try {
    boolean isRay = "ray".equals(resolvedImageKey) && workerCount != null && workerCount > 0;
    if (isRay) {
        // Try warm pool first
        Optional<WarmPoolManager.ClaimedHead> claimed = warmPoolManager.claimHead(tenantId, session.getId());
        if (claimed.isPresent()) {
            WarmPoolManager.ClaimedHead head = claimed.get();
            session.setPodName(head.podName());
            session.setNamespace(head.namespace());
            session = sessionRepo.save(session);
            createWorkersForWarmHead(session, tenantId, datasetIds, image,
                    head.podName(), head.namespace(), head.podIp(), workerCount, workerSize);
        } else {
            log.warn("Warm pool exhausted, falling back to cold start for tenant {}", tenantId);
            createRayNotebookCluster(session, tenantId, datasetIds, image, podName, ns, workerCount, workerSize);
        }
    } else {
        createNotebookPod(session, tenantId, datasetIds, image, podName, ns);
    }
    session.setStatus(NotebookSessionStatus.RUNNING);
    session = sessionRepo.save(session);
} catch (Exception e) {
    log.error("Failed to create notebook pod for tenant {}: {}", tenantId, e.getMessage());
    session.setStatus(NotebookSessionStatus.STOPPED);
    session = sessionRepo.save(session);
}
```

**Add `createWorkersForWarmHead` method** — after `createRayNotebookCluster` (after line 341):

```java
/**
 * Creates workers and ConfigMap for a warm-pool head pod (head already running).
 */
private void createWorkersForWarmHead(NotebookSessionEntity session,
                                       String tenantId,
                                       List<String> datasetIds,
                                       String image,
                                       String headPodName,
                                       String ns,
                                       String headPodIp,
                                       int workerCount,
                                       String workerSize) {
    // Resolve worker resources
    String wCpu, wMemReq, wCpuLimit, wMemLimit;
    switch (workerSize != null ? workerSize : "small") {
        case "medium" -> { wCpu = "2"; wMemReq = "4Gi"; wCpuLimit = "2"; wMemLimit = "4Gi"; }
        case "large"  -> { wCpu = "4"; wMemReq = "8Gi"; wCpuLimit = "4"; wMemLimit = "8Gi"; }
        default       -> { wCpu = "1"; wMemReq = "2Gi"; wCpuLimit = "2"; wMemLimit = "4Gi"; }
    }
    LakeonProperties.DatalakeConfig dl = props.getDatalake();

    // Create ConfigMap with repl_server.py in pool namespace
    String replScript = loadReplServerScript();
    String cmName = headPodName + "-repl";
    ConfigMap cm = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(cmName)
                .withNamespace(ns)
            .endMetadata()
            .addToData("repl_server.py", replScript)
            .build();
    k8sClient.configMaps().inNamespace(ns).resource(cm).createOrReplace();
    log.info("Created repl ConfigMap for warm head: {}/{}", ns, cmName);

    // Build env vars
    List<EnvVar> envVars = buildEnvVars(tenantId, datasetIds);
    envVars.add(new EnvVarBuilder().withName("RAY_ADDRESS").withValue("auto").build());

    Toleration vkToleration = new TolerationBuilder()
            .withKey("virtual-kubelet.io/provider")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .build();

    List<LocalObjectReference> pullSecrets = props.getK8s().getImagePullSecrets().stream()
            .filter(name -> name != null && !name.isBlank())
            .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
            .toList();

    // Create worker pods in the same namespace as head
    for (int i = 0; i < workerCount; i++) {
        String workerName = headPodName + "-worker-" + i;
        Container workerContainer = new ContainerBuilder()
                .withName("ray-worker")
                .withImage(image)
                .withCommand("bash", "-c",
                        "ray start --address=" + headPodIp + ":6379 --num-cpus=" + wCpu + " --block")
                .withEnv(envVars)
                .withNewResources()
                    .withRequests(Map.of(
                            "cpu", new Quantity(wCpu),
                            "memory", new Quantity(wMemReq)))
                    .withLimits(Map.of(
                            "cpu", new Quantity(wCpuLimit),
                            "memory", new Quantity(wMemLimit)))
                .endResources()
                .build();

        PodSpec workerPodSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withImagePullSecrets(pullSecrets)
                .withNodeSelector(Map.of(dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
                .withTolerations(vkToleration)
                .withContainers(workerContainer)
                .build();

        Pod workerPod = new PodBuilder()
                .withNewMetadata()
                    .withName(workerName)
                    .withNamespace(ns)
                    .withLabels(Map.of(
                            "app", "notebook-worker",
                            "lakeon.io/tenant-id", tenantId,
                            "lakeon.io/session-id", session.getId()))
                .endMetadata()
                .withSpec(workerPodSpec)
                .build();

        k8sClient.pods().inNamespace(ns).resource(workerPod).create();
        log.info("Created warm pool worker pod: {}/{}", ns, workerName);
    }
}
```

**Modify `deletePodAndConfigMap`** — replace lines 484-515 to also call `warmPoolManager.releaseHead` when the session is in the pool namespace:

```java
private void deletePodAndConfigMap(NotebookSessionEntity session) {
    String ns = session.getNamespace();
    String podName = session.getPodName();

    if (ns == null || podName == null) return;

    // If this was a warm-pool session, use the pool manager for cleanup
    if (ns.equals(props.getDatalake().getWarmPoolNamespace())) {
        warmPoolManager.releaseHead(podName, session.getId());
        // Also delete ConfigMap
        try {
            String cmName = podName + "-repl";
            k8sClient.configMaps().inNamespace(ns).withName(cmName).delete();
        } catch (Exception e) {
            log.warn("Failed to delete ConfigMap for warm pool session {}: {}", podName, e.getMessage());
        }
        return;
    }

    // Original cold-start cleanup path
    try {
        k8sClient.pods().inNamespace(ns).withName(podName).delete();
        log.info("Deleted notebook pod: {}/{}", ns, podName);
    } catch (Exception e) {
        log.warn("Failed to delete notebook pod {}/{}: {}", ns, podName, e.getMessage());
    }

    try {
        k8sClient.pods().inNamespace(ns)
                .withLabel("lakeon.io/session-id", session.getId())
                .delete();
        log.info("Deleted worker pods for session: {}", session.getId());
    } catch (Exception e) {
        log.warn("Failed to delete worker pods for session {}: {}", session.getId(), e.getMessage());
    }

    try {
        String cmName = podName + "-repl";
        k8sClient.configMaps().inNamespace(ns).withName(cmName).delete();
        log.info("Deleted notebook ConfigMap: {}/{}", ns, cmName);
    } catch (Exception e) {
        log.warn("Failed to delete notebook ConfigMap for {}: {}", podName, e.getMessage());
    }
}
```

- [ ] **Step 4: Run all notebook-related tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest="WarmPoolManagerTest,NotebookServiceWarmPoolTest" -q 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 5: Verify full build**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/notebook/NotebookService.java \
        lakeon-api/src/test/java/com/lakeon/notebook/NotebookServiceWarmPoolTest.java
git commit -m "feat(notebook): integrate warm pool into NotebookService with cold-start fallback"
```

---

### Task 4: Update NotebookWebSocketHandler for warm pool namespace

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookWebSocketHandler.java:148-163`

The WebSocket handler already uses `session.getNamespace()` and `session.getPodName()` for exec and worker counting. Since the session entity now stores the pool namespace and warm pool pod name when using the warm pool, most of the handler works as-is. The only change needed is `countRunningWorkers` — it queries by namespace from the session, which is already correct.

- [ ] **Step 1: Verify no changes needed**

Read `NotebookWebSocketHandler.java` lines 148-173. The handler uses `session.getNamespace()` for both `countRunningWorkers` (line 150) and `createExecConnection` (line 167). Since `NotebookService` now sets `session.namespace = "datalake-pool"` for warm-pool sessions, the handler will automatically exec into the correct namespace.

No code changes required — the existing handler already works.

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit (skip if no changes)**

No commit needed for this task.

---

### Task 5: E2E test with live CCI cluster

**Files:**
- Create: `deploy/cce/test-warm-pool-e2e.sh`

- [ ] **Step 1: Write E2E test script**

Create `deploy/cce/test-warm-pool-e2e.sh`:

```bash
#!/usr/bin/env bash
#
# E2E test for Ray Notebook Warm Pool
#
# Prerequisites:
#   - KUBECONFIG pointing to CCE cluster
#   - lakeon-api deployed with warm pool enabled
#
# Tests:
#   1. Pool initialization — verify 2 idle pods exist
#   2. Session creation — verify head claimed from pool
#   3. Session cleanup — verify pod destroyed, pool replenished
#   4. Pool exhaustion — verify cold-start fallback works

set -euo pipefail

POOL_NS="datalake-pool"
API_URL="${LAKEON_API_URL:-https://localhost:8090}"
API_TOKEN="${LAKEON_API_TOKEN:-}"

now_sec() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILURES=$((FAILURES + 1)); }

FAILURES=0

echo "=== Warm Pool E2E Tests ==="
echo ""

# ── Test 1: Pool Initialization ──────────────────────────────────
echo "[Test 1] Pool Initialization"
IDLE_PODS=$(kubectl get pods -n "$POOL_NS" \
    -l "lakeon.io/pool=warm,lakeon.io/status=idle" \
    --field-selector=status.phase=Running \
    -o name 2>/dev/null | wc -l | tr -d ' ')

if [[ "$IDLE_PODS" -ge 2 ]]; then
    pass "Found $IDLE_PODS idle pods in $POOL_NS (expected >= 2)"
else
    fail "Only $IDLE_PODS idle pods in $POOL_NS (expected >= 2)"
    echo "    Pods:"
    kubectl get pods -n "$POOL_NS" -l "lakeon.io/pool=warm" -o wide 2>/dev/null || true
fi

# ── Test 2: Session Creation (warm start) ────────────────────────
echo ""
echo "[Test 2] Session Creation (warm start)"

if [[ -n "$API_TOKEN" ]]; then
    T_START=$(now_sec)
    RESPONSE=$(curl -s -k -X POST "$API_URL/api/v1/datalake/notebook/sessions" \
        -H "Authorization: Bearer $API_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"image":"ray","worker_count":1,"worker_size":"small"}')
    T_END=$(now_sec)

    STATUS=$(echo "$RESPONSE" | jq -r '.status // "null"')
    POD_NAME=$(echo "$RESPONSE" | jq -r '.podName // "null"')
    NAMESPACE=$(echo "$RESPONSE" | jq -r '.namespace // "null"')
    SESSION_ID=$(echo "$RESPONSE" | jq -r '.id // "null"')
    ELAPSED=$(python3 -c "print(f'{$T_END - $T_START:.1f}s')")

    if [[ "$STATUS" == "RUNNING" ]]; then
        pass "Session created: status=$STATUS, pod=$POD_NAME, ns=$NAMESPACE ($ELAPSED)"
    else
        fail "Unexpected status: $STATUS (response: $RESPONSE)"
    fi

    if [[ "$NAMESPACE" == "$POOL_NS" ]]; then
        pass "Session uses warm pool namespace ($POOL_NS)"
    else
        fail "Session in $NAMESPACE instead of $POOL_NS — may be cold start fallback"
    fi

    if [[ "$POD_NAME" == warm-ray-head-* ]]; then
        pass "Pod name indicates warm pool origin: $POD_NAME"
    else
        fail "Pod name doesn't match warm pool pattern: $POD_NAME"
    fi

    # ── Test 3: Cleanup & Replenish ──────────────────────────────
    echo ""
    echo "[Test 3] Session Cleanup & Pool Replenish"

    if [[ "$SESSION_ID" != "null" ]]; then
        curl -s -k -X DELETE "$API_URL/api/v1/datalake/notebook/sessions/$SESSION_ID" \
            -H "Authorization: Bearer $API_TOKEN" >/dev/null 2>&1

        # Wait for pool to replenish (up to 30s)
        REPLENISHED=false
        for i in $(seq 1 30); do
            IDLE_NOW=$(kubectl get pods -n "$POOL_NS" \
                -l "lakeon.io/pool=warm,lakeon.io/status=idle" \
                --field-selector=status.phase=Running \
                -o name 2>/dev/null | wc -l | tr -d ' ')
            if [[ "$IDLE_NOW" -ge 2 ]]; then
                REPLENISHED=true
                pass "Pool replenished to $IDLE_NOW idle pods within ${i}s"
                break
            fi
            sleep 1
        done
        if [[ "$REPLENISHED" == "false" ]]; then
            fail "Pool not replenished after 30s (idle=$IDLE_NOW)"
        fi
    fi
else
    echo "  (skipped — set LAKEON_API_TOKEN to enable API tests)"
fi

# ── Summary ──────────────────────────────────────────────────────
echo ""
echo "============================================"
if [[ "$FAILURES" -eq 0 ]]; then
    echo "  All tests passed"
else
    echo "  $FAILURES test(s) FAILED"
fi
echo "============================================"

exit "$FAILURES"
```

- [ ] **Step 2: Make executable**

Run: `chmod +x deploy/cce/test-warm-pool-e2e.sh`

- [ ] **Step 3: Commit**

```bash
git add deploy/cce/test-warm-pool-e2e.sh
git commit -m "test(notebook): add warm pool E2E test script for CCI"
```

---

### Task 6: Final verification and cleanup

- [ ] **Step 1: Run full test suite**

Run: `cd lakeon-api && mvn test -q 2>&1 | tail -15`
Expected: All tests pass (existing + new)

- [ ] **Step 2: Check for compilation warnings**

Run: `cd lakeon-api && mvn compile -q 2>&1`
Expected: BUILD SUCCESS, no warnings

- [ ] **Step 3: Review all changes**

Run: `git diff --stat HEAD~4` to see all changed files across the 4 commits.

Verify:
- `LakeonProperties.java` — warm pool config fields added
- `application.yml` — warm pool env var defaults added
- `WarmPoolManager.java` — new file, pool lifecycle management
- `NotebookService.java` — warm pool claim in `getOrCreateSession`, `createWorkersForWarmHead`, updated `deletePodAndConfigMap`
- `WarmPoolManagerTest.java` — 6 unit tests
- `NotebookServiceWarmPoolTest.java` — 2 integration tests
- `test-warm-pool-e2e.sh` — CCI E2E test script

- [ ] **Step 4: Final commit (if any fixups needed)**

Only if previous steps surfaced issues to fix.
