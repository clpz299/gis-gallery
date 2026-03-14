package com.example.gisgallery.common.util;

import com.example.gisgallery.common.util.geotiff.GeoTiffTileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 瓦片工具类
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>根据经纬度范围（bbox）计算需要请求的瓦片坐标集合</li>
 *   <li>通过 HTTP 方式下载瓦片图片</li>
 *   <li>将瓦片按行列拼接为一张 PNG 输出</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>磁盘缓存、重投影、GeoTIFF 写出等高耦合能力应拆分到专用模块，通过 TileImageWriter 扩展</li>
 *   <li>为防止 OOM，做了瓦片数量与输出图片尺寸限制</li>
 * </ul>
 *
 * @author clpz299
 */
public final class TileUtils {

    private static final Logger log = LoggerFactory.getLogger(TileUtils.class);

    private static final int TILE_SIZE = 256;
    private static final long MAX_TILE_COUNT = 200_000L;
    private static final int MAX_RETRY = 2;
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int DEFAULT_PARALLELISM = 16;
    private static final int MAX_IMAGE_PIXELS_PER_DIM = 8192;

    private TileUtils() {
    }

    public static String mergeTiles(String url, String extent, int zoom, OutputStream out) throws IOException {
        return mergeTiles(url, extent, zoom, out, OutputFormat.PNG);
    }

    /**
     * 合并指定范围内的瓦片并写出 PNG 到输出流。
     *
     * <p>输入 extent 为 WGS84 经纬度范围，格式：minLon,minLat,maxLon,maxLat。</p>
     * <p>url 支持模板变量：</p>
     * <ul>
     *   <li>{z}/{x}/{y}：常见 XYZ/WMTS 模板</li>
     *   <li>{2}/{0}/{1}：兼容旧实现占位符（z/x/y）</li>
     * </ul>
     *
     * @param url    瓦片 URL 模板
     * @param extent bbox，csv 字符串
     * @param zoom   缩放级别
     * @param out    输出流
     * @param format 输出格式
     * @return 实际合并的 bbox（csv）
     */
    public static String mergeTiles(String url, String extent, int zoom, OutputStream out, OutputFormat format) throws IOException {
        Objects.requireNonNull(out, "out 不能为空");
        Objects.requireNonNull(format, "format 不能为空");
        if (isBlank(url) || isBlank(extent)) {
            throw new IllegalArgumentException("url / extent 不能为空");
        }
        if (zoom < 0 || zoom > 30) {
            throw new IllegalArgumentException("zoom 应在 0-30 之间");
        }

        TileMatrixSet matrixSet = detectMatrixSet(url);
        TileImage tileImage = mergeToTileImage(http(url), extent, zoom, matrixSet, DEFAULT_PARALLELISM);
        format.writer().write(tileImage, out);
        return tileImage.bbox().toCsv();
    }

    public static TileImage mergeToTileImage(TileSource source, String extent, int zoom, TileMatrixSet matrixSet, int parallelism) throws IOException {
        Objects.requireNonNull(source, "source 不能为空");
        if (isBlank(extent)) {
            throw new IllegalArgumentException("extent 不能为空");
        }
        if (zoom < 0 || zoom > 30) {
            throw new IllegalArgumentException("zoom 应在 0-30 之间");
        }
        Objects.requireNonNull(matrixSet, "matrixSet 不能为空");

        BBox requestBbox = BBox.parse(extent);
        List<TileCoord> coords = tilesForBbox(requestBbox, zoom, matrixSet);
        if (coords.isEmpty()) {
            throw new IOException("未计算到任何瓦片");
        }
        long tileCount = (long) coords.size();
        if (tileCount > MAX_TILE_COUNT) {
            throw new IOException("瓦片数量过大: " + tileCount);
        }

        BufferedImage stitched = mergeToImage(source, coords, parallelism);
        TileRange range = TileRange.from(coords);
        BBox actual = tilesBbox(range.minX(), range.minY(), range.maxX(), range.maxY(), zoom, matrixSet);
        return new TileImage(stitched, actual, matrixSet, range);
    }

    /**
     * 根据 bbox 计算命中的瓦片坐标集合（同一 zoom）。
     *
     * <p>返回的瓦片是一个矩形范围：minX..maxX, minY..maxY。</p>
     * <p>会对 X/Y 做合法范围裁剪，并在瓦片数量过大时返回空集合以避免 OOM。</p>
     */
    public static List<TileCoord> tilesForBbox(BBox bbox, int zoom, TileMatrixSet matrixSet) {
        Objects.requireNonNull(bbox, "bbox 不能为空");
        Objects.requireNonNull(matrixSet, "matrixSet 不能为空");

        // 该 zoom 下瓦片矩阵维度（EPSG:4326 可能是 2:1 的矩阵）
        int width = matrixSet.matrixWidth(zoom);
        int height = matrixSet.matrixHeight(zoom);

        // bbox 左上角与右下角，分别换算为瓦片坐标
        // 由于不同矩阵定义，lon/lat 与 x/y 的方向约束由 TileMatrixSet 内部统一处理
        TileCoord nw = matrixSet.lonLatToTile(bbox.minLon(), bbox.maxLat(), zoom);
        TileCoord se = matrixSet.lonLatToTile(bbox.maxLon(), bbox.minLat(), zoom);

        // 取矩形包围范围，并裁剪到合法 index 之内
        int minX = clamp(Math.min(nw.x(), se.x()), 0, width - 1);
        int maxX = clamp(Math.max(nw.x(), se.x()), 0, width - 1);
        int minY = clamp(Math.min(nw.y(), se.y()), 0, height - 1);
        int maxY = clamp(Math.max(nw.y(), se.y()), 0, height - 1);

        // 数量预估与硬限制
        long tilesX = (long) maxX - (long) minX + 1L;
        long tilesY = (long) maxY - (long) minY + 1L;
        long total = tilesX * tilesY;
        if (tilesX <= 0 || tilesY <= 0 || total <= 0) {
            return List.of();
        }
        if (total > MAX_TILE_COUNT) {
            return List.of();
        }

        // 生成矩形范围瓦片列表
        List<TileCoord> tiles = new ArrayList<>((int) Math.min(total, Integer.MAX_VALUE));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                tiles.add(new TileCoord(x, y, zoom));
            }
        }
        return tiles;
    }

    /**
     * 下载并合并瓦片列表为一张图片。
     *
     * <p>要求 coords 必须同一 zoom，且能构成完整矩形范围；如缺失某一瓦片会抛异常。</p>
     *
     * @param source      瓦片来源（例如 HTTP、缓存、合成等）
     * @param coords      瓦片坐标列表（同一 zoom）
     * @param parallelism 下载并发数
     * @return 拼接后的 BufferedImage
     */
    public static BufferedImage mergeToImage(TileSource source, List<TileCoord> coords, int parallelism) throws IOException {
        Objects.requireNonNull(source, "source 不能为空");
        if (coords == null || coords.isEmpty()) {
            throw new IllegalArgumentException("coords 不能为空");
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism 必须 > 0");
        }

        // 计算瓦片矩形边界，后续按该矩形逐格绘制
        TileRange range = TileRange.from(coords);
        int tilesX = range.maxX - range.minX + 1;
        int tilesY = range.maxY - range.minY + 1;
        int pixelsX = tilesX * TILE_SIZE;
        int pixelsY = tilesY * TILE_SIZE;
        if (pixelsX <= 0 || pixelsY <= 0 || pixelsX > MAX_IMAGE_PIXELS_PER_DIM || pixelsY > MAX_IMAGE_PIXELS_PER_DIM) {
            throw new IOException("输出图像过大: " + pixelsX + "x" + pixelsY);
        }

        // 并发下载瓦片：下载完成后放入内存 Map，等待绘制
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, coords.size()));
        try {
            Map<Point, BufferedImage> tileImages = new HashMap<>();
            List<Callable<Void>> tasks = new ArrayList<>(coords.size());
            for (TileCoord tc : coords) {
                tasks.add(() -> {
                    BufferedImage img = source.load(tc);
                    synchronized (tileImages) {
                        tileImages.put(new Point(tc.x(), tc.y()), img);
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    // 防止单个任务“卡死”；这里使用 connect+read 的总和做近似超时
                    f.get(READ_TIMEOUT + CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new IOException("下载瓦片任务失败", e);
                }
            }

            // 创建画布并逐瓦片绘制
            BufferedImage canvas = new BufferedImage(pixelsX, pixelsY, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setBackground(Color.WHITE);
            g.clearRect(0, 0, pixelsX, pixelsY);
            for (int x = range.minX; x <= range.maxX; x++) {
                for (int y = range.minY; y <= range.maxY; y++) {
                    BufferedImage tile = tileImages.get(new Point(x, y));
                    if (tile == null) {
                        // 强制完整性：缺任意瓦片都认为拼接失败（调用方可以选择降低要求/补空白）
                        throw new IOException("缺少瓦片: x=" + x + ", y=" + y + ", z=" + range.zoom);
                    }
                    int dx = (x - range.minX) * TILE_SIZE;
                    int dy = (y - range.minY) * TILE_SIZE;
                    g.drawImage(tile, dx, dy, null);
                }
            }
            g.dispose();
            return canvas;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("瓦片下载被中断", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 由瓦片矩形范围反算 bbox（瓦片边界对应的经纬度范围）。
     *
     * <p>这里使用：</p>
     * <ul>
     *   <li>minX,minY 对应的左上角</li>
     *   <li>(maxX+1),(maxY+1) 对应的右下角（瓦片边界）</li>
     * </ul>
     */
    public static BBox tilesBbox(int minX, int minY, int maxX, int maxY, int zoom, TileMatrixSet matrixSet) {
        Objects.requireNonNull(matrixSet, "matrixSet 不能为空");
        LonLat nw = matrixSet.tileToLonLat(minX, minY, zoom);
        LonLat se = matrixSet.tileToLonLat(maxX + 1, maxY + 1, zoom);
        return new BBox(nw.lon(), se.lat(), se.lon(), nw.lat());
    }

    /**
     * 构造一个 HTTP 瓦片源。
     *
     * <p>返回的 TileSource 会在 load 时进行网络请求并返回 BufferedImage。</p>
     */
    public static TileSource http(String urlTemplate) {
        if (isBlank(urlTemplate)) {
            throw new IllegalArgumentException("urlTemplate 不能为空");
        }
        return coord -> downloadTileWithRetry(urlTemplate, coord);
    }

    /**
     * 下载单张瓦片（带重试）。
     *
     * <p>判定有效图片的规则：</p>
     * <ul>
     *   <li>HTTP 200</li>
     *   <li>Content-Type 以 image 开头</li>
     *   <li>ImageIO 可解析且非“近似空白”（采样若干像素快速判断）</li>
     * </ul>
     */
    private static BufferedImage downloadTileWithRetry(String urlTemplate, TileCoord tc) throws IOException {
        String resolved = resolveTemplate(urlTemplate, tc);
        IOException last = null;
        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(resolved).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("User-Agent", "gis-gallery/1.0");
                int code = conn.getResponseCode();
                String contentType = conn.getContentType();
                if (code != 200) {
                    last = new IOException("HTTP " + code);
                    continue;
                }
                if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image")) {
                    last = new IOException("非图片响应: " + contentType);
                    continue;
                }
                try (InputStream in = conn.getInputStream()) {
                    BufferedImage image = ImageIO.read(in);
                    if (isEffectivelyBlank(image)) {
                        last = new IOException("空白/无效瓦片");
                        continue;
                    }
                    return image;
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        log.warn("瓦片下载失败: url={}, x={}, y={}, z={}, err={}", urlTemplate, tc.x(), tc.y(), tc.z(), last == null ? null : last.getMessage());
        throw last == null ? new IOException("瓦片下载失败") : last;
    }

    /**
     * 将常见模板变量替换为实际 x/y/z。
     *
     * <p>兼容两类模板：</p>
     * <ul>
     *   <li>{z},{x},{y}：常见 XYZ</li>
     *   <li>{2},{0},{1}：旧实现/MessageFormat 风格（z/x/y）</li>
     * </ul>
     */
    private static String resolveTemplate(String template, TileCoord tc) {
        return template
                .replace("{z}", Integer.toString(tc.z()))
                .replace("{x}", Integer.toString(tc.x()))
                .replace("{y}", Integer.toString(tc.y()))
                .replace("{0}", Integer.toString(tc.x()))
                .replace("{1}", Integer.toString(tc.y()))
                .replace("{2}", Integer.toString(tc.z()));
    }

    /**
     * 快速判断图片是否“近似空白”。
     *
     * <p>目的：过滤掉服务端返回的纯色占位图、透明图、或 1x1 错误图，避免拼接结果全白。</p>
     * <p>实现：采样四角+中心像素，若均相同，则认为“近似空白”。</p>
     */
    private static boolean isEffectivelyBlank(BufferedImage image) {
        if (image == null) {
            return true;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 1 || h <= 1) {
            return true;
        }
        int p = image.getRGB(0, 0);
        int[] xs = new int[]{0, w / 2, w - 1};
        int[] ys = new int[]{0, h / 2, h - 1};
        for (int y : ys) {
            for (int x : xs) {
                if (image.getRGB(x, y) != p) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 根据 URL 模板推断瓦片矩阵。
     *
     * <p>当前策略：</p>
     * <ul>
     *   <li>包含 "_w/wmts"：认为是 WebMercator（EPSG:3857）</li>
     *   <li>否则：按 EPSG:4326 处理</li>
     * </ul>
     *
     * <p>如后续出现更多类型（例如 GWC 的 EPSG:4326 矩阵），建议上层显式传入 TileMatrixSet。</p>
     */
    private static TileMatrixSet detectMatrixSet(String urlTemplate) {
        if (urlTemplate.contains("_w/wmts")) {
            return TileMatrixSet.WEB_MERCATOR;
        }
        return TileMatrixSet.EPSG_4326;
    }

    /**
     * 判断字符串是否为空（null 或 trim 后长度为 0）。
     */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 将整数裁剪到闭区间 [min, max]。
     */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 瓦片坐标。
     *
     * @param x 瓦片列号
     * @param y 瓦片行号
     * @param z 缩放级别
     *
     * @author clpz299
     */
    public record TileCoord(int x, int y, int z) {
    }

    /**
     * 经纬度点（WGS84 语义）。
     *
     * @author clpz299
     */
    public record LonLat(double lon, double lat) {
    }

    /**
     * 经纬度外接矩形（bbox）。
     *
     * @param minLon 最小经度
     * @param minLat 最小纬度
     * @param maxLon 最大经度
     * @param maxLat 最大纬度
     *
     * @author clpz299
     */
    public record BBox(double minLon, double minLat, double maxLon, double maxLat) {
        /**
         * 解析 bbox 字符串：minLon,minLat,maxLon,maxLat。
         */
        public static BBox parse(String csv) {
            if (isBlank(csv)) {
                throw new IllegalArgumentException("extent 不能为空");
            }
            String[] a = csv.split(",");
            if (a.length != 4) {
                throw new IllegalArgumentException("extent 格式应为: minLon,minLat,maxLon,maxLat");
            }
            double minLon = parseDouble(a[0], "minLon");
            double minLat = parseDouble(a[1], "minLat");
            double maxLon = parseDouble(a[2], "maxLon");
            double maxLat = parseDouble(a[3], "maxLat");
            if (minLon > maxLon || minLat > maxLat) {
                throw new IllegalArgumentException("extent 范围不合法");
            }
            return new BBox(minLon, minLat, maxLon, maxLat);
        }

        /**
         * 格式化为 csv：minLon,minLat,maxLon,maxLat（固定 8 位小数）。
         */
        public String toCsv() {
            return String.format(Locale.US, "%.8f,%.8f,%.8f,%.8f", minLon, minLat, maxLon, maxLat);
        }

        /**
         * 安全解析 double，并在失败时带上字段名提示。
         */
        private static double parseDouble(String s, String name) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("extent " + name + " 不是数字");
            }
        }
    }

    /**
     * 瓦片来源抽象。
     *
     * <p>通过该抽象可以实现：</p>
     * <ul>
     *   <li>HTTP 下载</li>
     *   <li>本地缓存读取</li>
     *   <li>合成/兜底瓦片（缺失时用空白图）</li>
     * </ul>
     *
     * @author clpz299
     */
    public interface TileSource {
        BufferedImage load(TileCoord coord) throws IOException;
    }

    /**
     * 输出格式枚举。
     *
     * <p>实际写出逻辑由 TileImageWriter 决定，可在专用模块中扩展。</p>
     *
     * @author clpz299
     */
    public enum OutputFormat {
        PNG((tileImage, out) -> ImageIO.write(tileImage.image(), "png", out)),
        TIF(GeoTiffTileWriter.writer());

        private final TileImageWriter writer;

        OutputFormat(TileImageWriter writer) {
            this.writer = writer;
        }

        public TileImageWriter writer() {
            return writer;
        }
    }

    /**
     * TileImage 的写出策略接口（输出阶段扩展点）。
     *
     * <p>通过该接口可以在输出阶段组合引入：</p>
     * <ul>
     *   <li>重投影</li>
     *   <li>写 GeoTIFF</li>
     *   <li>写 PNG/JPEG/WebP 等</li>
     * </ul>
     *
     * @author clpz299
     */
    @FunctionalInterface
    public interface TileImageWriter {
        void write(TileImage tileImage, OutputStream out) throws IOException;
    }

    /**
     * 拼接后的瓦片影像与元数据载体。
     *
     * @author clpz299
     */
    public record TileImage(BufferedImage image, BBox bbox, TileMatrixSet matrixSet, TileRange tileRange) {
    }

    /**
     * 瓦片矩阵集合（TileMatrixSet）抽象。
     *
     * <p>用于统一不同投影/矩阵定义下的：</p>
     * <ul>
     *   <li>经纬度 -> 瓦片 x/y</li>
     *   <li>瓦片 x/y -> 经纬度（瓦片左上角）</li>
     *   <li>瓦片矩阵宽高</li>
     * </ul>
     *
     * @author clpz299
     */
    public enum TileMatrixSet {
        WEB_MERCATOR,
        EPSG_4326,
        EPSG_4490,
        GWC_EPSG_4326;

        /**
         * 经纬度换算为瓦片坐标（x/y/z）。
         *
         * <p>会对 lon 做 180 度经线的环绕处理，对 lat 做合理裁剪，避免数学溢出。</p>
         */
        public TileCoord lonLatToTile(double lon, double lat, int zoom) {
            double clampedLat = clampLat(lat);
            double wrappedLon = wrapLon(lon);

            if (this == WEB_MERCATOR) {
                int n = 1 << zoom;
                int x = (int) Math.floor((wrappedLon + 180.0) / 360.0 * n);
                double latRad = Math.toRadians(clampedLat);
                int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + (1.0 / Math.cos(latRad))) / Math.PI) / 2.0 * n);
                return new TileCoord(x, y, zoom);
            }

            if (this == EPSG_4326 || this == EPSG_4490) {
                int width = matrixWidth(zoom);
                int height = matrixHeight(zoom);
                int x = (int) Math.floor((wrappedLon + 180.0) / 360.0 * width);
                int y = zoom == 0 ? 0 : (int) Math.floor((90.0 - clampedLat) / 180.0 * height);
                return new TileCoord(x, y, zoom);
            }

            int width = matrixWidth(zoom);
            int height = matrixHeight(zoom);
            int x = (int) Math.floor((wrappedLon + 180.0) / 360.0 * width);
            int y = zoom == 0 ? 0 : (int) Math.floor((90.0 - clampedLat) / 180.0 * height);
            return new TileCoord(x, y, zoom);
        }

        /**
         * 瓦片坐标换算为经纬度（瓦片左上角点）。
         */
        public LonLat tileToLonLat(int x, int y, int zoom) {
            if (this == WEB_MERCATOR) {
                int n = 1 << zoom;
                double lon = x / (double) n * 360.0 - 180.0;
                double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (double) n))));
                return new LonLat(lon, lat);
            }

            double lon = x / (double) matrixWidth(zoom) * 360.0 - 180.0;
            double lat;
            if (zoom == 0) {
                lat = 90.0;
            } else {
                lat = 90.0 - (y / (double) matrixHeight(zoom) * 180.0);
            }
            return new LonLat(lon, lat);
        }

        /**
         * 该 zoom 下瓦片矩阵宽度（列数）。
         */
        public int matrixWidth(int zoom) {
            if (this == GWC_EPSG_4326) {
                return 1 << (zoom + 1);
            }
            return 1 << zoom;
        }

        /**
         * 该 zoom 下瓦片矩阵高度（行数）。
         *
         * <p>注意 EPSG:4326 的常见 2:1 矩阵：height = 2^(z-1)。</p>
         */
        public int matrixHeight(int zoom) {
            if (zoom == 0) {
                return 1;
            }
            if (this == EPSG_4326 || this == EPSG_4490) {
                return 1 << (zoom - 1);
            }
            if (this == GWC_EPSG_4326) {
                return 1 << zoom;
            }
            return 1 << zoom;
        }

        /**
         * WebMercator 的数学公式在极点会趋于无穷，做一个常见的纬度裁剪。
         */
        private static double clampLat(double lat) {
            return Math.max(-85.05112878, Math.min(85.05112878, lat));
        }

        /**
         * 将经度环绕到 [-180, 180]，避免极端 lon 输入导致 x 计算越界。
         */
        private static double wrapLon(double lon) {
            double v = lon;
            while (v < -180.0) {
                v += 360.0;
            }
            while (v > 180.0) {
                v -= 360.0;
            }
            return v;
        }
    }

    /**
     * 瓦片矩形范围（内部辅助结构）。
     *
     * <p>用于从 coords 计算 min/max，并保证同一 zoom。</p>
     *
     * @author clpz299
     */
    public record TileRange(int minX, int minY, int maxX, int maxY, int zoom) {
        static TileRange from(List<TileCoord> coords) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int z = coords.get(0).z();
            for (TileCoord c : coords) {
                if (c.z() != z) {
                    throw new IllegalArgumentException("coords 必须同一 zoom");
                }
                minX = Math.min(minX, c.x());
                minY = Math.min(minY, c.y());
                maxX = Math.max(maxX, c.x());
                maxY = Math.max(maxY, c.y());
            }
            return new TileRange(minX, minY, maxX, maxY, z);
        }
    }
}
