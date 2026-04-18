package com.example.gisgallery.weather.application.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author clpz299
 */
@Service
public class GfsDownloadService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    public DownloadResult downloadToLocal(String url, String fileName, Map<String, String> headers) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        String resolvedName = fileName == null || fileName.isBlank()
                ? "gfs_" + LocalDateTime.now().format(TS) + suffixFromUrl(url)
                : fileName;

        Path dir = Path.of(System.getProperty("user.dir"), "data", "weather", "gfs", "raw");
        Files.createDirectories(dir);
        Path target = dir.resolve(resolvedName).normalize();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET();

        Map<String, String> allHeaders = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank()) {
                    allHeaders.put(k, v);
                }
            });
        }

        boolean hasUa = allHeaders.keySet().stream().anyMatch(k -> "user-agent".equalsIgnoreCase(k));
        if (!hasUa) {
            allHeaders.put("User-Agent", "gis-gallery/1.0 (Spring Boot)");
        }
        boolean hasAccept = allHeaders.keySet().stream().anyMatch(k -> "accept".equalsIgnoreCase(k));
        if (!hasAccept) {
            allHeaders.put("Accept", "*/*");
        }
        allHeaders.forEach(builder::header);

        HttpRequest req = builder.build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            byte[] preview = readPreview(resp.body(), 2048);
            String msg = "download failed, status=" + status + ", preview=" + new String(preview);
            throw new IOException(msg);
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(target);
        return new DownloadResult(target.toString(), size);
    }

    private String suffixFromUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".nc")) {
            return ".nc";
        }
        if (u.contains(".grb2") || u.contains(".grib2") || u.contains("pgrb2") || u.contains("grb2")) {
            return ".grb2";
        }
        return ".bin";
    }

    private byte[] readPreview(InputStream in, int maxBytes) {
        try (InputStream input = in) {
            byte[] buf = new byte[maxBytes];
            int read = input.read(buf);
            if (read <= 0) {
                return new byte[0];
            }
            if (read == maxBytes) {
                return buf;
            }
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public record DownloadResult(String localPath, long bytes) {
    }
}
