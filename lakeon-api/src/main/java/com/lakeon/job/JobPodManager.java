package com.lakeon.job;

import com.lakeon.config.LakeonProperties;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class JobPodManager {
    private static final Logger log = LoggerFactory.getLogger(JobPodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ObsStsService obsStsService;

    @Value("${lakeon.api.internal-port:8088}")
    private int internalPort;

    @Value("${lakeon.job.callback-base-url:}")
    private String callbackBaseUrl;

    @Value("${lakeon.job.namespace:}")
    private String jobNamespace;

    public JobPodManager(KubernetesClient k8sClient, LakeonProperties props, ObsStsService obsStsService) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.obsStsService = obsStsService;
    }

    /**
     * Launch a job Pod for the given JobEntity.
     * Creates a ConfigMap (job-{safePodId}-params with params.json) and a Pod.
     * Returns the pod name.
     */
    private String getJobNamespace() {
        return (jobNamespace != null && !jobNamespace.isBlank()) ? jobNamespace : props.getK8s().getNamespace();
    }

    public String launchJobPod(JobEntity job) {
        String namespace = getJobNamespace();
        String safePodId = job.getId().replace("_", "-");
        String podName = "job-" + safePodId;
        String configMapName = podName + "-params";

        // Resolve image/cpu/memory from job type config
        String typeKey = job.getType().name().toLowerCase().replace("_", "-");
        LakeonProperties.JobTypeConfig typeConfig = props.getJob().getTypes().get(typeKey);
        if (typeConfig == null || typeConfig.getImage() == null || typeConfig.getImage().isBlank()) {
            throw new IllegalStateException("No image configured for job type: " + typeKey);
        }
        String image = typeConfig.getImage();
        String cpu = typeConfig.getCpu() != null ? typeConfig.getCpu() : "2";
        String memory = typeConfig.getMemory() != null ? typeConfig.getMemory() : "4Gi";

        // Build callback URL — use configured base URL (for CCI) or internal service
        String callbackUrl;
        if (callbackBaseUrl != null && !callbackBaseUrl.isBlank()) {
            callbackUrl = callbackBaseUrl + "/api/v1/jobs/" + job.getId() + "/callback";
        } else {
            callbackUrl = "http://lakeon-api.lakeon.svc.cluster.local:" + internalPort
                    + "/api/v1/jobs/" + job.getId() + "/callback";
        }

        // Create ConfigMap with params.json
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-job",
                    "lakeon.io/job-id", job.getId(),
                    "lakeon.io/tenant-id", job.getTenantId(),
                    "lakeon.io/job-type", typeKey
                ))
            .endMetadata()
            .addToData("params.json", job.getParams() != null ? job.getParams() : "{}")
            .build();
        k8sClient.configMaps().inNamespace(namespace).resource(configMap).createOrReplace();
        log.info("Created job ConfigMap: {}/{}", namespace, configMapName);

        // Get STS credentials for the tenant
        ObsStsService.StsCredentials stsCreds = obsStsService.getCredentials(job.getTenantId());

        // Create the job Pod
        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-job",
                    "lakeon.io/job-id", job.getId(),
                    "lakeon.io/tenant-id", job.getTenantId(),
                    "lakeon.io/job-type", typeKey
                ))
                .withAnnotations(Map.of(
                    "virtual-kubelet.io/burst-to-cci", "enforce"
                ))
            .endMetadata()
            .withNewSpec()
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
                        .toList()
                )
                .withRestartPolicy("Never")
                .withNodeSelector(Map.of("type", "virtual-kubelet"))
                .withTolerations(new io.fabric8.kubernetes.api.model.TolerationBuilder()
                    .withKey("virtual-kubelet.io/provider")
                    .withOperator("Exists")
                    .build())
                .addNewContainer()
                    .withName("job")
                    .withImage(image)
                    .withImagePullPolicy("IfNotPresent")
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
                    .addNewEnv()
                        .withName("JOB_ID")
                        .withValue(job.getId())
                    .endEnv()
                    .addNewEnv()
                        .withName("JOB_TYPE")
                        .withValue(job.getType().name())
                    .endEnv()
                    .addNewEnv()
                        .withName("JOB_CALLBACK_URL")
                        .withValue(callbackUrl)
                    .endEnv()
                    .addNewEnv()
                        .withName("JOB_CALLBACK_TOKEN")
                        .withValue(job.getCallbackToken())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_ACCESS_KEY")
                        .withValue(stsCreds.accessKey())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_SECRET_KEY")
                        .withValue(stsCreds.secretKey())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_SESSION_TOKEN")
                        .withValue(stsCreds.sessionToken())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_ENDPOINT")
                        .withValue(props.getObs().getEndpoint())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_BUCKET")
                        .withValue(props.getObs().getBucket())
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_REGION")
                        .withValue(props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4")
                    .endEnv()
                    .addNewVolumeMount()
                        .withName("job-params")
                        .withMountPath("/etc/job")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .addNewVolumeMount()
                        .withName("dshm")
                        .withMountPath("/dev/shm")
                    .endVolumeMount()
                .endContainer()
                .addNewVolume()
                    .withName("job-params")
                    .withNewConfigMap()
                        .withName(configMapName)
                    .endConfigMap()
                .endVolume()
                .addNewVolume()
                    .withName("dshm")
                    .withNewEmptyDir()
                        .withMedium("Memory")
                        .withNewSizeLimit("2Gi")
                    .endEmptyDir()
                .endVolume()
            .endSpec()
            .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created job Pod: {}/{}", namespace, podName);

        return podName;
    }

    /**
     * Returns true if the pod is null, Succeeded, or Failed.
     */
    public boolean isPodTerminated(String podName) {
        String namespace = getJobNamespace();
        try {
            Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                // Pod not found — could be CCI (name prefix differs) or not yet registered.
                // Return false to avoid killing running CCI jobs.
                // Stuck task timeout will catch truly lost pods.
                return false;
            }
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
            return "Succeeded".equals(phase) || "Failed".equals(phase);
        } catch (Exception e) {
            log.warn("Error checking pod {} status: {}", podName, e.getMessage());
            return false;
        }
    }

    /**
     * Returns a human-readable termination reason from the pod status.
     * Extracts exit code, OOMKilled, and container last-state info.
     * Also attempts to read a tail of pod logs for the error message.
     */
    public String getTerminationReason(String podName) {
        String namespace = getJobNamespace();
        try {
            Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null || pod.getStatus() == null) {
                return "Pod not found";
            }

            StringBuilder sb = new StringBuilder();
            String phase = pod.getStatus().getPhase();
            sb.append("Pod phase: ").append(phase);

            // Check pod-level reason (e.g. Evicted)
            if (pod.getStatus().getReason() != null) {
                sb.append(", reason: ").append(pod.getStatus().getReason());
            }
            if (pod.getStatus().getMessage() != null) {
                sb.append(", message: ").append(pod.getStatus().getMessage());
            }

            // Check container statuses for termination details
            if (pod.getStatus().getContainerStatuses() != null) {
                for (var cs : pod.getStatus().getContainerStatuses()) {
                    var terminated = cs.getState() != null ? cs.getState().getTerminated() : null;
                    if (terminated != null) {
                        sb.append(". Container '").append(cs.getName()).append("': ");
                        sb.append("exitCode=").append(terminated.getExitCode());
                        if (terminated.getReason() != null) {
                            sb.append(", reason=").append(terminated.getReason());
                        }
                        if (terminated.getMessage() != null) {
                            sb.append(", message=").append(terminated.getMessage());
                        }
                    }
                    // Also check lastState (for CrashLoopBackOff scenarios)
                    var lastTerminated = cs.getLastState() != null ? cs.getLastState().getTerminated() : null;
                    if (lastTerminated != null && terminated == null) {
                        sb.append(". Container '").append(cs.getName()).append("' last terminated: ");
                        sb.append("exitCode=").append(lastTerminated.getExitCode());
                        if (lastTerminated.getReason() != null) {
                            sb.append(", reason=").append(lastTerminated.getReason());
                        }
                    }
                }
            }

            // Try to get tail of pod logs for error details
            try {
                String logs = k8sClient.pods().inNamespace(namespace).withName(podName)
                        .tailingLines(20).getLog();
                if (logs != null && !logs.isBlank()) {
                    // Extract last error/exception line
                    String lastError = extractLastError(logs);
                    if (lastError != null) {
                        sb.append(". Log: ").append(lastError);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read logs for pod {}: {}", podName, e.getMessage());
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Error getting termination reason for pod {}: {}", podName, e.getMessage());
            return "Unable to determine: " + e.getMessage();
        }
    }

    /**
     * Extract the last meaningful error line from pod logs.
     */
    private String extractLastError(String logs) {
        String[] lines = logs.split("\n");
        // Search backwards for error/exception/traceback lines
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.contains("error") || lower.contains("exception") ||
                lower.contains("traceback") || lower.contains("failed") ||
                lower.contains("killed") || lower.contains("oom")) {
                // Truncate very long lines
                return line.length() > 300 ? line.substring(0, 300) + "..." : line;
            }
        }
        // If no error line found, return last non-empty line
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line.length() > 300 ? line.substring(0, 300) + "..." : line;
            }
        }
        return null;
    }

    /**
     * Returns true if the pod exists (is not null).
     */
    public boolean podExists(String podName) {
        String namespace = getJobNamespace();
        try {
            Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
            return pod != null;
        } catch (Exception e) {
            log.warn("Error checking pod {} existence: {}", podName, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the pod and configmap for the given job ID.
     */
    public void deleteJobResources(String jobId) {
        String namespace = getJobNamespace();
        String safePodId = jobId.replace("_", "-");
        String podName = "job-" + safePodId;
        String configMapName = podName + "-params";

        try {
            k8sClient.pods().inNamespace(namespace).withName(podName).delete();
            log.info("Deleted job Pod: {}/{}", namespace, podName);
        } catch (Exception e) {
            log.warn("Failed to delete job Pod {}: {}", podName, e.getMessage());
        }

        try {
            k8sClient.configMaps().inNamespace(namespace).withName(configMapName).delete();
            log.info("Deleted job ConfigMap: {}/{}", namespace, configMapName);
        } catch (Exception e) {
            log.warn("Failed to delete job ConfigMap {}: {}", configMapName, e.getMessage());
        }
    }
}
