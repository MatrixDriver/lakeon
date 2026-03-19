package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages kb-write pods: dedicated Neon compute instances for KB write operations.
 * These pods connect directly to pageserver/safekeeper (same as regular compute pods)
 * but are used exclusively for write operations to avoid connection issues with
 * user-facing compute pods that may be suspended/restarted.
 */
@Component
public class KbWritePodManager {
    private static final Logger log = LoggerFactory.getLogger(KbWritePodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ComputeSpecBuilder specBuilder;

    public KbWritePodManager(KubernetesClient k8sClient, LakeonProperties props,
                             ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.specBuilder = new ComputeSpecBuilder(props, objectMapper);
    }

    /**
     * Ensure a kb-write pod exists and is ready for the given database.
     * Returns the address in "host:port" format.
     */
    public String ensureKbWritePod(DatabaseEntity entity) {
        String podName = podName(entity.getId());
        String configMapName = podName + "-config";
        String namespace = props.getK8s().getNamespace();

        // Check if pod already exists and is ready
        Pod existing = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (existing != null && existing.getMetadata().getDeletionTimestamp() == null) {
            String podIp = getPodIp(podName);
            if (podIp != null) {
                return podIp + ":55433";
            }
            // Pod exists but no IP yet, wait for it
            if (waitForPodReady(podName, 60_000)) {
                podIp = getPodIp(podName);
                return (podIp != null ? podIp : podName + "." + namespace) + ":55433";
            }
            // Pod stuck, delete and recreate
            log.warn("kb-write pod {} exists but not ready, recreating", podName);
            deleteKbWritePod(entity.getId());
        }

        // Wait for terminating pod to be gone
        if (existing != null && existing.getMetadata().getDeletionTimestamp() != null) {
            for (int i = 0; i < 30; i++) {
                if (k8sClient.pods().inNamespace(namespace).withName(podName).get() == null) break;
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Generate config with suspend_timeout=0 (API manages lifecycle)
        String configJson = specBuilder.generateComputeConfig(entity, 0);

        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-kb-write",
                    "lakeon.io/instance-id", entity.getId()
                ))
            .endMetadata()
            .addToData("config.json", configJson)
            .build();
        k8sClient.configMaps().inNamespace(namespace).resource(configMap).serverSideApply();

        String cpu = props.getKbWrite().getCpu();
        String memory = props.getKbWrite().getMemory();

        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-kb-write",
                    "lakeon.io/instance-id", entity.getId(),
                    "lakeon.io/tenant-id", entity.getTenantId()
                ))
            .endMetadata()
            .withNewSpec()
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
                        .toList()
                )
                .withNodeSelector(
                    props.getK8s().getComputeNodeSelector().isEmpty()
                        ? null
                        : new LinkedHashMap<>(props.getK8s().getComputeNodeSelector())
                )
                .addNewContainer()
                    .withName("compute")
                    .withImage(props.getK8s().getComputeImage())
                    .withCommand("/usr/local/bin/compute_ctl")
                    .withArgs(
                        "--pgdata", "/var/db/postgres/compute",
                        "-C", "postgresql://cloud_admin@localhost:55433/postgres",
                        "-b", "/usr/local/bin/postgres",
                        "--compute-id", podName,
                        "--config", "/config/config.json",
                        "--dev"
                    )
                    .addNewPort().withContainerPort(55433).withName("pg").endPort()
                    .addNewPort().withContainerPort(3080).withName("http").endPort()
                    .withNewResources()
                        .withRequests(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)
                        ))
                        .withLimits(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)
                        ))
                    .endResources()
                    .addNewVolumeMount()
                        .withName("config-volume")
                        .withMountPath("/config")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .withNewReadinessProbe()
                        .withNewTcpSocket().withNewPort(55433).endTcpSocket()
                        .withInitialDelaySeconds(1)
                        .withPeriodSeconds(1)
                    .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                    .withName("config-volume")
                    .withNewConfigMap().withName(configMapName).endConfigMap()
                .endVolume()
            .endSpec()
            .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created kb-write pod: {}/{}", namespace, podName);

        // Wait for pod to become ready
        if (!waitForPodReady(podName, 120_000)) {
            log.error("kb-write pod {} did not become ready in 120s", podName);
            throw new RuntimeException("kb-write pod did not become ready: " + podName);
        }

        String podIp = getPodIp(podName);
        return (podIp != null ? podIp : podName + "." + namespace) + ":55433";
    }

    public void deleteKbWritePod(String databaseId) {
        String podName = podName(databaseId);
        String namespace = props.getK8s().getNamespace();
        try {
            k8sClient.pods().inNamespace(namespace).withName(podName).delete();
        } catch (Exception e) {
            log.debug("Failed to delete kb-write pod {}: {}", podName, e.getMessage());
        }
        try {
            k8sClient.configMaps().inNamespace(namespace).withName(podName + "-config").delete();
        } catch (Exception e) {
            log.debug("Failed to delete kb-write configmap {}: {}", podName + "-config", e.getMessage());
        }
        log.info("Deleted kb-write pod: {}/{}", namespace, podName);
    }

    public String getKbWriteAddress(String databaseId) {
        String podName = podName(databaseId);
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null || pod.getMetadata().getDeletionTimestamp() != null) return null;
        if (!isPodReady(podName)) return null;
        String podIp = getPodIp(podName);
        return podIp != null ? podIp + ":55433" : null;
    }

    public boolean podExists(String databaseId) {
        String podName = podName(databaseId);
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        return pod != null && pod.getMetadata().getDeletionTimestamp() == null;
    }

    public java.util.List<String> listKbWritePodDatabaseIds() {
        String namespace = props.getK8s().getNamespace();
        return k8sClient.pods().inNamespace(namespace)
            .withLabel("app", "lakeon-kb-write")
            .list().getItems().stream()
            .filter(p -> p.getMetadata().getDeletionTimestamp() == null)
            .map(p -> p.getMetadata().getLabels().get("lakeon.io/instance-id"))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private String podName(String databaseId) {
        return "kb-write-" + databaseId.replace("_", "-");
    }

    private String getPodIp(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        return pod != null && pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
    }

    private boolean isPodReady(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return false;
        return pod.getStatus() != null
            && pod.getStatus().getConditions() != null
            && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private boolean waitForPodReady(String podName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPodReady(podName)) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}