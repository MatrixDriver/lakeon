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
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class WarmPoolManager {

    private static final Logger log = LoggerFactory.getLogger(WarmPoolManager.class);

    public record ClaimedHead(String podName, String podIp, String namespace) {}

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

    /**
     * Claim an idle warm-pool head pod for the given tenant/session.
     * Returns empty if pool is disabled, exhausted, or all candidates lack an IP.
     */
    public Optional<ClaimedHead> claimHead(String tenantId, String sessionId) {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) {
            return Optional.empty();
        }

        String ns = dl.getWarmPoolNamespace();
        var idlePods = k8sClient.pods().inNamespace(ns)
                .withLabel("lakeon.io/pool", "warm")
                .withLabel("lakeon.io/status", "idle")
                .list().getItems();

        for (Pod pod : idlePods) {
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
            String ip = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

            if (!"Running".equals(phase) || ip == null) {
                continue;
            }

            String podName = pod.getMetadata().getName();
            try {
                k8sClient.pods().inNamespace(ns).withName(podName).edit(p -> {
                    p.getMetadata().getLabels().put("lakeon.io/status", "claimed");
                    p.getMetadata().getLabels().put("lakeon.io/tenant-id", tenantId);
                    p.getMetadata().getLabels().put("lakeon.io/session-id", sessionId);
                    return p;
                });
                log.info("Claimed warm pool pod {} (ip={}) for tenant={}, session={}",
                        podName, ip, tenantId, sessionId);
                return Optional.of(new ClaimedHead(podName, ip, ns));
            } catch (Exception e) {
                log.warn("Failed to claim pod {}: {}", podName, e.getMessage());
            }
        }

        log.warn("Warm pool exhausted — no idle pod available for tenant={}, session={}",
                tenantId, sessionId);
        return Optional.empty();
    }

    /**
     * Release a claimed head pod: delete the pod and any associated worker pods.
     */
    public void releaseHead(String podName, String sessionId) {
        String ns = props.getDatalake().getWarmPoolNamespace();
        try {
            k8sClient.pods().inNamespace(ns).withName(podName).delete();
            log.info("Deleted warm pool pod: {}/{}", ns, podName);
        } catch (Exception e) {
            log.warn("Failed to delete warm pool pod {}/{}: {}", ns, podName, e.getMessage());
        }
        try {
            k8sClient.pods().inNamespace(ns)
                    .withLabel("lakeon.io/session-id", sessionId)
                    .delete();
            log.info("Deleted worker pods for session: {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete worker pods for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Periodically reconcile the warm pool: clean up stale pods and replenish to target size.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void reconcile() {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) {
            return;
        }

        String ns = dl.getWarmPoolNamespace();
        var allPoolPods = k8sClient.pods().inNamespace(ns)
                .withLabel("lakeon.io/pool", "warm")
                .list().getItems();

        // Clean up idle pods past timeout
        Instant cutoff = Instant.now().minusSeconds(dl.getWarmPoolIdleTimeoutMinutes() * 60L);
        int idleRunning = 0;
        int pending = 0;

        for (Pod pod : allPoolPods) {
            String status = pod.getMetadata().getLabels().getOrDefault("lakeon.io/status", "");
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";

            if ("idle".equals(status)) {
                // Check if past timeout
                Instant created = parseCreationTimestamp(pod);
                if (created != null && created.isBefore(cutoff)) {
                    try {
                        k8sClient.pods().inNamespace(ns)
                                .withName(pod.getMetadata().getName()).delete();
                        log.info("Deleted stale idle pod: {}", pod.getMetadata().getName());
                    } catch (Exception e) {
                        log.warn("Failed to delete stale pod {}: {}",
                                pod.getMetadata().getName(), e.getMessage());
                    }
                    continue;
                }

                if ("Running".equals(phase)) {
                    idleRunning++;
                } else if ("Pending".equals(phase)) {
                    pending++;
                }
            }
        }

        int available = idleRunning + pending;
        int target = dl.getWarmPoolSize();
        int toCreate = target - available;

        if (toCreate > 0) {
            log.info("Warm pool: idle={}, pending={}, target={} — creating {} pods",
                    idleRunning, pending, target, toCreate);
            for (int i = 0; i < toCreate; i++) {
                createIdleHeadPod(ns, dl);
            }
        }
    }

    @PostConstruct
    public void init() {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        if (!dl.isWarmPoolEnabled()) {
            log.info("Warm pool is disabled");
            return;
        }

        String ns = dl.getWarmPoolNamespace();
        // Ensure pool namespace exists
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
        log.info("Warm pool initialized: namespace={}, size={}, image={}",
                ns, dl.getWarmPoolSize(), dl.getWarmPoolImage());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void createIdleHeadPod(String ns, LakeonProperties.DatalakeConfig dl) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String podName = "warm-ray-head-" + suffix;

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
                    .withNodeSelector(Map.of(
                            dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
                    .withTolerations(vkToleration)
                    .withContainers(container)
                .endSpec()
                .build();

        try {
            k8sClient.pods().inNamespace(ns).resource(pod).create();
            log.info("Created warm pool pod: {}/{}", ns, podName);
        } catch (Exception e) {
            log.warn("Failed to create warm pool pod {}: {}", podName, e.getMessage());
        }
    }

    private Instant parseCreationTimestamp(Pod pod) {
        String ts = pod.getMetadata().getCreationTimestamp();
        if (ts == null) return null;
        try {
            return Instant.parse(ts);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
