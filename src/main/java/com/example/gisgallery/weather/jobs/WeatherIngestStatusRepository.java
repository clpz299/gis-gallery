package com.example.gisgallery.weather.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * @author clpz299
 */
@Repository
public class WeatherIngestStatusRepository {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestStatusRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public WeatherIngestStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsValues(String model, OffsetDateTime runTimeUtc, int leadHours, String element, String level) {
        Instant start = Instant.now();
        String sql = """
                select exists(
                  select 1
                  from weather_forecast_value v
                  join weather_forecast_time ft on ft.id = v.forecast_time_id
                  join weather_model_run mr on mr.id = ft.run_id
                  where mr.model = ?
                    and mr.run_time_utc = ?
                    and ft.lead_hours = ?
                    and v.element_code = ?
                    and v.level_code = ?
                  limit 1
                )
                """;
        Boolean r = jdbcTemplate.queryForObject(sql, Boolean.class,
                model,
                Timestamp.from(runTimeUtc.toInstant()),
                leadHours,
                element,
                level);
        boolean exists = Boolean.TRUE.equals(r);
        long tookMs = Duration.between(start, Instant.now()).toMillis();
        log.info("[weather.gfs][status-check] model={} runTimeUtc={} leadHours={} element={} level={} exists={} tookMs={}",
                model, runTimeUtc, leadHours, element, level, exists, tookMs);
        return exists;
    }
}
