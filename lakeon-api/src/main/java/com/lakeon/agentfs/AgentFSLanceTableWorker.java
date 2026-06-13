package com.lakeon.agentfs;

import org.springframework.stereotype.Component;

@Component
public class AgentFSLanceTableWorker implements AgentFSProcessingWorker {

    @Override
    public String processingProfile() {
        return AgentFSFolderProfile.PROCESSING_LANCE;
    }

    @Override
    public AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
        return AgentFSProcessingResult.done("lance table event accepted: " + event.path());
    }
}
