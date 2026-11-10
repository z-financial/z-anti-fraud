package com.example.zantifraud.evaluation.rule;

import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static com.example.zantifraud.feature.model.TransferFeatureKeys.TRANSFER_OUT_AMOUNT_24H;
import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {
    private final RuleEngine rules = new RuleEngine(5000L);

    @ParameterizedTest
    @CsvSource({
            "0, 4999, USD, NOT_MATCHED",
            "4999, 1, EUR, NOT_MATCHED",
            "4999, 2, EUR, MATCHED",
            "4999, 2, USD, MATCHED",
            "9223372036854775807, 9223372036854775807, USD, MATCHED"
    })
    void evaluatesTheProjectedAmountAgainstTheConfiguredLimit(
            long history, long current, String currency, RuleOutcomeStatus expected) {
        FeatureSnapshot features = new FeatureSnapshot(
                Map.of(TRANSFER_OUT_AMOUNT_24H, history), Set.of());

        RuleEvaluation result = rules.evaluate(transfer(current, currency), features);

        String reason = expected == RuleOutcomeStatus.MATCHED ? "TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED" : null;
        assertThat(result.outcomes()).containsExactly(new RuleOutcome("transfer-out-amount-24h-limit", expected, reason));
    }

    @Test
    void reportsUnavailableWhenTheHistoryFeatureIsMissingOrUnavailable() {
        RuleOutcome expected = new RuleOutcome("transfer-out-amount-24h-limit", RuleOutcomeStatus.UNAVAILABLE,
                "TRANSFER_OUT_HISTORY_UNAVAILABLE");
        EventModel event = transfer(1L, "EUR");

        assertThat(rules.evaluate(event, new FeatureSnapshot(Map.of(), Set.of())).outcomes())
                .containsExactly(expected);
        assertThat(rules.evaluate(event, new FeatureSnapshot(Map.of(), Set.of(TRANSFER_OUT_AMOUNT_24H))).outcomes())
                .containsExactly(expected);
    }

    private EventModel transfer(long amount, String currency) {
        return new EventModel("event-1", "operation-1", null, URI.create("urn:service:transfers"),
                EventType.TRANSFER, 1,
                new EventTime(OffsetDateTime.parse("2026-11-10T12:00:00Z"), EventTime.Precision.SECOND),
                new Transfer("sender", "recipient", amount, currency));
    }
}
