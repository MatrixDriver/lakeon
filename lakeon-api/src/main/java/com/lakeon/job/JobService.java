package com.lakeon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final Set<JobStatus> TERMINAL_STATUSES = Set.of(
        JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED
    );

    private final JobRepository jobRepository;
    private final JobPodManager jobPodManager;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public JobService(JobRepository jobRepository, JobPodManager jobPodManager, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobPodManager = jobPodManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new job in PENDING state, serialize params to JSON, save, then
     * register an afterCommit hook to asynchronously launch the pod.
     */
    @Transactional
    public JobEntity submitJob(TenantEntity tenant, JobType type, Map<String, Object> params) {
        JobEntity job = new JobEntity();
        job.setTenantId(tenant.getId());
        job.setType(type);
        job.setStatus(JobStatus.PENDING);

        try {
            job.setParams(params != null ? objectMapper.writeValueAsString(params) : "{}");
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize job params", e);
        }

        jobRepository.save(job);
        log.info("Submitted job {} of type {} for tenant {}", job.getId(), type, tenant.getId());

        String jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(() -> launchPod(jobId));
            }
        });

        return job;
    }

    /**
     * Find the job, launch its pod, update status to RUNNING.
     * On failure: set FAILED with error message.
     */
    private void launchPod(String jobId) {
        try {
            JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));

            if (job.getStatus() != JobStatus.PENDING) {
                log.warn("Job {} is in state {}, skipping pod launch", jobId, job.getStatus());
                return;
            }

            String podName = jobPodManager.launchJobPod(job);

            job.setPodName(podName);
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);
            log.info("Job {} is now RUNNING on pod {}", jobId, podName);
        } catch (Exception e) {
            log.error("Failed to launch pod for job {}: {}", jobId, e.getMessage(), e);
            try {
                jobRepository.findById(jobId).ifPresent(job -> {
                    job.setStatus(JobStatus.FAILED);
                    job.setError("Pod launch failed: " + e.getMessage());
                    job.setCompletedAt(Instant.now());
                    jobRepository.save(job);
                });
            } catch (Exception ex) {
                log.error("Failed to update job {} status to FAILED: {}", jobId, ex.getMessage());
            }
        }
    }

    /**
     * Get a job by ID, scoped to the tenant.
     */
    public JobEntity getJob(String tenantId, String jobId) {
        return jobRepository.findByIdAndTenantId(jobId, tenantId)
            .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
    }

    /**
     * List jobs for a tenant, optionally filtered by type and/or status.
     */
    public List<JobEntity> listJobs(String tenantId, JobType type, JobStatus status) {
        if (type != null && status != null) {
            return jobRepository.findAllByTenantIdAndTypeAndStatusOrderByCreatedAtDesc(tenantId, type, status);
        } else if (type != null) {
            return jobRepository.findAllByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
        } else if (status != null) {
            return jobRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        } else {
            return jobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        }
    }

    /**
     * Cancel a job. Skips if already in a terminal state.
     */
    @Transactional
    public JobEntity cancelJob(String tenantId, String jobId) {
        JobEntity job = jobRepository.findByIdAndTenantId(jobId, tenantId)
            .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));

        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            log.info("Job {} is already in terminal state {}, skipping cancel", jobId, job.getStatus());
            return job;
        }

        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);

        // Delete pod resources asynchronously (best-effort)
        executor.submit(() -> {
            try {
                jobPodManager.deleteJobResources(jobId);
            } catch (Exception e) {
                log.warn("Failed to delete resources for cancelled job {}: {}", jobId, e.getMessage());
            }
        });

        log.info("Cancelled job {} for tenant {}", jobId, tenantId);
        return job;
    }

    /**
     * Handle a callback from a job pod.
     * - RUNNING: progress update, update result only.
     * - SUCCEEDED/FAILED: terminal update, set status + result/error + completedAt, delete pod resources.
     * Returns false if the token is invalid or the job is not found.
     */
    public boolean handleCallback(String jobId, String token, String status, String resultJson, String error) {
        if (token == null || status == null) {
            log.warn("Callback for job {} missing token or status", jobId);
            return false;
        }

        JobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Callback for unknown job {}", jobId);
            return false;
        }

        if (!token.equals(job.getCallbackToken())) {
            log.warn("Callback for job {} has invalid token", jobId);
            return false;
        }

        if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED
                || job.getStatus() == JobStatus.CANCELLED) {
            log.info("Ignoring callback for job {} already in terminal state {}", jobId, job.getStatus());
            return true;
        }

        JobStatus newStatus;
        try {
            newStatus = JobStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Callback for job {} has unknown status: {}", jobId, status);
            return false;
        }

        if (newStatus == JobStatus.RUNNING) {
            // Progress update — only update result field
            if (resultJson != null) {
                job.setResult(resultJson);
                jobRepository.save(job);
                log.debug("Job {} progress update", jobId);
            }
        } else if (newStatus == JobStatus.SUCCEEDED || newStatus == JobStatus.FAILED) {
            // Terminal update
            job.setStatus(newStatus);
            if (resultJson != null) {
                job.setResult(resultJson);
            }
            if (error != null) {
                job.setError(error);
            }
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            log.info("Job {} completed with status {}", jobId, newStatus);

            // Clean up pod resources asynchronously
            executor.submit(() -> {
                try {
                    jobPodManager.deleteJobResources(jobId);
                } catch (Exception e) {
                    log.warn("Failed to delete resources for job {}: {}", jobId, e.getMessage());
                }
            });
        } else {
            log.warn("Callback for job {} received unexpected status: {}", jobId, newStatus);
            return false;
        }

        return true;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
