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
 * 天气栅格瓦片服务：按 z/x/y 生成一块规则网格（size*size）的数值瓦片。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>服务端返回规则网格点值，前端再进行插值/分层设色渲染</li>
 *   <li>按业务 bbox（weather.gfs.bbox）裁剪，避免中国范围外出现颜色铺底</li>
 *   <li>使用较“柔和”的插值（IDW）降低瓦片边界处的不连续</li>
 * </ul>
 *
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

    /**
     * 构建指定 z/x/y 的栅格瓦片数据。
     *
     * <p>说明：</p>
     * <ul>
     *   <li>入参 y 兼容 OpenLayers 内部 tileCoord 可能出现的负值（做了归一化）</li>
     *   <li>会先确定 runTimeUtc/leadHours，若未提供则使用最新 run + 最小时效兜底</li>
     *   <li>最终输出的 values 为按行（lat 从北到南）展开的一维数组</li>
     * </ul>
     *
     * @param z         瓦片层级
     * @param x         瓦片列号
     * @param y         瓦片行号（支持 OpenLayers tileCoord 负值形式）
     * @param size      网格边长（默认 32，范围限制 8~96）
     * @param model     模型名称（默认 gfs_0p25）
     * @param element   要素编码（默认 temp）
     * @param level     层次编码（默认 surface）
     * @param runTimeUtc 起报时间（UTC）
     * @param leadHours 预报时效（小时）
     * @return 栅格瓦片 DTO
     */
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
        // 兼容 OpenLayers tileCoord：其 y 可能为负，需要映射回标准 XYZ 的 y（yXyz = -y - 1）
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

        // 将 z/x/y 转为经纬度 bbox（WGS84），用于 DB 查询与网格中心点计算
        TileBbox bbox = webMercatorTileToLonLat(normalizedZ, normalizedX, normalizedY);
        if (chosenRun == null || chosenLead == null) {
            return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, "", null, null, emptyValues(gridSize));
        }

        // 业务裁剪：避免在中国范围外仍返回“有颜色”的瓦片
        TileBbox limit = limitBbox();
        if (!intersects(bbox, limit)) {
            return new WeatherGridTileResponseDto(normalizedZ, normalizedX, normalizedY, gridSize, "", null, null, emptyValues(gridSize));
        }

        // 为插值扩边查询：瓦片边缘如果只取 tile bbox 内点，会更容易出现块状突变
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
            // j 从 0 到 gridSize-1：纬度从北到南
            double lat = bbox.maxLat() - (j + 0.5) * (bbox.maxLat() - bbox.minLat()) / gridSize;
            for (int i = 0; i < gridSize; i++) {
                // i 从 0 到 gridSize-1：经度从西到东
                double lon = bbox.minLon() + (i + 0.5) * (bbox.maxLon() - bbox.minLon()) / gridSize;
                Double v = within(lon, lat, limit)
                        // DB 有数据时使用 IDW（反距离加权）插值；无数据则返回合成值便于前端联调
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

    /**
     * 最近邻取值（不插值）：用于 IDW 的兜底与极端情况处理。
     */
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

    /**
     * IDW（Inverse Distance Weighting）反距离加权插值。
     *
     * <p>实现说明：</p>
     * <ul>
     *   <li>从候选点中取 k 个最近点</li>
     *   <li>权重 w = 1 / d^power（实际使用 d^2 以避免 sqrt）</li>
     *   <li>若目标点与某采样点重合（d=0），直接返回该采样值</li>
     * </ul>
     *
     * @param points 候选点（通常来自 DB bbox 查询）
     * @param lon    目标经度
     * @param lat    目标纬度
     * @param k      邻居数量（上限 32）
     * @param power  距离衰减指数（越小越“柔和”，越大越“贴近最近点”）
     * @return 插值结果
     */
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

            // 使用“固定长度数组 + 替换最差项”的方式，保留最近的 kk 个点（避免全量排序）
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

        // 若没有任何有效候选点，则退回最近邻
        if (kk == 1 || !Double.isFinite(bestD2[0])) {
            return nearestValue(points, lon, lat);
        }

        double sumW = 0.0;
        double sumWV = 0.0;
        // 使用 d^2 的幂次：w = 1 / (d^2)^(power/2)
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

    /**
     * 为插值扩边查询 bbox：在 tile bbox 周围额外扩展 padCells 个“网格单元”宽度。
     *
     * <p>目的：减少 tile 边缘处缺少邻域点导致的插值突变。</p>
     */
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

    /**
     * 业务限制 bbox：来自配置 weather.gfs.bbox，用于裁剪渲染范围。
     */
    private TileBbox limitBbox() {
        WeatherGfsProperties.Bbox bbox = weatherGfsProperties.getBbox();
        if (bbox == null) {
            return new TileBbox(70, 15, 140, 55);
        }
        return new TileBbox(bbox.getMinLon(), bbox.getMinLat(), bbox.getMaxLon(), bbox.getMaxLat());
    }

    /**
     * 判断点是否落在 bbox 内（包含边界）。
     */
    private boolean within(double lon, double lat, TileBbox bbox) {
        return lon >= bbox.minLon() && lon <= bbox.maxLon() && lat >= bbox.minLat() && lat <= bbox.maxLat();
    }

    /**
     * 判断两个 bbox 是否相交（包含边界接触）。
     */
    private boolean intersects(TileBbox a, TileBbox b) {
        return a.minLon() <= b.maxLon()
                && a.maxLon() >= b.minLon()
                && a.minLat() <= b.maxLat()
                && a.maxLat() >= b.minLat();
    }

    /**
     * 将 WebMercator XYZ 瓦片 z/x/y 转换为经纬度 bbox（WGS84）。
     */
    private TileBbox webMercatorTileToLonLat(int z, int x, int y) {
        int n = 1 << z;
        double minLon = x / (double) n * 360.0 - 180.0;
        double maxLon = (x + 1) / (double) n * 360.0 - 180.0;

        double minLat = tileYToLat(y + 1, n);
        double maxLat = tileYToLat(y, n);
        return new TileBbox(minLon, minLat, maxLon, maxLat);
    }

    /**
     * WebMercator 的 tileY 转纬度（单位：度）。
     */
    private double tileYToLat(int y, int n) {
        double pi = Math.PI;
        double a = pi * (1.0 - 2.0 * y / (double) n);
        double latRad = Math.atan(Math.sinh(a));
        return Math.toDegrees(latRad);
    }

    /**
     * 若 DB 未配置要素单位，则提供简单兜底单位。
     */
    private String unitFor(String element) {
        return switch (element) {
            case "temp", "tmp_2m", "t2m", "temperature" -> "°C";
            case "wind_u", "wind_v", "wind_10m", "wind" -> "m/s";
            case "pressure", "prmsl" -> "Pa";
            default -> "";
        };
    }

    /**
     * 合成数据（仅用于 DB 无数据时的联调兜底）。
     */
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
