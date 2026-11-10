package com.example.zantifraud.feature.persistence.clickhouse.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseConfiguration {
    @Bean
    public DataSource clickHouseDataSource(ClickHouseProperties settings) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(settings.url());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        Properties properties = new Properties();
        properties.setProperty("connection_timeout", Integer.toString(settings.connectionTimeoutMs()));
        properties.setProperty("socket_timeout", Integer.toString(settings.socketTimeoutMs()));
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }
}
