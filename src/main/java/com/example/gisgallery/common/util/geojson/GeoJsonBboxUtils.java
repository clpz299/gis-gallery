package com.example.gisgallery.common.util.geojson;

import com.example.gisgallery.common.util.TileUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * GeoJSON 工具类，用于解析 GeoJSON 数据并提取 BBox 等信息
 *
 * @author clpz299
 */
public class GeoJsonBboxUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 GeoJSON 输入流中提取 BBox（外接矩形）
     *
     * @param inputStream GeoJSON 数据输入流
     * @return BBox 对象
     * @throws IOException 当无法解析或无法计算 BBox 时抛出
     */
    public static TileUtils.BBox extractBBox(InputStream inputStream) throws IOException {
        JsonNode root = objectMapper.readTree(inputStream);
        return extractBBox(root);
    }

    /**
     * 从 JsonNode 中提取 BBox
     *
     * @param root GeoJSON 根节点
     * @return BBox 对象
     * @throws IOException 当无法解析或无法计算 BBox 时抛出
     */
    public static TileUtils.BBox extractBBox(JsonNode root) throws IOException {
        // 1. 优先尝试直接获取顶层 bbox 属性
        if (root.has("bbox")) {
            JsonNode b = root.get("bbox");
            if (b.isArray() && b.size() >= 4) {
                return new TileUtils.BBox(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble());
            }
        }

        // 2. 如果没有顶层 bbox，尝试从 geometry 中递归计算
        String type = root.has("type") ? root.get("type").asText() : "";
        
        if ("FeatureCollection".equalsIgnoreCase(type)) {
            JsonNode features = root.get("features");
            if (features != null && features.isArray()) {
                return calculateBBoxFromFeatures(features);
            }
        } else if ("Feature".equalsIgnoreCase(type)) {
            JsonNode geometry = root.get("geometry");
            if (geometry != null) {
                return calculateBBoxFromGeometry(geometry);
            }
        } else if (isGeometryType(type)) {
            // 这里如果是直接的 Geometry 对象（如 Point, Polygon）
            return calculateBBoxFromGeometry(root);
        }

        throw new IOException("无法从 GeoJSON 中提取或计算有效的 BBox");
    }

    private static TileUtils.BBox calculateBBoxFromFeatures(JsonNode features) throws IOException {
        MinMax bounds = new MinMax();

        for (JsonNode feature : features) {
            try {
                // 优先看 Feature 自身的 bbox
                if (feature.has("bbox")) {
                    JsonNode b = feature.get("bbox");
                    if (b.isArray() && b.size() >= 4) {
                        bounds.update(b.get(0).asDouble(), b.get(1).asDouble());
                        bounds.update(b.get(2).asDouble(), b.get(3).asDouble());
                        continue;
                    }
                }

                // 否则解析 geometry
                JsonNode geometry = feature.get("geometry");
                if (geometry != null) {
                    TileUtils.BBox geomBBox = calculateBBoxFromGeometry(geometry);
                    bounds.update(geomBBox.minLon(), geomBBox.minLat());
                    bounds.update(geomBBox.maxLon(), geomBBox.maxLat());
                }
            } catch (Exception ignored) {
                // 忽略单个无效 Feature，继续尝试其他的
            }
        }

        if (bounds.initialized) {
            return new TileUtils.BBox(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat);
        }
        throw new IOException("FeatureCollection 中没有有效的几何数据");
    }

    private static TileUtils.BBox calculateBBoxFromGeometry(JsonNode geometry) throws IOException {
        String type = geometry.has("type") ? geometry.get("type").asText() : "";
        JsonNode coordinates = geometry.get("coordinates");

        // GeometryCollection 特殊处理，它没有 coordinates 而是 geometries
        if ("GeometryCollection".equalsIgnoreCase(type)) {
            JsonNode geometries = geometry.get("geometries");
            if (geometries != null && geometries.isArray()) {
                MinMax bounds = new MinMax();
                for (JsonNode g : geometries) {
                    TileUtils.BBox subBBox = calculateBBoxFromGeometry(g);
                    bounds.update(subBBox.minLon(), subBBox.minLat());
                    bounds.update(subBBox.maxLon(), subBBox.maxLat());
                }
                if (bounds.initialized) {
                    return new TileUtils.BBox(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat);
                }
            }
            throw new IOException("GeometryCollection 为空或无效");
        }

        if (coordinates == null || !coordinates.isArray()) {
            throw new IOException("Geometry 缺少 coordinates 数组: " + type);
        }

        MinMax bounds = new MinMax();

        // 根据 Geometry 类型递归遍历坐标
        switch (type) {
            case "Point":
                // Point coordinates is [lon, lat]
                processPoint(coordinates, bounds);
                break;
            case "MultiPoint":
                // MultiPoint coordinates is [[lon, lat], ...]
                processMultiPoint(coordinates, bounds);
                break;
            case "LineString":
                // LineString coordinates is [[lon, lat], ...]
                processLineString(coordinates, bounds);
                break;
            case "MultiLineString":
                // MultiLineString coordinates is [[[lon, lat], ...], ...]
                processMultiLineString(coordinates, bounds);
                break;
            case "Polygon":
                // Polygon coordinates is [[[lon, lat], ...], ...] (LinearRings)
                processPolygon(coordinates, bounds);
                break;
            case "MultiPolygon":
                // MultiPolygon coordinates is [[[[lon, lat], ...], ...], ...]
                processMultiPolygon(coordinates, bounds);
                break;
            default:
                throw new IOException("不支持的 Geometry 类型: " + type);
        }

        if (!bounds.initialized) {
            throw new IOException("Geometry 中没有有效坐标");
        }

        return new TileUtils.BBox(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat);
    }

    // --- 坐标递归遍历辅助方法 ---

    // [lon, lat]
    private static void processPoint(JsonNode coord, MinMax bounds) {
        if (coord.size() >= 2) {
            bounds.update(coord.get(0).asDouble(), coord.get(1).asDouble());
        }
    }

    // [[lon, lat], ...]
    private static void processMultiPoint(JsonNode coords, MinMax bounds) {
        for (JsonNode p : coords) {
            processPoint(p, bounds);
        }
    }

    // [[lon, lat], ...] (same structure as MultiPoint)
    private static void processLineString(JsonNode coords, MinMax bounds) {
        processMultiPoint(coords, bounds);
    }

    // [[[lon, lat], ...], ...]
    private static void processMultiLineString(JsonNode coords, MinMax bounds) {
        for (JsonNode line : coords) {
            processLineString(line, bounds);
        }
    }

    // [[[lon, lat], ...], ...] (same structure as MultiLineString)
    private static void processPolygon(JsonNode coords, MinMax bounds) {
        processMultiLineString(coords, bounds);
    }

    // [[[[lon, lat], ...], ...], ...]
    private static void processMultiPolygon(JsonNode coords, MinMax bounds) {
        for (JsonNode polygon : coords) {
            processPolygon(polygon, bounds);
        }
    }

    private static boolean isGeometryType(String type) {
        return "Point".equals(type) || "MultiPoint".equals(type) ||
               "LineString".equals(type) || "MultiLineString".equals(type) ||
               "Polygon".equals(type) || "MultiPolygon".equals(type) ||
               "GeometryCollection".equals(type);
    }

    /**
     * 内部辅助类，用于维护 BBox 极值
     */
    private static class MinMax {
        double minLon = 180.0;
        double minLat = 90.0;
        double maxLon = -180.0;
        double maxLat = -90.0;
        boolean initialized = false;

        void update(double lon, double lat) {
            if (!initialized) {
                minLon = lon;
                maxLon = lon;
                minLat = lat;
                maxLat = lat;
                initialized = true;
            } else {
                if (lon < minLon) minLon = lon;
                if (lon > maxLon) maxLon = lon;
                if (lat < minLat) minLat = lat;
                if (lat > maxLat) maxLat = lat;
            }
        }
    }
}
