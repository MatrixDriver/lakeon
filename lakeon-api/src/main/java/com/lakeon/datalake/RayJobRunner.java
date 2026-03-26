package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RayJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RayJobRunner.class);

    public static final CustomResourceDefinitionContext RAY_JOB_CONTEXT =
        new CustomResourceDefinitionContext.Builder()
            .withGroup("ray.io")
            .withVersion("v1")
            .withScope("Namespaced")
            .withPlural("rayjobs")
            .withKind("RayJob")
            .build();

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final DatalakeJobRepository repository;
    private final ObsStsService obsStsService;
    private final DatalakeNamespaceManager nsManager;

    public RayJobRunner(KubernetesClient k8sClient,
                        LakeonProperties props,
                        DatalakeJobRepository repository,
                        ObsStsService obsStsService,
                        DatalakeNamespaceManager nsManager) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.repository = repository;
        this.obsStsService = obsStsService;
        this.nsManager = nsManager;
    }

    /**
     * Create a RayJob CRD resource via GenericKubernetesResource.
     * The RayJob spec follows KubeRay Operator's ray.io/v1 schema.
     */
    public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
        String ns = nsManager.ensureNamespace(job.getTenantId());
        String rayJobName = rayJobName(job);
        String image = resolveImage(req, "ray");

        // Create inline script ConfigMap if needed (HEAD pod only)
        boolean hasInlineScript = req.getInlineScript() != null && !req.getInlineScript().isBlank();
        if (hasInlineScript) {
            createScriptConfigMap(ns, job.getId(), req.getInlineScript());
        }

        // Build the RayJob spec as a nested Map structure
        Map<String, Object> rayJobSpec = buildRayJobSpec(req, image, job.getTenantId(), hasInlineScript, job.getId());

        // Create GenericKubernetesResource
        GenericKubernetesResource resource = new GenericKubernetesResourceBuilder()
            .withNewMetadata()
                .withName(rayJobName)
                .withNamespace(ns)
                .addToLabels("lakeon.io/tenant-id", job.getTenantId())
                .addToLabels("lakeon.io/job-id", job.getId())
            .endMetadata()
            .build();
        resource.setAdditionalProperty("spec", rayJobSpec);

        // Set apiVersion and kind manually since GenericKubernetesResource needs them
        resource.setApiVersion("ray.io/v1");
        resource.setKind("RayJob");

        k8sClient.genericKubernetesResources(RAY_JOB_CONTEXT)
            .inNamespace(ns)
            .resource(resource)
            .create();

        log.info("Created RayJob: {}/{}", ns, rayJobName);

        job.setRayJobName(rayJobName);
        job.setCciNamespace(ns);
        job.setStatus(DatalakeJobStatus.STARTING);
        repository.save(job);
    }

    private Map<String, Object> buildRayJobSpec(DatalakeJobRequest req, String image, String tenantId,
                                                 boolean hasInlineScript, String jobId) {
        // entrypoint: inline script wins, then explicit entrypoint
        String entrypoint;
        if (hasInlineScript) {
            entrypoint = "python /app/main.py";
        } else if (req.getEntrypoint() != null && !req.getEntrypoint().isBlank()) {
            entrypoint = req.getEntrypoint();
        } else {
            entrypoint = "echo 'no entrypoint'";
        }

        // OBS STS credentials for runtime_env env_vars
        ObsStsService.StsCredentials stsCreds = obsStsService.getCredentials(tenantId);
        String obsEndpoint = props.getObs().getEndpoint();

        // Build runtime_env YAML string (pip packages + env vars)
        String runtimeEnvYaml = buildRuntimeEnvYaml(req, stsCreds, obsEndpoint);

        // Head group resources
        Map<String, Object> headSpecMap = req.getHead();
        String headCpu = enforceCciMinCpu(headSpecMap != null ? String.valueOf(headSpecMap.getOrDefault("cpu", "2")) : "2");
        String headMemory = headSpecMap != null ? String.valueOf(headSpecMap.getOrDefault("memory", "4Gi")) : "4Gi";
        Map<String, Object> headResources = new LinkedHashMap<>();
        headResources.put("requests", Map.of("cpu", headCpu, "memory", headMemory));
        headResources.put("limits", Map.of("cpu", headCpu, "memory", headMemory));

        // Worker group resources
        Map<String, Object> workersSpecMap = req.getWorkers();
        int workerCount = workersSpecMap != null ? ((Number) workersSpecMap.getOrDefault("replicas",
                workersSpecMap.getOrDefault("count", 2))).intValue() : 2;
        String workerCpu = enforceCciMinCpu(workersSpecMap != null ? String.valueOf(workersSpecMap.getOrDefault("cpu", "2")) : "2");
        String workerMemory = workersSpecMap != null ? String.valueOf(workersSpecMap.getOrDefault("memory", "4Gi")) : "4Gi";
        Map<String, Object> workerResources = new LinkedHashMap<>();
        workerResources.put("requests", Map.of("cpu", workerCpu, "memory", workerMemory));
        workerResources.put("limits", Map.of("cpu", workerCpu, "memory", workerMemory));

        // VK nodeSelector + tolerations for CCI
        Map<String, String> nodeSelector = Map.of(
            props.getDatalake().getVkNodeSelectorKey(), props.getDatalake().getVkNodeSelectorValue()
        );
        List<Map<String, Object>> tolerations = List.of(
            Map.of("key", "virtual-kubelet.io/provider", "operator", "Exists", "effect", "NoSchedule")
        );

        // imagePullSecrets list (from config, same pattern as PythonJobRunner)
        List<Map<String, String>> imagePullSecrets = props.getK8s().getImagePullSecrets().stream()
            .filter(name -> name != null && !name.isBlank())
            .map(name -> Map.of("name", name))
            .toList();

        // Head container (with optional ConfigMap volumeMount)
        Map<String, Object> headContainer = new LinkedHashMap<>();
        headContainer.put("name", "ray-head");
        headContainer.put("image", image);
        headContainer.put("resources", headResources);
        if (hasInlineScript) {
            headContainer.put("volumeMounts", List.of(Map.of(
                "name", "script-vol",
                "mountPath", "/app/main.py",
                "subPath", "main.py",
                "readOnly", true
            )));
        }

        // Worker container (no ConfigMap mount)
        Map<String, Object> workerContainer = new LinkedHashMap<>();
        workerContainer.put("name", "ray-worker");
        workerContainer.put("image", image);
        workerContainer.put("resources", workerResources);

        // Head pod spec
        Map<String, Object> headPodSpec = new LinkedHashMap<>();
        headPodSpec.put("nodeSelector", nodeSelector);
        headPodSpec.put("tolerations", tolerations);
        headPodSpec.put("imagePullSecrets", imagePullSecrets);
        headPodSpec.put("containers", List.of(headContainer));
        if (hasInlineScript) {
            String cmName = "dl-script-" + jobId.replace("_", "-");
            headPodSpec.put("volumes", List.of(Map.of(
                "name", "script-vol",
                "configMap", Map.of("name", cmName)
            )));
        }

        // Worker pod spec
        Map<String, Object> workerPodSpec = new LinkedHashMap<>();
        workerPodSpec.put("nodeSelector", nodeSelector);
        workerPodSpec.put("tolerations", tolerations);
        workerPodSpec.put("imagePullSecrets", imagePullSecrets);
        workerPodSpec.put("containers", List.of(workerContainer));

        // Build full RayJob spec
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("entrypoint", entrypoint);
        spec.put("shutdownAfterJobFinishes", true);
        spec.put("ttlSecondsAfterFinished", 300);
        spec.put("runtimeEnvYAML", runtimeEnvYaml);
        spec.put("rayClusterSpec", Map.of(
            "headGroupSpec", Map.of(
                "rayStartParams", Map.of("dashboard-host", "0.0.0.0"),
                "template", Map.of(
                    "spec", headPodSpec
                )
            ),
            "workerGroupSpecs", List.of(Map.of(
                "replicas", workerCount,
                "minReplicas", workerCount,
                "maxReplicas", workerCount,
                "groupName", "worker-group",
                "template", Map.of(
                    "spec", workerPodSpec
                )
            ))
        ));
        return spec;
    }

    /**
     * Builds a YAML string for Ray's runtime_env feature.
     * Includes pip packages (pyobsfs + user requirements) and env_vars for OBS access.
     */
    private String buildRuntimeEnvYaml(DatalakeJobRequest req, ObsStsService.StsCredentials stsCreds,
                                        String obsEndpoint) {
        StringBuilder sb = new StringBuilder();

        // pip packages
        sb.append("pip:\n");
        sb.append("  - pyobsfs\n");
        if (req.getRequirements() != null && !req.getRequirements().isBlank()) {
            String[] pkgs = req.getRequirements().trim().split("\\s+");
            for (String pkg : pkgs) {
                if (!pkg.isBlank()) {
                    sb.append("  - ").append(pkg).append("\n");
                }
            }
        }

        // env_vars
        sb.append("env_vars:\n");
        sb.append("  OBS_ACCESS_KEY_ID: \"").append(escape(stsCreds.accessKey())).append("\"\n");
        sb.append("  OBS_SECRET_ACCESS_KEY: \"").append(escape(stsCreds.secretKey())).append("\"\n");
        sb.append("  OBS_SECURITY_TOKEN: \"").append(escape(stsCreds.sessionToken())).append("\"\n");
        sb.append("  OBS_ENDPOINT: \"").append(escape(obsEndpoint)).append("\"\n");

        // Pass through user-supplied env vars (DATASET_PATH, OUTPUT_PATH, etc.)
        if (req.getEnvVars() != null) {
            req.getEnvVars().forEach((k, v) ->
                sb.append("  ").append(k).append(": \"").append(escape(v)).append("\"\n")
            );
        }

        return sb.toString();
    }

    /** Escapes double-quotes and backslashes for YAML string values. */
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Enforces CCI minimum CPU of 250m.
     * If the given value is a plain number (e.g. "1", "2"), it is returned as-is (already >= 1 core).
     * If it's a millicpu value (e.g. "200m"), it is bumped up to "250m".
     */
    private String enforceCciMinCpu(String cpu) {
        if (cpu == null || cpu.isBlank()) return "250m";
        if (cpu.endsWith("m")) {
            try {
                int millis = Integer.parseInt(cpu.substring(0, cpu.length() - 1));
                if (millis < 250) return "250m";
            } catch (NumberFormatException ignored) {}
        }
        return cpu;
    }

    /** Creates a ConfigMap containing the inline script as main.py (HEAD pod only). */
    private void createScriptConfigMap(String ns, String jobId, String script) {
        String safeId = jobId.replace("_", "-");
        ConfigMap cm = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("dl-script-" + safeId)
                .withNamespace(ns)
            .endMetadata()
            .addToData("main.py", script)
            .build();
        k8sClient.configMaps().inNamespace(ns).resource(cm).create();
        log.info("Created script ConfigMap: {}/dl-script-{}", ns, safeId);
    }

    public void cancel(DatalakeJobEntity job) {
        String ns = job.getCciNamespace();
        String name = job.getRayJobName();
        if (ns != null && name != null) {
            try {
                GenericKubernetesResource existing = k8sClient.genericKubernetesResources(RAY_JOB_CONTEXT)
                    .inNamespace(ns).withName(name).get();
                if (existing != null) {
                    k8sClient.genericKubernetesResources(RAY_JOB_CONTEXT)
                        .inNamespace(ns).withName(name).delete();
                    log.info("Deleted RayJob: {}/{}", ns, name);
                }
            } catch (Exception e) {
                log.warn("Failed to delete RayJob {}/{}: {}", ns, name, e.getMessage());
            }
        }
        job.setStatus(DatalakeJobStatus.CANCELLED);
        repository.save(job);
    }

    private String rayJobName(DatalakeJobEntity job) {
        String name = "ray-" + job.getId().replace("_", "-");
        return name.length() > 63 ? name.substring(0, 63) : name;
    }

    private String resolveImage(DatalakeJobRequest req, String defaultKey) {
        String key = req.getImageKey() != null ? req.getImageKey() : defaultKey;
        return props.getDatalake().getPresetImages().getOrDefault(key,
            props.getDatalake().getPresetImages().getOrDefault(defaultKey, "ray:2.10-py311"));
    }
}
