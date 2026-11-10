package com.example.zantifraud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ZAntiFraudApplicationTests {
    private static final String REQUEST = """
            {
              "requestId": "req-1",
              "idempotencyKey": "retry-1",
              "decisionPoint": "reserved-for-future-use",
              "event": {
                "eventId": "evt-1",
                "operationId": "transfer-1",
                "source": "urn:service:transfers",
                "eventType": "money_movement.transfer.requested",
                "schemaVersion": 1,
                "data": {"amount": "100.00", "currency": "EUR"}
              }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoads() {
    }

    @Test
    void reportsUnconfiguredEngineWithoutIssuingDecision() throws Exception {
        mvc.perform(post("/api/v1/risk-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.recommendedAction").value(nullValue()))
                .andExpect(jsonPath("$.decisionId").value(nullValue()))
                .andExpect(jsonPath("$.reasons[0].code").value("EVALUATION_NOT_CONFIGURED"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedOrInvalidEvents")
    void rejectsUnsupportedOrInvalidEvents(String body, String code) throws Exception {
        mvc.perform(post("/api/v1/risk-evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    static Stream<Arguments> unsupportedOrInvalidEvents() {
        Stream<Arguments> routing = Stream.of(
                Arguments.of(REQUEST.replace("urn:service:transfers", "urn:service:other"), "UNSUPPORTED_EVENT"),
                Arguments.of(REQUEST.replace("money_movement.transfer.requested", "authentication.login.attempted"),
                        "UNSUPPORTED_EVENT"),
                Arguments.of(REQUEST.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                        "UNSUPPORTED_SCHEMA_VERSION"));
        Stream<Arguments> payloads = Stream.of(
                "{}",
                "{\"amount\": \"0\", \"currency\": \"EUR\"}",
                "{\"amount\": \"-1\", \"currency\": \"EUR\"}",
                "{\"amount\": \"invalid\", \"currency\": \"EUR\"}",
                "{\"amount\": null, \"currency\": \"EUR\"}",
                "{\"amount\": true, \"currency\": \"EUR\"}",
                "{\"amount\": 100.01, \"currency\": \"EUR\"}",
                "{\"amount\": \"100\", \"currency\": null}",
                "{\"amount\": \"100\", \"currency\": \" \"}",
                "{\"amount\": \"100\", \"currency\": 123}")
                .map(payload -> Arguments.of(REQUEST.replace(
                        "{\"amount\": \"100.00\", \"currency\": \"EUR\"}", payload), "INVALID_EVENT_DATA"));
        return Stream.concat(routing, payloads);
    }

}
