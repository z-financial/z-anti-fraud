package com.example.zantifraud.feature.persistence.clickhouse.model;

import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.TransferLeg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Storage representation of one row in account_asset_movements. Component names
 * correspond to snake_case SQL columns. Source and event type use their wire values.
 * Both timestamps are UTC instants truncated to the table's millisecond precision.
 * Amount retains the upstream integer units without scaling or sign changes.
 * This record performs no database writes or automatic timestamp generation.
 */
public record AccountAssetMovementRow(
        String eventId,
        String sourceEventId,
        String operationId,
        String correlationId,
        String source,
        String eventType,
        int schemaVersion,
        String accountId,
        Long amount,
        String currency,
        Instant eventTime,
        Instant receivedAt
) {
    public AccountAssetMovementRow {
        requireNonblank(eventId, "eventId");
        requireNonblank(sourceEventId, "sourceEventId");
        requireNonblank(operationId, "operationId");
        requireNonblank(source, "source");
        requireNonblank(eventType, "eventType");
        requireNonblank(accountId, "accountId");
        requireNonblank(currency, "currency");
        if (correlationId != null) {
            requireNonblank(correlationId, "correlationId");
        }
        Objects.requireNonNull(amount, "amount is required");
        if (amount <= 0 || schemaVersion < 1) {
            throw new IllegalArgumentException("amount and schemaVersion must be positive");
        }
        eventTime = Objects.requireNonNull(eventTime, "eventTime is required").truncatedTo(ChronoUnit.MILLIS);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt is required").truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Maps a submitted leg without generating a new identity. The caller supplies
     * the original platform receipt time and must reuse it on delivery retries.
     */
    public static AccountAssetMovementRow from(FeatureEvent event, Instant receivedAt) {
        Objects.requireNonNull(event, "event is required");
        if ((event.eventType() != InternalEventType.TRANSFER_OUT
                && event.eventType() != InternalEventType.TRANSFER_IN)
                || !(event.data() instanceof TransferLeg transfer)) {
            throw new IllegalArgumentException("A submitted transfer leg is required");
        }
        return new AccountAssetMovementRow(
                event.eventId(), event.sourceEventId(), event.operationId(), event.correlationId(),
                event.source().toString(), event.eventType().wireValue(), event.schemaVersion(),
                transfer.accountId(), transfer.amount(), transfer.currency(),
                event.eventTime().value().toInstant(), receivedAt);
    }

    private static void requireNonblank(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
