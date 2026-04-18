package com.example.gisgallery.weather.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * @author clpz299
 */
@Data
public class GfsDownloadRequestDto {
    private String url;
    private String fileName;
    private Map<String, String> headers;
}

