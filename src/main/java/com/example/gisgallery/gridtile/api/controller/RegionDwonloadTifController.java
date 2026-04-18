package com.example.gisgallery.gridtile.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadRequest;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadResult;
import com.example.gisgallery.gridtile.application.service.TileDownloadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域下载与 TIF 预览控制器
 *
 * @author clpz299
 */
@RestController
@RequestMapping("/api/gridtile")
@Tag(name = "GridTile", description = "行政区瓦片下载与 TIF 预览接口")
public class RegionDwonloadTifController {

    @Autowired
    TileDownloadService tileDownloadService;

    @Operation(summary = "读取行政区数据", description = "读取内置 all_region.json，返回省市区层级行政区数据")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "读取成功"),
            @ApiResponse(responseCode = "500", description = "读取失败", content = @Content(schema = @Schema()))
    })
    @GetMapping("/regions")
    public RestResult<JsonNode> getRegions() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/basic-data/all_region.json");
        ObjectMapper objectMapper = new JsonMapper();
        JsonNode jsonNode = objectMapper.readTree(resource.getInputStream());
        return RestResult.success(jsonNode);
    }

    @Operation(summary = "提交瓦片下载任务", description = "按行政区、缩放级别和瓦片服务参数提交下载任务")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "任务提交成功"),
            @ApiResponse(responseCode = "500", description = "任务提交失败", content = @Content(schema = @Schema()))
    })
    @PostMapping("/download")
    public RestResult<RegionDownloadResult> downloadTiles(@RequestBody RegionDownloadRequest request) {
        if (request.getAdcode() == null) {
            return RestResult.failed("行政区划代码不能为空");
        }
        if (request.getZoomLevels() == null || request.getZoomLevels().isEmpty()) {
            return RestResult.failed("缩放级别不能为空");
        }
        if (request.getServiceUrl() == null || request.getServiceUrl().isBlank()) {
            return RestResult.failed("服务地址不能为空");
        }

        RegionDownloadResult result = tileDownloadService.startDownload(request);
        return RestResult.success(result);
    }

    @Operation(summary = "查询任务输出文件列表", description = "根据任务 ID 获取输出文件列表及预览下载 URL")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "500", description = "查询失败", content = @Content(schema = @Schema()))
    })
    @GetMapping("/tasks/{taskId}/files")
    public RestResult<List<Map<String, Object>>> listTaskFiles(@Parameter(description = "任务 ID", required = true) @PathVariable("taskId") String taskId) throws IOException {
        List<String> names = tileDownloadService.listTaskOutputFiles(taskId);
        List<Map<String, Object>> files = new ArrayList<>();
        for (String name : names) {
            Path p = tileDownloadService.resolveTaskOutputFile(taskId, name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("size", Files.size(p));
            String[] parts = name.split("/");
            StringBuilder encodedName = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) encodedName.append("/");
                encodedName.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
            }
            item.put("url", "/api/gridtile/tasks/" + taskId + "/files/" + encodedName.toString());
            item.put("type", guessType(name));
            files.add(item);
        }
        return RestResult.success(files);
    }

    @Operation(summary = "读取任务输出文件", description = "支持普通下载与 Range 分段读取（用于大文件预览）")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "读取成功"),
            @ApiResponse(responseCode = "206", description = "分段读取成功"),
            @ApiResponse(responseCode = "404", description = "文件不存在"),
            @ApiResponse(responseCode = "500", description = "读取失败", content = @Content(schema = @Schema()))
    })
    @GetMapping("/tasks/{taskId}/files/**")
    public ResponseEntity<?> readTaskFile(@Parameter(description = "任务 ID", required = true) @PathVariable("taskId") String taskId,
                                          jakarta.servlet.http.HttpServletRequest request,
                                          @RequestHeader HttpHeaders headers) throws IOException {
        String prefix = "/api/gridtile/tasks/" + taskId + "/files/";
        String uri = request.getRequestURI();
        String fileName = uri.substring(uri.indexOf(prefix) + prefix.length());
        fileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);
        
        Path file = tileDownloadService.resolveTaskOutputFile(taskId, fileName);
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        long fileSize = Files.size(file);
        if (fileSize <= 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        MediaType mediaType = guessMediaType(fileName);
        Resource resource = new FileSystemResource(file.toFile());
        List<HttpRange> ranges = headers.getRange();
        if (ranges == null || ranges.isEmpty()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(fileSize)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        long len = end - start + 1;
        Resource region = new RangeResource(file, start, len);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .contentLength(len)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize)
                .body(region);
    }

    private static String guessType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return "tif";
        }
        if (lower.endsWith(".png")) {
            return "png";
        }
        return "file";
    }

    private static MediaType guessMediaType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return MediaType.parseMediaType("image/tiff");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static final class RangeResource extends AbstractResource {
        private final Path file;
        private final long start;
        private final long length;

        private RangeResource(Path file, long start, long length) {
            this.file = file;
            this.start = start;
            this.length = length;
        }

        @Override
        public String getDescription() {
            return file.toAbsolutePath().toString();
        }

        @Override
        public String getFilename() {
            return file.getFileName().toString();
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            FileInputStream in = new FileInputStream(file.toFile());
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long n = in.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
            }
            return new BoundedInputStream(in, length);
        }
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        private BoundedInputStream(InputStream in, long remaining) {
            this.in = in;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
