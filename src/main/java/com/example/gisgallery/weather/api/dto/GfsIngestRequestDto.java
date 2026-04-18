package com.example.gisgallery.weather.api.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * @author clpz299
 */
@Data
public class GfsIngestRequestDto {
    private String url;
    private String fileName;
    private Map<String, String> headers;

    private String model;
    private OffsetDateTime runTimeUtc;
    private Integer leadHours;
    private OffsetDateTime validTimeUtc;

    private String element;
    private String level;
    private String variable;
    private String ncVar;
    private Integer timeIndex;
    private Integer levelIndex;

    private Integer stride;
    private Double minLon;
    private Double minLat;
    private Double maxLon;
    private Double maxLat;
}
