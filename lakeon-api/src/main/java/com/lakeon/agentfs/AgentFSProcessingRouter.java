package com.lakeon.agentfs;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentFSProcessingRouter {

    private final Map<String, AgentFSProcessingWorker> workers;

    public AgentFSProcessingRouter(List<AgentFSProcessingWorker> workers) {
        this.workers = workers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AgentFSProcessingWorker::processingProfile,
                        Function.identity(),
                        (left, right) -> left));
    }

    public AgentFSProcessingResult dispatch(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
        if (folder == null || AgentFSFolderProfile.PROCESSING_NONE.equals(folder.getProcessingProfile())) {
            return AgentFSProcessingResult.done("processing skipped");
        }
        AgentFSProcessingWorker worker = workers.get(folder.getProcessingProfile());
        if (worker != null) {
            return worker.process(folder, event);
        }
        return AgentFSProcessingResult.retry("missing worker: " + folder.getProcessingProfile());
    }
}
