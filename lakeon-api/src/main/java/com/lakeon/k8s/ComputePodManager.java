package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.ComputeSize;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
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
     * Create a compute pod for a specific branch.
     * Uses a transient DatabaseEntity proxy so existing createComputePod() works.
     */
    public String createComputePodForBranch(DatabaseEntity db, BranchEntity branch) {
        DatabaseEntity proxy = new DatabaseEntity();
        proxy.setId(branch.getId());
        proxy.setName(db.getName());
        proxy.setTenantId(db.getTenantId());
        proxy.setNeonTenantId(db.getNeonTenantId());
        proxy.setNeonTimelineId(branch.getNeonTimelineId());
        proxy.setDbUser(db.getDbUser());
        proxy.setDbPassword(db.getDbPassword());
        proxy.setComputeSize(db.getComputeSize());
        proxy.setSuspendTimeout(branch.getSuspendTimeout() != null
            ? branch.getSuspendTimeout() : db.getSuspendTimeout());

        String result = createComputePod(proxy);

        // Copy compute fields back to branch entity
        branch.setComputePodName(proxy.getComputePodName());
        branch.setComputeHost(proxy.getComputeHost());
        branch.setComputePort(proxy.getComputePort());

        return result;
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
    /**
     * Returns true if any compute pod has been in an abnormal state for longer than thresholdSeconds.
     * Abnormal states: Failed, CrashLoopBackOff, or Pending beyond threshold.
     */
    public boolean hasAbnormalPods(double thresholdSeconds) {
        String namespace = props.getK8s().getNamespace();
        List<Pod> pods = k8sClient.pods().inNamespace(namespace)
            .withLabel("app", "lakeon-compute")
            .list().getItems();

        long thresholdMs = (long) (thresholdSeconds * 1000);
        long now = System.currentTimeMillis();

        for (Pod pod : pods) {
            if (pod.getMetadata().getDeletionTimestamp() != null) continue;
            PodStatus status = pod.getStatus();
            if (status == null) continue;

            String phase = status.getPhase();

            // Failed pods are always abnormal
            if ("Failed".equals(phase)) {
                log.warn("Abnormal compute pod (Failed): {}", pod.getMetadata().getName());
                return true;
            }

            // CrashLoopBackOff in any container
            if (status.getContainerStatuses() != null) {
                for (ContainerStatus cs : status.getContainerStatuses()) {
                    if (cs.getState() != null && cs.getState().getWaiting() != null
                            && "CrashLoopBackOff".equals(cs.getState().getWaiting().getReason())) {
                        log.warn("Abnormal compute pod (CrashLoopBackOff): {}", pod.getMetadata().getName());
                        return true;
                    }
                }
            }

            // Pending beyond threshold — use startTime
            if ("Pending".equals(phase) && pod.getStatus().getStartTime() != null) {
                try {
                    long startMs = java.time.Instant.parse(pod.getStatus().getStartTime()).toEpochMilli();
                    if (now - startMs > thresholdMs) {
                        log.warn("Abnormal compute pod (Pending >{}s): {}", (long) thresholdSeconds, pod.getMetadata().getName());
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

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
     * Read recent container logs from a compute pod.
     */
    public String getComputePodLogs(String podName, int tailLines) {
        String namespace = props.getK8s().getNamespace();
        try {
            return k8sClient.pods().inNamespace(namespace).withName(podName)
                .tailingLines(tailLines)
                .getLog();
        } catch (Exception e) {
            log.debug("Failed to get logs for pod {}: {}", podName, e.getMessage());
            return "";
        }
    }

    /**
     * Check if compute pod has active client connections by querying pg_stat_activity.
     */
    private static final String PG_STAT_QUERY =
        "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND pid <> pg_backend_pid() AND application_name NOT LIKE 'compute_ctl:%'";

    public boolean hasActiveConnections(String podName) {
        return getActiveConnectionCount(podName) > 0;
    }

    /**
     * Get the number of active client connections on a compute pod.
     * Connects via TCP 127.0.0.1:55433 (Neon compute listens on 55433, not default 5432 unix socket).
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
                    .exec("psql", "-h", "127.0.0.1", "-p", "55433",
                        "-U", "cloud_admin", "-d", "postgres", "-t", "-A", "-c", PG_STAT_QUERY)) {
                exec.exitCode().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            String result = stdout.toString().trim();
            int count = Integer.parseInt(result);
            log.debug("Pod {} has {} active client connections", podName, count);
            return count;
        } catch (Exception e) {
            log.debug("Failed to get connection count for pod {}: {}", podName, e.getMessage());
            return 0;
        }
    }

    /**
     * Get the number of slow queries (running > 3 seconds) on a compute pod.
     */
    private static final String SLOW_QUERY_SQL =
        "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND state='active' AND pid <> pg_backend_pid() AND now() - query_start > interval '3 seconds'";

    public int getSlowQueryCount(String podName) {
        String namespace = props.getK8s().getNamespace();
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ExecWatch exec = k8sClient.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(stdout)
                    .writingError(stderr)
                    .exec("psql", "-h", "127.0.0.1", "-p", "55433",
                        "-U", "cloud_admin", "-d", "postgres", "-t", "-A", "-c", SLOW_QUERY_SQL)) {
                exec.exitCode().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            return Integer.parseInt(stdout.toString().trim());
        } catch (Exception e) {
            log.debug("Failed to get slow query count for pod {}: {}", podName, e.getMessage());
            return 0;
        }
    }

    /**
     * Sync a user's password (SCRAM hash) to the running compute pod's PostgreSQL.
     * Uses cloud_admin trust auth via localhost to ALTER ROLE.
     */
    public void syncPassword(String podName, String username, String scramHash) {
        String namespace = props.getK8s().getNamespace();
        String sql = "ALTER ROLE \"" + username.replace("\"", "\"\"") + "\" WITH PASSWORD '" + scramHash.replace("'", "''") + "'";
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ExecWatch exec = k8sClient.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(stdout)
                    .writingError(stderr)
                    .exec("psql", "-h", "127.0.0.1", "-p", "55433",
                        "-U", "cloud_admin", "-d", "postgres", "-c", sql)) {
                exec.exitCode().get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            log.info("Synced password for user {} on pod {}", username, podName);
        } catch (Exception e) {
            log.warn("Failed to sync password for user {} on pod {}: {}", username, podName, e.getMessage());
        }
    }

    /**
     * Get CPU and memory usage for a compute pod from K8s metrics API.
     * Returns [cpuCores, memoryBytes] or null if unavailable.
     */
    public double[] getComputePodResourceUsage(String podName) {
        String namespace = props.getK8s().getNamespace();
        try {
            PodMetrics pm = k8sClient.top().pods().inNamespace(namespace).withName(podName).metric();
            if (pm == null || pm.getContainers() == null) return null;
            double totalCpu = 0;
            double totalMem = 0;
            for (var container : pm.getContainers()) {
                Quantity cpuQ = container.getUsage().get("cpu");
                Quantity memQ = container.getUsage().get("memory");
                if (cpuQ != null) totalCpu += parseCpuNano(cpuQ);
                if (memQ != null) totalMem += parseMemBytes(memQ);
            }
            return new double[]{ totalCpu, totalMem };
        } catch (Exception e) {
            log.debug("Failed to get pod metrics for {}: {}", podName, e.getMessage());
            return null;
        }
    }

    private double parseCpuNano(Quantity q) {
        String val = q.getAmount();
        String fmt = q.getFormat();
        try {
            double amount = Double.parseDouble(val);
            if ("n".equals(fmt)) return amount / 1_000_000_000;
            if ("m".equals(fmt)) return amount / 1000;
            return amount;
        } catch (NumberFormatException e) { return 0; }
    }

    private double parseMemBytes(Quantity q) {
        String val = q.getAmount();
        String fmt = q.getFormat();
        try {
            double amount = Double.parseDouble(val);
            if ("Ki".equals(fmt)) return amount * 1024;
            if ("Mi".equals(fmt)) return amount * 1024 * 1024;
            if ("Gi".equals(fmt)) return amount * 1024 * 1024 * 1024;
            return amount;
        } catch (NumberFormatException e) { return 0; }
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
