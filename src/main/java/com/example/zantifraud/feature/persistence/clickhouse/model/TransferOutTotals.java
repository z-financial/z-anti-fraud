package com.example.zantifraud.feature.persistence.clickhouse.model;

import java.math.BigInteger;
import java.util.Objects;

/** Distinct business operations and sum of their matching OUT legs in upstream units. */
public record TransferOutTotals(long count, BigInteger amount) {
    public TransferOutTotals {
        Objects.requireNonNull(amount, "amount is required");
        if (count < 0 || amount.signum() < 0) {
            throw new IllegalArgumentException("Transfer totals must be nonnegative");
        }
    }
}
