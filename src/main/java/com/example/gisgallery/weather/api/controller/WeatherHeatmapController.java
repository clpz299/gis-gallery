package com.example.gisgallery.weather.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapResponseDto;
import com.example.gisgallery.weather.application.service.WeatherHeatmapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * @author clpz299
 */
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
@Tag(name = "Weather-Render", description = "天气要素热力图渲染查询接口")
public class WeatherHeatmapController {

    private final WeatherHeatmapService weatherHeatmapService;

    @Operation(summary = "查询热力图点集", description = "按模型、要素、层次、预报时次与空间范围返回热力图点集")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "500", description = "查询失败", content = @Content(schema = @Schema()))
    })
    @GetMapping("/heatmap")
    public RestResult<WeatherHeatmapResponseDto> heatmap(
            @Parameter(description = "模型名称，默认 gfs_0p25") @RequestParam(required = false) String model,
            @Parameter(description = "统一要素编码，如 temp/wind_u/wind_v/pressure") @RequestParam(required = false) String element,
            @Parameter(description = "层次编码，默认 surface") @RequestParam(required = false) String level,
            @Parameter(description = "起报时间（ISO-8601），例如 2026-04-18T06:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime runTimeUtc,
            @Parameter(description = "预报时效（小时）") @RequestParam(required = false) Integer leadHours,
            @Parameter(description = "最小经度") @RequestParam(required = false) Double minLon,
            @Parameter(description = "最小纬度") @RequestParam(required = false) Double minLat,
            @Parameter(description = "最大经度") @RequestParam(required = false) Double maxLon,
            @Parameter(description = "最大纬度") @RequestParam(required = false) Double maxLat,
            @Parameter(description = "返回点位数量上限（服务端可限幅）") @RequestParam(required = false) Integer points
    ) {
        return RestResult.success(weatherHeatmapService.buildHeatmap(model, element, level, runTimeUtc, leadHours, minLon, minLat, maxLon, maxLat, points));
    }
}
