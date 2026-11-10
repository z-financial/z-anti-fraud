package com.example.zantifraud.evaluation.model;

import java.util.Objects;

/**
 * A reason for the final recommendation or for evaluation unavailability.
 * Callers should branch on the stable code, not the human-readable message.
 * The rule ID is absent for reasons originating outside a rule.
 */
public record DecisionReason(String code, String message, String ruleId) {
    public DecisionReason {
        Objects.requireNonNull(code, "code is required");
        Objects.requireNonNull(message, "message is required");
        if (code.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("code and message must not be blank");
        }
        if (ruleId != null && ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must be absent or nonblank");
        }
    }
}
