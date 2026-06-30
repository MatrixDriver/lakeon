package com.lakeon.pageserver;

import java.time.Instant;

public record PageserverNodeStatus(
    PageserverNode node,
    boolean healthy,
    double loadScore,
    String source,
    String instanceId,
    boolean failoverCoolingDown,
    Instant failoverCooldownUntil
) {
    public PageserverNodeStatus(PageserverNode node, boolean healthy, double loadScore, String source) {
        this(node, healthy, loadScore, source, node.instanceId(), false, null);
    }
}
