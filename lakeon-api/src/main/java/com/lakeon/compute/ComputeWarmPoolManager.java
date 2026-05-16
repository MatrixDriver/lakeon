package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputeSpecBuilder;
import com.lakeon.model.entity.DatabaseEntity;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains a warm pool of pre-started compute pods in the
 * {@code lakeon-compute} namespace, labeled {@code lakeon.io/pool=warm}
 * and {@code lakeon.io/status=idle}. Each idle pod is booted with a mock
 * tenant/timeline (configured via {@code COMPUTE_WARM_POOL_MOCK_TENANT_ID}
 * / {@code _TIMELINE_ID}) so {@code compute_ctl} can start without waiting
 * on a real tenant.
 *
 * <p>B2.4 scope: pool maintenance only — replenish to {@code size},
 * clean up Failed/Error pods, age out idle pods older than 1h. No
 * {@code claim()} / {@code release()} yet (lands in B2.5 along with the
 * shared {@code buildPodSpec()} refactor of {@code ComputePodManager}).
 *
 * <p>The {@link #reconcile()} loop runs every 10s with a 5s initial
 * delay so the application has fully started before the first attempt.
 * When the feature is disabled (default), {@link #reconcile()} returns
 * immediately and makes no Kubernetes API calls.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-05-16-compute-warm-pool.md}
 */
@Component
public class ComputeWarmPoolManager {

    private static final Logger log = LoggerFactory.getLogger(ComputeWarmPoolManager.class);

    /** Namespace where warm-pool pods live. Matches {@code K8sConfig.namespace}'s
     *  conventional value but is hardcoded here because warm-pool pods are
     *  always in {@code lakeon-compute} regardless of overrides. */
    private static final String NAMESPACE = "lakeon-compute";

    /** Idle pods older than this are deleted; the next reconcile replenishes. */
    private static final long IDLE_POD_MAX_AGE_SECONDS = 3600;

    /** Suspend timeout in seconds passed to {@code compute_ctl} for idle pods.
     *  0 = never auto-suspend (idle pods must stay alive until claimed/aged-out). */
    private static final int IDLE_SUSPEND_TIMEOUT_SECONDS = 0;

    /** Suspend timeout passed to {@code compute_ctl} when reconfiguring a claimed
     *  warm pod for a real tenant. Matches the default in the cold-start path
     *  (ComputePodManager.createComputePod) so claimed pods behave identically
     *  to freshly created ones after the bind. */
    private static final int REAL_TENANT_SUSPEND_TIMEOUT_SECONDS = 600;

    /** Result of a successful claim. */
    public record ClaimedPod(String podName, String podIp) {}

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ComputeSpecBuilder specBuilder;
    private final ComputeReconfigureClient reconfigureClient;

    public ComputeWarmPoolManager(KubernetesClient k8sClient,
                                  LakeonProperties props,
                                  ComputeSpecBuilder specBuilder,
                                  ComputeReconfigureClient reconfigureClient) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.specBuilder = specBuilder;
        this.reconfigureClient = reconfigureClient;
    }

    /**
     * Periodically maintain the pool to target size. No-op when warm-pool
     * is disabled (the {@code !enabled} check is the first thing we do so
     * that disabled deployments never touch the K8s API).
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void reconcile() {
        if (!props.getComputeWarmPool().isEnabled()) {
            return;
        }
        reconcileNow();
    }

    /**
     * Synchronous reconcile, exposed for tests that want to bypass
     * {@code @Scheduled} timing.
     */
    public void reconcileNow() {
        List<Pod> poolPods = listPoolPods();

        int idleRunning = 0;
        for (Pod pod : poolPods) {
            String phase = phaseOf(pod);
            String status = statusLabelOf(pod);
            String name = pod.getMetadata().getName();
            // After kubectl/fabric8 delete, a pod stays phase=Running with its
            // labels intact for several seconds while the kubelet drains it.
            // Without this guard such pods would inflate idleRunning and the
            // next reconcile would skip replenishment. Mirrors the pattern in
            // ComputePodManager (countActivePods / listAllPods / hasAbnormalPods).
            boolean terminating = pod.getMetadata().getDeletionTimestamp() != null;

            // 1) Clean up Failed/Error pods regardless of label
            if ("Failed".equals(phase) || "Error".equals(phase)) {
                log.info("cleaning up failed warm-pool pod podName={} phase={}", name, phase);
                deletePodAndConfigMap(name);
                continue;
            }

            // 2) Age out idle pods > 1h. Replenish loop will create a fresh one.
            if ("idle".equals(status) && isStale(pod)) {
                log.info("aging out stale warm-pool idle pod podName={} ageSeconds>{}",
                         name, IDLE_POD_MAX_AGE_SECONDS);
                deletePodAndConfigMap(name);
                continue;
            }

            // 3) Count toward target — only Running idle pods that are NOT terminating
            if ("idle".equals(status) && "Running".equals(phase) && !terminating) {
                idleRunning++;
            }
        }

        int target = props.getComputeWarmPool().getSize();
        int deficit = target - idleRunning;
        if (deficit > 0) {
            log.info("warm-pool below target idleRunning={} target={} creating={}",
                     idleRunning, target, deficit);
            for (int i = 0; i < deficit; i++) {
                createIdlePod();
            }
        }
    }

    /**
     * Visible for testing + future metrics. Returns the count of warm-pool
     * pods currently in state {@code idle} AND phase {@code Running}.
     */
    public int idlePodCount() {
        int count = 0;
        for (Pod pod : listPoolPods()) {
            // Terminating pods (deletionTimestamp set) still report phase=Running
            // for several seconds — must not be counted as available capacity.
            boolean terminating = pod.getMetadata().getDeletionTimestamp() != null;
            if ("idle".equals(statusLabelOf(pod)) && "Running".equals(phaseOf(pod)) && !terminating) {
                count++;
            }
        }
        return count;
    }

    // ── Claim flow (B2.5a) ─────────────────────────────────────────────────

    /**
     * Try to claim a warm idle pod for the given database tenant.
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>warm pool is disabled,</li>
     *   <li>no idle pod is currently available (pool exhausted),</li>
     *   <li>the optimistic label swap loses a race to another claimer
     *       on every candidate,</li>
     *   <li>the {@code /configure} call fails (timeout, 4xx/5xx, IO error),</li>
     *   <li>or the spec couldn't be generated.</li>
     * </ul>
     *
     * <p>On any failure after a label swap was made, the pod is relabeled
     * {@code lakeon.io/status=failed} so the {@link #reconcile()} loop will
     * delete it and replenish on the next cycle.
     *
     * <p>Caller (B3 — ComputeLifecycleService) falls back to the
     * cold-start createComputePod path when this returns empty.
     */
    public Optional<ClaimedPod> claim(DatabaseEntity entity) {
        if (!props.getComputeWarmPool().isEnabled()) {
            return Optional.empty();
        }

        // 1) Find an idle Running, non-terminating pool pod and atomically
        //    swap its labels via fabric8's resource-version-locked edit().
        //    On race loss, KubernetesClientException is thrown and we move
        //    on to the next candidate.
        Pod claimed = null;
        try {
            for (Pod pod : listPoolPods()) {
                if (pod.getMetadata() == null) continue;
                String podName = pod.getMetadata().getName();
                if (podName == null) continue;
                if (!"Running".equals(phaseOf(pod))) continue;
                if (!"idle".equals(statusLabelOf(pod))) continue;
                if (pod.getMetadata().getDeletionTimestamp() != null) continue;

                try {
                    Pod swapped = k8sClient.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
                        if (p.getMetadata().getLabels() == null) {
                            p.getMetadata().setLabels(new LinkedHashMap<>());
                        }
                        Map<String, String> labels = p.getMetadata().getLabels();
                        labels.put("lakeon.io/status", "claiming");
                        labels.put("lakeon.io/tenant-id", entity.getTenantId());
                        labels.put("lakeon.io/instance-id", entity.getId());
                        return p;
                    });
                    // edit() returns the updated pod (with refreshed status
                    // including podIP). Prefer it over the stale list copy.
                    claimed = swapped != null ? swapped : pod;
                    break;
                } catch (KubernetesClientException e) {
                    log.info("warm-pool claim race lost for podName={} — trying next candidate ({})",
                             podName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("warm-pool claim: error selecting candidate for tenantId={} dbId={} error={}",
                     entity.getTenantId(), entity.getId(), e.toString());
            return Optional.empty();
        }

        if (claimed == null) {
            return Optional.empty();
        }

        String podName = claimed.getMetadata().getName();
        String podIp = claimed.getStatus() != null ? claimed.getStatus().getPodIP() : null;

        // 2) Generate the real tenant spec + POST to compute_ctl. Any
        //    failure past this point requires us to mark the pod failed
        //    so the reconcile loop replenishes.
        try {
            String specJson;
            try {
                specJson = specBuilder.generateComputeConfig(entity, REAL_TENANT_SUSPEND_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("warm-pool claim: spec generation failed podName={} dbId={} error={}",
                         podName, entity.getId(), e.toString());
                markPodFailed(podName);
                return Optional.empty();
            }

            ComputeReconfigureClient.Result result =
                reconfigureClient.reconfigure(podName, podIp, specJson);

            if (result.success()) {
                log.info("warm-pool claim succeeded podName={} podIp={} tenantId={} dbId={} elapsedMs={}",
                         podName, podIp, entity.getTenantId(), entity.getId(), result.elapsedMs());
                return Optional.of(new ClaimedPod(podName, podIp));
            }

            log.warn("warm-pool reconfigure failed podName={} statusCode={} error={} — marking pod failed",
                     podName, result.statusCode(), result.errorMessage());
            markPodFailed(podName);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("warm-pool claim: unexpected error after swap podName={} error={}",
                     podName, e.toString());
            markPodFailed(podName);
            return Optional.empty();
        }
    }

    /**
     * Relabel a claimed pod {@code lakeon.io/status=failed} so the
     * {@link #reconcile()} loop's Failed-cleanup branch deletes it on
     * the next cycle. Swallows exceptions: the reconcile loop also has
     * its own aging-out fallback, so a transient failure here is recoverable.
     */
    private void markPodFailed(String podName) {
        try {
            k8sClient.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
                if (p.getMetadata().getLabels() == null) {
                    p.getMetadata().setLabels(new LinkedHashMap<>());
                }
                p.getMetadata().getLabels().put("lakeon.io/status", "failed");
                return p;
            });
        } catch (Exception e) {
            log.warn("warm-pool: failed to mark pod failed podName={} error={}",
                     podName, e.toString());
        }
    }

    // ── Pod construction ───────────────────────────────────────────────────

    private void createIdlePod() {
        LakeonProperties.ComputeWarmPoolConfig cfg = props.getComputeWarmPool();
        String mockTenantId = cfg.getMockTenantId();
        String mockTimelineId = cfg.getMockTimelineId();
        if (mockTenantId == null || mockTenantId.isBlank()
                || mockTimelineId == null || mockTimelineId.isBlank()) {
            log.warn("cannot create warm-pool idle pod: mockTenantId or mockTimelineId not configured "
                     + "(set COMPUTE_WARM_POOL_MOCK_TENANT_ID / _TIMELINE_ID — B2.2)");
            return;
        }

        String podName = "warm-pool-" + UUID.randomUUID().toString().substring(0, 8);
        String configMapName = podName + "-config";
        String image = (cfg.getImage() != null && !cfg.getImage().isBlank())
            ? cfg.getImage()
            : props.getK8s().getComputeImage();

        // Build a DatabaseEntity proxy so specBuilder.generateComputeConfig works
        // unchanged. tenantId is just a label here, not a real Lakeon tenant.
        DatabaseEntity proxy = new DatabaseEntity();
        proxy.setId(podName);
        proxy.setName("warm-pool-mock");
        proxy.setTenantId("warm-pool");
        proxy.setNeonTenantId(mockTenantId);
        proxy.setNeonTimelineId(mockTimelineId);
        proxy.setDbUser("lakeon");
        proxy.setDbPassword("");
        proxy.setComputeSize("1cu");
        proxy.setSuspendTimeout("0s");

        String configJson;
        try {
            configJson = specBuilder.generateComputeConfig(proxy, IDLE_SUSPEND_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("failed to generate compute config for warm-pool pod podName={} error={}",
                     podName, e.getMessage());
            return;
        }

        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(
                    "app", "lakeon-compute",
                    "lakeon.io/pool", "warm",
                    "lakeon.io/instance-id", podName
                ))
            .endMetadata()
            .addToData("config.json", configJson)
            .build();

        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(NAMESPACE)
                .withLabels(Map.of(
                    "app", "lakeon-compute",
                    "lakeon.io/pool", "warm",
                    "lakeon.io/status", "idle",
                    "lakeon.io/instance-id", podName
                ))
            .endMetadata()
            .withNewSpec()
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> new LocalObjectReferenceBuilder().withName(n).build())
                        .toList()
                )
                .withTerminationGracePeriodSeconds(60L)
                .withNodeSelector(
                    props.getK8s().getComputeNodeSelector().isEmpty()
                        ? null
                        : new LinkedHashMap<>(props.getK8s().getComputeNodeSelector())
                )
                .withTolerations(new TolerationBuilder()
                    .withKey("lakeon/compute-only")
                    .withOperator("Exists")
                    .withEffect("NoSchedule")
                    .build())
                .addNewContainer()
                    .withName("compute")
                    .withImage(image)
                    .withCommand("/usr/local/bin/compute_ctl")
                    .withArgs(
                        "--pgdata", "/var/db/postgres/compute",
                        "-C", "postgresql://cloud_admin@localhost:55433/postgres",
                        "-b", "/usr/local/bin/postgres",
                        "--compute-id", podName,
                        "--config", "/config/config.json",
                        "--dev"
                    )
                    .addNewPort()
                        .withContainerPort(55433)
                        .withName("pg")
                    .endPort()
                    .addNewPort()
                        .withContainerPort(3080)
                        .withName("http")
                    .endPort()
                    // MUST match ComputeSize.CU_1 — see ComputePodManager.resolveComputeCpu/Memory.
                    // The pod advertises setComputeSize("1cu"), so when B2.5's claim() binds a
                    // tenant here the runtime must already match CU_1's CPU/memory or the tenant
                    // gets memory-starved (1Gi instead of 2Gi). ComputePodManager sets requests
                    // == limits to the size's values; we mirror that exactly. B2.5 will dedupe.
                    .withNewResources()
                        .withRequests(Map.of(
                            "cpu", new Quantity("1"),
                            "memory", new Quantity("2Gi")
                        ))
                        .withLimits(Map.of(
                            "cpu", new Quantity("1"),
                            "memory", new Quantity("2Gi")
                        ))
                    .endResources()
                    .addNewVolumeMount()
                        .withName("config-volume")
                        .withMountPath("/config")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .withNewStartupProbe()
                        .withNewTcpSocket()
                            .withNewPort(55433)
                        .endTcpSocket()
                        .withInitialDelaySeconds(1)
                        .withPeriodSeconds(1)
                        .withFailureThreshold(180)
                    .endStartupProbe()
                    .withNewReadinessProbe()
                        .withNewTcpSocket()
                            .withNewPort(55433)
                        .endTcpSocket()
                        .withPeriodSeconds(1)
                        .withFailureThreshold(3)
                    .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                    .withName("config-volume")
                    .withNewConfigMap()
                        .withName(configMapName)
                    .endConfigMap()
                .endVolume()
            .endSpec()
            .build();

        try {
            k8sClient.configMaps().inNamespace(NAMESPACE).resource(configMap).serverSideApply();
            k8sClient.pods().inNamespace(NAMESPACE).resource(pod).create();
            log.info("created warm-pool idle pod podName={} namespace={} image={}",
                     podName, NAMESPACE, image);
        } catch (Exception e) {
            // Don't propagate — the next reconcile cycle will retry.
            log.warn("failed to create warm-pool idle pod podName={} error={}",
                     podName, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<Pod> listPoolPods() {
        return k8sClient.pods().inNamespace(NAMESPACE)
            .withLabel("lakeon.io/pool", "warm")
            .list().getItems();
    }

    private void deletePodAndConfigMap(String podName) {
        try {
            k8sClient.pods().inNamespace(NAMESPACE).withName(podName).delete();
        } catch (Exception e) {
            log.warn("failed to delete warm-pool pod podName={} error={}", podName, e.getMessage());
        }
        try {
            k8sClient.configMaps().inNamespace(NAMESPACE).withName(podName + "-config").delete();
        } catch (Exception e) {
            log.warn("failed to delete warm-pool ConfigMap configMapName={}-config error={}",
                     podName, e.getMessage());
        }
    }

    private static String phaseOf(Pod pod) {
        return pod.getStatus() != null ? pod.getStatus().getPhase() : null;
    }

    private static String statusLabelOf(Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null) return "";
        return pod.getMetadata().getLabels().getOrDefault("lakeon.io/status", "");
    }

    private static boolean isStale(Pod pod) {
        if (pod.getMetadata() == null) return false;
        String ts = pod.getMetadata().getCreationTimestamp();
        if (ts == null) return false;
        try {
            Instant created = Instant.parse(ts);
            return created.isBefore(Instant.now().minusSeconds(IDLE_POD_MAX_AGE_SECONDS));
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
