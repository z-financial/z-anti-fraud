package com.example.zantifraud.event.model;

import java.util.Objects;

/**
 * One internal submitted-transfer leg, owned by accountId. The internal event type
 * defines OUT or IN direction; eventId identifies this leg and sourceEventId
 * identifies the upstream submission. Both legs share the business operationId.
 * Amount is a positive integer in the currency/asset units defined by upstream;
 * it is never a signed balance delta. Related legs are linked by operationId.
 */
public record TransferLeg(
        String accountId,
        Long amount,
        String currency
) implements FeaturePayload {
    public TransferLeg {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
