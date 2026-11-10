package com.example.zantifraud.evaluation.service;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.model.EvaluationStatus;
import com.example.zantifraud.event.mapping.EventAdaptationException;
import com.example.zantifraud.event.mapping.EventAdapterRegistry;
import com.example.zantifraud.event.mapping.TransferRequestedEventAdapter;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.TransferRequested;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.service.EmptyFeatureService;
import com.example.zantifraud.feature.service.FeatureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RiskEvaluationCoordinatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransferRequestedEventAdapter adapter = new TransferRequestedEventAdapter(mapper);
    private final EventAdapterRegistry registry = new EventAdapterRegistry(List.of(adapter));
    private final FeatureService features = mock(FeatureService.class);
    private final RiskEvaluationCoordinator coordinator = new RiskEvaluationCoordinator(registry, features);

    @Test
    void passesTypedEventAndMetadataToFeatureServiceWithoutIssuingDecision() throws Exception {
        JsonEventEnvelope envelope = envelope("urn:service:transfers", 1, "123456789.123456789");
        when(features.queryFeatures(any(), eq(Set.of())))
                .thenReturn(new FeatureSnapshot(Map.of(), Set.of()));

        RiskEvaluationResponse response = coordinator.evaluate(request(envelope));

        ArgumentCaptor<EventModel> captured = ArgumentCaptor.forClass(EventModel.class);
        verify(features).queryFeatures(captured.capture(), eq(Set.of()));
        EventModel event = captured.getValue();
        assertThat(event.data()).isEqualTo(new TransferRequested(new BigDecimal("123456789.123456789"), "EUR"));
        assertThat(event.eventId()).isEqualTo(envelope.eventId());
        assertThat(event.operationId()).isEqualTo(envelope.operationId());
        assertThat(event.correlationId()).isEqualTo(envelope.correlationId());
        assertThat(event.source()).isEqualTo(envelope.source());
        assertThat(event.eventType()).isEqualTo(envelope.eventType());
        assertThat(event.schemaVersion()).isEqualTo(envelope.schemaVersion());
        assertThat(event.eventTime()).isEqualTo(envelope.eventTime());
        assertThat(response.requestId()).isEqualTo("req-1");
        assertThat(response.status()).isEqualTo(EvaluationStatus.UNAVAILABLE);
        assertThat(response.decisionId()).isNull();
        assertThat(response.recommendedAction()).isNull();
    }

    @Test
    void doesNotQueryFeaturesForUnregisteredSource() throws Exception {
        RiskEvaluationRequest request = request(envelope("urn:service:other", 1, "100"));
        assertThatThrownBy(() -> coordinator.evaluate(request))
                .isInstanceOfSatisfying(EventAdaptationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_EVENT"));
        verifyNoInteractions(features);
    }

    @Test
    void doesNotQueryFeaturesForUnsupportedSchema() throws Exception {
        RiskEvaluationRequest request = request(envelope("urn:service:transfers", 2, "100"));
        assertThatThrownBy(() -> coordinator.evaluate(request))
                .isInstanceOfSatisfying(EventAdaptationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION"));
        verifyNoInteractions(features);
    }

    @Test
    void doesNotQueryFeaturesForInvalidPayload() throws Exception {
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

    @Test
    void emptyFeatureServiceReportsMissingEvidenceRatherThanZero() throws Exception {
        EventModel event = adapter.adapt(envelope("urn:service:transfers", 1, "100"));
        EmptyFeatureService empty = new EmptyFeatureService();

        FeatureSnapshot snapshot = empty.queryFeatures(event, Set.of("transfer_count_1h"));

        assertThat(snapshot.values()).isEmpty();
        assertThat(snapshot.unavailableFeatures()).containsExactly("transfer_count_1h");
        assertThat(empty.queryFeatures(event, Set.of()).unavailableFeatures()).isEmpty();
    }

    private JsonEventEnvelope envelope(String source, int schemaVersion, String amount) throws Exception {
        return new JsonEventEnvelope("evt-1", "transfer-1", "workflow-1", URI.create(source),
                EventType.TRANSFER_REQUESTED, schemaVersion,
                new EventTime(OffsetDateTime.parse("2026-11-10T12:00:00+02:00"), EventTime.Precision.SECOND),
                mapper.readTree("{\"amount\": \"" + amount + "\", \"currency\": \"EUR\"}"));
    }

    private RiskEvaluationRequest request(JsonEventEnvelope envelope) {
        return new RiskEvaluationRequest("req-1", "retry-1", null, envelope);
    }
}
