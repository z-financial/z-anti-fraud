package com.example.zantifraud.feature.persistence.clickhouse;

import com.example.zantifraud.feature.persistence.clickhouse.config.ClickHouseProperties;
import com.example.zantifraud.feature.persistence.clickhouse.model.TransferOutTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AccountAssetMovementRepositoryTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private AccountAssetMovementRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new AccountAssetMovementRepository(dataSource, new ClickHouseProperties(
                "jdbc:clickhouse:http://localhost:8123/default", "default", "", 2000, 5000, 5));
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBigDecimal("transfer_out_count")).thenReturn(BigDecimal.valueOf(2));
        when(result.getBigDecimal("transfer_out_amount"))
                .thenReturn(BigDecimal.valueOf(Long.MAX_VALUE));
    }

    @Test
    void bindsValuesSeparatelyUsesUtcInstantsAndPreservesLargeTotals() throws Exception {
        TransferOutTotals totals = query();
        assertThat(totals).isEqualTo(new TransferOutTotals(2L, Long.MAX_VALUE));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("FROM account_asset_movements FINAL", "uniqExact(operation_id)",
                "sum(toInt128(amount))", "account_id = ?",
                "event_type = 'money_movement.transfer.out'",
                "event_time >= fromUnixTimestamp64Milli(?, 'UTC')",
                "event_time < fromUnixTimestamp64Milli(?, 'UTC')", "event_id != ?")
                .doesNotContain("currency");
        verify(statement).setString(1, "account-'1");
        verify(statement).setLong(2, Instant.parse("2026-10-31T22:00:00.123Z").toEpochMilli());
        verify(statement).setLong(3, Instant.parse("2026-11-01T22:00:00.123Z").toEpochMilli());
        verify(statement).setString(4, "current-event");
        verify(statement).setQueryTimeout(5);
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void missingAggregateRowIsFailureNotZero() throws Exception {
        when(result.next()).thenReturn(false);
        assertThatThrownBy(this::query).isInstanceOf(SQLException.class);
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void countOverflowIsFailureNotTruncation() throws Exception {
        when(result.getBigDecimal("transfer_out_count")).thenReturn(new BigDecimal("9223372036854775808"));
        assertThatThrownBy(this::query).isInstanceOf(SQLException.class);
    }

    @Test
    void amountOverflowIsFailureNotTruncation() throws Exception {
        when(result.getBigDecimal("transfer_out_amount")).thenReturn(new BigDecimal("9223372036854775808"));
        assertThatThrownBy(this::query).isInstanceOf(SQLException.class);
    }

    @Test
    void queryFailureClosesConnectionAndStatement() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLTimeoutException("timeout"));
        assertThatThrownBy(this::query).isInstanceOf(SQLTimeoutException.class);
        verify(statement).close();
        verify(connection).close();
    }

    private TransferOutTotals query() throws SQLException {
        return repository.queryTransferOutTotals("account-'1",
                Instant.parse("2026-10-31T22:00:00.123Z"),
                Instant.parse("2026-11-01T22:00:00.123Z"), "current-event");
    }
}
