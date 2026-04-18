package com.example.gisgallery.weather.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.weather.api.dto.WeatherGridTileResponseDto;
import com.example.gisgallery.weather.application.service.WeatherGridTileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@Tag(name = "Weather-Grid", description = "天气栅格瓦片接口（前端插值/分层设色渲染）")
public class WeatherGridTileController {

    private final WeatherGridTileService weatherGridTileService;

    @Operation(summary = "获取栅格瓦片数据", description = "按 z/x/y 返回一块规则网格数据（size*size），前端可插值后分层设色渲染")
    @GetMapping("/grid-tile")
    public RestResult<WeatherGridTileResponseDto> gridTile(
            @Parameter(description = "瓦片层级 z", required = true) @RequestParam int z,
            @Parameter(description = "瓦片列 x", required = true) @RequestParam int x,
            @Parameter(description = "瓦片行 y", required = true) @RequestParam int y,
            @Parameter(description = "网格边长 size（默认32，范围8~96）") @RequestParam(required = false) Integer size,
            @Parameter(description = "模型名称，默认 gfs_0p25") @RequestParam(required = false) String model,
            @Parameter(description = "统一要素编码，如 temp/wind_u/wind_v/pressure") @RequestParam(required = false) String element,
            @Parameter(description = "层次编码，默认 surface") @RequestParam(required = false) String level,
            @Parameter(description = "起报时间（ISO-8601）") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime runTimeUtc,
            @Parameter(description = "预报时效（小时）") @RequestParam(required = false) Integer leadHours
    ) {
        return RestResult.success(weatherGridTileService.buildTile(z, x, y, size, model, element, level, runTimeUtc, leadHours));
    }
}

