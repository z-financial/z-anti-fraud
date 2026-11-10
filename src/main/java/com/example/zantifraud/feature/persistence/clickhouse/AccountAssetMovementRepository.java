package com.example.zantifraud.feature.persistence.clickhouse;

import com.example.zantifraud.feature.persistence.clickhouse.config.ClickHouseProperties;
import com.example.zantifraud.feature.persistence.clickhouse.model.TransferOutTotals;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

@Repository
public class AccountAssetMovementRepository {
    // FINAL deduplicates redeliveries. Exclude the current event and keep other legs of its operation.
    static final String TRANSFER_OUT_TOTALS_SQL = """
            SELECT uniqExact(operation_id) AS transfer_out_count,
                   coalesce(sum(toInt128(amount)), toInt128(0)) AS transfer_out_amount
            FROM account_asset_movements FINAL
            WHERE account_id = ?
              AND currency = ?
              AND event_type = 'money_movement.transfer.out'
              AND event_time >= fromUnixTimestamp64Milli(?, 'UTC')
              AND event_time < fromUnixTimestamp64Milli(?, 'UTC')
              AND event_id != ?
            """;

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public AccountAssetMovementRepository(@Qualifier("clickHouseDataSource") DataSource dataSource,
                                          ClickHouseProperties settings) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = settings.queryTimeoutSeconds();
    }

    public TransferOutTotals queryTransferOutTotals(String accountId, String currency,
                                                    Instant start, Instant end,
                                                    String excludedEventId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TRANSFER_OUT_TOTALS_SQL)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setString(1, accountId);
            statement.setString(2, currency);
            // Numeric instants avoid JDBC/JVM default-timezone conversion.
            statement.setLong(3, start.toEpochMilli());
            statement.setLong(4, end.toEpochMilli());
            statement.setString(5, excludedEventId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Transfer totals query returned no aggregate row");
                }
                BigDecimal count = result.getBigDecimal("transfer_out_count");
                BigDecimal amount = result.getBigDecimal("transfer_out_amount");
                if (count == null || amount == null) {
                    throw new SQLException("Transfer totals query returned null aggregates");
                }
                try {
                    return new TransferOutTotals(count.longValueExact(), amount.toBigIntegerExact());
                } catch (ArithmeticException | IllegalArgumentException exception) {
                    throw new SQLException("Transfer totals cannot be represented exactly", exception);
                }
            }
        }
    }
}
