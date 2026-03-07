package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.ComputeSize;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ComputePodManager {
    private static final Logger log = LoggerFactory.getLogger(ComputePodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ComputePodManager(KubernetesClient k8sClient, LakeonProperties props, ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        Gauge.builder("lakeon_compute_pods_active", this, ComputePodManager::countActivePods)
            .description("Number of active compute pods")
            .register(meterRegistry);
    }

    public double countActivePods() {
        try {
            String namespace = props.getK8s().getNamespace();
            return k8sClient.pods().inNamespace(namespace)
                .withLabel("app", "lakeon-compute")
                .list().getItems().stream()
                .filter(p -> p.getMetadata().getDeletionTimestamp() == null)
                .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Create a compute Pod for the given database entity.
     * Uses a ConfigMap to inject compute config (avoids shell injection).
     * Returns the compute address in "host:port" format.
     */
    public String createComputePod(DatabaseEntity entity) {
        String podName = "compute-" + entity.getId().replace("_", "-");
        String configMapName = podName + "-config";
        String namespace = props.getK8s().getNamespace();
        ComputeSize size = ComputeSize.fromLabel(entity.getComputeSize());

        String configJson = generateComputeConfig(entity);

        // Create ConfigMap with compute spec (safe: no shell interpretation)
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-compute",
                    "lakeon.io/instance-id", entity.getId()
                ))
            .endMetadata()
            .addToData("config.json", configJson)
            .build();
        // Check if pod already exists (idempotent wake)
        Pod existingPod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (existingPod != null) {
            // Skip terminating pods — they'll be gone soon
            if (existingPod.getMetadata().getDeletionTimestamp() != null) {
                log.info("Compute Pod {}/{} is terminating, waiting for deletion", namespace, podName);
                for (int i = 0; i < 30; i++) {
                    var pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
                    if (pod == null) break;
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } else {
                log.info("Compute Pod already exists: {}/{}, reusing", namespace, podName);
                String podIp = getPodIp(podName);
                entity.setComputePodName(podName);
                entity.setComputeHost(podIp);
                entity.setComputePort(55433);
                return (podIp != null ? podIp : podName + "." + namespace) + ":55433";
            }
        }

        k8sClient.configMaps().inNamespace(namespace).resource(configMap).serverSideApply();

        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-compute",
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
                    .addNewPort()
                        .withContainerPort(55433)
                        .withName("pg")
                    .endPort()
                    .addNewPort()
                        .withContainerPort(3080)
                        .withName("http")
                    .endPort()
                    .withNewResources()
                        .withRequests(Map.of(
                            "cpu", new Quantity(resolveComputeCpu(size)),
                            "memory", new Quantity(resolveComputeMemory(size))
                        ))
                        .withLimits(Map.of(
                            "cpu", new Quantity(resolveComputeCpu(size)),
                            "memory", new Quantity(resolveComputeMemory(size))
                        ))
                    .endResources()
                    .addNewVolumeMount()
                        .withName("config-volume")
                        .withMountPath("/config")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .withNewReadinessProbe()
                        .withNewTcpSocket()
                            .withNewPort(55433)
                        .endTcpSocket()
                        .withInitialDelaySeconds(1)
                        .withPeriodSeconds(1)
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

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created compute Pod: {}/{}", namespace, podName);

        // Wait for pod IP assignment (usually available within a few seconds)
        String podIp = null;
        for (int i = 0; i < 10; i++) {
            podIp = getPodIp(podName);
            if (podIp != null) break;
            try { Thread.sleep(1000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        entity.setComputePodName(podName);
        entity.setComputeHost(podIp);
        entity.setComputePort(55433);

        return (podIp != null ? podIp : podName + "." + namespace) + ":55433";
    }

    /**
     * Delete a compute Pod and its associated ConfigMap.
     */
    public void deleteComputePod(String podName) {
        deleteComputePod(podName, false);
    }

    /**
     * Delete a compute Pod and its associated ConfigMap.
     * If waitForDeletion is true, blocks until the Pod is fully removed.
     */
    public void deleteComputePod(String podName, boolean waitForDeletion) {
        String namespace = props.getK8s().getNamespace();
        k8sClient.pods().inNamespace(namespace).withName(podName).delete();
        // Clean up associated ConfigMap
        String configMapName = podName + "-config";
        try {
            k8sClient.configMaps().inNamespace(namespace).withName(configMapName).delete();
        } catch (Exception e) {
            log.warn("Failed to delete ConfigMap {}: {}", configMapName, e.getMessage());
        }
        log.info("Deleted compute Pod: {}/{}", namespace, podName);

        if (waitForDeletion) {
            for (int i = 0; i < 30; i++) {
                var pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
                if (pod == null) {
                    log.info("Pod {}/{} fully deleted", namespace, podName);
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("Pod {}/{} still exists after 30s wait", namespace, podName);
        }
    }

    /**
     * Check if a Pod is ready.
     */
    public boolean isPodReady(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) return false;
        return pod.getStatus() != null
            && pod.getStatus().getConditions() != null
            && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Get the IP address of a Pod.
     */
    public String getPodIp(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        return pod != null && pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
    }

    /**
     * Wait for a Pod to become ready (up to timeoutMs milliseconds).
     */
    public boolean waitForPodReady(String podName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPodReady(podName)) return true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Get the last activity time for a compute pod (epoch millis).
     * This checks the pod's last transition time as an approximation.
     */
    public long getLastActivityTime(String podName) {
        String namespace = props.getK8s().getNamespace();
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod != null && pod.getStatus() != null && pod.getStatus().getConditions() != null) {
            return pod.getStatus().getConditions().stream()
                .filter(c -> c.getLastTransitionTime() != null)
                .mapToLong(c -> {
                    try {
                        return java.time.Instant.parse(c.getLastTransitionTime()).toEpochMilli();
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .max()
                .orElse(System.currentTimeMillis());
        }
        return System.currentTimeMillis();
    }

    /**
     * Check if compute pod has active client connections by querying pg_stat_activity.
     */
    public boolean hasActiveConnections(String podName) {
        String namespace = props.getK8s().getNamespace();
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ExecWatch exec = k8sClient.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(stdout)
                    .writingError(stderr)
                    .exec("psql", "-U", "cloud_admin", "-d", "postgres", "-t", "-A", "-c",
                        "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND pid != pg_backend_pid()")) {
                exec.exitCode().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            String result = stdout.toString().trim();
            int count = Integer.parseInt(result);
            log.debug("Pod {} has {} active client connections", podName, count);
            return count > 0;
        } catch (Exception e) {
            log.debug("Failed to check active connections for pod {}: {}", podName, e.getMessage());
            return false;
        }
    }

    /**
     * Get the number of active client connections on a compute pod.
     * Returns 0 if pod is not reachable or has no connections.
     */
    public int getActiveConnectionCount(String podName) {
        String namespace = props.getK8s().getNamespace();
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ExecWatch exec = k8sClient.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(stdout)
                    .writingError(stderr)
                    .exec("psql", "-U", "cloud_admin", "-d", "postgres", "-t", "-A", "-c",
                        "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND pid != pg_backend_pid()")) {
                exec.exitCode().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            String result = stdout.toString().trim();
            return Integer.parseInt(result);
        } catch (Exception e) {
            log.debug("Failed to get connection count for pod {}: {}", podName, e.getMessage());
            return 0;
        }
    }

    private String generateComputeConfig(DatabaseEntity entity) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("format_version", 2);
        spec.put("operation_uuid", UUID.randomUUID().toString());
        spec.put("tenant_id", entity.getNeonTenantId());
        spec.put("timeline_id", entity.getNeonTimelineId());
        String pageserverFqdn = extractPageserverHost() + ".lakeon.svc.cluster.local";
        spec.put("pageserver_connstring", "postgresql://" + pageserverFqdn + ":6400");
        spec.put("safekeeper_connstrings", parseSafekeeperUrls());
        spec.put("mode", "Primary");
        spec.put("suspend_timeout_seconds", 300);

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("cluster_id", "lakeon_" + entity.getId());
        cluster.put("name", entity.getName());
        cluster.put("state", "restarted");
        cluster.put("roles", List.of(
            Map.of(
                "name", entity.getDbUser() != null ? entity.getDbUser() : "lakeon",
                "encrypted_password", entity.getDbPassword() != null ? entity.getDbPassword() : ""
            ),
            Map.of(
                "name", "cloud_admin",
                "encrypted_password", com.lakeon.util.ScramUtils.generateScramHash("cloud-admin-internal")
            )
        ));
        cluster.put("databases", List.of(Map.of(
            "name", entity.getName(),
            "owner", entity.getDbUser() != null ? entity.getDbUser() : "lakeon"
        )));
        cluster.put("settings", getDefaultPgSettings(entity));
        spec.put("cluster", cluster);

        Map<String, Object> config = Map.of(
            "spec", spec,
            "compute_ctl_config", Map.of("jwks", Map.of("keys", List.of()))
        );
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate compute config", e);
        }
    }

    private String resolveComputeCpu(ComputeSize size) {
        String override = props.getK8s().getComputeCpu();
        return (override != null && !override.isBlank()) ? override : size.getCpu();
    }

    private String resolveComputeMemory(ComputeSize size) {
        String override = props.getK8s().getComputeMemory();
        return (override != null && !override.isBlank()) ? override : size.getMemory();
    }

    private String extractPageserverHost() {
        String url = props.getNeon().getPageserverUrl();
        return url.replaceAll("https?://", "").replaceAll(":\\d+$", "");
    }

    private List<String> parseSafekeeperUrls() {
        String urls = props.getNeon().getSafekeeperUrls();
        if (urls == null || urls.isBlank()) return List.of();
        return Arrays.asList(urls.split(","));
    }

    private List<Map<String, String>> getDefaultPgSettings(DatabaseEntity entity) {
        String pageserverFqdn = extractPageserverHost() + ".lakeon.svc.cluster.local";
        List<Map<String, String>> settings = new ArrayList<>();
        settings.add(Map.of("name", "shared_preload_libraries", "value", "neon", "vartype", "string"));
        settings.add(Map.of("name", "fsync", "value", "off", "vartype", "bool"));
        settings.add(Map.of("name", "wal_level", "value", "logical", "vartype", "enum"));
        settings.add(Map.of("name", "wal_log_hints", "value", "on", "vartype", "bool"));
        settings.add(Map.of("name", "log_connections", "value", "on", "vartype", "bool"));
        settings.add(Map.of("name", "port", "value", "55433", "vartype", "integer"));
        settings.add(Map.of("name", "shared_buffers", "value", "128MB", "vartype", "string"));
        settings.add(Map.of("name", "max_connections", "value", "100", "vartype", "integer"));
        settings.add(Map.of("name", "listen_addresses", "value", "0.0.0.0", "vartype", "string"));
        settings.add(Map.of("name", "neon.pageserver_connstring", "value", "postgresql://pageserver." + "lakeon.svc.cluster.local:6400", "vartype", "string"));
        settings.add(Map.of("name", "neon.safekeepers", "value", props.getNeon().getSafekeeperUrls(), "vartype", "string"));
        settings.add(Map.of("name", "neon.tenant_id", "value", entity.getNeonTenantId(), "vartype", "string"));
        settings.add(Map.of("name", "neon.timeline_id", "value", entity.getNeonTimelineId(), "vartype", "string"));
        return settings;
    }
}
