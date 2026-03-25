package com.lakeon.datalake;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatalakeStatusPoller {
    private static final Logger log = LoggerFactory.getLogger(DatalakeStatusPoller.class);

    private final KubernetesClient k8sClient;
    private final DatalakeJobRepository repository;

    public DatalakeStatusPoller(KubernetesClient k8sClient, DatalakeJobRepository repository) {
        this.k8sClient = k8sClient;
        this.repository = repository;
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
                changed = true;
            }
        } else if (status.getFailed() != null && status.getFailed() > 0) {
            if (job.getStatus() != DatalakeJobStatus.FAILED) {
                deleteScriptConfigMap(job);
                job.setStatus(DatalakeJobStatus.FAILED);
                job.setFinishedAt(java.time.Instant.now());
                // Try to get error from conditions
                if (status.getConditions() != null) {
                    status.getConditions().stream()
                        .filter(c -> "Failed".equals(c.getType()))
                        .findFirst()
                        .ifPresent(c -> job.setErrorMessage(c.getMessage()));
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
                    job.setFinishedAt(java.time.Instant.now());
                    changed = true;
                }
            }
            case "FAILED" -> {
                if (job.getStatus() != DatalakeJobStatus.FAILED) {
                    job.setStatus(DatalakeJobStatus.FAILED);
                    job.setFinishedAt(java.time.Instant.now());
                    Object message = statusMap.get("message");
                    if (message != null) job.setErrorMessage(message.toString());
                    changed = true;
                }
            }
        }

        if (changed) {
            repository.save(job);
        }
    }

    private void deleteScriptConfigMap(DatalakeJobEntity job) {
        if (job.getCciNamespace() == null) return;
        String cmName = "dl-script-" + job.getId();
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
