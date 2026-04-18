package com.example.gisgallery.weather.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author clpz299
 */
@Data
@AllArgsConstructor
public class GfsIngestResponseDto {
    private String localPath;
    private int pointsIngested;
}

