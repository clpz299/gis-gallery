package com.example.gisgallery.weather.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author clpz299
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeatherHeatmapPointDto {
    private double lon;
    private double lat;
    private double value;
}

