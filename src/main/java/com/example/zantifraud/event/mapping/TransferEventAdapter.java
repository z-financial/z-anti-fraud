package com.example.zantifraud.event.mapping;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.event.model.TransferLeg;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Component
public class TransferEventAdapter implements EventAdapter {
    private static final URI SOURCE = URI.create("urn:service:transfers");
    private static final Set<EventAdapterKey> KEYS = Set.of(
            new EventAdapterKey(EventType.TRANSFER, SOURCE));
    private final ObjectMapper objectMapper;

    public TransferEventAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EventAdapterKey> supportedEvents() {
        return KEYS;
    }

    @Override
    public AdaptedEvent adapt(JsonEventEnvelope envelope) {
        if (!KEYS.contains(new EventAdapterKey(envelope.eventType(), envelope.source()))) {
            throw new EventAdaptationException("UNSUPPORTED_EVENT",
                    "This adapter does not support the event type and source combination.");
        }
        if (envelope.schemaVersion() != 1) {
            throw new EventAdaptationException("UNSUPPORTED_SCHEMA_VERSION",
                    "The transfer adapter supports schema version 1 only.");
        }

        JsonNode data = envelope.data();
        // Reject coercion from strings/fractions and overflow before converting to Long.
        if (!data.path("fromAccountId").isTextual()
                || !data.path("toAccountId").isTextual()
                || !data.path("currency").isTextual()
                || !data.path("amount").isIntegralNumber()
                || !data.path("amount").canConvertToLong()) {
            throw invalidPayload();
        }
        try {
            Transfer transfer = objectMapper.treeToValue(data, Transfer.class);
            EventModel event = new EventModel(envelope.eventId(), envelope.operationId(), envelope.correlationId(),
                    envelope.source(), envelope.eventType(), envelope.schemaVersion(), envelope.eventTime(), transfer);
            FeatureEvent outgoing = leg(event, InternalEventType.TRANSFER_OUT, "out",
                    transfer.fromAccountId(), transfer);
            FeatureEvent incoming = leg(event, InternalEventType.TRANSFER_IN, "in",
                    transfer.toAccountId(), transfer);
            return new AdaptedEvent(event, List.of(outgoing, incoming));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    private FeatureEvent leg(EventModel event, InternalEventType type, String direction,
                             String accountId, Transfer transfer) {
        // Stable V1 identity: global upstream event ID encoded without delimiters,
        // followed by direction. Do not change this scheme on redelivery/replay.
        String id = "transfer:v1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(event.eventId().getBytes(StandardCharsets.UTF_8)) + ":" + direction;
        return new FeatureEvent(id, event.eventId(), event.operationId(), event.correlationId(),
                event.source(), type, event.schemaVersion(), event.eventTime(),
                new TransferLeg(accountId, transfer.amount(), transfer.currency()));
    }

    private static EventAdaptationException invalidPayload() {
        return new EventAdaptationException("INVALID_EVENT_DATA",
                "Transfer requires nonblank fromAccountId, toAccountId and currency strings and a positive Int64 amount.");
    }
}
