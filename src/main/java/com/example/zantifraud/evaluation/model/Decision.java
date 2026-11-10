package com.example.zantifraud.evaluation.model;

import java.util.List;
import java.util.Objects;

/** Recommendation and reasons produced by the decision policy. */
public record Decision(RiskEvaluationStatus status, RecommendedAction recommendedAction,
                       List<DecisionReason> reasons) {
    public Decision {
        Objects.requireNonNull(status, "status is required");
        reasons = List.copyOf(reasons);
        if (status == RiskEvaluationStatus.UNAVAILABLE) {
            if (recommendedAction != null) {
                throw new IllegalArgumentException("An unavailable evaluation cannot recommend an action");
            }
        } else {
            Objects.requireNonNull(recommendedAction, "recommendedAction is required");
        }
    }
}
