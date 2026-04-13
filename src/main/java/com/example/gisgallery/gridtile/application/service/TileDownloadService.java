package com.example.gisgallery.gridtile.application.service;

import com.example.gisgallery.common.util.TileUtils;
import com.example.gisgallery.common.util.cache.CachingTileSource;
import com.example.gisgallery.common.util.cache.FileSystemTileCache;
import com.example.gisgallery.common.util.geojson.GeoJsonBboxUtils;
import com.example.gisgallery.common.util.geotiff.GeoTiffTileWriter;
import com.example.gisgallery.common.util.reproject.GeoToolsReprojectingWriter;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadRequest;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 瓦片下载服务
 *
 * @author clpz299
 */
@Service
public class TileDownloadService {

    private static final Logger log = LoggerFactory.getLogger(TileDownloadService.class);
    private static final String DOWNLOAD_ROOT = "outputs";

    /**
     * 执行瓦片下载任务
     *
     * @param request 下载请求参数
     * @return 任务ID
     */
    public RegionDownloadResult startDownload(RegionDownloadRequest request) {
        String taskId = UUID.randomUUID().toString();
        
        // 简单异步执行，实际生产建议使用线程池或消息队列
        CompletableFuture.runAsync(() -> {
            try {
                executeTask(taskId, request);
            } catch (Exception e) {
                log.error("任务执行失败: {}", taskId, e);
            }
        });

        return RegionDownloadResult.builder()
                .taskId(taskId)
                .status("RUNNING")
                .outputPath(Paths.get(DOWNLOAD_ROOT, taskId).toAbsolutePath().toString())
                .build();
    }

    private void executeTask(String taskId, RegionDownloadRequest request) throws Exception {
        log.info("开始执行下载任务: {}", taskId);

        // 1. 获取行政区划 GeoJSON 并计算 BBox
        TileUtils.BBox bbox = fetchRegionBBox(request.getAdcode());
        log.info("任务 {} 获取到 BBox: {}", taskId, bbox.toCsv());

        Path outputDir = Paths.get(DOWNLOAD_ROOT, taskId);
        Files.createDirectories(outputDir);

        // 2. 遍历缩放级别
        for (Integer zoom : request.getZoomLevels()) {
            log.info("任务 {} 开始处理 zoom: {}", taskId, zoom);
            if (request.isMerge()) {
                downloadAndMerge(request, zoom, bbox, outputDir);
            } else {
                downloadAsXyz(taskId, request, zoom, bbox, outputDir);
            }
        }
        
        log.info("任务 {} 完成", taskId);
    }

    /**
     * 下载并合并
     * @param request
     * @param zoom
     * @param bbox
     * @param outputDir
     * @throws IOException
     */
    private void downloadAndMerge(RegionDownloadRequest request, int zoom, TileUtils.BBox bbox, Path outputDir) throws IOException {
        String ext = "tif".equalsIgnoreCase(request.getFormat()) ? "tif" : "png";
        String fileName = String.format("%s_z%d.%s", request.getRegionName(), zoom, ext);
        File outputFile = outputDir.resolve(fileName).toFile();

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            // 1. 确定基础 Writer
            TileUtils.TileImageWriter baseWriter;
            if ("tif".equalsIgnoreCase(request.getFormat())) {
                baseWriter = GeoTiffTileWriter.writer();
            } else {
                baseWriter = TileUtils.OutputFormat.PNG.writer();
            }

            // 2. 构造重投影写入链： Reproject -> Base Writer
            TileUtils.TileImageWriter writer = new GeoToolsReprojectingWriter(
                    baseWriter,
                    // "EPSG:4490" or "EPSG:4326"
                    request.getTargetEpsg()
            );
            
            // 使用 TileUtils 的 mergeToTileImage 逻辑，这里手动组装
            TileUtils.TileMatrixSet matrixSet = detectMatrixSet(request.getServiceUrl(), request.getSourceEpsg());
            TileUtils.TileImage tileImage = TileUtils.mergeToTileImage(
                    TileUtils.http(request.getServiceUrl()),
                    bbox.toCsv(),
                    zoom,
                    matrixSet,
                    // 并发数
                    16
            );
            
            writer.write(tileImage, out);
        }
    }


    /**
     * 以 XYZ 目录结构下载瓦片（不合并输出）。
     *
     * <p>行为说明：</p>
     * <ol>
     *   <li>根据 bbox 与 zoom 计算需要下载的瓦片坐标集合（x/y/z）</li>
     *   <li>通过缓存装饰器把“下载结果”落盘为 tiles/z/x/y 的目录结构</li>
     * </ol>
     *
     * <p>适用场景：</p>
     * <ul>
     *   <li>离线底图：后续在前端/服务端以 XYZ 方式直接加载</li>
     *   <li>分片管理：按瓦片颗粒度增量更新与复用</li>
     * </ul>
     *
     * @param taskId    任务 ID（用于日志区分）
     * @param request   下载请求参数（包含服务地址、源坐标系等）
     * @param zoom      缩放级别
     * @param bbox      目标区域四至范围
     * @param outputDir 输出目录（任务输出根目录）
     */
    private void downloadAsXyz(String taskId, RegionDownloadRequest request, int zoom, TileUtils.BBox bbox, Path outputDir) throws IOException {
        TileUtils.TileMatrixSet matrixSet = detectMatrixSet(request.getServiceUrl(), request.getSourceEpsg());
        List<TileUtils.TileCoord> coords = TileUtils.tilesForBbox(bbox, zoom, matrixSet);
        
        if (coords.isEmpty()) {
            log.warn("任务 {} zoom {} 未计算到瓦片", taskId, zoom);
            return;
        }

        // 使用 FileSystemTileCache 作为下载器
        // 这里的 outputDir 作为缓存根目录，taskId 作为 namespace (或直接用 tiles)
        // 为了直接输出 z/x/y 结构，我们将 outputDir 设为根，namespace 设为 "tiles"
        FileSystemTileCache cache = new FileSystemTileCache(outputDir);
        TileUtils.TileSource httpSource = TileUtils.http(request.getServiceUrl());
        TileUtils.TileSource cachingSource = new CachingTileSource(httpSource, cache, "tiles");

        // 并发下载
        coords.parallelStream().forEach(coord -> {
            try {
                // 调用 load 会触发缓存逻辑（即下载并保存）
                cachingSource.load(coord);
            } catch (Exception e) {
                log.warn("瓦片下载失败: {}", coord, e);
            }
        });
    }

    /**
     * 获取指定行政区的 GeoJSON 并计算其外接矩形（BBox）。
     *
     * <p>说明：</p>
     * <ul>
     *   <li>GeoJSON 由在线数据源提供，返回内容通常包含 geometry 坐标</li>
     *   <li>GeoJSON 不一定包含 bbox 字段，因此这里通过解析 geometry 计算四至</li>
     * </ul>
     *
     * @param adcode 行政区划代码
     * @return 行政区四至 BBox（minLon,minLat,maxLon,maxLat）
     */
    private TileUtils.BBox fetchRegionBBox(Long adcode) throws IOException {
        String url = String.format("https://geo.datav.aliyun.com/areas_v3/bound/%d.json", adcode);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        
        try (InputStream in = conn.getInputStream()) {
            // 使用专门的 GeoJsonUtils 提取 BBox，支持递归解析 geometry
            return GeoJsonBboxUtils.extractBBox(in);
        }
    }

    /**
     * 推断瓦片服务使用的切片矩阵（TileMatrixSet）。
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>优先使用前端明确传入的 sourceEpsg（更可靠）</li>
     *   <li>若未明确传入，则根据 URL 特征做兜底推断（兼容历史配置）</li>
     * </ol>
     *
     * <p>说明：</p>
     * <ul>
     *   <li>EPSG:3857 通常对应互联网底图（Web Mercator）</li>
     *   <li>EPSG:4326 通常对应经纬度瓦片（WGS84/CGCS2000 近似经纬度）</li>
     * </ul>
     *
     * @param urlTemplate 瓦片服务 URL 模板
     * @param sourceEpsg  源坐标系（例如 EPSG:3857 / EPSG:4326）
     * @return 对应的切片矩阵枚举
     */
    private TileUtils.TileMatrixSet detectMatrixSet(String urlTemplate, String sourceEpsg) {
        // 优先使用明确传入的 sourceEpsg
        if ("EPSG:4326".equalsIgnoreCase(sourceEpsg)) {
            return TileUtils.TileMatrixSet.EPSG_4326;
        }
        if ("EPSG:3857".equalsIgnoreCase(sourceEpsg)) {
            return TileUtils.TileMatrixSet.WEB_MERCATOR;
        }

        // 兼容旧逻辑：根据 URL 特征推断
        if (urlTemplate.contains("_w/wmts") || urlTemplate.contains("webmercator") || urlTemplate.contains("3857") || urlTemplate.contains("GoogleMapsCompatible")) {
            return TileUtils.TileMatrixSet.WEB_MERCATOR;
        }
        if (urlTemplate.contains("_c/wmts") || urlTemplate.contains("4326")) {
            return TileUtils.TileMatrixSet.EPSG_4326;
        }
        
        // 默认 Web Mercator (最常见)
        return TileUtils.TileMatrixSet.WEB_MERCATOR;
    }

    public List<String> listTaskOutputFiles(String taskId) throws IOException {
        Path dir = taskOutputDir(taskId);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }
        
        // 递归查找目录下的所有普通文件，并将路径转为相对路径返回
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace("\\", "/"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public Path resolveTaskOutputFile(String taskId, String fileName) throws IOException {
        if (taskId == null || taskId.isBlank()) {
            throw new IOException("taskId 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("fileName 不能为空");
        }
        fileName = fileName.replace("\\", "/");
        if (fileName.contains("..") || fileName.startsWith("/") || fileName.startsWith("\\") || fileName.contains(":")) {
            throw new IOException("非法文件名");
        }
        Path dir = taskOutputDir(taskId);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IOException("非法路径");
        }
        return file;
    }

    private Path taskOutputDir(String taskId) {
        return Paths.get(DOWNLOAD_ROOT, taskId);
    }
}
