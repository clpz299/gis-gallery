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
public class WeatherGridTileResponseDto {
    private int z;
    private int x;
    private int y;
    private int size;
    private String unit;
    private Double min;
    private Double max;
    private List<Double> values;
}

