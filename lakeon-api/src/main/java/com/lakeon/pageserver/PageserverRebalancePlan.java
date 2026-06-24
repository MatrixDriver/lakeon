package com.lakeon.pageserver;

import java.util.List;

public record PageserverRebalancePlan(
    boolean dryRun,
    List<Move> moves
) {
    public record Move(
        String tenantId,
        int shardId,
        String fromNodeId,
        String toNodeId,
        long nextEpoch,
        String reason
    ) {
    }
}
