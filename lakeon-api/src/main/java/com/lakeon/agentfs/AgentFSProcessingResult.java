package com.lakeon.agentfs;

public record AgentFSProcessingResult(boolean accepted, boolean retryable, String message) {
    public static AgentFSProcessingResult done(String message) {
        return new AgentFSProcessingResult(true, false, message);
    }

    public static AgentFSProcessingResult retry(String message) {
        return new AgentFSProcessingResult(false, true, message);
    }
}
