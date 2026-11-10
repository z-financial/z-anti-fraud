package com.example.zantifraud.evaluation.api.http.response;

import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.RecommendedAction;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evaluation outcome, distinct from business execution.
 * Completed/degraded outcomes require a recommendation and decision
 * metadata. An unavailable outcome has no recommendation or decision metadata.
 * requestId identifies the current attempt. Decision metadata is issued per evaluation
 * until durable replay is implemented.
 */
public record RiskEvaluationResponse(
        String requestId,
        String decisionId,
        String eventId,
        String operationId,
        RiskEvaluationStatus status,
        RecommendedAction recommendedAction,
        List<DecisionReason> reasons,
        String ruleSetVersion,
        String policyVersion,
        Instant evaluatedAt
) {
    public RiskEvaluationResponse {
        requireText(requestId, "requestId");
        requireText(eventId, "eventId");
        requireText(operationId, "operationId");
        Objects.requireNonNull(status, "status is required");
        reasons = List.copyOf(reasons);
        if (status == RiskEvaluationStatus.UNAVAILABLE) {
            if (recommendedAction != null || decisionId != null || ruleSetVersion != null
                    || policyVersion != null || evaluatedAt != null) {
                throw new IllegalArgumentException("Unavailable evaluation cannot carry decision metadata");
            }
        } else {
            Objects.requireNonNull(recommendedAction, "recommendedAction is required");
            requireText(decisionId, "decisionId");
            requireText(ruleSetVersion, "ruleSetVersion");
            requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt is required");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
