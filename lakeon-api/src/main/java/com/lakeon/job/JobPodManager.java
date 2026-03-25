package com.lakeon.job;

import com.lakeon.config.LakeonProperties;
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

    @Value("${lakeon.api.internal-port:8088}")
    private int internalPort;

    public JobPodManager(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    /**
     * Launch a job Pod for the given JobEntity.
     * Creates a ConfigMap (job-{safePodId}-params with params.json) and a Pod.
     * Returns the pod name.
     */
    public String launchJobPod(JobEntity job) {
        String namespace = props.getK8s().getNamespace();
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

        // Build callback URL (uses http-internal service port, not the SSL server port)
        String callbackUrl = "http://lakeon-api.lakeon.svc.cluster.local:" + internalPort
                + "/api/v1/jobs/" + job.getId() + "/callback";

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
            .endMetadata()
            .withNewSpec()
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
                        .toList()
                )
                .withRestartPolicy("Never")
                .withNodeSelector(Map.of("lakeon/role", "compute"))
                .addNewContainer()
                    .withName("job")
                    .withImage(image)
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
                        .withNewValueFrom()
                            .withNewSecretKeyRef()
                                .withName("obs-credentials")
                                .withKey("access-key")
                            .endSecretKeyRef()
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withName("OBS_SECRET_KEY")
                        .withNewValueFrom()
                            .withNewSecretKeyRef()
                                .withName("obs-credentials")
                                .withKey("secret-key")
                            .endSecretKeyRef()
                        .endValueFrom()
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
        String namespace = props.getK8s().getNamespace();
        try {
            Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                return true;
            }
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
            return "Succeeded".equals(phase) || "Failed".equals(phase);
        } catch (Exception e) {
            log.warn("Error checking pod {} status: {}", podName, e.getMessage());
            return false;
        }
    }

    /**
     * Returns true if the pod exists (is not null).
     */
    public boolean podExists(String podName) {
        String namespace = props.getK8s().getNamespace();
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
        String namespace = props.getK8s().getNamespace();
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
