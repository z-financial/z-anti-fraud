package com.example.zantifraud.evaluation.rule;

import java.util.List;

/** Findings from the rules applicable to one business event. */
public record RuleEvaluation(List<RuleOutcome> outcomes) {
    public RuleEvaluation {
        outcomes = List.copyOf(outcomes);
    }
}
