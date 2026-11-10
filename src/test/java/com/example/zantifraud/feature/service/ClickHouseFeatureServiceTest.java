package com.example.zantifraud.feature.service;

import com.example.zantifraud.event.model.*;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.persistence.clickhouse.AccountAssetMovementRepository;
import com.example.zantifraud.feature.persistence.clickhouse.model.TransferOutTotals;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static com.example.zantifraud.feature.service.ClickHouseFeatureService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClickHouseFeatureServiceTest {
    private final AccountAssetMovementRepository movements = mock(AccountAssetMovementRepository.class);
    private final ClickHouseFeatureService service = new ClickHouseFeatureService(movements);

    @Test
    void queriesSenderHistoryOnceForBothLegsAndDoesNotAddCurrentAmount() throws Exception {
        BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        when(movements.queryTransferOutTotals(any(), any(), any(), any(), any()))
                .thenReturn(new TransferOutTotals(2L, amount));

        FeatureSnapshot result = service.queryFeatures(List.of(
                event(InternalEventType.TRANSFER_IN), event(InternalEventType.TRANSFER_OUT)));

        verify(movements).queryTransferOutTotals("account-1", "EUR",
                Instant.parse("2026-10-31T22:00:00.123Z"),
                Instant.parse("2026-11-01T22:00:00.123Z"), "current-event");
        verifyNoMoreInteractions(movements);
        assertThat(result.values()).containsEntry(TRANSFER_OUT_COUNT_24H, 2L)
                .containsEntry(TRANSFER_OUT_AMOUNT_24H, amount);
        assertThat(result.unavailableFeatures()).isEmpty();
    }

    @Test
    void incomingLegHasNoConfiguredFeaturesAndDoesNotQueryHistory() {
        FeatureSnapshot result = service.queryFeatures(List.of(event(InternalEventType.TRANSFER_IN)));
        assertThat(result.values()).isEmpty();
        assertThat(result.unavailableFeatures()).isEmpty();
        verifyNoInteractions(movements);
    }

    @Test
    void emptyHistoryHasAvailableZeros() throws Exception {
        when(movements.queryTransferOutTotals(any(), any(), any(), any(), any()))
                .thenReturn(new TransferOutTotals(0L, BigInteger.ZERO));
        FeatureSnapshot result = service.queryFeatures(List.of(event(InternalEventType.TRANSFER_OUT)));
        assertThat(result.values()).containsEntry(TRANSFER_OUT_COUNT_24H, 0L)
                .containsEntry(TRANSFER_OUT_AMOUNT_24H, BigInteger.ZERO);
        assertThat(result.unavailableFeatures()).isEmpty();
    }

    @Test
    void databaseFailureMakesBothFeaturesUnavailableRatherThanZero() throws Exception {
        when(movements.queryTransferOutTotals(any(), any(), any(), any(), any()))
                .thenThrow(new SQLException("connection failed"));
        FeatureSnapshot result = service.queryFeatures(List.of(event(InternalEventType.TRANSFER_OUT)));
        assertThat(result.values()).isEmpty();
        assertThat(result.unavailableFeatures()).containsExactlyInAnyOrder(
                TRANSFER_OUT_COUNT_24H, TRANSFER_OUT_AMOUNT_24H);
    }

    @Test
    void rejectsMismatchedPayloadBeforeQueryingHistory() {
        FeatureEvent valid = event(InternalEventType.TRANSFER_OUT);
        FeatureEvent invalid = new FeatureEvent(valid.eventId(), valid.sourceEventId(), valid.operationId(),
                valid.correlationId(), valid.source(), valid.eventType(), valid.schemaVersion(), valid.eventTime(),
                new EventPayload() { });
        assertThatThrownBy(() -> service.queryFeatures(List.of(invalid))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(movements);
    }

    @Test
    void rejectsMultipleSenderLegsBeforeQueryingHistory() {
        FeatureEvent outgoing = event(InternalEventType.TRANSFER_OUT);
        FeatureEvent second = new FeatureEvent("second-event", outgoing.sourceEventId(), outgoing.operationId(),
                outgoing.correlationId(), outgoing.source(), outgoing.eventType(), outgoing.schemaVersion(),
                outgoing.eventTime(), new TransferLeg("account-2", 100L, "EUR"));

        assertThatThrownBy(() -> service.queryFeatures(List.of(outgoing, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only one outgoing leg");
        verifyNoInteractions(movements);
    }

    private FeatureEvent event(InternalEventType type) {
        boolean outgoing = type == InternalEventType.TRANSFER_OUT;
        return new FeatureEvent(outgoing ? "current-event" : "incoming-event", "source-1", "operation-1", null,
                URI.create("urn:service:transfers"),
                type, 1, new EventTime(OffsetDateTime.parse("2026-11-02T00:00:00.123999+02:00"),
                EventTime.Precision.MICROSECOND), new TransferLeg(outgoing ? "account-1" : "account-2", 100L, "EUR"));
    }
}
