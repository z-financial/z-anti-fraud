package com.example.zantifraud.evaluation.decision;

import com.example.zantifraud.evaluation.model.Decision;
import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.RecommendedAction;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;
import com.example.zantifraud.evaluation.rule.RuleEvaluation;
import com.example.zantifraud.evaluation.rule.RuleOutcome;
import com.example.zantifraud.evaluation.rule.RuleOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DecisionEngineTest {
    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void blocksOnMatchesEvenWhenAnotherRuleIsUnavailable() {
        RuleEvaluation evaluation = new RuleEvaluation(List.of(
                new RuleOutcome("missing-history", RuleOutcomeStatus.UNAVAILABLE, "HISTORY_UNAVAILABLE"),
                new RuleOutcome("amount-rule", RuleOutcomeStatus.MATCHED, "AMOUNT_EXCEEDED"),
                new RuleOutcome("recipient-rule", RuleOutcomeStatus.NOT_MATCHED, null),
                new RuleOutcome("count-rule", RuleOutcomeStatus.MATCHED, "COUNT_EXCEEDED")));

        Decision decision = engine.decide(evaluation);

        assertThat(decision.status()).isEqualTo(RiskEvaluationStatus.COMPLETED);
        assertThat(decision.recommendedAction()).isEqualTo(RecommendedAction.BLOCK);
        assertThat(decision.reasons()).extracting(DecisionReason::ruleId, DecisionReason::code)
                .containsExactly(tuple("amount-rule", "AMOUNT_EXCEEDED"), tuple("count-rule", "COUNT_EXCEEDED"));
    }

    @Test
    void doesNotAllowAnEventWithoutApplicableRules() {
        Decision decision = engine.decide(new RuleEvaluation(List.of()));

        assertThat(decision.status()).isEqualTo(RiskEvaluationStatus.UNAVAILABLE);
        assertThat(decision.recommendedAction()).isNull();
        assertThat(decision.reasons()).extracting(DecisionReason::code).containsExactly("NO_APPLICABLE_RULES");
    }
}
