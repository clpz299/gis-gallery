package com.example.gisgallery.weather.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author clpz299
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeatherHeatmapResponseDto {
    private String element;
    private String unit;
    private List<WeatherHeatmapPointDto> points;
}

