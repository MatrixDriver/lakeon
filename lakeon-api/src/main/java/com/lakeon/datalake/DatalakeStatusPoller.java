package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetSourceType;
import com.lakeon.dataset.DatasetStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class DatalakeStatusPoller {
    private static final Logger log = LoggerFactory.getLogger(DatalakeStatusPoller.class);

    private final KubernetesClient k8sClient;
    private final DatalakeJobRepository repository;
    private final DatasetRepository datasetRepository;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    public DatalakeStatusPoller(KubernetesClient k8sClient, DatalakeJobRepository repository,
                                DatasetRepository datasetRepository, LakeonProperties props, ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.repository = repository;
        this.datasetRepository = datasetRepository;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Poll active jobs (STARTING or RUNNING) and sync status from K8s.
     * Runs on a fixed delay from application.yml pollIntervalMs.
     */
    @Scheduled(fixedDelayString = "${lakeon.datalake.poll-interval-ms:10000}")
    public void poll() {
        List<DatalakeJobEntity> activeJobs = repository.findByStatusIn(
            List.of(DatalakeJobStatus.STARTING, DatalakeJobStatus.RUNNING)
        );

        for (DatalakeJobEntity job : activeJobs) {
            try {
                syncJobStatus(job);
            } catch (Exception e) {
                log.warn("Failed to sync status for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    private void syncJobStatus(DatalakeJobEntity job) {
        if (job.getType() == DatalakeJobType.PYTHON) {
            syncPythonJobStatus(job);
        } else if (job.getType() == DatalakeJobType.RAY || job.getType() == DatalakeJobType.FINETUNE) {
            syncRayJobStatus(job);
        }
    }

    /**
     * Sync status from a K8s Job (batch/v1).
     */
    private void syncPythonJobStatus(DatalakeJobEntity job) {
        if (job.getK8sJobName() == null || job.getCciNamespace() == null) return;

        io.fabric8.kubernetes.api.model.batch.v1.Job k8sJob = k8sClient.batch().v1().jobs()
            .inNamespace(job.getCciNamespace())
            .withName(job.getK8sJobName())
            .get();

        if (k8sJob == null) {
            // Job was deleted externally or not yet created
            return;
        }

        io.fabric8.kubernetes.api.model.batch.v1.JobStatus status = k8sJob.getStatus();
        if (status == null) return;

        // K8s Job: succeeded > 0 → SUCCEEDED, failed > 0 → FAILED, active > 0 → RUNNING
        boolean changed = false;
        if (status.getSucceeded() != null && status.getSucceeded() > 0) {
            if (job.getStatus() != DatalakeJobStatus.SUCCEEDED) {
                deleteScriptConfigMap(job);
                job.setStatus(DatalakeJobStatus.SUCCEEDED);
                job.setFinishedAt(java.time.Instant.now());
                registerOutputDataset(job);
                changed = true;
            }
        } else if (status.getFailed() != null && status.getFailed() > 0) {
            if (job.getStatus() != DatalakeJobStatus.FAILED) {
                deleteScriptConfigMap(job);
                job.setStatus(DatalakeJobStatus.FAILED);
                job.setFinishedAt(java.time.Instant.now());
                // Try to get error from conditions, translate K8s messages for users
                if (status.getConditions() != null) {
                    status.getConditions().stream()
                        .filter(c -> "Failed".equals(c.getType()))
                        .findFirst()
                        .ifPresent(c -> {
                            String msg = c.getMessage();
                            if (msg != null && msg.contains("backoff limit")) {
                                msg = "脚本执行出错（退出码非 0），请查看运行日志";
                            }
                            job.setErrorMessage(msg);
                        });
                }
                changed = true;
            }
        } else if (status.getActive() != null && status.getActive() > 0) {
            if (job.getStatus() == DatalakeJobStatus.STARTING) {
                job.setStatus(DatalakeJobStatus.RUNNING);
                job.setStartedAt(java.time.Instant.now());
                changed = true;
            }
        }

        if (changed) {
            // Persist logs to OBS before pod is cleaned up
            persistJobLogs(job);
            repository.save(job);
        }
    }

    /**
     * Sync status from a RayJob CRD.
     */
    private void syncRayJobStatus(DatalakeJobEntity job) {
        if (job.getRayJobName() == null || job.getCciNamespace() == null) return;

        io.fabric8.kubernetes.api.model.GenericKubernetesResource rayJob =
            k8sClient.genericKubernetesResources(RayJobRunner.RAY_JOB_CONTEXT)
                .inNamespace(job.getCciNamespace())
                .withName(job.getRayJobName())
                .get();

        if (rayJob == null) return;

        // RayJob status.jobStatus field: "PENDING", "RUNNING", "SUCCEEDED", "FAILED"
        // Access via additionalProperties
        @SuppressWarnings("unchecked")
        Map<String, Object> statusMap = (Map<String, Object>) rayJob.getAdditionalProperties().get("status");
        if (statusMap == null) return;

        String rayStatus = (String) statusMap.get("jobStatus");
        if (rayStatus == null) return;

        boolean changed = false;
        switch (rayStatus) {
            case "RUNNING" -> {
                if (job.getStatus() == DatalakeJobStatus.STARTING || job.getStatus() == DatalakeJobStatus.PENDING) {
                    job.setStatus(DatalakeJobStatus.RUNNING);
                    job.setStartedAt(java.time.Instant.now());
                    changed = true;
                }
            }
            case "SUCCEEDED" -> {
                if (job.getStatus() != DatalakeJobStatus.SUCCEEDED) {
                    job.setStatus(DatalakeJobStatus.SUCCEEDED);
                    if (job.getStartedAt() == null) job.setStartedAt(job.getCreatedAt());
                    job.setFinishedAt(java.time.Instant.now());
                    registerOutputDataset(job);
                    changed = true;
                }
            }
            case "FAILED" -> {
                if (job.getStatus() != DatalakeJobStatus.FAILED) {
                    job.setStatus(DatalakeJobStatus.FAILED);
                    if (job.getStartedAt() == null) job.setStartedAt(job.getCreatedAt());
                    job.setFinishedAt(java.time.Instant.now());
                    Object message = statusMap.get("message");
                    if (message != null) job.setErrorMessage(message.toString());
                    changed = true;
                }
            }
        }

        if (changed) {
            persistJobLogs(job);
            repository.save(job);
        }
    }

    /**
     * Capture pod logs and upload to OBS for persistence.
     * Must be called before pod cleanup (while pod still exists).
     */
    private void persistJobLogs(DatalakeJobEntity job) {
        if (job.getCciNamespace() == null) return;
        try {
            // Find pods for this job
            List<Pod> pods;
            if (job.getK8sJobName() != null) {
                pods = k8sClient.pods().inNamespace(job.getCciNamespace())
                    .withLabel("job-name", job.getK8sJobName())
                    .list().getItems();
            } else if (job.getRayJobName() != null) {
                pods = k8sClient.pods().inNamespace(job.getCciNamespace())
                    .withLabel("lakeon.io/job-id", job.getId())
                    .list().getItems();
            } else {
                return;
            }

            if (pods.isEmpty()) return;

            // Collect logs from all pods
            StringBuilder allLogs = new StringBuilder();
            for (Pod pod : pods) {
                String podName = pod.getMetadata().getName();
                if (pods.size() > 1) {
                    allLogs.append("=== Pod: ").append(podName).append(" ===\n");
                }
                try {
                    String podLog = k8sClient.pods()
                        .inNamespace(job.getCciNamespace())
                        .withName(podName)
                        .getLog();
                    if (podLog != null) {
                        allLogs.append(podLog);
                        if (!podLog.endsWith("\n")) allLogs.append("\n");
                    }
                } catch (Exception e) {
                    allLogs.append("[failed to get logs: ").append(e.getMessage()).append("]\n");
                }
            }

            if (allLogs.isEmpty()) return;

            // Upload to OBS
            String obsKey = "datalake-logs/" + job.getTenantId() + "/" + job.getId() + "/output.log";
            String ak = props.getObs().getAccessKey();
            String sk = props.getObs().getSecretKey();
            if (ak == null || ak.isBlank()) return;

            try (S3Client s3 = S3Client.builder()
                    .endpointOverride(URI.create(props.getObs().getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk)))
                    .region(Region.of(props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4"))
                    .build()) {

                byte[] bytes = allLogs.toString().getBytes(StandardCharsets.UTF_8);
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(props.getObs().getBucket())
                        .key(obsKey)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                    RequestBody.fromBytes(bytes));

                job.setLogObsPath(obsKey);
                log.info("Persisted logs for job {} to OBS: {} ({} bytes)", job.getId(), obsKey, bytes.length);
            }
        } catch (Exception e) {
            log.warn("Failed to persist logs for job {}: {}", job.getId(), e.getMessage());
        }
    }

    /**
     * Register the job's output as a new dataset if output was written.
     */
    private void registerOutputDataset(DatalakeJobEntity job) {
        try {
            String spec = job.getSpec();
            if (spec == null) return;
            var specMap = objectMapper.readValue(spec, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});

            // OUTPUT_PATH obs key is stored in the job entity spec indirectly
            // The OBS key pattern: tenant-{tenantId}/jobs/{jobId}/output/data.parquet
            String bucket = props.getObs().getBucket();
            String outputKey = "tenant-" + job.getTenantId() + "/jobs/" + job.getId() + "/output/data.parquet";

            // Check if file exists on OBS
            String ak = props.getObs().getAccessKey();
            String sk = props.getObs().getSecretKey();
            if (ak == null || ak.isBlank()) return;

            try (var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                    .endpointOverride(java.net.URI.create(props.getObs().getEndpoint()))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(ak, sk)))
                    .region(software.amazon.awssdk.regions.Region.of(
                            props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4"))
                    .build()) {

                var head = s3.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                        .bucket(bucket).key(outputKey).build());

                long fileSize = head.contentLength();

                // Try to read metadata (row_count, schema)
                String metaKey = "tenant-" + job.getTenantId() + "/jobs/" + job.getId() + "/output/_metadata.json";
                Long rowCount = null;
                String schemaJson = null;
                try {
                    var metaResp = s3.getObject(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                            .bucket(bucket).key(metaKey).build());
                    var meta = objectMapper.readValue(metaResp.readAllBytes(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                    if (meta.get("row_count") instanceof Number n) rowCount = n.longValue();
                    if (meta.get("schema") != null) schemaJson = objectMapper.writeValueAsString(meta.get("schema"));
                } catch (Exception ignored) {}

                // Create dataset entity
                DatasetEntity ds = new DatasetEntity();
                ds.setTenantId(job.getTenantId());
                ds.setName(job.getName() + "-output");
                ds.setDescription("由作业 " + job.getName() + " 生成");
                ds.setSourceType(DatasetSourceType.JOB_OUTPUT);
                ds.setObsPath(outputKey);
                ds.setFileSize(fileSize);
                ds.setRowCount(rowCount);
                ds.setSchemaJson(schemaJson);
                ds.setStatus(DatasetStatus.READY);
                ds.setJobId(job.getId());
                datasetRepository.save(ds);
                log.info("Registered output dataset '{}' for job {}", ds.getName(), job.getId());
            }
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            // No output file — job didn't write output, which is fine
            log.debug("No output file for job {}", job.getId());
        } catch (Exception e) {
            log.warn("Failed to register output dataset for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private void deleteScriptConfigMap(DatalakeJobEntity job) {
        if (job.getCciNamespace() == null) return;
        String cmName = "dl-script-" + job.getId().replace("_", "-");
        try {
            k8sClient.configMaps()
                    .inNamespace(job.getCciNamespace())
                    .withName(cmName)
                    .delete();
            log.debug("Deleted script ConfigMap: {}/{}", job.getCciNamespace(), cmName);
        } catch (Exception e) {
            log.warn("Failed to delete ConfigMap {}/{}: {}", job.getCciNamespace(), cmName, e.getMessage());
        }
    }
}
