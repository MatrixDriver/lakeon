package com.lakeon.pageserver;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PageserverLoadSnapshot(
    Map<String, Double> loadScores,
    Set<String> unavailableNodeIds,
    Instant observedAt,
    String source,
    boolean fresh,
    Map<String, Map<String, Double>> loadBreakdownByNode
) {
    public static PageserverLoadSnapshot empty() {
        return new PageserverLoadSnapshot(Map.of(), Set.of(), null, "static", false, Map.of());
    }

    public static PageserverLoadSnapshot fresh(
        Map<String, Double> loadScores,
        Set<String> unavailableNodeIds,
        Instant observedAt,
        String source
    ) {
        return fresh(loadScores, unavailableNodeIds, observedAt, source, Map.of());
    }

    public static PageserverLoadSnapshot fresh(
        Map<String, Double> loadScores,
        Set<String> unavailableNodeIds,
        Instant observedAt,
        String source,
        Map<String, Map<String, Double>> loadBreakdownByNode
    ) {
        return new PageserverLoadSnapshot(
            Map.copyOf(loadScores),
            Set.copyOf(unavailableNodeIds),
            observedAt,
            source,
            true,
            copyBreakdown(loadBreakdownByNode));
    }

    public boolean isFresh() {
        return fresh;
    }

    private static Map<String, Map<String, Double>> copyBreakdown(Map<String, Map<String, Double>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return input.entrySet().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> Map.copyOf(entry.getValue())));
    }
}
