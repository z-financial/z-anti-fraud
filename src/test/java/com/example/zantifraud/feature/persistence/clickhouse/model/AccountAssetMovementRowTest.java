package com.example.zantifraud.feature.persistence.clickhouse.model;

import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.EventPayload;
import com.example.zantifraud.event.model.EventTime;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.TransferLeg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountAssetMovementRowTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-01T00:05:00.987654Z");

    @ParameterizedTest
    @EnumSource(value = InternalEventType.class, names = {"TRANSFER_OUT", "TRANSFER_IN"})
    void preservesLegMetadataAndAmountAndNormalizesTimestamps(InternalEventType type) {
        FeatureEvent event = event("event-1", type,
                new TransferLeg("account-1", Long.MAX_VALUE, "EUR"));

        AccountAssetMovementRow row = AccountAssetMovementRow.from(event, RECEIVED_AT);

        assertThat(row).isEqualTo(new AccountAssetMovementRow(
                "event-1", "source-1", "operation-1", "deal-1", "urn:service:transfers", type.wireValue(), 1,
                "account-1", Long.MAX_VALUE, "EUR",
                Instant.parse("2026-10-31T22:00:00.123Z"), Instant.parse("2026-11-01T00:05:00.987Z")));
        // UTC conversion crosses the source's month boundary; no rounding into a later millisecond.
        assertThat(row.eventTime()).isEqualTo(Instant.parse("2026-10-31T22:00:00.123Z"));
        assertThat(row.receivedAt()).isEqualTo(Instant.parse("2026-11-01T00:05:00.987Z"));
    }

    @Test
    void retriesKeepTheSameRowWhileRelatedLegsKeepTheirOwnIdentities() {
        FeatureEvent outgoing = event("event-out", InternalEventType.TRANSFER_OUT,
                new TransferLeg("sender", 30000L, "EUR"));
        FeatureEvent incoming = event("event-in", InternalEventType.TRANSFER_IN,
                new TransferLeg("recipient", 30000L, "EUR"));

        AccountAssetMovementRow out = AccountAssetMovementRow.from(outgoing, RECEIVED_AT);
        AccountAssetMovementRow in = AccountAssetMovementRow.from(incoming, RECEIVED_AT);

        assertThat(AccountAssetMovementRow.from(outgoing, RECEIVED_AT)).isEqualTo(out);
        assertThat(out.eventId()).isNotEqualTo(in.eventId());
        assertThat(out.operationId()).isEqualTo(in.operationId());
        assertThat(out.sourceEventId()).isEqualTo(in.sourceEventId());
        assertThat(out.accountId()).isNotEqualTo(in.accountId());
        assertThat(out.amount()).isEqualTo(in.amount());
    }

    @Test
    void rejectsPayloadThatDoesNotMatchTheTransferInternalEventType() {
        FeatureEvent event = event("event-1", InternalEventType.TRANSFER_OUT, new EventPayload() { });

        assertThatThrownBy(() -> AccountAssetMovementRow.from(event, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A submitted transfer leg is required");
    }

    private FeatureEvent event(String id, InternalEventType type, EventPayload payload) {
        return new FeatureEvent(id, "source-1", "operation-1", "deal-1", URI.create("urn:service:transfers"), type, 1,
                new EventTime(OffsetDateTime.parse("2026-11-01T00:00:00.123999999+02:00"),
                        EventTime.Precision.NANOSECOND), payload);
    }
}
