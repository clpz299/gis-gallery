package com.example.gisgallery.common.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author clpz299
 */
@Component
public class DatabaseConnectionStatusLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionStatusLogger.class);

    private final Environment environment;
    private final DataSource dataSource;

    public DatabaseConnectionStatusLogger(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        String appName = environment.getProperty("spring.application.name", "application");
        String profiles = Arrays.stream(environment.getActiveProfiles()).collect(Collectors.joining(","));
        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String driver = environment.getProperty("spring.datasource.driver-class-name");

        Integer poolMax = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class);
        Integer poolMin = environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class);
        Long connTimeout = environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class);

        log.info("[{}] datasource configured url={}, username={}, driver={}, profiles={}",
                appName,
                safeJdbcUrl(url),
                safeValue(username),
                safeValue(driver),
                profiles.isBlank() ? "default" : profiles);

        if (poolMax != null || poolMin != null || connTimeout != null) {
            log.info("[{}] hikari pool maximumPoolSize={}, minimumIdle={}, connectionTimeoutMs={}",
                    appName, safeValue(poolMax), safeValue(poolMin), safeValue(connTimeout));
        }

        if (url == null || url.isBlank()) {
            log.warn("[{}] datasource url is empty, skip connectivity check", appName);
            return;
        }

        Instant start = Instant.now();
        try (Connection conn = dataSource.getConnection()) {
            Duration took = Duration.between(start, Instant.now());
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            String version = meta.getDatabaseProductVersion();

            Integer ping = null;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select 1")) {
                if (rs.next()) {
                    ping = rs.getInt(1);
                }
            }

            log.info("[{}] datasource connection OK ({} {}) ping={} tookMs={}",
                    appName,
                    safeValue(product),
                    safeValue(version),
                    safeValue(ping),
                    took.toMillis());
        } catch (Exception e) {
            Duration took = Duration.between(start, Instant.now());
            log.warn("[{}] datasource connection FAILED url={} tookMs={} err={}",
                    appName,
                    safeJdbcUrl(url),
                    took.toMillis(),
                    e.getMessage());
        }
    }

    private String safeValue(Object v) {
        return Objects.toString(v, "");
    }

    private String safeJdbcUrl(String url) {
        if (url == null) {
            return "";
        }
        String out = url;
        out = out.replaceAll("(?i)(://)([^/@:]+):([^/@]+)@", "$1***:***@");
        out = out.replaceAll("(?i)(password=)[^&;]+", "$1***");
        return out;
    }
}

