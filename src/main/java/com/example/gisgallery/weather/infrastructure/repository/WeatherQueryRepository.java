package com.example.gisgallery.weather.infrastructure.repository;

import com.example.gisgallery.weather.api.dto.WeatherForecastTimeDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapPointDto;
import com.example.gisgallery.weather.api.dto.WeatherRunDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * @author clpz299
 */
@Repository
public class WeatherQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public WeatherQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<WeatherRunDto> listRuns(String model, int limit) {
        String sql = """
                select model, run_time_utc
                from weather_model_run
                where model = ?
                order by run_time_utc desc
                limit ?
                """;
        return jdbcTemplate.query(sql, runRowMapper(), model, limit);
    }

    public List<WeatherForecastTimeDto> listForecastTimes(String model, OffsetDateTime runTimeUtc) {
        String sql = """
                select ft.lead_hours, ft.valid_time_utc
                from weather_forecast_time ft
                join weather_model_run mr on mr.id = ft.run_id
                where mr.model = ? and mr.run_time_utc = ?
                order by ft.lead_hours asc
                """;
        return jdbcTemplate.query(sql, forecastTimeRowMapper(), model, Timestamp.from(runTimeUtc.toInstant()));
    }

    public List<WeatherHeatmapPointDto> queryHeatmap(String model,
                                                     OffsetDateTime runTimeUtc,
                                                     int leadHours,
                                                     String element,
                                                     String level,
                                                     double minLon,
                                                     double minLat,
                                                     double maxLon,
                                                     double maxLat) {
        String sql = """
                select p.lon, p.lat, v.value
                from weather_forecast_value v
                join weather_grid_point p on p.id = v.point_id
                join weather_forecast_time ft on ft.id = v.forecast_time_id
                join weather_model_run mr on mr.id = ft.run_id
                where mr.model = ?
                  and mr.run_time_utc = ?
                  and ft.lead_hours = ?
                  and v.element_code = ?
                  and v.level_code = ?
                  and p.geom && st_makeenvelope(?, ?, ?, ?, 4326)
                """;
        return jdbcTemplate.query(sql, heatmapRowMapper(),
                model,
                Timestamp.from(runTimeUtc.toInstant()),
                leadHours,
                element,
                level,
                minLon, minLat, maxLon, maxLat);
    }

    public String getElementUnit(String element) {
        String sql = "select unit from weather_element where code = ?";
        return Objects.toString(jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString(1) : "", element), "");
    }

    private RowMapper<WeatherRunDto> runRowMapper() {
        return (rs, rowNum) -> new WeatherRunDto(
                rs.getString("model"),
                rs.getObject("run_time_utc", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }

    private RowMapper<WeatherForecastTimeDto> forecastTimeRowMapper() {
        return (rs, rowNum) -> new WeatherForecastTimeDto(
                rs.getInt("lead_hours"),
                rs.getObject("valid_time_utc", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)
        );
    }

    private RowMapper<WeatherHeatmapPointDto> heatmapRowMapper() {
        return (ResultSet rs, int rowNum) -> new WeatherHeatmapPointDto(
                rs.getDouble("lon"),
                rs.getDouble("lat"),
                rs.getDouble("value")
        );
    }
}

