package com.example.gisgallery.dem.api.controller;

import com.example.gisgallery.dem.api.dto.DemDownloadRequest;
import com.example.gisgallery.dem.application.service.DemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author clpz299
 */
@RestController
@RequestMapping("/api/dem")
@Tag(name = "DEM API", description = "Digital Elevation Model Download API")
public class DemController {

    private final DemService demService;

    public DemController(DemService demService) {
        this.demService = demService;
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ok");
    }

    @GetMapping(value = "/mock", produces = "image/tiff")
    public ResponseEntity<byte[]> mock() {
        byte[] bytes = demService.createMockGeoTiff();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/tiff"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mock_dem.tif\"");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    @Operation(summary = "Download DEM GeoTIFF", description = "Proxy OpenTopography Global Raster API to download DEM data as GeoTIFF")
    @PostMapping(value = "/download", produces = "image/tiff")
    public ResponseEntity<byte[]> downloadDem(@RequestBody DemDownloadRequest request) {
        ResponseEntity<byte[]> response = demService.downloadDem(request);
        
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.setContentType(MediaType.parseMediaType("image/tiff"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dem.tif\"");
        
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers)
                .body(response.getBody());
    }
}
