package com.example.zantifraud.evaluation.decision;

import com.example.zantifraud.evaluation.model.Decision;
import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.RecommendedAction;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;
import com.example.zantifraud.evaluation.rule.RuleEvaluation;
import com.example.zantifraud.evaluation.rule.RuleOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Applies the V1 decision policy to the findings of the rule engine. */
@Component
public class DecisionEngine {
    public static final String POLICY_VERSION = "policy-v1";

    public Decision decide(RuleEvaluation evaluation) {
        if (evaluation.outcomes().isEmpty()) {
            return new Decision(RiskEvaluationStatus.UNAVAILABLE, null,
                    List.of(new DecisionReason("NO_APPLICABLE_RULES", "No risk rules apply to the event.", null)));
        }

        List<DecisionReason> violations = new ArrayList<>();
        List<DecisionReason> unavailable = new ArrayList<>();
        for (RuleOutcome outcome : evaluation.outcomes()) {
            switch (outcome.status()) {
                case MATCHED -> violations.add(new DecisionReason(
                        outcome.reasonCode(), "A risk rule detected a violation.", outcome.ruleId()));
                case UNAVAILABLE -> unavailable.add(new DecisionReason(
                        outcome.reasonCode(), "A required risk rule could not be evaluated.", outcome.ruleId()));
                case NOT_MATCHED -> { }
            }
        }

        if (!violations.isEmpty()) {
            return new Decision(RiskEvaluationStatus.COMPLETED, RecommendedAction.BLOCK, violations);
        }
        if (!unavailable.isEmpty()) {
            return new Decision(RiskEvaluationStatus.UNAVAILABLE, null, unavailable);
        }
        return new Decision(RiskEvaluationStatus.COMPLETED, RecommendedAction.ALLOW, List.of());
    }
}
