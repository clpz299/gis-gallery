package com.example.gisgallery.weather.application.service;

import com.example.gisgallery.configurer.WeatherGfsProperties;
import com.example.gisgallery.weather.api.dto.WeatherForecastTimeDto;
import com.example.gisgallery.weather.api.dto.WeatherGridTileResponseDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapPointDto;
import com.example.gisgallery.weather.api.dto.WeatherRunDto;
import com.example.gisgallery.weather.infrastructure.repository.WeatherQueryRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author clpz299
 */
@Service
public class WeatherGridTileService {

    private final WeatherQueryRepository weatherQueryRepository;
    private final WeatherGfsProperties weatherGfsProperties;

    public WeatherGridTileService(WeatherQueryRepository weatherQueryRepository, WeatherGfsProperties weatherGfsProperties) {
        this.weatherQueryRepository = weatherQueryRepository;
        this.weatherGfsProperties = weatherGfsProperties;
    }

    public WeatherGridTileResponseDto buildTile(int z,
                                                int x,
                                                int y,
                                                Integer size,
                                                String model,
                                                String element,
                                                String level,
                                                OffsetDateTime runTimeUtc,
                                                Integer leadHours) {
        int normalizedZ = Math.max(0, z);
        int n = 1 << Math.min(30, normalizedZ);
        int normalizedX = ((x % n) + n) % n;
        int normalizedY = y < 0 ? (-y - 1) : y;
        if (normalizedY < 0 || normalizedY >= n) {
            int gridSize = size == null ? 32 : Math.max(8, Math.min(size, 96));
            return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, "", null, null, emptyValues(gridSize));
        }

        int gridSize = size == null ? 32 : Math.max(8, Math.min(size, 96));
        String normalizedModel = model == null || model.isBlank() ? "gfs_0p25" : model.trim().toLowerCase(Locale.ROOT);
        String normalizedElement = element == null || element.isBlank() ? "temp" : element.trim().toLowerCase(Locale.ROOT);
        String normalizedLevel = level == null || level.isBlank() ? "surface" : level.trim().toLowerCase(Locale.ROOT);

        OffsetDateTime chosenRun = runTimeUtc;
        Integer chosenLead = leadHours;

        if (chosenRun == null) {
            List<WeatherRunDto> runs = weatherQueryRepository.listRuns(normalizedModel, 1);
            if (!runs.isEmpty()) {
                chosenRun = runs.get(0).getRunTimeUtc();
            }
        }
        if (chosenLead == null && chosenRun != null) {
            List<WeatherForecastTimeDto> times = weatherQueryRepository.listForecastTimes(normalizedModel, chosenRun);
            if (!times.isEmpty()) {
                chosenLead = times.get(0).getLeadHours();
            }
        }

        TileBbox bbox = webMercatorTileToLonLat(normalizedZ, normalizedX, normalizedY);
        if (chosenRun == null || chosenLead == null) {
            return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, "", null, null, emptyValues(gridSize));
        }

        TileBbox limit = limitBbox();
        if (!intersects(bbox, limit)) {
            return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, "", null, null, emptyValues(gridSize));
        }

        TileBbox query = expandForInterpolation(bbox, gridSize, 4);
        List<WeatherHeatmapPointDto> points = weatherQueryRepository.queryHeatmap(
                normalizedModel,
                chosenRun,
                chosenLead,
                normalizedElement,
                normalizedLevel,
                Math.max(query.minLon(), limit.minLon()),
                Math.max(query.minLat(), limit.minLat()),
                Math.min(query.maxLon(), limit.maxLon()),
                Math.min(query.maxLat(), limit.maxLat())
        );

        String unit = Objects.toString(weatherQueryRepository.getElementUnit(normalizedElement), "");
        if (unit.isBlank()) {
            unit = unitFor(normalizedElement);
        }

        List<Double> values = new ArrayList<>(gridSize * gridSize);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        boolean hasDb = points != null && !points.isEmpty();
        for (int j = 0; j < gridSize; j++) {
            double lat = bbox.maxLat() - (j + 0.5) * (bbox.maxLat() - bbox.minLat()) / gridSize;
            for (int i = 0; i < gridSize; i++) {
                double lon = bbox.minLon() + (i + 0.5) * (bbox.maxLon() - bbox.minLon()) / gridSize;
                Double v = within(lon, lat, limit)
                        ? (hasDb ? idwValue(points, lon, lat, 12, 1.5) : syntheticValue(normalizedElement, lon, lat))
                        : null;
                values.add(v);
                if (v != null && Double.isFinite(v)) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
        }

        Double outMin = Double.isFinite(min) ? min : null;
        Double outMax = Double.isFinite(max) ? max : null;
        return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, unit, outMin, outMax, values);
    }

    private List<Double> emptyValues(int gridSize) {
        List<Double> values = new ArrayList<>(gridSize * gridSize);
        for (int i = 0; i < gridSize * gridSize; i++) {
            values.add(null);
        }
        return values;
    }

    private Double nearestValue(List<WeatherHeatmapPointDto> points, double lon, double lat) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        WeatherHeatmapPointDto best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (WeatherHeatmapPointDto p : points) {
            double dx = p.getLon() - lon;
            double dy = p.getLat() - lat;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best == null ? null : best.getValue();
    }

    private Double idwValue(List<WeatherHeatmapPointDto> points, double lon, double lat, int k, double power) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        int kk = Math.max(1, Math.min(k, 32));
        double[] bestD2 = new double[kk];
        double[] bestV = new double[kk];
        for (int i = 0; i < kk; i++) {
            bestD2[i] = Double.POSITIVE_INFINITY;
            bestV[i] = Double.NaN;
        }

        for (WeatherHeatmapPointDto p : points) {
            double v = p.getValue();
            if (!Double.isFinite(v)) {
                continue;
            }
            double dx = p.getLon() - lon;
            double dy = p.getLat() - lat;
            double d2 = dx * dx + dy * dy;
            if (d2 <= 0.0) {
                return v;
            }

            int worstIdx = 0;
            double worstD2 = bestD2[0];
            for (int i = 1; i < kk; i++) {
                if (bestD2[i] > worstD2) {
                    worstD2 = bestD2[i];
                    worstIdx = i;
                }
            }
            if (d2 < worstD2) {
                bestD2[worstIdx] = d2;
                bestV[worstIdx] = v;
            }
        }

        if (kk == 1 || !Double.isFinite(bestD2[0])) {
            return nearestValue(points, lon, lat);
        }

        double sumW = 0.0;
        double sumWV = 0.0;
        double p2 = power * 0.5;
        for (int i = 0; i < kk; i++) {
            double d2 = bestD2[i];
            double v = bestV[i];
            if (!Double.isFinite(d2) || !Double.isFinite(v)) {
                continue;
            }
            double w = 1.0 / Math.pow(d2, p2);
            sumW += w;
            sumWV += w * v;
        }
        if (sumW <= 0.0) {
            return nearestValue(points, lon, lat);
        }
        return sumWV / sumW;
    }

    private record TileBbox(double minLon, double minLat, double maxLon, double maxLat) {
    }

    private TileBbox expandForInterpolation(TileBbox bbox, int gridSize, int padCells) {
        int pad = Math.max(0, Math.min(padCells, 16));
        if (pad == 0) {
            return bbox;
        }
        double dx = (bbox.maxLon() - bbox.minLon()) / Math.max(1, gridSize);
        double dy = (bbox.maxLat() - bbox.minLat()) / Math.max(1, gridSize);
        return new TileBbox(
                bbox.minLon() - dx * pad,
                bbox.minLat() - dy * pad,
                bbox.maxLon() + dx * pad,
                bbox.maxLat() + dy * pad
        );
    }

    private TileBbox limitBbox() {
        WeatherGfsProperties.Bbox bbox = weatherGfsProperties.getBbox();
        if (bbox == null) {
            return new TileBbox(70, 15, 140, 55);
        }
        return new TileBbox(bbox.getMinLon(), bbox.getMinLat(), bbox.getMaxLon(), bbox.getMaxLat());
    }

    private boolean within(double lon, double lat, TileBbox bbox) {
        return lon >= bbox.minLon() && lon <= bbox.maxLon() && lat >= bbox.minLat() && lat <= bbox.maxLat();
    }

    private boolean intersects(TileBbox a, TileBbox b) {
        return a.minLon() <= b.maxLon()
                && a.maxLon() >= b.minLon()
                && a.minLat() <= b.maxLat()
                && a.maxLat() >= b.minLat();
    }

    private TileBbox webMercatorTileToLonLat(int z, int x, int y) {
        int n = 1 << z;
        double minLon = x / (double) n * 360.0 - 180.0;
        double maxLon = (x + 1) / (double) n * 360.0 - 180.0;

        double minLat = tileYToLat(y + 1, n);
        double maxLat = tileYToLat(y, n);
        return new TileBbox(minLon, minLat, maxLon, maxLat);
    }

    private double tileYToLat(int y, int n) {
        double pi = Math.PI;
        double a = pi * (1.0 - 2.0 * y / (double) n);
        double latRad = Math.atan(Math.sinh(a));
        return Math.toDegrees(latRad);
    }

    private String unitFor(String element) {
        return switch (element) {
            case "temp", "tmp_2m", "t2m", "temperature" -> "°C";
            case "wind_u", "wind_v", "wind_10m", "wind" -> "m/s";
            case "pressure", "prmsl" -> "Pa";
            default -> "";
        };
    }

    private Double syntheticValue(String element, double lon, double lat) {
        double lonRad = Math.toRadians(lon);
        double latRad = Math.toRadians(lat);
        double base = Math.sin(lonRad * 1.3) * Math.cos(latRad * 1.7);
        double wave = Math.sin((lonRad + latRad) * 2.0);
        double raw = 0.65 * base + 0.35 * wave;
        return switch (element) {
            case "temp", "tmp_2m", "t2m", "temperature" -> 22.0 + 12.0 * raw - (lat - 30.0) * 0.15;
            case "wind_u", "wind_v", "wind_10m", "wind" -> Math.max(0.0, 3.0 + 6.0 * raw);
            case "pressure", "prmsl" -> 101325.0 + 1800.0 * raw;
            default -> raw;
        };
    }
}
