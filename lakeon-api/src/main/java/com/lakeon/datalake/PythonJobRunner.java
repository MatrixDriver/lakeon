package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class PythonJobRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonJobRunner.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final DatalakeJobRepository repository;
    private final ObsStsService obsStsService;

    public PythonJobRunner(KubernetesClient k8sClient,
                           LakeonProperties props,
                           DatalakeJobRepository repository,
                           ObsStsService obsStsService) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.repository = repository;
        this.obsStsService = obsStsService;
    }

    public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();

        // 1. Determine image
        String imageKey = req.getImageKey() != null ? req.getImageKey() : "python-slim";
        String image = dl.getPresetImages().getOrDefault(imageKey,
                dl.getPresetImages().getOrDefault("python-slim", "python:3.11-slim"));

        // 2. Build namespace and job name
        String ns = dl.getCciNamespacePrefix() + job.getTenantId().replace("_", "-");
        String jobName = k8sJobName(job);

        // 2.5. Ensure namespace exists (must be before configmap/job creation)
        if (k8sClient.namespaces().withName(ns).get() == null) {
            k8sClient.namespaces().resource(
                new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                    .withNewMetadata().withName(ns)
                        .addToLabels("app", "datalake")
                        .addToLabels("lakeon.io/tenant-id", job.getTenantId())
                    .endMetadata()
                    .build()
            ).create();
            log.info("Created namespace: {}", ns);
        }

        // 3. Determine command
        List<String> command;
        boolean hasInlineScript = req.getInlineScript() != null && !req.getInlineScript().isBlank();
        if (hasInlineScript) {
            createScriptConfigMap(ns, job.getId(), req.getInlineScript());
            command = List.of("/bin/sh", "-c", "python /app/main.py");
        } else if (req.getEntrypoint() != null && !req.getEntrypoint().isBlank()) {
            command = List.of("/bin/sh", "-c", req.getEntrypoint().trim());
        } else {
            command = List.of();
        }

        // 4. Build resource requests/limits
        Map<String, String> resources = req.getResources() != null ? req.getResources() : Map.of();
        String cpu = resources.getOrDefault("cpu", "1");
        String memory = resources.getOrDefault("memory", "2Gi");

        // 5. Build env vars: user-defined + auto-injected OUTPUT_PATH
        List<EnvVar> envVars = new ArrayList<>();
        if (req.getEnvVars() != null) {
            req.getEnvVars().forEach((k, v) ->
                    envVars.add(new EnvVarBuilder().withName(k).withValue(v).build()));
        }
        String outputPath = req.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            String bucket = props.getObs().getBucket();
            outputPath = "s3://" + bucket + "/tenant-" + job.getTenantId()
                    + "/jobs/" + job.getId() + "/output/";
        }
        envVars.add(new EnvVarBuilder().withName("OUTPUT_PATH").withValue(outputPath).build());

        // Inject OBS STS credentials for tenant isolation
        ObsStsService.StsCredentials stsCreds = obsStsService.getCredentials(job.getTenantId());
        envVars.add(new EnvVarBuilder().withName("OBS_ACCESS_KEY").withValue(stsCreds.accessKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SECRET_KEY").withValue(stsCreds.secretKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SESSION_TOKEN").withValue(stsCreds.sessionToken()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_ENDPOINT").withValue(props.getObs().getEndpoint()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_BUCKET").withValue(props.getObs().getBucket()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_REGION")
                .withValue(props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4").build());

        // 6. Build toleration for VK
        Toleration vkToleration = new TolerationBuilder()
                .withKey("virtual-kubelet.io/provider")
                .withOperator("Exists")
                .build();

        // 7. Build container
        var containerBuilder = new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("python-job")
                .withImage(image)
                .withEnv(envVars)
                .withNewResources()
                    .withRequests(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)))
                    .withLimits(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)))
                .endResources();

        if (!command.isEmpty()) {
            containerBuilder.withCommand(command);
        }

        if (hasInlineScript) {
            containerBuilder.withVolumeMounts(new VolumeMountBuilder()
                    .withName("script-vol")
                    .withMountPath("/app/main.py")
                    .withSubPath("main.py")
                    .withReadOnly(true)
                    .build());
        }

        // 8. Build pod spec
        var podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withNodeSelector(Map.of(
                        dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
                .withTolerations(vkToleration)
                .withContainers(containerBuilder.build());

        if (hasInlineScript) {
            podSpecBuilder.withVolumes(new VolumeBuilder()
                    .withName("script-vol")
                    .withNewConfigMap()
                        .withName("dl-script-" + job.getId().replace("_", "-"))
                    .endConfigMap()
                    .build());
        }

        var podTemplateSpec = new PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withLabels(Map.of(
                            "app", "datalake-job",
                            "lakeon.io/job-id", job.getId(),
                            "lakeon.io/tenant-id", job.getTenantId()))
                .endMetadata()
                .withSpec(podSpecBuilder.build())
                .build();

        // 9. Build Job spec with retry_count → backoffLimit
        var jobSpecBuilder = new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
                .withBackoffLimit(req.getRetryCount())
                .withTemplate(podTemplateSpec);

        if (req.getTimeoutSeconds() != null) {
            jobSpecBuilder.withActiveDeadlineSeconds(req.getTimeoutSeconds().longValue());
        }

        Job k8sJob = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(ns)
                    .withLabels(Map.of(
                            "app", "datalake-job",
                            "lakeon.io/job-id", job.getId(),
                            "lakeon.io/tenant-id", job.getTenantId()))
                .endMetadata()
                .withSpec(jobSpecBuilder.build())
                .build();

        // 10. Create the Job (namespace already ensured in step 2.5)
        k8sClient.batch().v1().jobs().inNamespace(ns).resource(k8sJob).create();
        log.info("Created K8s Job: {}/{}", ns, jobName);

        // 12. Update entity
        job.setK8sJobName(jobName);
        job.setCciNamespace(ns);
        job.setStatus(DatalakeJobStatus.STARTING);
        repository.save(job);
    }

    /** Creates a ConfigMap containing the inline script as main.py */
    private void createScriptConfigMap(String ns, String jobId, String script) {
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("dl-script-" + jobId)
                    .withNamespace(ns)
                .endMetadata()
                .addToData("main.py", script)
                .build();
        k8sClient.configMaps().inNamespace(ns).resource(cm).create();
        log.info("Created script ConfigMap: {}/dl-script-{}", ns, jobId);
    }

    public void cancel(DatalakeJobEntity job) {
        String ns = job.getCciNamespace();
        String jobName = job.getK8sJobName();

        if (ns != null && jobName != null) {
            try {
                k8sClient.batch().v1().jobs().inNamespace(ns).withName(jobName).delete();
                log.info("Deleted K8s Job: {}/{}", ns, jobName);
            } catch (Exception e) {
                log.warn("Failed to delete K8s Job {}/{}: {}", ns, jobName, e.getMessage());
            }
        }

        job.setStatus(DatalakeJobStatus.CANCELLED);
        repository.save(job);
    }

    private String k8sJobName(DatalakeJobEntity job) {
        String name = "dl-" + job.getId().replace("_", "-");
        return name.length() > 63 ? name.substring(0, 63) : name;
    }
}
