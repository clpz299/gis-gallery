package com.example.gisgallery.weather.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.weather.api.dto.GfsDownloadRequestDto;
import com.example.gisgallery.weather.api.dto.GfsDownloadResponseDto;
import com.example.gisgallery.weather.api.dto.GfsIngestRequestDto;
import com.example.gisgallery.weather.api.dto.GfsIngestResponseDto;
import com.example.gisgallery.weather.api.dto.NetcdfHeatmapRequestDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapResponseDto;
import com.example.gisgallery.weather.application.service.GfsDownloadService;
import com.example.gisgallery.weather.application.service.GfsIngestService;
import com.example.gisgallery.weather.application.service.GfsNetcdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * @author clpz299
 */
@RestController
@RequestMapping("/api/weather/gfs")
@RequiredArgsConstructor
public class WeatherGfsController {

    private final GfsDownloadService gfsDownloadService;
    private final GfsNetcdfService gfsNetcdfService;
    private final GfsIngestService gfsIngestService;

    @PostMapping("/download")
    public RestResult<GfsDownloadResponseDto> download(@RequestBody GfsDownloadRequestDto req) throws IOException, InterruptedException {
        var r = gfsDownloadService.downloadToLocal(req.getUrl(), req.getFileName(), req.getHeaders());
        return RestResult.success(new GfsDownloadResponseDto(r.localPath(), r.bytes()));
    }

    @PostMapping("/netcdf/heatmap")
    public RestResult<WeatherHeatmapResponseDto> netcdfHeatmap(@RequestBody NetcdfHeatmapRequestDto req) throws IOException {
        WeatherHeatmapResponseDto out = gfsNetcdfService.sampleToHeatmap(
                req.getLocalPath(),
                req.getElement(),
                req.getVariable(),
                req.getTimeIndex(),
                req.getLevelIndex(),
                req.getStride(),
                req.getMinLon(),
                req.getMinLat(),
                req.getMaxLon(),
                req.getMaxLat()
        );
        return RestResult.success(out);
    }

    @PostMapping("/ingest")
    public RestResult<GfsIngestResponseDto> ingest(@RequestBody GfsIngestRequestDto req) throws Exception {
        return RestResult.success(gfsIngestService.ingest(req));
    }
}
