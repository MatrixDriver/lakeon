package com.lakeon.agentfs;

import org.springframework.stereotype.Component;

@Component
public class AgentFSDatasetWorker implements AgentFSProcessingWorker {

    @Override
    public String processingProfile() {
        return AgentFSFolderProfile.PROCESSING_DATASET;
    }

    @Override
    public AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
        return AgentFSProcessingResult.done("dataset event accepted: " + event.path());
    }
}
