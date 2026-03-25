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
        String imageKey = req.getImageKey() != null ? req.getImageKey() : "python-data";
        String image = dl.getPresetImages().getOrDefault(imageKey,
                dl.getPresetImages().getOrDefault("python-data", "python:3.11-slim"));

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

            // Copy SWR image pull secret into new namespace
            String sourceNs = props.getK8s().getNamespace();
            try {
                var srcSecret = k8sClient.secrets().inNamespace(sourceNs).withName("swr-secret").get();
                if (srcSecret != null) {
                    var newSecret = new io.fabric8.kubernetes.api.model.SecretBuilder()
                        .withNewMetadata().withName("swr-secret").withNamespace(ns).endMetadata()
                        .withType(srcSecret.getType())
                        .withData(srcSecret.getData())
                        .build();
                    k8sClient.secrets().inNamespace(ns).resource(newSecret).createOrReplace();
                    // Patch default SA with imagePullSecrets
                    k8sClient.serviceAccounts().inNamespace(ns).withName("default")
                        .edit(sa -> new io.fabric8.kubernetes.api.model.ServiceAccountBuilder(sa)
                            .withImagePullSecrets(
                                new io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder()
                                    .withName("swr-secret").build())
                            .build());
                    log.info("Copied swr-secret to namespace: {}", ns);
                }
            } catch (Exception e) {
                log.warn("Failed to copy swr-secret to {}: {}", ns, e.getMessage());
            }
        }

        // 3. Determine command (prepend pip install if requirements specified)
        String pipInstall = "";
        if (req.getRequirements() != null && !req.getRequirements().isBlank()) {
            // requirements is a space/newline-separated list of packages, e.g. "scikit-learn matplotlib"
            String pkgs = req.getRequirements().trim().replaceAll("\\s+", " ");
            pipInstall = "pip install --no-cache-dir " + pkgs + " && ";
        }

        // Build the log upload wrapper:
        // Run user script, tee output to /tmp/job.log, then upload log to OBS
        String logKey = "datalake-logs/" + job.getTenantId() + "/" + job.getId() + "/output.log";
        String logUpload = String.format(
            "; EXIT_CODE=$?; python -c \""
            + "import boto3,os; "
            + "s3=boto3.client('s3',endpoint_url=os.environ.get('OBS_ENDPOINT',''),aws_access_key_id=os.environ.get('OBS_ACCESS_KEY',''),aws_secret_access_key=os.environ.get('OBS_SECRET_KEY',''),aws_session_token=os.environ.get('OBS_SESSION_TOKEN'),region_name=os.environ.get('OBS_REGION','cn-north-4')); "
            + "s3.upload_file('/tmp/job.log',os.environ.get('OBS_BUCKET',''),'%s')"
            + "\" 2>/dev/null; exit $EXIT_CODE", logKey);

        List<String> command;
        boolean hasInlineScript = req.getInlineScript() != null && !req.getInlineScript().isBlank();
        String userCmd;
        if (hasInlineScript) {
            createScriptConfigMap(ns, job.getId(), req.getInlineScript());
            userCmd = pipInstall + "python /app/main.py";
        } else if (req.getEntrypoint() != null && !req.getEntrypoint().isBlank()) {
            userCmd = pipInstall + req.getEntrypoint().trim();
        } else {
            userCmd = "echo 'No script or entrypoint specified'";
        }
        // Wrap: run user cmd with tee to capture logs, then upload
        command = List.of("/bin/sh", "-c", "(" + userCmd + ") 2>&1 | tee /tmp/job.log" + logUpload);

        // Pre-set logObsPath so DatalakeLogService can find it
        job.setLogObsPath(logKey);

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
        String obsEndpoint = props.getObs().getEndpoint();
        String obsRegion = props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4";
        // OBS_* for our scripts (export_parquet.py, log upload)
        envVars.add(new EnvVarBuilder().withName("OBS_ACCESS_KEY").withValue(stsCreds.accessKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SECRET_KEY").withValue(stsCreds.secretKey()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_SESSION_TOKEN").withValue(stsCreds.sessionToken()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_ENDPOINT").withValue(obsEndpoint).build());
        envVars.add(new EnvVarBuilder().withName("OBS_BUCKET").withValue(props.getObs().getBucket()).build());
        envVars.add(new EnvVarBuilder().withName("OBS_REGION").withValue(obsRegion).build());
        // AWS_* for pandas/s3fs/boto3 auto-detection (reads s3:// paths automatically)
        envVars.add(new EnvVarBuilder().withName("AWS_ACCESS_KEY_ID").withValue(stsCreds.accessKey()).build());
        envVars.add(new EnvVarBuilder().withName("AWS_SECRET_ACCESS_KEY").withValue(stsCreds.secretKey()).build());
        envVars.add(new EnvVarBuilder().withName("AWS_SESSION_TOKEN").withValue(stsCreds.sessionToken()).build());
        envVars.add(new EnvVarBuilder().withName("AWS_ENDPOINT_URL").withValue(obsEndpoint).build());
        envVars.add(new EnvVarBuilder().withName("AWS_DEFAULT_REGION").withValue(obsRegion).build());

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
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder().withName(name).build())
                        .toList())
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
