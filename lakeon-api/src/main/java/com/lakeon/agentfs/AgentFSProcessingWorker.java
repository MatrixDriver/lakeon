package com.lakeon.agentfs;

public interface AgentFSProcessingWorker {
    String processingProfile();
    void process(AgentFSFolderEntity folder, AgentFSProcessingEvent event);
}
