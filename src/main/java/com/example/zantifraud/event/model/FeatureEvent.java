package com.example.zantifraud.event.model;

import java.net.URI;
import java.util.Objects;

/** A derived event for feature storage and lookup. sourceEventId identifies the upstream event. */
public record FeatureEvent(
        String eventId,
        String sourceEventId,
        String operationId,
        String correlationId,
        URI source,
        InternalEventType eventType,
        int schemaVersion,
        EventTime eventTime,
        FeaturePayload data
) {
    public FeatureEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(sourceEventId, "sourceEventId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(eventTime, "eventTime is required");
        Objects.requireNonNull(data, "data is required");
        if (eventId.isBlank() || sourceEventId.isBlank() || operationId.isBlank()) {
            throw new IllegalArgumentException("eventId, sourceEventId and operationId must not be blank");
        }
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be absent or nonblank");
        }
        if (!source.isAbsolute() || schemaVersion < 1) {
            throw new IllegalArgumentException("source must be absolute and schemaVersion must be positive");
        }
    }
}
