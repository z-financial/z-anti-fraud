package com.example.zantifraud.feature.service;

import com.example.zantifraud.event.model.FeatureEvent;
import com.example.zantifraud.event.model.InternalEventType;
import com.example.zantifraud.event.model.TransferLeg;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.persistence.clickhouse.AccountAssetMovementRepository;
import com.example.zantifraud.feature.persistence.clickhouse.model.TransferOutTotals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ClickHouseFeatureService implements FeatureService {
    public static final String TRANSFER_OUT_COUNT_24H = "transfer-out-count-24h";
    public static final String TRANSFER_OUT_AMOUNT_24H = "transfer-out-amount-24h";
    private static final Set<String> TRANSFER_FEATURES = Set.of(TRANSFER_OUT_COUNT_24H, TRANSFER_OUT_AMOUNT_24H);
    private static final Logger log = LoggerFactory.getLogger(ClickHouseFeatureService.class);

    private final AccountAssetMovementRepository movements;

    public ClickHouseFeatureService(AccountAssetMovementRepository movements) {
        this.movements = movements;
    }

    /** Persistence remains a placeholder until the ingestion path is implemented. */
    @Override
    public void persistEvents(List<FeatureEvent> events) {
        Objects.requireNonNull(events, "events are required");
        events.forEach(event -> Objects.requireNonNull(event, "event is required"));
    }

    /** V1 uses the single sender leg to populate the outgoing history features. */
    @Override
    public FeatureSnapshot queryFeatures(List<FeatureEvent> events) {
        Objects.requireNonNull(events, "events are required");
        FeatureEvent outgoing = null;
        for (FeatureEvent event : events) {
            Objects.requireNonNull(event, "event is required");
            if (event.eventType() == InternalEventType.TRANSFER_OUT) {
                if (outgoing != null) {
                    throw new IllegalArgumentException("V1 supports only one outgoing leg per feature snapshot");
                }
                outgoing = event;
            }
        }
        if (outgoing == null) {
            return new FeatureSnapshot(Map.of(), Set.of());
        }
        if (!(outgoing.data() instanceof TransferLeg transfer)) {
            throw new IllegalArgumentException("A submitted transfer payload is required");
        }
        Instant end = outgoing.eventTime().value().toInstant().truncatedTo(ChronoUnit.MILLIS);
        Instant start = end.minus(24, ChronoUnit.HOURS);
        try {
            TransferOutTotals totals = movements.queryTransferOutTotals(
                    transfer.accountId(), transfer.currency(), start, end, outgoing.eventId());
            return new FeatureSnapshot(Map.of(
                    TRANSFER_OUT_COUNT_24H, totals.count(),
                    TRANSFER_OUT_AMOUNT_24H, totals.amount()), Set.of());
        } catch (SQLException exception) {
            // No account IDs, credentials, SQL parameters, or driver messages in logs.
            log.warn("ClickHouse transfer history unavailable (SQL state: {})", exception.getSQLState());
            return new FeatureSnapshot(Map.of(), TRANSFER_FEATURES);
        }
    }
}
