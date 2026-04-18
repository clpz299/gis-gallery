package com.example.gisgallery.weather.application.service;

import com.example.gisgallery.weather.api.dto.WeatherHeatmapPointDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapResponseDto;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author clpz299
 */
@Service
public class WeatherHeatmapService {

    private final WeatherQueryRepository weatherQueryRepository;

    public WeatherHeatmapService(WeatherQueryRepository weatherQueryRepository) {
        this.weatherQueryRepository = weatherQueryRepository;
    }

    public WeatherHeatmapResponseDto buildHeatmap(String model,
                                                  String element,
                                                  String level,
                                                  OffsetDateTime runTimeUtc,
                                                  Integer leadHours,
                                                  Double minLon,
                                                  Double minLat,
                                                  Double maxLon,
                                                  Double maxLat,
                                                  Integer points) {
        String normalized = element == null ? "tmp_2m" : element.trim().toLowerCase(Locale.ROOT);
        String normalizedModel = model == null || model.isBlank() ? "gfs_0p25" : model.trim().toLowerCase(Locale.ROOT);
        String normalizedLevel = level == null || level.isBlank() ? "surface" : level.trim().toLowerCase(Locale.ROOT);

        double bboxMinLon = minLon == null ? 70.0 : minLon;
        double bboxMinLat = minLat == null ? 15.0 : minLat;
        double bboxMaxLon = maxLon == null ? 140.0 : maxLon;
        double bboxMaxLat = maxLat == null ? 55.0 : maxLat;

        OffsetDateTime chosenRun = runTimeUtc;
        Integer chosenLead = leadHours;
        if (chosenRun == null) {
            List<com.example.gisgallery.weather.api.dto.WeatherRunDto> runs = weatherQueryRepository.listRuns(normalizedModel, 1);
            if (!runs.isEmpty()) {
                chosenRun = runs.get(0).getRunTimeUtc();
            }
        }
        if (chosenLead == null && chosenRun != null) {
            List<com.example.gisgallery.weather.api.dto.WeatherForecastTimeDto> times = weatherQueryRepository.listForecastTimes(normalizedModel, chosenRun);
            if (!times.isEmpty()) {
                chosenLead = times.get(0).getLeadHours();
            }
        }

        if (chosenRun != null && chosenLead != null) {
            List<WeatherHeatmapPointDto> dbPoints = weatherQueryRepository.queryHeatmap(
                    normalizedModel,
                    chosenRun,
                    chosenLead,
                    normalized,
                    normalizedLevel,
                    bboxMinLon,
                    bboxMinLat,
                    bboxMaxLon,
                    bboxMaxLat
            );
            if (!dbPoints.isEmpty()) {
                String unit = weatherQueryRepository.getElementUnit(normalized);
                return new WeatherHeatmapResponseDto(normalized, unit, dbPoints);
            }
        }

        int targetPoints = points == null ? 900 : Math.max(100, Math.min(points, 5000));
        int side = (int) Math.ceil(Math.sqrt(targetPoints));
        double stepLon = (bboxMaxLon - bboxMinLon) / (side - 1.0);
        double stepLat = (bboxMaxLat - bboxMinLat) / (side - 1.0);

        List<WeatherHeatmapPointDto> out = new ArrayList<>(side * side);
        for (int y = 0; y < side; y++) {
            double lat = bboxMinLat + y * stepLat;
            for (int x = 0; x < side; x++) {
                double lon = bboxMinLon + x * stepLon;
                double value = syntheticValue(normalized, lon, lat);
                out.add(new WeatherHeatmapPointDto(lon, lat, value));
            }
        }

        return new WeatherHeatmapResponseDto(normalized, unitFor(normalized), out);
    }

    private String unitFor(String element) {
        return switch (element) {
            case "temp", "tmp_2m", "t2m", "temperature" -> "°C";
            case "rh_2m", "rh", "humidity" -> "%";
            case "prate", "precip", "precipitation" -> "mm/h";
            case "wind_u", "wind_v", "wind_10m", "wind" -> "m/s";
            case "pressure", "prmsl" -> "Pa";
            default -> "";
        };
    }

    private double syntheticValue(String element, double lon, double lat) {
        double lonRad = Math.toRadians(lon);
        double latRad = Math.toRadians(lat);
        double base = Math.sin(lonRad * 1.3) * Math.cos(latRad * 1.7);
        double wave = Math.sin((lonRad + latRad) * 2.0);
        double raw = 0.65 * base + 0.35 * wave;

        return switch (element) {
            case "temp", "tmp_2m", "t2m", "temperature" -> 22.0 + 12.0 * raw - (lat - 30.0) * 0.15;
            case "rh_2m", "rh", "humidity" -> clamp(55.0 + 30.0 * raw - (lat - 25.0) * 0.6, 0.0, 100.0);
            case "prate", "precip", "precipitation" -> Math.max(0.0, 2.0 + 4.5 * raw);
            case "wind_u", "wind_v", "wind_10m", "wind" -> Math.max(0.0, 3.0 + 6.0 * raw);
            case "pressure", "prmsl" -> 101325.0 + 1800.0 * raw;
            default -> raw;
        };
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
