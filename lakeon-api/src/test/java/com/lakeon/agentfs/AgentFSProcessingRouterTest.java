package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentFSProcessingRouterTest {

    @Test
    void dispatches_events_to_matching_processing_worker_only() {
        RecordingWorker memory = new RecordingWorker("small-file-memory");
        RecordingWorker dataset = new RecordingWorker("dataset");
        AgentFSProcessingRouter router = new AgentFSProcessingRouter(List.of(memory, dataset));
        AgentFSFolderEntity folder = new AgentFSFolderEntity();
        folder.setProcessingProfile("small-file-memory");

        AgentFSProcessingResult result = router.dispatch(
                folder,
                new AgentFSProcessingEvent("tn_1", "/notes/a.md", "etag-1", "put"));

        assertEquals(List.of("/notes/a.md"), memory.paths);
        assertEquals(List.of(), dataset.paths);
        assertEquals(true, result.accepted());
        assertEquals(false, result.retryable());
    }

    @Test
    void none_processing_profile_does_not_dispatch() {
        RecordingWorker memory = new RecordingWorker("small-file-memory");
        AgentFSProcessingRouter router = new AgentFSProcessingRouter(List.of(memory));
        AgentFSFolderEntity folder = new AgentFSFolderEntity();
        folder.setProcessingProfile("none");

        AgentFSProcessingResult result = router.dispatch(
                folder,
                new AgentFSProcessingEvent("tn_1", "/plain.txt", "etag-1", "put"));

        assertEquals(List.of(), memory.paths);
        assertEquals(true, result.accepted());
        assertEquals("processing skipped", result.message());
    }

    @Test
    void missing_processing_worker_returns_retryable_result() {
        AgentFSProcessingRouter router = new AgentFSProcessingRouter(List.of());
        AgentFSFolderEntity folder = new AgentFSFolderEntity();
        folder.setProcessingProfile("dataset");

        AgentFSProcessingResult result = router.dispatch(
                folder,
                new AgentFSProcessingEvent("tn_1", "/datasets/orders.csv", "etag-1", "put"));

        assertEquals(false, result.accepted());
        assertEquals(true, result.retryable());
        assertEquals("missing worker: dataset", result.message());
    }

    private static final class RecordingWorker implements AgentFSProcessingWorker {
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
        public AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
            paths.add(event.path());
            return AgentFSProcessingResult.done("recorded");
        }
    }
}
