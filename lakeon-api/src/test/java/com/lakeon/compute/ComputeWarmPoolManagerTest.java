package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputeSpecBuilder;
import com.lakeon.model.entity.DatabaseEntity;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ComputeWarmPoolManager} pool maintenance behavior (B2.4):
 * reconcile is a no-op when disabled, replenishes when below target, cleans
 * up Failed pods, and skips creation cleanly when the mock tenant config is
 * missing.
 *
 * The fabric8 fluent K8s API is mocked via deep stubs — chains like
 *   k8sClient.pods().inNamespace(ns).withLabel(...).list()
 * return real {@link PodList} objects we control. Only the terminal
 * operations we assert on (create, delete, list) are stubbed.
 *
 * KubernetesMockServer would be cleaner but kubernetes-server-mock is not on
 * the classpath; adding it just for this test is out of scope for B2.4.
 */
class ComputeWarmPoolManagerTest {

    private KubernetesClient k8sClient;
    private LakeonProperties props;
    private ComputeSpecBuilder specBuilder;
    private ComputeReconfigureClient reconfigureClient;
    private ComputeWarmPoolManager manager;

    // Fabric8 fluent chain stubs (kept on the instance so tests can stub
    // terminal methods + verify calls).
    @SuppressWarnings("rawtypes")
    private MixedOperation podsOp;
    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation podsNs;
    @SuppressWarnings("rawtypes")
    private FilterWatchListDeletable podsFiltered;
    @SuppressWarnings("rawtypes")
    private MixedOperation cmOp;
    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation cmNs;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() {
        k8sClient = mock(KubernetesClient.class);
        specBuilder = mock(ComputeSpecBuilder.class);
        reconfigureClient = mock(ComputeReconfigureClient.class);
        props = new LakeonProperties();
        props.getK8s().setComputeImage("default/compute:test");
        props.getComputeWarmPool().setEnabled(true);
        props.getComputeWarmPool().setSize(2);
        props.getComputeWarmPool().setMockTenantId("tenant-mock-123");
        props.getComputeWarmPool().setMockTimelineId("timeline-mock-456");

        lenient().when(specBuilder.generateComputeConfig(any(), anyInt()))
            .thenReturn("{\"spec\":\"mock\"}");

        // Pods fluent chain
        podsOp = mock(MixedOperation.class);
        podsNs = mock(NonNamespaceOperation.class);
        podsFiltered = mock(FilterWatchListDeletable.class);
        lenient().when(k8sClient.pods()).thenReturn(podsOp);
        lenient().when(podsOp.inNamespace("lakeon-compute")).thenReturn(podsNs);
        lenient().when(podsNs.withLabel(anyString(), anyString())).thenReturn(podsFiltered);
        lenient().when(podsFiltered.list()).thenReturn(new PodList());

        // ConfigMap fluent chain
        cmOp = mock(MixedOperation.class);
        cmNs = mock(NonNamespaceOperation.class);
        lenient().when(k8sClient.configMaps()).thenReturn(cmOp);
        lenient().when(cmOp.inNamespace("lakeon-compute")).thenReturn(cmNs);

        manager = new ComputeWarmPoolManager(k8sClient, props, specBuilder, reconfigureClient);
    }

    // ── 1. disabled → no k8s calls ─────────────────────────────────────────
    @Test
    void reconcile_disabled_isNoOp() {
        props.getComputeWarmPool().setEnabled(false);
        manager.reconcile();
        verify(k8sClient, never()).pods();
        verify(k8sClient, never()).configMaps();
    }

    // ── 2. below target → creates idle pods ────────────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconcileNow_belowTarget_createsIdlePods() {
        // No idle pods currently
        when(podsFiltered.list()).thenReturn(new PodList());

        PodResource podRes = mock(PodResource.class);
        when(podsNs.resource(any(Pod.class))).thenReturn(podRes);
        when(podRes.create()).thenReturn(null);

        Resource cmRes = mock(Resource.class);
        when(cmNs.resource(any(ConfigMap.class))).thenReturn(cmRes);
        when(cmRes.serverSideApply()).thenReturn(null);

        manager.reconcileNow();

        verify(podsNs, times(2)).resource(any(Pod.class));
        verify(podRes, times(2)).create();
        verify(cmNs, times(2)).resource(any(ConfigMap.class));
        verify(cmRes, times(2)).serverSideApply();
    }

    // ── 3. at target → no creates ──────────────────────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconcileNow_atTarget_doesNothing() {
        PodList list = new PodList();
        list.setItems(List.of(idleRunningPod("warm-pool-aaa"), idleRunningPod("warm-pool-bbb")));
        when(podsFiltered.list()).thenReturn(list);

        manager.reconcileNow();

        verify(podsNs, never()).resource(any(Pod.class));
        verify(cmNs, never()).resource(any(ConfigMap.class));
    }

    // ── 4. mock tenant missing → skip create, no crash ─────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconcileNow_missingMockTenant_skipsCreate() {
        props.getComputeWarmPool().setMockTenantId("");
        when(podsFiltered.list()).thenReturn(new PodList());

        manager.reconcileNow();

        // No creates attempted
        verify(podsNs, never()).resource(any(Pod.class));
        verify(cmNs, never()).resource(any(ConfigMap.class));
    }

    // ── 5. failed pods are cleaned up ──────────────────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconcileNow_failedPodsCleanedUp() {
        Pod failed = idleRunningPod("warm-pool-fail1");
        failed.getStatus().setPhase("Failed");
        PodList list = new PodList();
        list.setItems(List.of(failed));
        when(podsFiltered.list()).thenReturn(list);

        // For deletion: withName(podName).delete() chain
        PodResource podByName = mock(PodResource.class);
        when(podsNs.withName("warm-pool-fail1")).thenReturn(podByName);
        when(podByName.delete()).thenReturn(List.of());

        Resource cmByName = mock(Resource.class);
        when(cmNs.withName("warm-pool-fail1-config")).thenReturn(cmByName);
        when(cmByName.delete()).thenReturn(List.of());

        // Stub creates too (deficit = 2 will trigger creates after cleanup)
        PodResource createPodRes = mock(PodResource.class);
        when(podsNs.resource(any(Pod.class))).thenReturn(createPodRes);
        Resource createCmRes = mock(Resource.class);
        when(cmNs.resource(any(ConfigMap.class))).thenReturn(createCmRes);

        manager.reconcileNow();

        verify(podByName, atLeastOnce()).delete();
        verify(cmByName, atLeastOnce()).delete();
    }

    // ── 6. idlePodCount returns count of idle+Running ──────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void idlePodCount_returnsCorrectCount() {
        PodList list = new PodList();
        list.setItems(List.of(
            idleRunningPod("warm-pool-a"),
            idleRunningPod("warm-pool-b"),
            idleRunningPod("warm-pool-c")
        ));
        when(podsFiltered.list()).thenReturn(list);

        assertThat(manager.idlePodCount()).isEqualTo(3);
    }

    // ── 7. terminating pods (deletionTimestamp set) do not count toward target ─
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconcileNow_terminatingPodsNotCountedTowardTarget() {
        // Two pods both labeled idle+Running, but one has deletionTimestamp set
        // (just `kubectl delete`d — still phase=Running for several seconds).
        // Pool size=2 → terminating pod is excluded → idleRunning=1 → deficit=1.
        Pod alive = idleRunningPod("warm-pool-alive");
        Pod terminating = idleRunningPod("warm-pool-terminating");
        terminating.getMetadata().setDeletionTimestamp(Instant.now().toString());

        PodList list = new PodList();
        list.setItems(List.of(alive, terminating));
        when(podsFiltered.list()).thenReturn(list);

        PodResource podRes = mock(PodResource.class);
        when(podsNs.resource(any(Pod.class))).thenReturn(podRes);
        when(podRes.create()).thenReturn(null);

        Resource cmRes = mock(Resource.class);
        when(cmNs.resource(any(ConfigMap.class))).thenReturn(cmRes);
        when(cmRes.serverSideApply()).thenReturn(null);

        manager.reconcileNow();

        // Exactly 1 new pod created (target 2 - 1 alive counted = deficit 1)
        verify(podsNs, times(1)).resource(any(Pod.class));
        verify(podRes, times(1)).create();

        // And idlePodCount agrees — terminating pod is invisible there too
        assertThat(manager.idlePodCount()).isEqualTo(1);
    }

    // ── 8. claim disabled → empty, no k8s calls ────────────────────────────
    @Test
    void claim_disabled_returnsEmpty() {
        props.getComputeWarmPool().setEnabled(false);
        DatabaseEntity entity = newEntity("db-1", "tenant-1");

        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isEmpty();
        verify(k8sClient, never()).pods();
        verify(reconfigureClient, never()).reconfigure(anyString(), anyString(), anyString());
    }

    // ── 9. no idle pods → empty, no reconfigure ────────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claim_noIdlePods_returnsEmpty() {
        when(podsFiltered.list()).thenReturn(new PodList());
        DatabaseEntity entity = newEntity("db-1", "tenant-1");

        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isEmpty();
        verify(reconfigureClient, never()).reconfigure(anyString(), anyString(), anyString());
    }

    // ── 10. happy path: swap + reconfigure succeed ─────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claim_happyPath_returnsPodInfo() {
        Pod pod = idleRunningPod("warm-pool-aaa");
        pod.getStatus().setPodIP("10.0.0.42");
        PodList list = new PodList();
        list.setItems(List.of(pod));
        when(podsFiltered.list()).thenReturn(list);

        PodResource podByName = mock(PodResource.class);
        when(podsNs.withName("warm-pool-aaa")).thenReturn(podByName);
        // edit() returns the edited pod — return our pod so podIP read works
        when(podByName.edit(any(UnaryOperator.class))).thenReturn(pod);

        when(specBuilder.generateComputeConfig(any(DatabaseEntity.class), eq(600)))
            .thenReturn("{\"real\":\"spec\"}");
        when(reconfigureClient.reconfigure("warm-pool-aaa", "10.0.0.42", "{\"real\":\"spec\"}"))
            .thenReturn(new ComputeReconfigureClient.Result(true, 200, null, 400));

        DatabaseEntity entity = newEntity("db-1", "tenant-1");
        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isPresent();
        assertThat(result.get().podName()).isEqualTo("warm-pool-aaa");
        assertThat(result.get().podIp()).isEqualTo("10.0.0.42");

        // Verify specBuilder was called with the right entity + 600s timeout
        ArgumentCaptor<DatabaseEntity> entityCap = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(specBuilder).generateComputeConfig(entityCap.capture(), eq(600));
        assertThat(entityCap.getValue().getId()).isEqualTo("db-1");
        assertThat(entityCap.getValue().getTenantId()).isEqualTo("tenant-1");

        // One edit() — the claiming swap. No "failed" relabel.
        verify(podByName, times(1)).edit(any(UnaryOperator.class));
    }

    // ── 11. reconfigure fails → pod marked failed, returns empty ───────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claim_reconfigureFails_marksPodFailed_returnsEmpty() {
        Pod pod = idleRunningPod("warm-pool-bbb");
        pod.getStatus().setPodIP("10.0.0.43");
        PodList list = new PodList();
        list.setItems(List.of(pod));
        when(podsFiltered.list()).thenReturn(list);

        PodResource podByName = mock(PodResource.class);
        when(podsNs.withName("warm-pool-bbb")).thenReturn(podByName);
        when(podByName.edit(any(UnaryOperator.class))).thenReturn(pod);

        when(reconfigureClient.reconfigure(anyString(), anyString(), anyString()))
            .thenReturn(new ComputeReconfigureClient.Result(false, 403, "missing compute_id", 50));

        DatabaseEntity entity = newEntity("db-1", "tenant-1");
        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isEmpty();
        // Two edit() calls: claiming swap + failed relabel
        verify(podByName, times(2)).edit(any(UnaryOperator.class));
    }

    // ── 12. race lost on first pod → falls through to second ───────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claim_labelSwapRaceLost_continuesToNextCandidate() {
        Pod first = idleRunningPod("warm-pool-first");
        first.getStatus().setPodIP("10.0.0.1");
        Pod second = idleRunningPod("warm-pool-second");
        second.getStatus().setPodIP("10.0.0.2");
        PodList list = new PodList();
        list.setItems(List.of(first, second));
        when(podsFiltered.list()).thenReturn(list);

        PodResource firstRes = mock(PodResource.class);
        PodResource secondRes = mock(PodResource.class);
        when(podsNs.withName("warm-pool-first")).thenReturn(firstRes);
        when(podsNs.withName("warm-pool-second")).thenReturn(secondRes);
        when(firstRes.edit(any(UnaryOperator.class)))
            .thenThrow(new KubernetesClientException("conflict: object has been modified"));
        when(secondRes.edit(any(UnaryOperator.class))).thenReturn(second);

        when(reconfigureClient.reconfigure("warm-pool-second", "10.0.0.2", "{\"spec\":\"mock\"}"))
            .thenReturn(new ComputeReconfigureClient.Result(true, 200, null, 100));

        DatabaseEntity entity = newEntity("db-2", "tenant-2");
        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isPresent();
        assertThat(result.get().podName()).isEqualTo("warm-pool-second");
        assertThat(result.get().podIp()).isEqualTo("10.0.0.2");
    }

    // ── 13. all races lost → empty ─────────────────────────────────────────
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claim_allRacesLost_returnsEmpty() {
        Pod a = idleRunningPod("warm-pool-a");
        a.getStatus().setPodIP("10.0.0.10");
        Pod b = idleRunningPod("warm-pool-b");
        b.getStatus().setPodIP("10.0.0.11");
        PodList list = new PodList();
        list.setItems(List.of(a, b));
        when(podsFiltered.list()).thenReturn(list);

        PodResource aRes = mock(PodResource.class);
        PodResource bRes = mock(PodResource.class);
        when(podsNs.withName("warm-pool-a")).thenReturn(aRes);
        when(podsNs.withName("warm-pool-b")).thenReturn(bRes);
        when(aRes.edit(any(UnaryOperator.class)))
            .thenThrow(new KubernetesClientException("conflict"));
        when(bRes.edit(any(UnaryOperator.class)))
            .thenThrow(new KubernetesClientException("conflict"));

        DatabaseEntity entity = newEntity("db-3", "tenant-3");
        Optional<ComputeWarmPoolManager.ClaimedPod> result = manager.claim(entity);

        assertThat(result).isEmpty();
        verify(reconfigureClient, never()).reconfigure(anyString(), anyString(), anyString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private DatabaseEntity newEntity(String id, String tenantId) {
        DatabaseEntity e = new DatabaseEntity();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setName("test-db");
        e.setNeonTenantId("neon-t");
        e.setNeonTimelineId("neon-tl");
        e.setDbUser("lakeon");
        e.setDbPassword("");
        e.setComputeSize("1cu");
        e.setSuspendTimeout("600s");
        return e;
    }

    private Pod idleRunningPod(String name) {
        Pod p = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace("lakeon-compute");
        Map<String, String> labels = new HashMap<>();
        labels.put("lakeon.io/pool", "warm");
        labels.put("lakeon.io/status", "idle");
        meta.setLabels(labels);
        meta.setCreationTimestamp(Instant.now().minus(1, ChronoUnit.MINUTES).toString());
        p.setMetadata(meta);
        PodStatus status = new PodStatus();
        status.setPhase("Running");
        p.setStatus(status);
        return p;
    }
}
