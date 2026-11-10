package com.example.zantifraud.evaluation.rule;

import com.example.zantifraud.evaluation.decision.DecisionEngine;
import com.example.zantifraud.evaluation.model.RecommendedAction;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static com.example.zantifraud.feature.model.TransferFeatureKeys.TRANSFER_OUT_AMOUNT_24H;
import static com.example.zantifraud.feature.model.TransferFeatureKeys.TRANSFER_OUT_COUNT_24H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineTest {
    private final RuleEngine rules = new RuleEngine(500000L, 50L, 100000L);

    @ParameterizedTest
    @CsvSource({
            "450000, 49999, USD, NOT_MATCHED",
            "450000, 50000, EUR, NOT_MATCHED",
            "450000, 50001, EUR, MATCHED",
            "450000, 50001, USD, MATCHED",
            "9223372036854775807, 9223372036854775807, USD, MATCHED"
    })
    void evaluatesTheProjectedAmountAgainstTheConfiguredLimit(
            long history, long current, String currency, RuleOutcomeStatus expected) {
        RuleEvaluation result = rules.evaluate(transfer(current, currency), features(history, 0L));

        String reason = expected == RuleOutcomeStatus.MATCHED ? "TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED" : null;
        assertThat(result.outcomes().get(0))
                .isEqualTo(new RuleOutcome("transfer-out-amount-24h-limit", expected, reason));
    }

    @ParameterizedTest
    @CsvSource({"0, NOT_MATCHED", "48, NOT_MATCHED", "49, NOT_MATCHED", "50, MATCHED",
            "9223372036854775807, MATCHED"})
    void includesCurrentTransferOnceInProjectedCount(long history, RuleOutcomeStatus expected) {
        RuleEvaluation result = rules.evaluate(transfer(1L, "EUR"), features(0L, history));

        String reason = expected == RuleOutcomeStatus.MATCHED ? "TRANSFER_OUT_COUNT_24H_LIMIT_EXCEEDED" : null;
        assertThat(result.outcomes().get(1))
                .isEqualTo(new RuleOutcome("transfer-out-count-24h-limit", expected, reason));
    }

    @ParameterizedTest
    @CsvSource({"99999, NOT_MATCHED", "100000, NOT_MATCHED", "100001, MATCHED",
            "9223372036854775807, MATCHED"})
    void evaluatesSingleAmountWithoutHistory(long amount, RuleOutcomeStatus expected) {
        RuleEvaluation result = rules.evaluate(transfer(amount, "EUR"), new FeatureSnapshot(Map.of(), Set.of()));

        String reason = expected == RuleOutcomeStatus.MATCHED ? "TRANSFER_SINGLE_AMOUNT_LIMIT_EXCEEDED" : null;
        assertThat(result.outcomes().get(2))
                .isEqualTo(new RuleOutcome("transfer-single-amount-limit", expected, reason));
    }

    @Test
    void reportsEveryMatchingRule() {
        RuleEvaluation result = rules.evaluate(transfer(100001L, "EUR"), features(450000L, 50L));

        assertThat(result.outcomes()).containsExactly(
                new RuleOutcome("transfer-out-amount-24h-limit", RuleOutcomeStatus.MATCHED,
                        "TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED"),
                new RuleOutcome("transfer-out-count-24h-limit", RuleOutcomeStatus.MATCHED,
                        "TRANSFER_OUT_COUNT_24H_LIMIT_EXCEEDED"),
                new RuleOutcome("transfer-single-amount-limit", RuleOutcomeStatus.MATCHED,
                        "TRANSFER_SINGLE_AMOUNT_LIMIT_EXCEEDED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {TRANSFER_OUT_AMOUNT_24H, TRANSFER_OUT_COUNT_24H})
    void missingUnavailableInvalidOrNegativeHistoryNeverBecomesZero(String key) {
        String availableKey = key.equals(TRANSFER_OUT_AMOUNT_24H) ? TRANSFER_OUT_COUNT_24H : TRANSFER_OUT_AMOUNT_24H;
        for (FeatureSnapshot snapshot : new FeatureSnapshot[]{
                new FeatureSnapshot(Map.of(availableKey, 0L), Set.of()),
                new FeatureSnapshot(Map.of(availableKey, 0L), Set.of(key)),
                new FeatureSnapshot(Map.of(availableKey, 0L, key, -1L), Set.of()),
                new FeatureSnapshot(Map.of(availableKey, 0L, key, "0"), Set.of())}) {
            RuleEvaluation result = rules.evaluate(transfer(1L, "EUR"), snapshot);
            assertThat(result.outcomes()).filteredOn(o -> o.status() == RuleOutcomeStatus.UNAVAILABLE).hasSize(1);
            var decision = new DecisionEngine().decide(result);
            assertThat(decision.status()).isEqualTo(RiskEvaluationStatus.UNAVAILABLE);
            assertThat(decision.recommendedAction()).isNull();
        }
    }

    @Test
    void singleAmountStillBlocksWhenBothHistoryFeaturesAreUnavailable() {
        RuleEvaluation result = rules.evaluate(transfer(100001L, "EUR"), new FeatureSnapshot(
                Map.of(), Set.of(TRANSFER_OUT_AMOUNT_24H, TRANSFER_OUT_COUNT_24H)));

        assertThat(result.outcomes()).extracting(RuleOutcome::status).containsExactly(
                RuleOutcomeStatus.UNAVAILABLE, RuleOutcomeStatus.UNAVAILABLE, RuleOutcomeStatus.MATCHED);
        var decision = new DecisionEngine().decide(result);
        assertThat(decision.status()).isEqualTo(RiskEvaluationStatus.COMPLETED);
        assertThat(decision.recommendedAction()).isEqualTo(RecommendedAction.BLOCK);
        assertThat(decision.reasons()).hasSize(1);
        assertThat(decision.reasons().get(0).ruleId()).isEqualTo("transfer-single-amount-limit");
    }

    @Test
    void usesConfiguredLimitsForEveryRuleAndHandlesMaximumCountLimit() {
        RuleEngine configured = new RuleEngine(10L, Long.MAX_VALUE, 5L);
        RuleEvaluation equal = configured.evaluate(transfer(5L, "EUR"), features(5L, Long.MAX_VALUE - 1));
        assertThat(equal.outcomes()).allMatch(o -> o.status() == RuleOutcomeStatus.NOT_MATCHED);
        RuleEvaluation exceeded = configured.evaluate(transfer(6L, "EUR"), features(5L, Long.MAX_VALUE));
        assertThat(exceeded.outcomes()).allMatch(o -> o.status() == RuleOutcomeStatus.MATCHED);
    }

    @ParameterizedTest
    @CsvSource({"0, 50, 100000", "-1, 50, 100000", "500000, 0, 100000",
            "500000, -1, 100000", "500000, 50, 0", "500000, 50, -1"})
    void rejectsNonpositiveLimits(long amount, long count, long single) {
        assertThatThrownBy(() -> new RuleEngine(amount, count, single))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
    }

    private FeatureSnapshot features(long amount, long count) {
        return new FeatureSnapshot(Map.of(TRANSFER_OUT_AMOUNT_24H, amount, TRANSFER_OUT_COUNT_24H, count), Set.of());
    }

    private EventModel transfer(long amount, String currency) {
        return new EventModel("event-1", "operation-1", null, URI.create("urn:service:transfers"),
                EventType.TRANSFER, 1,
                new EventTime(OffsetDateTime.parse("2026-11-10T12:00:00Z"), EventTime.Precision.SECOND),
                new Transfer("sender", "recipient", amount, currency));
    }
}
