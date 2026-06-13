package com.lakeon.agentfs;

public interface AgentFSProcessingWorker {
    String processingProfile();
    AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event);
}
