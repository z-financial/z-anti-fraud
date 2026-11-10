package com.example.zantifraud.evaluation.rule;

import java.util.Objects;

/** The finding from one rule, with a stable reason code where applicable. */
public record RuleOutcome(String ruleId, RuleOutcomeStatus status, String reasonCode) {
    public RuleOutcome {
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(status, "status is required");
        if (ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (reasonCode != null && reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must be nonblank when present");
        }
        if (status != RuleOutcomeStatus.NOT_MATCHED && reasonCode == null) {
            throw new IllegalArgumentException("reasonCode is required for a match or unavailable rule");
        }
    }
}
