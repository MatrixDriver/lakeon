package com.lakeon.pageserver;

public record PageserverPlacement(
    String tenantId,
    int shardId,
    PageserverNode node,
    long epoch,
    String source
) {
    public PageserverPlacement {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must be non-negative");
        }
        if (node == null) {
            throw new IllegalArgumentException("pageserver node is required");
        }
        if (epoch <= 0) {
            throw new IllegalArgumentException("placement epoch must be positive");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("placement source is required");
        }
    }
}
