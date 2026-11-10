package com.example.zantifraud.event.mapping;

import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.FeatureEvent;

import java.util.List;
import java.util.Objects;

/** A decoded business event and its derived events for the feature service. */
public record AdaptedEvent(EventModel event, List<FeatureEvent> featureEvents) {
    public AdaptedEvent {
        Objects.requireNonNull(event, "event is required");
        featureEvents = List.copyOf(featureEvents);
    }
}
