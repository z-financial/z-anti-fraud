package com.example.zantifraud.event.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Minimal schema-v1 payload for the transfer source used by this MVP. */
public record TransferRequested(BigDecimal amount, String currency) implements EventPayload {
    public TransferRequested {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
