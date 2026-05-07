package com.example.gisgallery.dem.application.service;

import com.example.gisgallery.dem.api.dto.DemDownloadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.net.URI;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * @author clpz299
 */
@Service
public class DemService {

    private static final Logger log = LoggerFactory.getLogger(DemService.class);

    private final RestTemplate restTemplate;

    public DemService() {
        this.restTemplate = new RestTemplate();
    }

    public ResponseEntity<byte[]> downloadDem(DemDownloadRequest request) {
        String baseUrl = "https://portal.opentopography.org/API/globaldem";

        if (request == null || request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("demtype", request.getDemtype())
                .queryParam("south", request.getSouth())
                .queryParam("north", request.getNorth())
                .queryParam("west", request.getWest())
                .queryParam("east", request.getEast())
                .queryParam("outputFormat", "GTiff")
                .queryParam("API_Key", request.getApiKey())
                .build()
                .toUri();

        log.info("Starting OpenTopography DEM download process");
        log.info("Target URL: {}", uri.toString().replaceAll("API_Key=[^&]+", "API_Key=***"));
        log.info("Requested DEM Type: {}, Bounding Box: [south={}, north={}, west={}, east={}]", 
                 request.getDemtype(), request.getSouth(), request.getNorth(), request.getWest(), request.getEast());
        log.info("Request area estimate(km²): {}, grid estimate(px): {}, approx raw size(MB): {}",
                format2(estimateAreaKm2(request.getSouth(), request.getNorth(), request.getWest(), request.getEast())),
                estimatePixels(request.getDemtype(), request.getSouth(), request.getNorth(), request.getWest(), request.getEast()),
                format2(estimateRawSizeMb(request.getDemtype(), request.getSouth(), request.getNorth(), request.getWest(), request.getEast())));

        try {
            long startTime = System.currentTimeMillis();
            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
            long costTime = System.currentTimeMillis() - startTime;
            
            if (response.getStatusCode().is2xxSuccessful()) {
                long contentLength = response.getHeaders().getContentLength();
                MediaType contentType = response.getHeaders().getContentType();
                byte[] body = response.getBody();
                int bodyLen = body == null ? 0 : body.length;
                log.info("Successfully downloaded DEM data from OpenTopography. Status: {}, Time cost: {}ms, Header Content-Length: {} bytes, Body bytes: {}, Content-Type: {}",
                        response.getStatusCode(), costTime, contentLength, bodyLen, contentType);
                if (bodyLen >= 4) {
                    String headHex = toHex(body, 0, Math.min(16, bodyLen));
                    log.info("DEM response head(0..{}): {}", Math.min(15, bodyLen - 1), headHex);
                    boolean isTiff = isTiffHeader(body);
                    log.info("DEM response header looks like TIFF: {}", isTiff);
                    if (isTiff) {
                        validateGeoTiff(body);
                    }
                }
            } else {
                log.warn("Received non-2xx status code from OpenTopography. Status: {}, Time cost: {}ms", 
                         response.getStatusCode(), costTime);
            }
            
            return response;
        } catch (HttpStatusCodeException e) {
            String resp = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            String trimmed = resp == null ? "" : resp.trim();
            if (trimmed.length() > 2000) {
                trimmed = trimmed.substring(0, 2000) + "...(truncated)";
            }
            log.error("OpenTopography request failed. Status: {}, Response body: {}", e.getStatusCode(), trimmed);
            throw e;
        } catch (Exception e) {
            log.error("Failed to download DEM data from OpenTopography. Error: {}", e.getMessage(), e);
            throw e;
        }
    }

    public byte[] createMockGeoTiff() {
        File tmp = null;
        GeoTiffWriter writer = null;
        try {
            int width = 96;
            int height = 96;
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
            WritableRaster raster = img.getRaster();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double nx = x / (double) (width - 1);
                    double ny = y / (double) (height - 1);
                    double z = 2000.0 * Math.sin(nx * Math.PI) * Math.cos(ny * Math.PI) + 2500.0;
                    int v = (int) Math.round(Math.max(0, Math.min(65535, z)));
                    raster.setSample(x, y, 0, v);
                }
            }

            CoordinateReferenceSystem crs = CRS.decode("EPSG:4326", true);
            Envelope2D env = new Envelope2D(crs, 103.0, 30.0, 0.02, 0.02);
            GridCoverageFactory factory = new GridCoverageFactory();
            GridCoverage2D coverage = factory.create("mock_dem", img, env);

            tmp = File.createTempFile("mock_dem_", ".tif");
            writer = new GeoTiffWriter(tmp);
            writer.write(coverage, null);

            try (FileInputStream in = new FileInputStream(tmp); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            log.error("Create mock GeoTIFF failed: {}", e.getMessage(), e);
            return new byte[0];
        } finally {
            if (writer != null) {
                try {
                    writer.dispose();
                } catch (Exception ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static String estimatePixels(String demtype, Double south, Double north, Double west, Double east) {
        if (demtype == null || south == null || north == null || west == null || east == null) return "unknown";
        double resDeg = estimateResolutionDeg(demtype);
        if (!(resDeg > 0)) return "unknown";
        double dx = Math.abs(east - west);
        double dy = Math.abs(north - south);
        long w = Math.max(1, (long) Math.ceil(dx / resDeg));
        long h = Math.max(1, (long) Math.ceil(dy / resDeg));
        return w + "x" + h + " (" + (w * h) + ")";
    }

    private static double estimateRawSizeMb(String demtype, Double south, Double north, Double west, Double east) {
        if (demtype == null || south == null || north == null || west == null || east == null) return Double.NaN;
        double resDeg = estimateResolutionDeg(demtype);
        if (!(resDeg > 0)) return Double.NaN;
        double dx = Math.abs(east - west);
        double dy = Math.abs(north - south);
        long w = Math.max(1, (long) Math.ceil(dx / resDeg));
        long h = Math.max(1, (long) Math.ceil(dy / resDeg));
        long pixels = w * h;
        long bytes = pixels * 2L;
        return bytes / 1024.0 / 1024.0;
    }

    private static double estimateResolutionDeg(String demtype) {
        String t = demtype.trim().toUpperCase(Locale.ROOT);
        if (t.equals("COP90") || t.equals("SRTMGL3")) return 3.0 / 3600.0;
        if (t.equals("SRTMGL1") || t.equals("SRTMGL1_E") || t.equals("COP30") || t.equals("AW3D30") || t.equals("AW3D30_E") || t.equals("NASADEM")) {
            return 1.0 / 3600.0;
        }
        return 1.0 / 3600.0;
    }

    private static double estimateAreaKm2(Double south, Double north, Double west, Double east) {
        if (south == null || north == null || west == null || east == null) return Double.NaN;
        double lat = (south + north) / 2.0;
        double dyDeg = Math.abs(north - south);
        double dxDeg = Math.abs(east - west);
        double kmPerDegLat = 110.574;
        double kmPerDegLon = 111.320 * Math.cos(Math.toRadians(lat));
        double wKm = dxDeg * kmPerDegLon;
        double hKm = dyDeg * kmPerDegLat;
        return Math.max(0, wKm * hKm);
    }

    private static String format2(double v) {
        if (!Double.isFinite(v)) return "unknown";
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void validateGeoTiff(byte[] bytes) {
        File tmp = null;
        GeoTiffReader reader = null;
        try {
            tmp = File.createTempFile("opentopo_", ".tif");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            reader = new GeoTiffReader(tmp);
            GridCoverage2D cov = reader.read(null);
            int w = cov.getRenderedImage().getWidth();
            int h = cov.getRenderedImage().getHeight();
            int bands = cov.getRenderedImage().getSampleModel().getNumBands();
            int dataType = cov.getRenderedImage().getSampleModel().getDataType();
            CoordinateReferenceSystem crs = cov.getCoordinateReferenceSystem2D();
            Object noData = cov.getProperty("GC_NODATA");
            log.info("GeoTIFF validate(geotools): size={}x{}, bands={}, dataType={}, crs={}, noData={}",
                    w, h, bands, dataType, crs == null ? null : crs.getName().toString(), noData);
        } catch (Exception e) {
            log.error("GeoTIFF validate(geotools) failed: {}", e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Exception ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static boolean isTiffHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        // Classic TIFF: II*<0> or MM<0>*
        boolean leClassic = bytes[0] == 0x49 && bytes[1] == 0x49 && bytes[2] == 0x2A && bytes[3] == 0x00;
        boolean beClassic = bytes[0] == 0x4D && bytes[1] == 0x4D && bytes[2] == 0x00 && bytes[3] == 0x2A;
        // BigTIFF: II+<0> or MM<0>+
        boolean leBig = bytes[0] == 0x49 && bytes[1] == 0x49 && bytes[2] == 0x2B && bytes[3] == 0x00;
        boolean beBig = bytes[0] == 0x4D && bytes[1] == 0x4D && bytes[2] == 0x00 && bytes[3] == 0x2B;
        return leClassic || beClassic || leBig || beBig;
    }

    private static String toHex(byte[] bytes, int offset, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        int end = Math.min(bytes.length, offset + len);
        for (int i = offset; i < end; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i + 1 < end) sb.append(' ');
        }
        return sb.toString();
    }
}
