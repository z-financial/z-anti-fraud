package com.example.zantifraud.evaluation.rule;

import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.event.model.EventType;
import com.example.zantifraud.event.model.Transfer;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static com.example.zantifraud.feature.model.TransferFeatureKeys.TRANSFER_OUT_AMOUNT_24H;

/** Evaluates the V1 rules and reports findings for the decision policy. */
@Component
public class RuleEngine {
    public static final String RULE_SET_VERSION = "rules-v1";

    private static final String TRANSFER_OUT_LIMIT_RULE = "transfer-out-amount-24h-limit";

    private final Long transferOutAmount24hLimit;

    public RuleEngine(@Value("${risk.rules.transfer-out-amount-24h-limit}") Long transferOutAmount24hLimit) {
        this.transferOutAmount24hLimit = Objects.requireNonNull(transferOutAmount24hLimit);
        if (transferOutAmount24hLimit <= 0) {
            throw new IllegalArgumentException("The 24-hour outgoing transfer limit must be positive");
        }
    }

    public RuleEvaluation evaluate(EventModel event, FeatureSnapshot features) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(features, "features are required");
        if (event.eventType() != EventType.TRANSFER) {
            return new RuleEvaluation(List.of());
        }
        if (!(event.data() instanceof Transfer transfer)) {
            throw new IllegalArgumentException("A transfer payload is required");
        }

        RuleOutcome outcome = evaluateTransferOutLimit(transfer, features);
        return new RuleEvaluation(List.of(outcome));
    }

    private RuleOutcome evaluateTransferOutLimit(Transfer transfer, FeatureSnapshot features) {
        Object value = features.values().get(TRANSFER_OUT_AMOUNT_24H);
        if (!(value instanceof Long history) || history < 0) {
            return new RuleOutcome(TRANSFER_OUT_LIMIT_RULE, RuleOutcomeStatus.UNAVAILABLE,
                    "TRANSFER_OUT_HISTORY_UNAVAILABLE");
        }
        // Subtract the current amount from the limit to avoid overflowing the projected total.
        long remainingAllowance = transferOutAmount24hLimit - transfer.amount();
        if (history > remainingAllowance) {
            return new RuleOutcome(TRANSFER_OUT_LIMIT_RULE, RuleOutcomeStatus.MATCHED,
                    "TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED");
        }
        return new RuleOutcome(TRANSFER_OUT_LIMIT_RULE, RuleOutcomeStatus.NOT_MATCHED, null);
    }
}
