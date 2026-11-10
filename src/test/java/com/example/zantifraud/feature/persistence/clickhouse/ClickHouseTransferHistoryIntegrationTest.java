package com.example.zantifraud.feature.persistence.clickhouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real-engine check; never touches the application's table. */
@EnabledIfEnvironmentVariable(named = "CLICKHOUSE_TEST_JDBC_URL", matches = ".+")
class ClickHouseTransferHistoryIntegrationTest {
    @Test
    void aggregatesDeduplicatedOutgoingHistoryWithExactBoundariesAndWideAmounts() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", System.getenv().getOrDefault("CLICKHOUSE_TEST_USERNAME", "default"));
        properties.setProperty("password", System.getenv().getOrDefault("CLICKHOUSE_TEST_PASSWORD", ""));
        properties.setProperty("connection_timeout", "2000");
        properties.setProperty("socket_timeout", "10000");
        String table = "test_movements_" + UUID.randomUUID().toString().replace("-", "");
        Instant end = Instant.parse("2026-11-01T12:00:00.123Z");
        Instant start = end.minus(24, ChronoUnit.HOURS);
        try (Connection connection = DriverManager.getConnection(System.getenv("CLICKHOUSE_TEST_JDBC_URL"), properties);
             Statement ddl = connection.createStatement()) {
            ddl.execute(resource("account_asset_movements.sql").replace("account_asset_movements", table));
            try {
                insert(connection, table, "first", "op-1", "account-1", "EUR", "out", start, Long.MAX_VALUE);
                insert(connection, table, "second", "op-1", "account-1", "EUR", "out", start.plusMillis(1), Long.MAX_VALUE);
                insert(connection, table, "third", "op-2", "account-1", "EUR", "out", end.minusSeconds(60), 10);
                // Redelivery is inserted separately so FINAL must work even across parts.
                insert(connection, table, "third", "op-2", "account-1", "EUR", "out", end.minusSeconds(60), 10);
                insert(connection, table, "old", "old", "account-1", "EUR", "out", start.minusMillis(1), 1000);
                insert(connection, table, "at-end", "at-end", "account-1", "EUR", "out", end, 1000);
                insert(connection, table, "future", "future", "account-1", "EUR", "out", end.plusMillis(1), 1000);
                insert(connection, table, "current-event", "op-1", "account-1", "EUR", "out", end.minusSeconds(1), 1000);
                insert(connection, table, "incoming", "incoming", "account-1", "EUR", "in", start, 1000);
                insert(connection, table, "other-account", "other-account", "account-2", "EUR", "out", start, 1000);
                insert(connection, table, "other-currency", "other-currency", "account-1", "USD", "out", start, 1000);

                String sql = AccountAssetMovementRepository.TRANSFER_OUT_TOTALS_SQL
                        .replace("account_asset_movements", table);
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    query.setQueryTimeout(10);
                    query.setString(1, "account-1");
                    query.setString(2, "EUR");
                    query.setLong(3, start.toEpochMilli());
                    query.setLong(4, end.toEpochMilli());
                    query.setString(5, "current-event");
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBigDecimal("transfer_out_count").longValueExact()).isEqualTo(2);
                        assertThat(result.getBigDecimal("transfer_out_amount").toBigIntegerExact())
                                .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.TEN));
                    }
                    query.setString(1, "no-history");
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBigDecimal("transfer_out_count").longValueExact()).isZero();
                        assertThat(result.getBigDecimal("transfer_out_amount").toBigIntegerExact()).isZero();
                    }
                }
            } finally {
                ddl.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private void insert(Connection connection, String table, String eventId, String operationId,
                        String accountId, String currency, String direction, Instant time, long amount) throws SQLException {
        String sql = "INSERT INTO " + table + " (event_id, source_event_id, operation_id, source, event_type, schema_version, "
                + "account_id, amount, currency, event_time, received_at) "
                + "SELECT ?, 'source-test', ?, 'urn:service:transfers', ?, 1, ?, ?, ?, "
                + "fromUnixTimestamp64Milli(?, 'UTC'), fromUnixTimestamp64Milli(?, 'UTC')";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, eventId);
            insert.setString(2, operationId);
            insert.setString(3, "money_movement.transfer." + direction);
            insert.setString(4, accountId);
            insert.setLong(5, amount);
            insert.setString(6, currency);
            insert.setLong(7, time.toEpochMilli());
            insert.setLong(8, time.toEpochMilli());
            insert.executeUpdate();
        }
    }

    private String resource(String name) throws Exception {
        return new ClassPathResource("db/clickhouse/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
