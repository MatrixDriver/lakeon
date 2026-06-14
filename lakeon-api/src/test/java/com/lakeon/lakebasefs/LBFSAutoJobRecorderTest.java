package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LBFSAutoJobRecorderTest {

    @Test
    void records_succeeded_auto_job_for_registered_folder() {
        LBFSAutoJobRepository repo = mock(LBFSAutoJobRepository.class);
        when(repo.findFirstByTenantIdAndFolderIdAndSourcePathAndSourceEtagAndProfileOrderByCreatedAtDesc(
                "tn_1", "fld_1", "/datasets/orders.csv", "etag_1", "dataset"))
                .thenReturn(Optional.empty());
        when(repo.save(any(LBFSAutoJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        LBFSAutoJobRecorder recorder = new LBFSAutoJobRecorder(repo);

        LBFSAutoJobEntity job = recorder.record(
                folder("tn_1", "fld_1", "dataset"),
                new LakebaseFSProcessingEvent("tn_1", "/datasets/orders.csv", "etag_1", "put"),
                LakebaseFSProcessingResult.done("profiled csv"));

        assertEquals("tn_1", job.getTenantId());
        assertEquals("fld_1", job.getFolderId());
        assertEquals("/datasets/orders.csv", job.getSourcePath());
        assertEquals("etag_1", job.getSourceEtag());
        assertEquals("dataset", job.getProfile());
        assertEquals("succeeded", job.getStatus());
        assertEquals(1, job.getAttempts());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getFinishedAt());
        verify(repo).save(job);
    }

    @Test
    void reuses_existing_job_for_same_source_etag_and_marks_retrying() {
        LBFSAutoJobEntity existing = new LBFSAutoJobEntity();
        existing.setTenantId("tn_1");
        existing.setFolderId("fld_1");
        existing.setSourcePath("/tables/orders/metadata/v1.metadata.json");
        existing.setSourceEtag("etag_1");
        existing.setProfile("iceberg");
        existing.setAttempts(1);
        LBFSAutoJobRepository repo = mock(LBFSAutoJobRepository.class);
        when(repo.findFirstByTenantIdAndFolderIdAndSourcePathAndSourceEtagAndProfileOrderByCreatedAtDesc(
                "tn_1", "fld_1", existing.getSourcePath(), "etag_1", "iceberg"))
                .thenReturn(Optional.of(existing));
        when(repo.save(any(LBFSAutoJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        LBFSAutoJobRecorder recorder = new LBFSAutoJobRecorder(repo);

        LBFSAutoJobEntity job = recorder.record(
                folder("tn_1", "fld_1", "iceberg"),
                new LakebaseFSProcessingEvent("tn_1", existing.getSourcePath(), "etag_1", "put"),
                LakebaseFSProcessingResult.retry("missing worker: iceberg"));

        assertEquals(existing, job);
        assertEquals("retrying", job.getStatus());
        assertEquals(2, job.getAttempts());
        assertEquals("missing worker: iceberg", job.getLastError());
    }

    private static LakebaseFSFolderEntity folder(String tenantId, String id, String processingProfile) {
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setTenantId(tenantId);
        folder.setId(id);
        folder.setDisplayName("dataset");
        folder.setDirectoryKind("data-dir");
        folder.setStoragePolicy("object-first");
        folder.setProcessingProfile(processingProfile);
        return folder;
    }
}
