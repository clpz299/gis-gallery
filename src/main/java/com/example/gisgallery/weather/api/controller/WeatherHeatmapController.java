package com.example.gisgallery.weather.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapResponseDto;
import com.example.gisgallery.weather.application.service.WeatherHeatmapService;
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
public class WeatherHeatmapController {

    private final WeatherHeatmapService weatherHeatmapService;

    @GetMapping("/heatmap")
    public RestResult<WeatherHeatmapResponseDto> heatmap(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String element,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime runTimeUtc,
            @RequestParam(required = false) Integer leadHours,
            @RequestParam(required = false) Double minLon,
            @RequestParam(required = false) Double minLat,
            @RequestParam(required = false) Double maxLon,
            @RequestParam(required = false) Double maxLat,
            @RequestParam(required = false) Integer points
    ) {
        return RestResult.success(weatherHeatmapService.buildHeatmap(model, element, level, runTimeUtc, leadHours, minLon, minLat, maxLon, maxLat, points));
    }
}
