package com.example.zantifraud.feature.persistence.clickhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("feature.clickhouse")
public record ClickHouseProperties(
        @DefaultValue("jdbc:clickhouse:http://localhost:8123/default") String url,
        @DefaultValue("default") String username,
        @DefaultValue("") String password,
        @DefaultValue("2000") int connectionTimeoutMs,
        @DefaultValue("5000") int socketTimeoutMs,
        @DefaultValue("5") int queryTimeoutSeconds
) {
    public ClickHouseProperties {
        if (url == null || !url.startsWith("jdbc:clickhouse:")) {
            throw new IllegalArgumentException("feature.clickhouse.url must be a ClickHouse JDBC URL");
        }
        if (connectionTimeoutMs <= 0 || socketTimeoutMs <= 0 || queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("ClickHouse timeouts must be positive");
        }
    }
}
