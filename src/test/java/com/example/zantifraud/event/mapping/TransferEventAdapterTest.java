package com.example.zantifraud.event.mapping;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.event.model.TransferLeg;
import com.example.zantifraud.feature.persistence.clickhouse.model.AccountAssetMovementRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferEventAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransferEventAdapter adapter = new TransferEventAdapter(mapper);

    @Test
    void redeliveryReconstructsIdenticalLegsAndRowsWithStableV1Ids() throws Exception {
        JsonEventEnvelope envelope = envelope("evt-1", "account-B");
        AdaptedEvent first = adapter.adapt(envelope);
        // Reconstruct the adapter to ensure identity does not depend on process state.
        AdaptedEvent retry = new TransferEventAdapter(mapper).adapt(envelope);
        assertThat(retry).isEqualTo(first);
        assertThat(first.event()).isEqualTo(new EventModel(envelope.eventId(), envelope.operationId(),
                envelope.correlationId(), envelope.source(), EventType.TRANSFER, envelope.schemaVersion(),
                envelope.eventTime(), new Transfer("account-A", "account-B", 100L, "EUR")));
        assertThat(first.featureEvents()).extracting(FeatureEvent::eventId)
                .containsExactly("transfer:v1:ZXZ0LTE:out", "transfer:v1:ZXZ0LTE:in");
        assertThat(first.featureEvents()).extracting(FeatureEvent::eventType)
                .containsExactly(InternalEventType.TRANSFER_OUT, InternalEventType.TRANSFER_IN);
        assertThat(first.featureEvents()).extracting(FeatureEvent::sourceEventId).containsOnly(first.event().eventId());
        Instant receivedAt = Instant.parse("2026-11-10T12:01:00Z");
        assertThat(first.featureEvents().stream().map(event -> AccountAssetMovementRow.from(event, receivedAt)).toList())
                .isEqualTo(retry.featureEvents().stream().map(event -> AccountAssetMovementRow.from(event, receivedAt)).toList());
    }

    @Test
    void differentSubmissionsWithinOneOperationDoNotReuseLegIds() throws Exception {
        AdaptedEvent first = adapter.adapt(envelope("evt-1", "account-B"));
        AdaptedEvent second = adapter.adapt(envelope("evt-1:out/another", "account-B"));
        assertThat(second.featureEvents()).extracting(FeatureEvent::eventId)
                .doesNotContainAnyElementsOf(first.featureEvents().stream().map(FeatureEvent::eventId).toList());
        assertThat(second.event().operationId()).isEqualTo(first.event().operationId());
    }

    @Test
    void sameAccountTransferStillHasTwoDistinctLegs() throws Exception {
        List<FeatureEvent> result = adapter.adapt(envelope("evt-self", "account-A")).featureEvents();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).eventId()).isNotEqualTo(result.get(1).eventId());
        assertThat(result).extracting(FeatureEvent::data)
                .containsOnly(new TransferLeg("account-A", 100L, "EUR"));
    }

    private JsonEventEnvelope envelope(String eventId, String recipient) throws Exception {
        return new JsonEventEnvelope(eventId, "operation-1", "workflow-1", URI.create("urn:service:transfers"),
                EventType.TRANSFER, 1,
                new EventTime(OffsetDateTime.parse("2026-11-10T12:00:00Z"), EventTime.Precision.SECOND),
                mapper.readTree("{\"fromAccountId\":\"account-A\",\"toAccountId\":\"" + recipient
                        + "\",\"amount\":100,\"currency\":\"EUR\"}"));
    }
}
