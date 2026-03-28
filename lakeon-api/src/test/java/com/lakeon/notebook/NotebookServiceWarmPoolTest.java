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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotebookService — warm pool integration")
class NotebookServiceWarmPoolTest {

    @Mock NotebookSessionRepository sessionRepo;
    @Mock DatasetRepository datasetRepo;
    @Mock KubernetesClient k8sClient;
    @Mock DatalakeNamespaceManager nsManager;
    @Mock ObsStsService obsStsService;
    @Mock WarmPoolManager warmPoolManager;

    private LakeonProperties props;
    private NotebookService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setWarmPoolEnabled(true);
        props.getDatalake().setWarmPoolNamespace("datalake-pool");
        props.getDatalake().setVkNodeSelectorKey("type");
        props.getDatalake().setVkNodeSelectorValue("virtual-kubelet");
        props.getK8s().setImagePullSecrets(List.of("my-pull-secret"));
        props.getObs().setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        props.getObs().setBucket("test-bucket");
        props.getObs().setRegion("cn-north-4");

        service = new NotebookService(sessionRepo, datasetRepo, k8sClient, props,
                nsManager, obsStsService, warmPoolManager);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private MixedOperation<Pod, PodList, PodResource> mockPods() {
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        lenient().when(k8sClient.pods()).thenReturn(pods);
        return pods;
    }

    @SuppressWarnings("unchecked")
    private MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> mockConfigMaps() {
        MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> cms = mock(MixedOperation.class);
        lenient().when(k8sClient.configMaps()).thenReturn(cms);
        return cms;
    }

    private void mockSessionRepoNoExisting() {
        lenient().when(sessionRepo.findByTenantIdAndStatus(anyString(), eq(NotebookSessionStatus.RUNNING)))
                .thenReturn(Optional.empty());
        lenient().when(sessionRepo.findByTenantIdAndStatus(anyString(), eq(NotebookSessionStatus.STARTING)))
                .thenReturn(Optional.empty());
        // save returns the argument (simulate JPA)
        when(sessionRepo.save(any(NotebookSessionEntity.class))).thenAnswer(inv -> {
            NotebookSessionEntity s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId("nbs_test1234567");
                s.setCreatedAt(Instant.now());
                s.setUpdatedAt(Instant.now());
                s.setLastActiveAt(Instant.now());
            }
            return s;
        });
    }

    private void mockStsCredentials() {
        lenient().when(obsStsService.getCredentials(anyString()))
                .thenReturn(new ObsStsService.StsCredentials("ak", "sk", "token", Instant.now().plusSeconds(3600)));
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("raySessionUsesWarmPool: claims warm head, creates workers in pool namespace")
    @SuppressWarnings("unchecked")
    void raySessionUsesWarmPool() {
        mockSessionRepoNoExisting();
        mockStsCredentials();

        // Warm pool returns a claimed head
        when(warmPoolManager.claimHead(eq("t1"), anyString()))
                .thenReturn(Optional.of(new WarmPoolManager.ClaimedHead(
                        "warm-ray-head-abc12345", "10.0.0.1", "datalake-pool")));

        // nsManager returns a tenant ns (will be overridden by warm pool)
        when(nsManager.ensureNamespace("t1")).thenReturn("datalake-tenant-123");

        // Mock k8s for ConfigMap creation
        var cms = mockConfigMaps();
        var cmsInNs = mock(NonNamespaceOperation.class);
        when(cms.inNamespace("datalake-pool")).thenReturn(cmsInNs);
        var cmRes = mock(Resource.class);
        when(cmsInNs.resource(any(ConfigMap.class))).thenReturn(cmRes);

        // Mock k8s for worker pod creation
        var pods = mockPods();
        var podsInNs = mock(NonNamespaceOperation.class);
        when(pods.inNamespace("datalake-pool")).thenReturn(podsInNs);
        var podRes = mock(PodResource.class);
        when(podsInNs.resource(any(Pod.class))).thenReturn(podRes);

        NotebookSessionEntity result = service.getOrCreateSession("t1", "ray", null, 2, "small");

        assertThat(result.getStatus()).isEqualTo(NotebookSessionStatus.RUNNING);
        assertThat(result.getPodName()).isEqualTo("warm-ray-head-abc12345");
        assertThat(result.getNamespace()).isEqualTo("datalake-pool");

        // Verify 2 worker pods created
        verify(podsInNs, times(2)).resource(any(Pod.class));
        verify(podRes, times(2)).create();

        // Verify ConfigMap created
        verify(cmsInNs).resource(any(ConfigMap.class));
        verify(cmRes).createOrReplace();
    }

    @Test
    @DisplayName("fallbackToColdStart: warm pool exhausted, creates Ray cluster in tenant namespace")
    @SuppressWarnings("unchecked")
    void fallbackToColdStart() {
        mockSessionRepoNoExisting();
        mockStsCredentials();

        // Warm pool exhausted
        when(warmPoolManager.claimHead(eq("t1"), anyString()))
                .thenReturn(Optional.empty());

        when(nsManager.ensureNamespace("t1")).thenReturn("datalake-tenant-123");

        // Mock k8s for ConfigMap creation in tenant ns
        var cms = mockConfigMaps();
        var cmsInNs = mock(NonNamespaceOperation.class);
        when(cms.inNamespace("datalake-tenant-123")).thenReturn(cmsInNs);
        var cmRes = mock(Resource.class);
        when(cmsInNs.resource(any(ConfigMap.class))).thenReturn(cmRes);

        // Mock k8s for pod creation in tenant ns
        var pods = mockPods();
        var podsInNs = mock(NonNamespaceOperation.class);
        when(pods.inNamespace("datalake-tenant-123")).thenReturn(podsInNs);
        var podRes = mock(PodResource.class);
        when(podsInNs.resource(any(Pod.class))).thenReturn(podRes);

        // Mock head pod IP polling: return Running pod with IP on first poll
        var headPodRes = mock(PodResource.class);
        when(podsInNs.withName(argThat(n -> n != null && n.startsWith("notebook-"))))
                .thenReturn(headPodRes);
        Pod runningHead = new PodBuilder()
                .withNewStatus().withPhase("Running").withPodIP("10.0.0.99").endStatus()
                .build();
        when(headPodRes.get()).thenReturn(runningHead);

        NotebookSessionEntity result = service.getOrCreateSession("t1", "ray", null, 1, "small");

        assertThat(result.getStatus()).isEqualTo(NotebookSessionStatus.RUNNING);
        assertThat(result.getNamespace()).isEqualTo("datalake-tenant-123");

        // Head + 1 worker = 2 pod creates
        verify(podsInNs, atLeast(2)).resource(any(Pod.class));
        verify(podRes, atLeast(2)).create();
    }
}
