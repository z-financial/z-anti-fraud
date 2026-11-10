package com.example.zantifraud.evaluation.service;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.decision.DecisionEngine;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;
import com.example.zantifraud.evaluation.rule.RuleEngine;
import com.example.zantifraud.evaluation.rule.RuleEvaluation;
import com.example.zantifraud.event.mapping.EventAdaptationException;
import com.example.zantifraud.event.mapping.EventAdapterRegistry;
import com.example.zantifraud.event.mapping.TransferEventAdapter;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.event.model.TransferLeg;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.service.FeatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RiskEvaluationCoordinatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransferEventAdapter adapter = new TransferEventAdapter(mapper);
    private final EventAdapterRegistry registry = new EventAdapterRegistry(List.of(adapter));
    private final FeatureService features = mock(FeatureService.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final RiskEvaluationCoordinator coordinator =
            new RiskEvaluationCoordinator(registry, features, rules, new DecisionEngine());

    @Test
    void evaluatesTheDecodedEventAndSnapshotAfterPersistenceAndFeatureLookup() throws Exception {
        JsonEventEnvelope envelope = envelope("urn:service:transfers", 1, "9223372036854775807");
        FeatureSnapshot snapshot = new FeatureSnapshot(Map.of(), Set.of());
        when(features.queryFeatures(any())).thenReturn(snapshot);
        when(rules.evaluate(any(), any())).thenReturn(new RuleEvaluation(List.of()));

        RiskEvaluationResponse response = coordinator.evaluate(request(envelope));

        ArgumentCaptor<List<FeatureEvent>> captured = ArgumentCaptor.captor();
        InOrder calls = inOrder(features, rules);
        calls.verify(features).persistEvents(captured.capture());
        assertThat(captured.getValue()).hasSize(2);
        FeatureEvent outgoing = captured.getValue().get(0);
        FeatureEvent incoming = captured.getValue().get(1);
        calls.verify(features).queryFeatures(same(captured.getValue()));
        EventModel decoded = new EventModel(envelope.eventId(), envelope.operationId(), envelope.correlationId(),
                envelope.source(), EventType.TRANSFER, envelope.schemaVersion(), envelope.eventTime(),
                new Transfer("account-1", "account-2", Long.MAX_VALUE, "EUR"));
        calls.verify(rules).evaluate(eq(decoded), same(snapshot));
        calls.verifyNoMoreInteractions();
        assertThat(outgoing.eventType()).isEqualTo(InternalEventType.TRANSFER_OUT);
        assertThat(incoming.eventType()).isEqualTo(InternalEventType.TRANSFER_IN);
        assertThat(outgoing.data()).isEqualTo(new TransferLeg("account-1", Long.MAX_VALUE, "EUR"));
        assertThat(incoming.data()).isEqualTo(new TransferLeg("account-2", Long.MAX_VALUE, "EUR"));
        assertThat(outgoing.eventId()).isNotEqualTo(incoming.eventId()).isNotEqualTo(envelope.eventId());
        for (FeatureEvent event : captured.getValue()) {
            assertThat(event.sourceEventId()).isEqualTo(envelope.eventId());
            assertThat(event.operationId()).isEqualTo(envelope.operationId());
            assertThat(event.correlationId()).isEqualTo(envelope.correlationId());
            assertThat(event.source()).isEqualTo(envelope.source());
            assertThat(event.schemaVersion()).isEqualTo(envelope.schemaVersion());
            assertThat(event.eventTime()).isEqualTo(envelope.eventTime());
        }
        assertThat(response.eventId()).isEqualTo(envelope.eventId());
        assertThat(response.operationId()).isEqualTo(envelope.operationId());
        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.status()).isEqualTo(RiskEvaluationStatus.UNAVAILABLE);
        assertThat(response.decisionId()).isNull();
        assertThat(response.recommendedAction()).isNull();
    }

    @Test
    void doesNotCallFeatureServiceForUnregisteredSource() throws Exception {
        RiskEvaluationRequest request = request(envelope("urn:service:other", 1, "100"));
        assertThatThrownBy(() -> coordinator.evaluate(request))
                .isInstanceOfSatisfying(EventAdaptationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_EVENT"));
        verifyNoInteractions(features);
    }

    @Test
    void doesNotCallFeatureServiceForUnsupportedSchema() throws Exception {
        RiskEvaluationRequest request = request(envelope("urn:service:transfers", 2, "100"));
        assertThatThrownBy(() -> coordinator.evaluate(request))
                .isInstanceOfSatisfying(EventAdaptationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION"));
        verifyNoInteractions(features);
    }

    @Test
    void doesNotCallFeatureServiceForInvalidPayload() throws Exception {
        RiskEvaluationRequest request = request(envelope("urn:service:transfers", 1, "-100"));
        assertThatThrownBy(() -> coordinator.evaluate(request))
                .isInstanceOfSatisfying(EventAdaptationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_EVENT_DATA"));
        verifyNoInteractions(features);
    }

    @Test
    void rejectsAmbiguousAdapterRegistrations() {
        assertThatThrownBy(() -> new EventAdapterRegistry(List.of(adapter, adapter)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate event adapter registration");
    }

    private JsonEventEnvelope envelope(String source, int schemaVersion, String amount) throws Exception {
        return envelope(EventType.TRANSFER, source, schemaVersion, amount);
    }

    private JsonEventEnvelope envelope(EventType type, String source, int schemaVersion, String amount) throws Exception {
        return new JsonEventEnvelope("evt-1", "transfer-1", "workflow-1", URI.create(source),
                type, schemaVersion,
                new EventTime(OffsetDateTime.parse("2026-11-10T12:00:00+02:00"), EventTime.Precision.SECOND),
                mapper.readTree("{\"fromAccountId\": \"account-1\", \"toAccountId\": \"account-2\", "
                        + "\"amount\": " + amount + ", \"currency\": \"EUR\"}"));
    }

    private RiskEvaluationRequest request(JsonEventEnvelope envelope) {
        return new RiskEvaluationRequest("req-1", "retry-1", null, envelope);
    }
}
