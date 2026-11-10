package com.example.zantifraud.event.model;

import java.net.URI;
import java.util.Objects;

/** Decoded business event with the upstream identity and typed data. */
public record EventModel(
        String eventId,
        String operationId,
        String correlationId,
        URI source,
        EventType eventType,
        int schemaVersion,
        EventTime eventTime,
        EventPayload data
) {
    public EventModel {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(eventTime, "eventTime is required");
        Objects.requireNonNull(data, "data is required");
        if (eventId.isBlank() || operationId.isBlank()) {
            throw new IllegalArgumentException("eventId and operationId must not be blank");
        }
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be absent or nonblank");
        }
        if (!source.isAbsolute() || schemaVersion < 1) {
            throw new IllegalArgumentException("source must be absolute and schemaVersion must be positive");
        }
    }
}
