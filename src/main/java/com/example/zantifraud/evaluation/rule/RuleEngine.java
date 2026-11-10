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
import static com.example.zantifraud.feature.model.TransferFeatureKeys.TRANSFER_OUT_COUNT_24H;

/** Evaluates the V1 rules and reports findings for the decision policy. */
@Component
public class RuleEngine {
    public static final String RULE_SET_VERSION = "rules-v2";

    private static final String TRANSFER_OUT_LIMIT_RULE = "transfer-out-amount-24h-limit";
    private static final String TRANSFER_COUNT_LIMIT_RULE = "transfer-out-count-24h-limit";
    private static final String TRANSFER_SINGLE_LIMIT_RULE = "transfer-single-amount-limit";

    private final long transferOutAmount24hLimit;
    private final long transferOutCount24hLimit;
    private final long transferSingleAmountLimit;

    public RuleEngine(
            @Value("${risk.rules.transfer-out-amount-24h-limit}") Long transferOutAmount24hLimit,
            @Value("${risk.rules.transfer-out-count-24h-limit}") Long transferOutCount24hLimit,
            @Value("${risk.rules.transfer-single-amount-limit}") Long transferSingleAmountLimit) {
        this.transferOutAmount24hLimit = positiveLimit(transferOutAmount24hLimit, "24-hour outgoing amount");
        this.transferOutCount24hLimit = positiveLimit(transferOutCount24hLimit, "24-hour outgoing count");
        this.transferSingleAmountLimit = positiveLimit(transferSingleAmountLimit, "single-transfer amount");
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

        return new RuleEvaluation(List.of(
                evaluateTransferOutLimit(transfer, features),
                evaluateTransferCountLimit(features),
                evaluateTransferSingleLimit(transfer)));
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

    private RuleOutcome evaluateTransferCountLimit(FeatureSnapshot features) {
        Object value = features.values().get(TRANSFER_OUT_COUNT_24H);
        if (!(value instanceof Long history) || history < 0) {
            return new RuleOutcome(TRANSFER_COUNT_LIMIT_RULE, RuleOutcomeStatus.UNAVAILABLE,
                    "TRANSFER_OUT_COUNT_HISTORY_UNAVAILABLE");
        }
        // history + 1 > limit, without overflowing even when history is Long.MAX_VALUE.
        if (history >= transferOutCount24hLimit) {
            return new RuleOutcome(TRANSFER_COUNT_LIMIT_RULE, RuleOutcomeStatus.MATCHED,
                    "TRANSFER_OUT_COUNT_24H_LIMIT_EXCEEDED");
        }
        return new RuleOutcome(TRANSFER_COUNT_LIMIT_RULE, RuleOutcomeStatus.NOT_MATCHED, null);
    }

    private RuleOutcome evaluateTransferSingleLimit(Transfer transfer) {
        if (transfer.amount() > transferSingleAmountLimit) {
            return new RuleOutcome(TRANSFER_SINGLE_LIMIT_RULE, RuleOutcomeStatus.MATCHED,
                    "TRANSFER_SINGLE_AMOUNT_LIMIT_EXCEEDED");
        }
        return new RuleOutcome(TRANSFER_SINGLE_LIMIT_RULE, RuleOutcomeStatus.NOT_MATCHED, null);
    }

    private static long positiveLimit(Long limit, String name) {
        Objects.requireNonNull(limit, name + " limit is required");
        if (limit <= 0) {
            throw new IllegalArgumentException("The " + name + " limit must be positive");
        }
        return limit;
    }
}
