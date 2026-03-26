package com.lakeon.job;

import com.lakeon.config.LakeonProperties;
import com.lakeon.knowledge.KbWriteQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class JobScheduledTasks {
    private static final Logger log = LoggerFactory.getLogger(JobScheduledTasks.class);

    private final JobRepository jobRepository;
    private final JobPodManager jobPodManager;
    private final LakeonProperties props;
    private final KbWriteQueue kbWriteQueue;

    public JobScheduledTasks(JobRepository jobRepository, JobPodManager jobPodManager,
                              LakeonProperties props, @Lazy KbWriteQueue kbWriteQueue) {
        this.jobRepository = jobRepository;
        this.jobPodManager = jobPodManager;
        this.props = props;
        this.kbWriteQueue = kbWriteQueue;
    }

    @Scheduled(fixedDelayString = "${lakeon.job.orphan-check-interval-ms:60000}")
    void detectOrphanedJobs() {
        List<JobEntity> activeJobs = jobRepository.findAllByStatusIn(
                List.of(JobStatus.PENDING, JobStatus.RUNNING));

        if (activeJobs.isEmpty()) {
            return;
        }

        int pendingTimeoutMinutes = props.getJob().getPendingTimeoutMinutes();
        int timeoutMinutes = props.getJob().getTimeoutMinutes();
        Instant now = Instant.now();

        for (JobEntity job : activeJobs) {
            try {
                if (job.getStatus() == JobStatus.PENDING) {
                    handlePendingJob(job, now, pendingTimeoutMinutes);
                } else if (job.getStatus() == JobStatus.RUNNING) {
                    handleRunningJob(job, now, timeoutMinutes);
                }
            } catch (Exception e) {
                log.error("Error checking orphaned job {}: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    private void handlePendingJob(JobEntity job, Instant now, int pendingTimeoutMinutes) {
        Instant deadline = job.getCreatedAt().plusSeconds(pendingTimeoutMinutes * 60L);
        if (now.isAfter(deadline)) {
            boolean podAbsent = job.getPodName() == null || !jobPodManager.podExists(job.getPodName());
            if (podAbsent) {
                log.warn("Pending job {} timed out after {} minutes with no pod — marking FAILED",
                        job.getId(), pendingTimeoutMinutes);
                job.setStatus(JobStatus.FAILED);
                job.setError("Job timed out in PENDING state after " + pendingTimeoutMinutes + " minutes");
                job.setCompletedAt(now);
                jobRepository.save(job);
                notifyKbWriteQueue(job);
            }
        }
    }

    private void handleRunningJob(JobEntity job, Instant now, int timeoutMinutes) {
        // Check if pod terminated without callback
        if (job.getPodName() != null && jobPodManager.isPodTerminated(job.getPodName())) {
            String reason = jobPodManager.getTerminationReason(job.getPodName());
            log.warn("Running job {} has terminated pod but no callback received — {}",
                    job.getId(), reason);
            job.setStatus(JobStatus.FAILED);
            job.setError("处理任务异常终止: " + reason);
            job.setCompletedAt(now);
            jobRepository.save(job);
            deleteResourcesQuietly(job.getId());
            notifyKbWriteQueue(job);
            return;
        }

        // Check for overall timeout
        if (job.getStartedAt() != null) {
            Instant deadline = job.getStartedAt().plusSeconds(timeoutMinutes * 60L);
            if (now.isAfter(deadline)) {
                log.warn("Running job {} exceeded timeout of {} minutes — marking FAILED",
                        job.getId(), timeoutMinutes);
                job.setStatus(JobStatus.FAILED);
                job.setError("Job timed out after " + timeoutMinutes + " minutes");
                job.setCompletedAt(now);
                jobRepository.save(job);
                deleteResourcesQuietly(job.getId());
                notifyKbWriteQueue(job);
            }
        }
    }

    private void notifyKbWriteQueue(JobEntity job) {
        try {
            kbWriteQueue.onJobCompleted(job.getId(), false, null, job.getError());
        } catch (Exception e) {
            log.debug("No write task linked to job {}", job.getId());
        }
    }

    private void deleteResourcesQuietly(String jobId) {
        try {
            jobPodManager.deleteJobResources(jobId);
        } catch (Exception e) {
            log.warn("Failed to delete resources for orphaned job {}: {}", jobId, e.getMessage());
        }
    }
}
