package com.example.zantifraud.feature.service;

import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MVP placeholder: no database queries or feature computation. */
@Service
public class EmptyFeatureService implements FeatureService {
    @Override
    public FeatureSnapshot queryFeatures(EventModel currentEvent, Set<String> requiredFeatures) {
        Objects.requireNonNull(currentEvent, "currentEvent is required");
        return new FeatureSnapshot(Map.of(), requiredFeatures);
    }
}
