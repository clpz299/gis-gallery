package com.example.gisgallery.weather.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.weather.api.dto.WeatherForecastTimeDto;
import com.example.gisgallery.weather.api.dto.WeatherRunDto;
import com.example.gisgallery.weather.infrastructure.repository.WeatherQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @author clpz299
 */
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherMetaController {

    private final WeatherQueryRepository weatherQueryRepository;

    @GetMapping("/runs")
    public RestResult<List<WeatherRunDto>> runs(@RequestParam(required = false) String model,
                                               @RequestParam(required = false) Integer limit) {
        String m = model == null || model.isBlank() ? "gfs_0p25" : model;
        int l = limit == null ? 24 : Math.max(1, Math.min(limit, 200));
        return RestResult.success(weatherQueryRepository.listRuns(m, l));
    }

    @GetMapping("/forecast-times")
    public RestResult<List<WeatherForecastTimeDto>> forecastTimes(@RequestParam(required = false) String model,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime runTimeUtc) {
        String m = model == null || model.isBlank() ? "gfs_0p25" : model;
        return RestResult.success(weatherQueryRepository.listForecastTimes(m, runTimeUtc));
    }
}

