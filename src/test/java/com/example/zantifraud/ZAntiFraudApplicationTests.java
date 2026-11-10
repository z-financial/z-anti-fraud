package com.example.zantifraud;

import com.example.zantifraud.feature.persistence.clickhouse.AccountAssetMovementRepository;
import com.example.zantifraud.feature.persistence.clickhouse.model.TransferOutTotals;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "risk.rules.transfer-out-amount-24h-limit=100000")
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
                "eventType": "money_movement.transfer",
                "schemaVersion": 1,
                "eventTime": {"value": "2026-11-10T12:00:00Z", "precision": "SECOND"},
                "data": {"fromAccountId": "account-1", "toAccountId": "account-2", "amount": 10000, "currency": "EUR"}
              }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountAssetMovementRepository movements;

    @Test
    void contextLoads() {
    }

    @ParameterizedTest
    @CsvSource({"90000, ALLOW", "90001, BLOCK"})
    void returnsTheRecommendationAndDecisionMetadata(long history, String action) throws Exception {
        when(movements.queryTransferOutTotals(any(), any(), any(), any()))
                .thenReturn(new TransferOutTotals(1L, history));
        var result = mvc.perform(post("/api/v1/risk-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.operationId").value("transfer-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendedAction").value(action))
                .andExpect(jsonPath("$.decisionId").isNotEmpty())
                .andExpect(jsonPath("$.ruleSetVersion").value("rules-v1"))
                .andExpect(jsonPath("$.policyVersion").value("policy-v1"))
                .andExpect(jsonPath("$.evaluatedAt").isNotEmpty());
        if ("BLOCK".equals(action)) {
            result.andExpect(jsonPath("$.reasons[0].code").value("TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.reasons[0].ruleId").value("transfer-out-amount-24h-limit"));
        } else {
            result.andExpect(jsonPath("$.reasons").isEmpty());
        }
    }

    @Test
    void returnsUnavailableWhenRequiredHistoryCannotBeRead() throws Exception {
        when(movements.queryTransferOutTotals(any(), any(), any(), any()))
                .thenThrow(new SQLException("History is unavailable"));
        mvc.perform(post("/api/v1/risk-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.recommendedAction").value(nullValue()))
                .andExpect(jsonPath("$.decisionId").value(nullValue()))
                .andExpect(jsonPath("$.reasons[0].code").value("TRANSFER_OUT_HISTORY_UNAVAILABLE"))
                .andExpect(jsonPath("$.reasons[0].ruleId").value("transfer-out-amount-24h-limit"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedOrInvalidEvents")
    void rejectsUnsupportedOrInvalidEvents(String body, String code) throws Exception {
        mvc.perform(post("/api/v1/risk-evaluations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    static Stream<Arguments> unsupportedOrInvalidEvents() throws Exception {
        Stream<Arguments> routing = Stream.of(
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.confirm.out"), "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.confirm.in"), "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.out"), "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.in"), "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("urn:service:transfers", "urn:service:other"), "UNSUPPORTED_EVENT"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "authentication.login.attempted"),
                        "UNSUPPORTED_EVENT"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.requested"),
                        "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("money_movement.transfer", "money_movement.transfer.completed"),
                        "INVALID_REQUEST"),
                Arguments.of(REQUEST.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                        "UNSUPPORTED_SCHEMA_VERSION"));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode valid = (ObjectNode) mapper.readTree(REQUEST);
        Stream.Builder<Arguments> invalid = Stream.builder();
        for (String amount : new String[]{"0", "-1", "null", "true", "100.01", "100.0",
                "\"100\"", "\"invalid\"", "9223372036854775808", "-9223372036854775809"}) {
            ObjectNode body = valid.deepCopy();
            ((ObjectNode) body.get("event").get("data")).set("amount", mapper.readTree(amount));
            invalid.add(Arguments.of(body.toString(), "INVALID_EVENT_DATA"));
        }
        for (String field : new String[]{"fromAccountId", "toAccountId", "currency"}) {
            for (String value : new String[]{"\" \"", "123", "true", "[]", "{}"}) {
                ObjectNode body = valid.deepCopy();
                ((ObjectNode) body.get("event").get("data")).set(field, mapper.readTree(value));
                invalid.add(Arguments.of(body.toString(), "INVALID_EVENT_DATA"));
            }
        }
        for (String field : new String[]{"fromAccountId", "toAccountId", "currency", "amount"}) {
            ObjectNode body = valid.deepCopy();
            ObjectNode data = (ObjectNode) body.get("event").get("data");
            data.putNull(field);
            invalid.add(Arguments.of(body.toString(), "INVALID_EVENT_DATA"));
            data.remove(field);
            invalid.add(Arguments.of(body.toString(), "INVALID_EVENT_DATA"));
        }
        Stream<Arguments> payloads = invalid.build();
        return Stream.concat(routing, payloads);
    }

}
