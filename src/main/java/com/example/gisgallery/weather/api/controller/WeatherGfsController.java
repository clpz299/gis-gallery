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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Weather-GFS", description = "GFS 数据下载、解析预览与入库接口")
public class WeatherGfsController {

    private final GfsDownloadService gfsDownloadService;
    private final GfsNetcdfService gfsNetcdfService;
    private final GfsIngestService gfsIngestService;

    @Operation(summary = "下载 GFS 原始文件", description = "根据 URL 下载 GFS 文件到本地并返回保存路径与文件大小")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "下载成功"),
            @ApiResponse(responseCode = "500", description = "下载失败", content = @Content(schema = @Schema()))
    })
    @PostMapping("/download")
    public RestResult<GfsDownloadResponseDto> download(@RequestBody GfsDownloadRequestDto req) throws IOException, InterruptedException {
        var r = gfsDownloadService.downloadToLocal(req.getUrl(), req.getFileName(), req.getHeaders());
        return RestResult.success(new GfsDownloadResponseDto(r.localPath(), r.bytes()));
    }

    @Operation(summary = "本地文件解析热力点", description = "对本地 NetCDF/GRIB 文件按 bbox+stride 采样并返回热力图点集")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "解析成功"),
            @ApiResponse(responseCode = "500", description = "解析失败", content = @Content(schema = @Schema()))
    })
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

    @Operation(summary = "GFS 一体化入库", description = "执行下载 -> 解析 -> bbox+stride 采样 -> 入 PostgreSQL 全流程")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "入库成功"),
            @ApiResponse(responseCode = "500", description = "入库失败", content = @Content(schema = @Schema()))
    })
    @PostMapping("/ingest")
    public RestResult<GfsIngestResponseDto> ingest(@RequestBody GfsIngestRequestDto req) throws Exception {
        return RestResult.success(gfsIngestService.ingest(req));
    }
}
