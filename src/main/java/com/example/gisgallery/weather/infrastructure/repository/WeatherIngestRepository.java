package com.example.gisgallery.weather.infrastructure.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @author clpz299
 */
@Repository
public class WeatherIngestRepository {

    private final JdbcTemplate jdbcTemplate;

    public WeatherIngestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long upsertModelRun(String model, OffsetDateTime runTimeUtc) {
        String sql = """
                insert into weather_model_run(model, run_time_utc)
                values (?, ?)
                on conflict (model, run_time_utc) do update set model = excluded.model
                returning id
                """;
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, model, Timestamp.from(runTimeUtc.toInstant())));
    }

    public long upsertForecastTime(long runId, int leadHours, OffsetDateTime validTimeUtc) {
        String sql = """
                insert into weather_forecast_time(run_id, lead_hours, valid_time_utc)
                values (?, ?, ?)
                on conflict (run_id, lead_hours) do update set valid_time_utc = excluded.valid_time_utc
                returning id
                """;
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, runId, leadHours, Timestamp.from(validTimeUtc.toInstant())));
    }

    public void upsertElement(String code, String name, String unit) {
        String sql = """
                insert into weather_element(code, name, unit)
                values (?, ?, ?)
                on conflict (code) do update set name = excluded.name, unit = excluded.unit
                """;
        jdbcTemplate.update(sql, code, name, unit == null ? "" : unit);
    }

    public void upsertLevel(String code, String levelType, Double levelValue, String unit) {
        String sql = """
                insert into weather_level(code, level_type, level_value, unit)
                values (?, ?, ?, ?)
                on conflict (code) do update set level_type = excluded.level_type, level_value = excluded.level_value, unit = excluded.unit
                """;
        jdbcTemplate.update(sql, code, levelType, levelValue, unit == null ? "" : unit);
    }

    public void batchInsertGridPoints(List<PointRow> points) {
        String sql = """
                insert into weather_grid_point(lon, lat, geom)
                values (?, ?, st_setsrid(st_makepoint(?, ?), 4326))
                on conflict (lon, lat) do nothing
                """;
        jdbcTemplate.batchUpdate(sql, points, 500, (ps, row) -> {
            ps.setDouble(1, row.lon());
            ps.setDouble(2, row.lat());
            ps.setDouble(3, row.lon());
            ps.setDouble(4, row.lat());
        });
    }

    public int upsertForecastValuesByLonLat(long forecastTimeId, String elementCode, String levelCode, List<ValueLonLatRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append("(?, ?, ?)");
        }

        String sql = """
                with vals(lon, lat, value) as (values %s)
                insert into weather_forecast_value(forecast_time_id, element_code, level_code, point_id, value)
                select ?, ?, ?, p.id, vals.value
                from vals
                join weather_grid_point p on p.lon = vals.lon and p.lat = vals.lat
                on conflict (forecast_time_id, element_code, level_code, point_id)
                do update set value = excluded.value
                """.formatted(values);

        Object[] params = new Object[3 + rows.size() * 3];
        int idx = 0;
        for (ValueLonLatRow r : rows) {
            params[idx++] = r.lon();
            params[idx++] = r.lat();
            params[idx++] = r.value();
        }
        params[idx++] = forecastTimeId;
        params[idx++] = elementCode;
        params[idx] = levelCode;

        return jdbcTemplate.update(sql, params);
    }

    public record PointRow(double lon, double lat) {
    }

    public record ValueLonLatRow(double lon, double lat, double value) {
    }
}
