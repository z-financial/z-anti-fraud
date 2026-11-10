package com.example.zantifraud.evaluation.api.http.request;

import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Objects;

/**
 * HTTP/JSON representation of producer-supplied facts about one occurrence.
 * An API adapter must validate and convert the payload into a typed internal
 * event model before evaluation. Future Protobuf adapters should map directly
 * to that model rather than passing through this JSON contract.
 *
 * <p>The pair {@code (source, eventId)} identifies an occurrence. Preserve it on
 * redelivery and assign a new event ID to each lifecycle occurrence, including
 * completion. Related lifecycle events share an operation ID.
 *
 * <p>The ingestion boundary must authenticate the caller and authorize the
 * asserted source before accepting this envelope. Construction validates only
 * the data shape.
 *
 * @param correlationId optional broader workflow reference
 * @param schemaVersion positive version of the typed payload contract
 * @param eventTime optional occurrence time, absent when the source cannot
 *                  establish an absolute timestamp
 * @param data payload whose type and schema version must be checked by the adapter
 */
public record JsonEventEnvelope(
        String eventId,
        String operationId,
        String correlationId,
        URI source,
        EventType eventType,
        int schemaVersion,
        EventTime eventTime,
        JsonNode data
) {
    public JsonEventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(data, "data is required");

        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be absent or nonblank");
        }
        if (!source.isAbsolute()) {
            throw new IllegalArgumentException("source must be an absolute URI");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (!data.isObject()) {
            throw new IllegalArgumentException("data must be a JSON object");
        }
        data = data.deepCopy();
    }

    /** Returns a copy so callers cannot mutate the request's stored payload. */
    @Override
    public JsonNode data() {
        return data.deepCopy();
    }
}
