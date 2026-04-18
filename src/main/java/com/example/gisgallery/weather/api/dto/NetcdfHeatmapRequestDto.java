package com.example.gisgallery.weather.api.dto;

import lombok.Data;

/**
 * @author clpz299
 */
@Data
public class NetcdfHeatmapRequestDto {
    private String localPath;
    private String element;
    private String variable;
    private Integer timeIndex;
    private Integer levelIndex;
    private Integer stride;
    private Double minLon;
    private Double minLat;
    private Double maxLon;
    private Double maxLat;
}

