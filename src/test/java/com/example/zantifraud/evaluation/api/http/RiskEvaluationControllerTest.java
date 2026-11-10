package com.example.zantifraud.evaluation.api.http;

import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.EvaluationStatus;
import com.example.zantifraud.evaluation.model.RecommendedAction;
import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.service.RiskEvaluationCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RiskEvaluationController.class)
class RiskEvaluationControllerTest {
    private static final String PATH = "/api/v1/risk-evaluations";
    private static final String REQUEST = """
            {
              "requestId": "req-1",
              "idempotencyKey": "retry-1",
              "event": {
                "eventId": "evt-1",
                "operationId": "transfer-1",
                "source": "urn:service:transfers",
                "eventType": "money_movement.transfer",
                "schemaVersion": 1,
                "eventTime": {"value": "2026-11-09T14:00:00+02:00", "precision": "SECOND"},
                "data": {"fromAccountId": "account-1", "toAccountId": "account-2", "amount": 10000, "currency": "EUR"}
              }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RiskEvaluationCoordinator coordinator;

    @ParameterizedTest
    @EnumSource(RecommendedAction.class)
    void returnsSuccessfulEvaluationIncludingBlock(RecommendedAction action) throws Exception {
        when(coordinator.evaluate(any())).thenAnswer(invocation -> {
            RiskEvaluationRequest request = invocation.getArgument(0);
            assertThat(request.decisionPoint()).isNull();
            assertThat(request.event().data().get("amount").longValue()).isEqualTo(10000L);
            assertThat(request.event().eventType().wireValue()).isEqualTo("money_movement.transfer");
            assertThat(request.event().eventTime().value().toInstant())
                    .isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
            return decision(request, EvaluationStatus.COMPLETED, action);
        });

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.decisionId").value("decision-1"))
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.operationId").value("transfer-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendedAction").value(action.name()))
                .andExpect(jsonPath("$.reasons[0].code").value("TEST_POLICY"))
                .andExpect(jsonPath("$.reasons[0].message").isString())
                .andExpect(jsonPath("$.ruleSetVersion").value("rules-v1"))
                .andExpect(jsonPath("$.policyVersion").value("policy-v1"))
                .andExpect(jsonPath("$.evaluatedAt").value("2026-11-09T12:00:00Z"));
    }

    @Test
    void returnsDegradedRecommendation() throws Exception {
        when(coordinator.evaluate(any())).thenAnswer(invocation ->
                decision(invocation.getArgument(0), EvaluationStatus.DEGRADED, RecommendedAction.BLOCK));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.recommendedAction").value("BLOCK"));
    }

    @Test
    void returnsUnavailableWithoutRecommendation() throws Exception {
        when(coordinator.evaluate(any())).thenReturn(new RiskEvaluationResponse(
                "req-1", null, "evt-1", "transfer-1", EvaluationStatus.UNAVAILABLE, null,
                List.of(new DecisionReason("HISTORY_UNAVAILABLE", "Required history is unavailable.", null)),
                null, null, null));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.recommendedAction").value(nullValue()))
                .andExpect(jsonPath("$.decisionId").value(nullValue()))
                .andExpect(jsonPath("$.reasons[0].code").value("HISTORY_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInputBeforeCallingService(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(coordinator);
    }

    static Stream<String> invalidRequests() {
        String payload = "{\"fromAccountId\": \"account-1\", \"toAccountId\": \"account-2\", \"amount\": 10000, \"currency\": \"EUR\"}";
        String eventTime = "\"eventTime\": {\"value\": \"2026-11-09T14:00:00+02:00\", \"precision\": \"SECOND\"}";
        return Stream.of(
                "{", "null", "{}",
                REQUEST.replace("\"requestId\": \"req-1\",", ""),
                REQUEST.replace("\"req-1\"", "\" \""),
                REQUEST.replace("\"retry-1\"", "\"\""),
                REQUEST.replace("\"evt-1\"", "null"),
                REQUEST.replace("\"transfer-1\"", "\"\""),
                REQUEST.replace("urn:service:transfers", "relative-source"),
                REQUEST.replace("money_movement.transfer", "unknown.event"),
                REQUEST.replace("money_movement.transfer", "TRANSFER"),
                REQUEST.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0"),
                REQUEST.replace("2026-11-09T14:00:00+02:00", "2026-11-09T14:00:00"),
                REQUEST.replace(eventTime + ",", ""),
                REQUEST.replace(eventTime, "\"eventTime\": null"),
                REQUEST.replace(payload, "null"),
                REQUEST.replace(payload, "[]"),
                REQUEST.replace(payload, "\"escaped JSON text\""),
                REQUEST.replace(payload, "42"));
    }

    @Test
    void roundTripsWireEventTypeAndProtectsPayloadFromMutation() throws Exception {
        RiskEvaluationRequest request = objectMapper.readValue(REQUEST, RiskEvaluationRequest.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.event().data()).put("amount", 99900L);
        String serialized = objectMapper.writeValueAsString(request);

        assertThat(objectMapper.readTree(serialized).at("/event/eventType").asText())
                .isEqualTo("money_movement.transfer");
        assertThat(objectMapper.readTree(serialized).at("/event/data/amount").longValue()).isEqualTo(10000L);
    }

    private static RiskEvaluationResponse decision(RiskEvaluationRequest request,
                                                   EvaluationStatus status, RecommendedAction action) {
        return new RiskEvaluationResponse(request.requestId(), "decision-1", request.event().eventId(),
                request.event().operationId(), status, action,
                List.of(new DecisionReason("TEST_POLICY", "Test policy result.", "test-rule")),
                "rules-v1", "policy-v1", Instant.parse("2026-11-09T12:00:00Z"));
    }
}
