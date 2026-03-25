package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public RayJobRunner(KubernetesClient k8sClient,
                        LakeonProperties props,
                        DatalakeJobRepository repository,
                        ObsStsService obsStsService) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.repository = repository;
        this.obsStsService = obsStsService;
    }

    /**
     * Create a RayJob CRD resource via GenericKubernetesResource.
     * The RayJob spec follows KubeRay Operator's ray.io/v1 schema.
     */
    public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
        String ns = props.getDatalake().getCciNamespacePrefix() + job.getTenantId();
        String rayJobName = rayJobName(job);
        String image = resolveImage(req, "ray");

        // Build the RayJob spec as a nested Map structure
        Map<String, Object> rayJobSpec = buildRayJobSpec(req, image, job.getTenantId());

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

    private Map<String, Object> buildRayJobSpec(DatalakeJobRequest req, String image, String tenantId) {
        // entrypoint
        String entrypoint = req.getEntrypoint() != null ? req.getEntrypoint() : "echo 'no entrypoint'";

        // Head group resources
        Map<String, Object> headResources = new LinkedHashMap<>();
        Map<String, Object> headSpec = req.getHead();
        String headCpu = headSpec != null ? String.valueOf(headSpec.getOrDefault("cpu", "2")) : "2";
        String headMemory = headSpec != null ? String.valueOf(headSpec.getOrDefault("memory", "4Gi")) : "4Gi";
        headResources.put("requests", Map.of("cpu", headCpu, "memory", headMemory));
        headResources.put("limits", Map.of("cpu", headCpu, "memory", headMemory));

        // Worker group resources
        Map<String, Object> workerSpec = req.getWorkers();
        int workerCount = workerSpec != null ? ((Number) workerSpec.getOrDefault("count", 1)).intValue() : 1;
        String workerCpu = workerSpec != null ? String.valueOf(workerSpec.getOrDefault("cpu", "2")) : "2";
        String workerMemory = workerSpec != null ? String.valueOf(workerSpec.getOrDefault("memory", "4Gi")) : "4Gi";
        Map<String, Object> workerResources = new LinkedHashMap<>();
        workerResources.put("requests", Map.of("cpu", workerCpu, "memory", workerMemory));
        workerResources.put("limits", Map.of("cpu", workerCpu, "memory", workerMemory));

        // VK nodeSelector + toleration for all pod groups
        Map<String, String> nodeSelector = Map.of(
            props.getDatalake().getVkNodeSelectorKey(), props.getDatalake().getVkNodeSelectorValue()
        );
        List<Map<String, Object>> tolerations = List.of(
            Map.of("key", "virtual-kubelet.io/provider", "operator", "Exists", "effect", "NoSchedule")
        );

        // OBS STS env vars for tenant isolation
        ObsStsService.StsCredentials stsCreds = obsStsService.getCredentials(tenantId);
        List<Map<String, String>> obsEnv = List.of(
            Map.of("name", "OBS_ACCESS_KEY",    "value", stsCreds.accessKey()),
            Map.of("name", "OBS_SECRET_KEY",    "value", stsCreds.secretKey()),
            Map.of("name", "OBS_SESSION_TOKEN", "value", stsCreds.sessionToken()),
            Map.of("name", "OBS_ENDPOINT",      "value", props.getObs().getEndpoint()),
            Map.of("name", "OBS_BUCKET",        "value", props.getObs().getBucket()),
            Map.of("name", "OBS_REGION",        "value",
                props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4")
        );

        // Head container
        Map<String, Object> headContainer = new LinkedHashMap<>();
        headContainer.put("name", "ray-head");
        headContainer.put("image", image);
        headContainer.put("resources", headResources);
        headContainer.put("env", obsEnv);

        // Worker container
        Map<String, Object> workerContainer = new LinkedHashMap<>();
        workerContainer.put("name", "ray-worker");
        workerContainer.put("image", image);
        workerContainer.put("resources", workerResources);
        workerContainer.put("env", obsEnv);

        // Build the full RayJob spec
        return Map.of(
            "entrypoint", entrypoint,
            "shutdownAfterJobFinishes", true,
            "ttlSecondsAfterFinished", 300,
            "rayClusterSpec", Map.of(
                "headGroupSpec", Map.of(
                    "rayStartParams", Map.of("dashboard-host", "0.0.0.0"),
                    "template", Map.of(
                        "spec", Map.of(
                            "nodeSelector", nodeSelector,
                            "tolerations", tolerations,
                            "containers", List.of(headContainer)
                        )
                    )
                ),
                "workerGroupSpecs", List.of(Map.of(
                    "replicas", workerCount,
                    "minReplicas", workerCount,
                    "maxReplicas", workerCount,
                    "groupName", "worker-group",
                    "template", Map.of(
                        "spec", Map.of(
                            "nodeSelector", nodeSelector,
                            "tolerations", tolerations,
                            "containers", List.of(workerContainer)
                        )
                    )
                ))
            )
        );
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
        String name = "ray-" + job.getId();
        return name.length() > 63 ? name.substring(0, 63) : name;
    }

    private String resolveImage(DatalakeJobRequest req, String defaultKey) {
        String key = req.getImageKey() != null ? req.getImageKey() : defaultKey;
        return props.getDatalake().getPresetImages().getOrDefault(key,
            props.getDatalake().getPresetImages().getOrDefault(defaultKey, "ray:2.10-py311"));
    }
}
