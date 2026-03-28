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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarmPoolManager")
class WarmPoolManagerTest {

    @Mock KubernetesClient k8sClient;
    @Mock DatalakeNamespaceManager nsManager;

    private LakeonProperties props;
    private WarmPoolManager manager;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setWarmPoolEnabled(true);
        props.getDatalake().setWarmPoolSize(2);
        props.getDatalake().setWarmPoolNamespace("datalake-pool");
        props.getDatalake().setWarmPoolImage("test-image:latest");
        props.getDatalake().setWarmPoolIdleTimeoutMinutes(30);
        manager = new WarmPoolManager(k8sClient, props, nsManager);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private MixedOperation<Pod, PodList, PodResource> mockPods() {
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        lenient().when(k8sClient.pods()).thenReturn(pods);
        return pods;
    }

    private Pod makeIdlePod(String name, String ip, String phase) {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace("datalake-pool")
                    .withLabels(Map.of(
                            "lakeon.io/pool", "warm",
                            "lakeon.io/status", "idle",
                            "app", "notebook"))
                    .withCreationTimestamp(java.time.Instant.now().toString())
                .endMetadata()
                .withNewStatus()
                    .withPhase(phase)
                    .withPodIP(ip)
                .endStatus()
                .build();
        return pod;
    }

    // ── ClaimHead ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ClaimHead")
    class ClaimHead {

        @Test
        @DisplayName("claimIdlePod: returns ClaimedHead and edits labels")
        void claimIdlePod() {
            Pod idlePod = makeIdlePod("warm-ray-head-abc12345", "10.0.0.1", "Running");

            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            var withPoolLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/pool", "warm")).thenReturn(withPoolLabel);
            var withStatusLabel = mock(FilterWatchListDeletable.class);
            when(withPoolLabel.withLabel("lakeon.io/status", "idle")).thenReturn(withStatusLabel);

            PodList podList = new PodListBuilder().withItems(idlePod).build();
            when(withStatusLabel.list()).thenReturn(podList);

            // Mock the edit call - pod().inNamespace().withName().edit()
            var podRes = mock(PodResource.class);
            when(inNs.withName("warm-ray-head-abc12345")).thenReturn(podRes);
            when(podRes.edit(any(UnaryOperator.class))).thenReturn(idlePod);

            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant1", "session1");

            assertThat(result).isPresent();
            assertThat(result.get().podName()).isEqualTo("warm-ray-head-abc12345");
            assertThat(result.get().podIp()).isEqualTo("10.0.0.1");
            assertThat(result.get().namespace()).isEqualTo("datalake-pool");
            verify(podRes).edit(any(UnaryOperator.class));
        }

        @Test
        @DisplayName("poolExhausted: empty pod list returns empty")
        void poolExhausted() {
            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            var withPoolLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/pool", "warm")).thenReturn(withPoolLabel);
            var withStatusLabel = mock(FilterWatchListDeletable.class);
            when(withPoolLabel.withLabel("lakeon.io/status", "idle")).thenReturn(withStatusLabel);

            PodList podList = new PodListBuilder().withItems(List.of()).build();
            when(withStatusLabel.list()).thenReturn(podList);

            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant1", "session1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("skipsPodWithoutIp: pod with null IP returns empty")
        void skipsPodWithoutIp() {
            Pod noIpPod = makeIdlePod("warm-ray-head-noip", null, "Running");
            // Clear the IP (makeIdlePod sets it)
            noIpPod.getStatus().setPodIP(null);

            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            var withPoolLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/pool", "warm")).thenReturn(withPoolLabel);
            var withStatusLabel = mock(FilterWatchListDeletable.class);
            when(withPoolLabel.withLabel("lakeon.io/status", "idle")).thenReturn(withStatusLabel);

            PodList podList = new PodListBuilder().withItems(noIpPod).build();
            when(withStatusLabel.list()).thenReturn(podList);

            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("tenant1", "session1");

            assertThat(result).isEmpty();
        }
    }

    // ── ReleaseHead ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ReleaseHead")
    class ReleaseHead {

        @Test
        @DisplayName("deletesPodAndWorkers")
        @SuppressWarnings("unchecked")
        void deletesPodAndWorkers() {
            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            // Delete pod by name
            var podRes = mock(PodResource.class);
            when(inNs.withName("warm-ray-head-abc")).thenReturn(podRes);

            // Delete workers by session label
            var withLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/session-id", "session1")).thenReturn(withLabel);

            manager.releaseHead("warm-ray-head-abc", "session1");

            verify(podRes).delete();
            verify(withLabel).delete();
        }
    }

    // ── Reconcile ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reconcile")
    class Reconcile {

        @Test
        @DisplayName("replenishesPool: 0 idle pods creates 2")
        @SuppressWarnings("unchecked")
        void replenishesPool() {
            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            // List all pool pods
            var withPoolLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/pool", "warm")).thenReturn(withPoolLabel);
            PodList podList = new PodListBuilder().withItems(List.of()).build();
            when(withPoolLabel.list()).thenReturn(podList);

            // Mock pod creation (resource().create())
            var podResource = mock(PodResource.class);
            when(inNs.resource(any(Pod.class))).thenReturn(podResource);

            manager.reconcile();

            verify(inNs, times(2)).resource(any(Pod.class));
            verify(podResource, times(2)).create();
        }

        @Test
        @DisplayName("poolFull: 2 idle Running pods creates none")
        @SuppressWarnings("unchecked")
        void poolFull() {
            Pod pod1 = makeIdlePod("warm-ray-head-aaa", "10.0.0.1", "Running");
            Pod pod2 = makeIdlePod("warm-ray-head-bbb", "10.0.0.2", "Running");

            var pods = mockPods();
            var inNs = mock(NonNamespaceOperation.class);
            when(pods.inNamespace("datalake-pool")).thenReturn(inNs);

            var withPoolLabel = mock(FilterWatchListDeletable.class);
            when(inNs.withLabel("lakeon.io/pool", "warm")).thenReturn(withPoolLabel);
            PodList podList = new PodListBuilder().withItems(pod1, pod2).build();
            when(withPoolLabel.list()).thenReturn(podList);

            manager.reconcile();

            verify(inNs, never()).resource(any(Pod.class));
        }
    }

    // ── Disabled ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Disabled")
    class Disabled {

        @BeforeEach
        void disablePool() {
            props.getDatalake().setWarmPoolEnabled(false);
        }

        @Test
        @DisplayName("claimReturnEmpty: disabled returns empty, no k8s interaction")
        void claimReturnEmpty() {
            Optional<WarmPoolManager.ClaimedHead> result = manager.claimHead("t1", "s1");

            assertThat(result).isEmpty();
            verifyNoInteractions(k8sClient);
        }

        @Test
        @DisplayName("reconcileNoop: disabled triggers no k8s interaction")
        void reconcileNoop() {
            manager.reconcile();

            verifyNoInteractions(k8sClient);
        }
    }
}
