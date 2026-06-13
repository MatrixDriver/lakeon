package com.lakeon.agentfs;

import org.springframework.stereotype.Component;

@Component
public class AgentFSTableWorker implements AgentFSProcessingWorker {

    @Override
    public String processingProfile() {
        return AgentFSFolderProfile.PROCESSING_ICEBERG;
    }

    @Override
    public AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
        String profile = folder.getProcessingProfile();
        if (AgentFSFolderProfile.PROCESSING_ICEBERG.equals(profile)) {
            return AgentFSProcessingResult.done("iceberg table event accepted: " + event.path());
        }
        if (AgentFSFolderProfile.PROCESSING_LANCE.equals(profile)) {
            return AgentFSProcessingResult.done("lance table event accepted: " + event.path());
        }
        return AgentFSProcessingResult.retry("unsupported table profile: " + profile);
    }
}
