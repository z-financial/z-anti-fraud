package com.example.zantifraud.event.mapping;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.TransferRequested;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Component
public class TransferRequestedEventAdapter implements EventAdapter {
    private static final EventAdapterKey KEY = new EventAdapterKey(
            EventType.TRANSFER_REQUESTED, URI.create("urn:service:transfers"));
    private final ObjectMapper objectMapper;

    public TransferRequestedEventAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EventAdapterKey> supportedEvents() {
        return Set.of(KEY);
    }

    @Override
    public EventModel adapt(JsonEventEnvelope envelope) {
        if (!KEY.equals(new EventAdapterKey(envelope.eventType(), envelope.source()))) {
            throw new EventAdaptationException("UNSUPPORTED_EVENT",
                    "This adapter does not support the event type and source combination.");
        }
        if (envelope.schemaVersion() != 1) {
            throw new EventAdaptationException("UNSUPPORTED_SCHEMA_VERSION",
                    "The transfer-requested adapter supports schema version 1 only.");
        }

        JsonNode data = envelope.data();
        // Decimal strings avoid floating-point rounding before payload conversion.
        if (!data.path("currency").isTextual() || !data.path("amount").isTextual()) {
            throw invalidPayload();
        }
        try {
            TransferRequested payload = objectMapper.treeToValue(data, TransferRequested.class);
            return new EventModel(envelope.eventId(), envelope.operationId(), envelope.correlationId(),
                    envelope.source(), envelope.eventType(), envelope.schemaVersion(), envelope.eventTime(), payload);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    private static EventAdaptationException invalidPayload() {
        return new EventAdaptationException("INVALID_EVENT_DATA",
                "Transfer data requires a positive decimal amount string and a nonblank currency string.");
    }
}
