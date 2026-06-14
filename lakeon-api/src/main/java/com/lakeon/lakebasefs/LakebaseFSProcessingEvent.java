package com.lakeon.lakebasefs;

public record LakebaseFSProcessingEvent(
        String tenantId,
        String path,
        String etag,
        String eventType) {
}
