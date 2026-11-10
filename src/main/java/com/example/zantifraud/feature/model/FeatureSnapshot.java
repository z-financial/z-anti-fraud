package com.example.zantifraud.feature.model;

import java.util.Map;
import java.util.Set;

/** Feature values must be immutable. Unavailable features are never represented as zero. */
public record FeatureSnapshot(Map<String, Object> values, Set<String> unavailableFeatures) {
    public FeatureSnapshot {
        values = Map.copyOf(values);
        unavailableFeatures = Set.copyOf(unavailableFeatures);
        if (unavailableFeatures.stream().anyMatch(values::containsKey)) {
            throw new IllegalArgumentException("A feature cannot be both available and unavailable");
        }
    }
}
