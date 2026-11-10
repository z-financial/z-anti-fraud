package com.example.zantifraud.feature.service;

import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.feature.model.FeatureSnapshot;

import java.util.List;

/** Selects persistence behavior and the feature set from the event type. */
public interface FeatureService {
    /** Requests persistence of the derived events together; does not guarantee atomicity. */
    void persistEvents(List<FeatureEvent> events);

    /** Returns one snapshot for the feature events derived from a single business event. */
    FeatureSnapshot queryFeatures(List<FeatureEvent> events);
}
