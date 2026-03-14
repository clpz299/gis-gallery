package com.example.gisgallery.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 按“公里”切经纬度网格工具（WGS84 近似）
 *
 * @author clpz299
 */
public final class GridSplitUtil {

    /**
     * 1° 纬度 ≈ 111 km
     */
    private static final double KM_PER_DEG_LAT = 111.0;

    /**
     * 私有构造函数，防止实例化工具类
     */
    private GridSplitUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 网格单元（经纬度外接矩形）。
     * <p>使用 Java 14+ 的 record 替代 Lombok 注解，更加轻量和保证不可变性。</p>
     *
     * @param id     顺序编号
     * @param minLon 最小经度
     * @param minLat 最小纬度
     * @param maxLon 最大经度
     * @param maxLat 最大纬度
     */
    public record Grid(int id, double minLon, double minLat, double maxLon, double maxLat) {
    }

    /**
     * 1° 经度 ≈ 111 * cos(lat) km
     *
     * @param lat 纬度
     * @return 该纬度下1°经度对应的公里数
     */
    private static double kmPerDegLon(double lat) {
        // 使用 Math.max 避免极点处 cos 值为 0 或负数（由于浮点误差）导致的除零异常或负数步长
        double cosLat = Math.max(Math.cos(Math.toRadians(lat)), 1e-6);
        return KM_PER_DEG_LAT * cosLat;
    }

    /**
     * 把外接矩形按“公里”步长切成网格
     *
     * @param minLon 最小经度
     * @param minLat 最小纬度
     * @param maxLon 最大经度
     * @param maxLat 最大纬度
     * @param stepKm 每格边长（公里）
     * @return 网格列表（按行优先顺序编号）
     */
    public static List<Grid> split(double minLon, double minLat,
                                   double maxLon, double maxLat,
                                   double stepKm) {
        // 1. 参数校验
        if (stepKm <= 0.001) {
            throw new IllegalArgumentException("步长 (stepKm) 必须大于 0.001 公里");
        }
        if (minLat >= maxLat || minLon >= maxLon) {
            throw new IllegalArgumentException("无效的边界范围：最小值必须小于最大值");
        }
        if (minLat < -90.0 || maxLat > 90.0 || minLon < -180.0 || maxLon > 180.0) {
            throw new IllegalArgumentException("坐标超出范围：经度应在 [-180, 180] 之间，纬度应在 [-90, 90] 之间");
        }

        // 2. 预估容量，减少 ArrayList 扩容开销
        double latStep = stepKm / KM_PER_DEG_LAT;
        double avgLat = (minLat + maxLat) / 2.0;
        double avgLonStep = stepKm / kmPerDegLon(avgLat);

        int estimatedRows = (int) Math.ceil((maxLat - minLat) / latStep);
        int estimatedCols = (int) Math.ceil((maxLon - minLon) / avgLonStep);
        // 预估总数，防止过大导致 OOM，硬限制为 20 万
        int maxGridCount = 200000;
        int estimatedCapacity = Math.min(Math.max(16, estimatedRows * estimatedCols), maxGridCount);

        List<Grid> list = new ArrayList<>(estimatedCapacity);
        int id = 0;

        // 3. 循环切割网格
        double lat = minLat;
        while (lat < maxLat) {
            double latTop = Math.min(lat + latStep, maxLat);
            // 经度方向按平均纬度动态计算步长
            double midLat = (lat + latTop) / 2.0;
            double lonStep = stepKm / kmPerDegLon(midLat);

            double lon = minLon;
            while (lon < maxLon) {
                // 检查数量限制
                if (list.size() >= maxGridCount) {
                    throw new IllegalStateException(String.format(
                            "网格数量超出限制 (%d)。范围: [%f, %f, %f, %f], 步长: %f。可能是输入数据不合理。",
                            maxGridCount, minLon, minLat, maxLon, maxLat, stepKm));
                }

                double lonRight = Math.min(lon + lonStep, maxLon);
                list.add(new Grid(id++, lon, lat, lonRight, latTop));
                lon = lonRight;
            }
            lat = latTop;
        }
        return list;
    }
}
