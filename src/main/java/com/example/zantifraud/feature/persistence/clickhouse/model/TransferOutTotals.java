package com.example.zantifraud.feature.persistence.clickhouse.model;

/** Distinct business operations and sum of their matching OUT legs in upstream units. */
public record TransferOutTotals(long count, long amount) {
    public TransferOutTotals {
        if (count < 0 || amount < 0) {
            throw new IllegalArgumentException("Transfer totals must be nonnegative");
        }
    }
}
