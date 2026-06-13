package com.lakeon.agentfs;

public record AgentFSProcessingEvent(
        String tenantId,
        String path,
        String etag,
        String eventType) {
}
