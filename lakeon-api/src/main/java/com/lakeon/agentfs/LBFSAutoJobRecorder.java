package com.lakeon.agentfs;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class LBFSAutoJobRecorder {

    private final LBFSAutoJobRepository jobRepository;

    public LBFSAutoJobRecorder(LBFSAutoJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public LBFSAutoJobEntity record(
            AgentFSFolderEntity folder,
            AgentFSProcessingEvent event,
            AgentFSProcessingResult result) {
        if (folder == null || folder.getId() == null || folder.getId().isBlank()) {
            return null;
        }
        String profile = folder.getProcessingProfile();
        LBFSAutoJobEntity job = jobRepository
                .findFirstByTenantIdAndFolderIdAndSourcePathAndSourceEtagAndProfileOrderByCreatedAtDesc(
                        folder.getTenantId(),
                        folder.getId(),
                        event.path(),
                        event.etag(),
                        profile)
                .orElseGet(LBFSAutoJobEntity::new);

        job.setTenantId(folder.getTenantId());
        job.setFolderId(folder.getId());
        job.setSourcePath(event.path());
        job.setSourceEtag(event.etag());
        job.setProfile(profile);
        job.setAttempts(job.getAttempts() + 1);
        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }

        if (result.accepted()) {
            job.setStatus("succeeded");
            job.setLastError(null);
            job.setFinishedAt(Instant.now());
        } else if (result.retryable()) {
            job.setStatus("retrying");
            job.setLastError(result.message());
            job.setFinishedAt(null);
        } else {
            job.setStatus("failed");
            job.setLastError(result.message());
            job.setFinishedAt(Instant.now());
        }

        return jobRepository.save(job);
    }
}
