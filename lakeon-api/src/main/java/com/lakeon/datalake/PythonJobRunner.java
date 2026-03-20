package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
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

    public PythonJobRunner(KubernetesClient k8sClient,
                           LakeonProperties props,
                           DatalakeJobRepository repository) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.repository = repository;
    }

    public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();

        // 1. Determine image
        String imageKey = req.getImageKey() != null ? req.getImageKey() : "python-slim";
        String image = dl.getPresetImages().getOrDefault(imageKey,
                dl.getPresetImages().getOrDefault("python-slim",
                        "python:3.11-slim"));

        // 2. Build namespace and job name
        String ns = dl.getCciNamespacePrefix() + job.getTenantId();
        String jobName = k8sJobName(job);

        // 3. Build command from entrypoint
        List<String> command = new ArrayList<>();
        if (req.getEntrypoint() != null && !req.getEntrypoint().isBlank()) {
            command.addAll(Arrays.asList(req.getEntrypoint().trim().split("\\s+")));
        }

        // 4. Build resource requests/limits
        Map<String, String> resources = req.getResources() != null ? req.getResources() : Map.of();
        String cpu = resources.getOrDefault("cpu", "1");
        String memory = resources.getOrDefault("memory", "2Gi");

        // 5. Build env vars
        List<EnvVar> envVars = new ArrayList<>();
        if (req.getEnvVars() != null) {
            req.getEnvVars().forEach((k, v) ->
                    envVars.add(new EnvVarBuilder().withName(k).withValue(v).build()));
        }

        // 6. Build toleration for VK
        Toleration vkToleration = new TolerationBuilder()
                .withKey("virtual-kubelet.io/provider")
                .withOperator("Exists")
                .build();

        // 7. Build the Job spec
        var containerBuilder = new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("python-job")
                .withImage(image)
                .withEnv(envVars)
                .withNewResources()
                    .withRequests(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)
                    ))
                    .withLimits(Map.of(
                            "cpu", new Quantity(cpu),
                            "memory", new Quantity(memory)
                    ))
                .endResources();

        if (!command.isEmpty()) {
            containerBuilder.withCommand(command);
        }

        var podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withNodeSelector(Map.of(
                        dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()
                ))
                .withTolerations(vkToleration)
                .withContainers(containerBuilder.build());

        var podTemplateSpec = new PodTemplateSpecBuilder()
                .withNewMetadata()
                    .withLabels(Map.of(
                            "app", "datalake-job",
                            "lakeon.io/job-id", job.getId(),
                            "lakeon.io/tenant-id", job.getTenantId()
                    ))
                .endMetadata()
                .withSpec(podSpecBuilder.build())
                .build();

        var jobSpecBuilder = new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
                .withBackoffLimit(0)
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
                            "lakeon.io/tenant-id", job.getTenantId()
                    ))
                .endMetadata()
                .withSpec(jobSpecBuilder.build())
                .build();

        // 8. Create the Job
        k8sClient.batch().v1().jobs().inNamespace(ns).resource(k8sJob).create();
        log.info("Created K8s Job: {}/{}", ns, jobName);

        // 9. Update entity
        job.setK8sJobName(jobName);
        job.setCciNamespace(ns);
        job.setStatus(DatalakeJobStatus.STARTING);
        repository.save(job);
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
        String name = "dl-" + job.getId();
        return name.length() > 63 ? name.substring(0, 63) : name;
    }
}
