package com.lakeon.pageserver;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PageserverLoadSnapshot(
    Map<String, Double> loadScores,
    Set<String> unavailableNodeIds,
    Instant observedAt,
    String source,
    boolean fresh
) {
    public static PageserverLoadSnapshot empty() {
        return new PageserverLoadSnapshot(Map.of(), Set.of(), null, "static", false);
    }

    public static PageserverLoadSnapshot fresh(
        Map<String, Double> loadScores,
        Set<String> unavailableNodeIds,
        Instant observedAt,
        String source
    ) {
        return new PageserverLoadSnapshot(
            Map.copyOf(loadScores),
            Set.copyOf(unavailableNodeIds),
            observedAt,
            source,
            true);
    }

    public boolean isFresh() {
        return fresh;
    }
}
