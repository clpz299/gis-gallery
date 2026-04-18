package com.example.gisgallery.weather.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * @author clpz299
 */
@Data
@AllArgsConstructor
public class WeatherForecastTimeDto {
    private int leadHours;
    private OffsetDateTime validTimeUtc;
}

