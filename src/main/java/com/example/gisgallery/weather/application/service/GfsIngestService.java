package com.example.gisgallery.weather.application.service;

import com.example.gisgallery.weather.api.dto.GfsIngestRequestDto;
import com.example.gisgallery.weather.api.dto.GfsIngestResponseDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapPointDto;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * @author clpz299
 */
@Service
public class GfsIngestService {

    private final GfsDownloadService gfsDownloadService;
    private final GfsNetcdfService gfsNetcdfService;
    private final WeatherIngestRepository weatherIngestRepository;

    public GfsIngestService(GfsDownloadService gfsDownloadService,
                            GfsNetcdfService gfsNetcdfService,
                            WeatherIngestRepository weatherIngestRepository) {
        this.gfsDownloadService = gfsDownloadService;
        this.gfsNetcdfService = gfsNetcdfService;
        this.weatherIngestRepository = weatherIngestRepository;
    }

    public GfsIngestResponseDto ingest(GfsIngestRequestDto req) throws Exception {
        String model = req.getModel() == null || req.getModel().isBlank() ? "gfs_0p25" : req.getModel().trim().toLowerCase(Locale.ROOT);
        OffsetDateTime runTimeUtc = req.getRunTimeUtc() == null ? OffsetDateTime.now().withNano(0) : req.getRunTimeUtc();
        int leadHours = req.getLeadHours() == null ? 0 : Math.max(0, req.getLeadHours());
        OffsetDateTime validTimeUtc = req.getValidTimeUtc() == null ? runTimeUtc.plusHours(leadHours) : req.getValidTimeUtc();

        String element = req.getElement() == null || req.getElement().isBlank() ? "tmp_2m" : req.getElement().trim().toLowerCase(Locale.ROOT);
        String level = req.getLevel() == null || req.getLevel().isBlank() ? "surface" : req.getLevel().trim().toLowerCase(Locale.ROOT);

        weatherIngestRepository.upsertElement(element, element, "");
        weatherIngestRepository.upsertLevel(level, "custom", null, "");

        long runId = weatherIngestRepository.upsertModelRun(model, runTimeUtc);
        long forecastTimeId = weatherIngestRepository.upsertForecastTime(runId, leadHours, validTimeUtc);

        var download = gfsDownloadService.downloadToLocal(req.getUrl(), req.getFileName(), req.getHeaders());
        String parseVar = req.getNcVar() != null && !req.getNcVar().isBlank() ? req.getNcVar() : req.getVariable();
        List<WeatherHeatmapPointDto> points = gfsNetcdfService.samplePoints(
                download.localPath(),
                parseVar,
                req.getTimeIndex(),
                req.getLevelIndex(),
                req.getStride(),
                req.getMinLon(),
                req.getMinLat(),
                req.getMaxLon(),
                req.getMaxLat()
        );

        weatherIngestRepository.batchInsertGridPoints(points.stream()
                .map(p -> new WeatherIngestRepository.PointRow(p.getLon(), p.getLat()))
                .collect(Collectors.toList()));

        int total = 0;
        int batchSize = 800;
        for (int i = 0; i < points.size(); i += batchSize) {
            int end = Math.min(points.size(), i + batchSize);
            List<WeatherIngestRepository.ValueLonLatRow> rows = points.subList(i, end).stream()
                    .map(p -> new WeatherIngestRepository.ValueLonLatRow(p.getLon(), p.getLat(), p.getValue()))
                    .collect(Collectors.toList());
            total += weatherIngestRepository.upsertForecastValuesByLonLat(forecastTimeId, element, level, rows);
        }

        return new GfsIngestResponseDto(download.localPath(), points.size());
    }
}
