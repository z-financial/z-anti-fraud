package com.example.zantifraud.event.model;

import java.util.Objects;

/** One sender and one recipient, in the same currency, submitted for risk evaluation. */
public record Transfer(String fromAccountId, String toAccountId, Long amount, String currency) implements EventPayload {
    public Transfer {
        Objects.requireNonNull(fromAccountId, "fromAccountId is required");
        Objects.requireNonNull(toAccountId, "toAccountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (fromAccountId.isBlank() || toAccountId.isBlank() || currency.isBlank() || amount <= 0) {
            throw new IllegalArgumentException("Account IDs and currency must be nonblank and amount positive");
        }
    }
}
