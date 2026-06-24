package com.lakeon.pageserver;

public record PageserverNodeStatus(
    PageserverNode node,
    boolean healthy,
    double loadScore,
    String source
) {
}
