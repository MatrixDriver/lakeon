package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LakebaseFSProcessingRouterTest {

    @Test
    void dispatches_events_to_matching_processing_worker_only() {
        RecordingWorker memory = new RecordingWorker("agent-home");
        RecordingWorker dataset = new RecordingWorker("dataset");
        LakebaseFSProcessingRouter router = new LakebaseFSProcessingRouter(List.of(memory, dataset));
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setProcessingProfile("agent-home");

        LakebaseFSProcessingResult result = router.dispatch(
                folder,
                new LakebaseFSProcessingEvent("tn_1", "/notes/a.md", "etag-1", "put"));

        assertEquals(List.of("/notes/a.md"), memory.paths);
        assertEquals(List.of(), dataset.paths);
        assertEquals(true, result.accepted());
        assertEquals(false, result.retryable());
    }

    @Test
    void none_processing_profile_does_not_dispatch() {
        RecordingWorker memory = new RecordingWorker("agent-home");
        LakebaseFSProcessingRouter router = new LakebaseFSProcessingRouter(List.of(memory));
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setProcessingProfile("none");

        LakebaseFSProcessingResult result = router.dispatch(
                folder,
                new LakebaseFSProcessingEvent("tn_1", "/plain.txt", "etag-1", "put"));

        assertEquals(List.of(), memory.paths);
        assertEquals(true, result.accepted());
        assertEquals("processing skipped", result.message());
    }

    @Test
    void missing_processing_worker_returns_retryable_result() {
        LakebaseFSProcessingRouter router = new LakebaseFSProcessingRouter(List.of());
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setProcessingProfile("dataset");

        LakebaseFSProcessingResult result = router.dispatch(
                folder,
                new LakebaseFSProcessingEvent("tn_1", "/datasets/orders.csv", "etag-1", "put"));

        assertEquals(false, result.accepted());
        assertEquals(true, result.retryable());
        assertEquals("missing worker: dataset", result.message());
    }

    private static final class RecordingWorker implements LakebaseFSProcessingWorker {
        private final String profile;
        private final List<String> paths = new ArrayList<>();

        private RecordingWorker(String profile) {
            this.profile = profile;
        }

        @Override
        public String processingProfile() {
            return profile;
        }

        @Override
        public LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
            paths.add(event.path());
            return LakebaseFSProcessingResult.done("recorded");
        }
    }
}
