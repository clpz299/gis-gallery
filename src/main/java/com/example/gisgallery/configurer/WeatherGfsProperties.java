package com.example.gisgallery.configurer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author clpz299
 */
@Data
@ConfigurationProperties(prefix = "weather.gfs")
public class WeatherGfsProperties {
    private boolean enabled = false;
    private String model = "gfs_0p25";
    private String cron = "0 */30 * * * *";
    private int publishLagMinutes = 120;

    private List<Integer> runHoursUtc = List.of(0, 6, 12, 18);

    private Lead lead = new Lead();
    private Bbox bbox = new Bbox();
    private int stride = 6;
    private String level = "surface";

    private List<Element> elements = new ArrayList<>();

    @Data
    public static class Lead {
        private int start = 0;
        private int end = 48;
        private int step = 3;
    }

    @Data
    public static class Bbox {
        private double minLon = 70;
        private double minLat = 15;
        private double maxLon = 140;
        private double maxLat = 55;
    }

    @Data
    public static class Element {
        private String code;
        private String variable;
        private String gfsVar;
        private String ncVar;
        private String levParam;
        private String urlTemplate;
        private Map<String, String> headers;
    }
}
