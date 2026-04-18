package com.example.gisgallery.weather.application.service;

import com.example.gisgallery.configurer.WeatherGfsProperties;
import com.example.gisgallery.weather.api.dto.GfsIngestRequestDto;
import com.example.gisgallery.weather.api.dto.GfsIngestResponseDto;
import com.example.gisgallery.weather.jobs.WeatherIngestStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author clpz299
 */
@Component
@EnableConfigurationProperties(WeatherGfsProperties.class)
public class WeatherGfsAutoIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeatherGfsAutoIngestScheduler.class);

    private final WeatherGfsProperties props;
    private final WeatherIngestStatusRepository statusRepository;
    private final GfsIngestService gfsIngestService;

    public WeatherGfsAutoIngestScheduler(WeatherGfsProperties props,
                                        WeatherIngestStatusRepository statusRepository,
                                        GfsIngestService gfsIngestService) {
        this.props = props;
        this.statusRepository = statusRepository;
        this.gfsIngestService = gfsIngestService;
    }

    @Scheduled(cron = "${weather.gfs.cron:0 */30 * * * *}", zone = "UTC")
    public void run() {
        Instant jobStart = Instant.now();
        if (!props.isEnabled()) {
            log.debug("[weather.gfs][scheduler] disabled, skip this tick");
            return;
        }

        String model = normalize(props.getModel(), "gfs_0p25");
        List<Integer> runHours = props.getRunHoursUtc();
        if (runHours == null || runHours.isEmpty()) {
            runHours = List.of(0, 6, 12, 18);
        }

        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime runTime = resolveLatestRun(nowUtc, runHours, props.getPublishLagMinutes());
        OffsetDateTime runTimeUtc = runTime.toOffsetDateTime();

        WeatherGfsProperties.Lead lead = props.getLead();
        int start = lead == null ? 0 : lead.getStart();
        int end = lead == null ? 48 : lead.getEnd();
        int step = lead == null ? 3 : Math.max(1, lead.getStep());

        WeatherGfsProperties.Bbox bbox = props.getBbox();
        double minLon = bbox == null ? 70 : bbox.getMinLon();
        double minLat = bbox == null ? 15 : bbox.getMinLat();
        double maxLon = bbox == null ? 140 : bbox.getMaxLon();
        double maxLat = bbox == null ? 55 : bbox.getMaxLat();

        String level = normalize(props.getLevel(), "surface");
        int stride = props.getStride() <= 0 ? 6 : props.getStride();

        List<WeatherGfsProperties.Element> elements = props.getElements();
        if (elements == null || elements.isEmpty()) {
            log.warn("[weather.gfs] enabled but no elements configured, skip");
            return;
        }

        log.info("[weather.gfs][scheduler-start] nowUtc={} model={} selectedRun={} leads={}..{} step={} bbox=[{},{},{},{}] stride={} elements={}",
                nowUtc,
                model,
                runTimeUtc,
                start, end, step,
                minLon, minLat, maxLon, maxLat,
                stride,
                elements.stream().map(WeatherGfsProperties.Element::getCode).toList());

        int totalPlanned = 0;
        int totalSkippedExisting = 0;
        int totalSkippedConfig = 0;
        int totalSuccess = 0;
        int totalFailed = 0;

        for (int leadHours = start; leadHours <= end; leadHours += step) {
            OffsetDateTime validTimeUtc = runTimeUtc.plusHours(leadHours);
            log.info("[weather.gfs][lead-begin] runTimeUtc={} leadHours={} validTimeUtc={}", runTimeUtc, leadHours, validTimeUtc);
            for (WeatherGfsProperties.Element el : elements) {
                totalPlanned++;
                String element = normalize(el.getCode(), null);
                if (element == null) {
                    totalSkippedConfig++;
                    log.warn("[weather.gfs][skip-config] empty element.code at leadHours={}", leadHours);
                    continue;
                }
                if (statusRepository.existsValues(model, runTimeUtc, leadHours, element, level)) {
                    totalSkippedExisting++;
                    log.info("[weather.gfs][skip-exists] model={} runTimeUtc={} leadHours={} element={} level={}",
                            model, runTimeUtc, leadHours, element, level);
                    continue;
                }

                String gfsVar = normalize(el.getGfsVar(), el.getVariable());
                String ncVar = normalize(el.getNcVar(), null);
                String levParam = normalize(el.getLevParam(), "lev_2_m_above_ground");
                String url = buildUrl(el.getUrlTemplate(), model, runTimeUtc, leadHours, minLon, minLat, maxLon, maxLat, element, gfsVar, levParam);
                if (url == null || url.isBlank()) {
                    totalSkippedConfig++;
                    log.warn("[weather.gfs] missing urlTemplate for element={}, skip ingest", element);
                    continue;
                }

                try {
                    Instant oneStart = Instant.now();
                    GfsIngestRequestDto req = new GfsIngestRequestDto();
                    req.setUrl(url);
                    req.setFileName(null);
                    req.setHeaders(el.getHeaders());
                    req.setModel(model);
                    req.setRunTimeUtc(runTimeUtc);
                    req.setLeadHours(leadHours);
                    req.setValidTimeUtc(validTimeUtc);
                    req.setElement(element);
                    req.setLevel(level);
                    req.setVariable(normalize(el.getVariable(), gfsVar));
                    req.setNcVar(ncVar);
                    req.setTimeIndex(0);
                    req.setLevelIndex(0);
                    req.setStride(stride);
                    req.setMinLon(minLon);
                    req.setMinLat(minLat);
                    req.setMaxLon(maxLon);
                    req.setMaxLat(maxLat);

                    log.info("[weather.gfs][ingest-start] model={} runTimeUtc={} leadHours={} element={} level={} url={}",
                            model, runTimeUtc, leadHours, element, level, safeUrlForLog(url));

                    GfsIngestResponseDto result = gfsIngestService.ingest(req);
                    totalSuccess++;
                    long tookMs = Duration.between(oneStart, Instant.now()).toMillis();
                    log.info("[weather.gfs][ingest-success] model={} runTimeUtc={} leadHours={} element={} pointsIngested={} localPath={} tookMs={}",
                            model, runTimeUtc, leadHours, element, result.getPointsIngested(), result.getLocalPath(), tookMs);
                } catch (Exception e) {
                    totalFailed++;
                    log.warn("[weather.gfs] ingest failed model={} runTimeUtc={} leadHours={} element={} err={}",
                            model, runTimeUtc, leadHours, element, e.getMessage());
                }
            }
        }

        long jobTookMs = Duration.between(jobStart, Instant.now()).toMillis();
        log.info("[weather.gfs][scheduler-end] planned={} success={} failed={} skipExisting={} skipConfig={} tookMs={}",
                totalPlanned, totalSuccess, totalFailed, totalSkippedExisting, totalSkippedConfig, jobTookMs);
    }

    private ZonedDateTime resolveLatestRun(ZonedDateTime nowUtc, List<Integer> runHoursUtc, int publishLagMinutes) {
        ZonedDateTime candidate = runHoursUtc.stream()
                .distinct()
                .filter(h -> h >= 0 && h <= 23)
                .map(h -> nowUtc.toLocalDate().atTime(h, 0).atZone(ZoneOffset.UTC))
                .max(Comparator.naturalOrder())
                .orElse(nowUtc.toLocalDate().atTime(0, 0).atZone(ZoneOffset.UTC));

        ZonedDateTime best = runHoursUtc.stream()
                .distinct()
                .filter(h -> h >= 0 && h <= 23)
                .map(h -> nowUtc.toLocalDate().atTime(h, 0).atZone(ZoneOffset.UTC))
                .filter(rt -> !rt.isAfter(nowUtc))
                .max(Comparator.naturalOrder())
                .orElse(candidate.minusDays(1));

        if (publishLagMinutes > 0) {
            ZonedDateTime lagged = best.plusMinutes(publishLagMinutes);
            if (lagged.isAfter(nowUtc)) {
                ZonedDateTime prev = best.minusHours(6);
                return prev;
            }
        }
        return best;
    }

    private String buildUrl(String template,
                            String model,
                            OffsetDateTime runTimeUtc,
                            int leadHours,
                            double minLon,
                            double minLat,
                            double maxLon,
                            double maxLat,
                            String element,
                            String gfsVar,
                            String levParam) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String runDate = String.format(Locale.ROOT, "%04d%02d%02d",
                runTimeUtc.getYear(), runTimeUtc.getMonthValue(), runTimeUtc.getDayOfMonth());
        String runHour = String.format(Locale.ROOT, "%02d", runTimeUtc.getHour());
        String lead3 = String.format(Locale.ROOT, "%03d", leadHours);
        return template
                .replace("{model}", safe(model))
                .replace("{runDate}", runDate)
                .replace("{runHour}", runHour)
                .replace("{leadHours}", lead3)
                .replace("{leadHoursInt}", Integer.toString(leadHours))
                .replace("{minLon}", Double.toString(minLon))
                .replace("{minLat}", Double.toString(minLat))
                .replace("{maxLon}", Double.toString(maxLon))
                .replace("{maxLat}", Double.toString(maxLat))
                .replace("{element}", safe(element))
                .replace("{gfsVar}", safe(gfsVar == null ? "" : gfsVar.toUpperCase(Locale.ROOT)))
                .replace("{levParam}", safe(levParam));
    }

    private String normalize(String v, String fallback) {
        if (v == null) return fallback;
        String s = v.trim();
        return s.isEmpty() ? fallback : s.toLowerCase(Locale.ROOT);
    }

    private String safe(String v) {
        return Objects.toString(v, "");
    }

    private String safeUrlForLog(String url) {
        if (url == null) {
            return "";
        }
        String masked = url.replaceAll("(?i)(password=)[^&;]+", "$1***")
                .replaceAll("(?i)(token=)[^&;]+", "$1***")
                .replaceAll("(?i)(tk=)[^&;]+", "$1***")
                .replaceAll("(?i)(apikey=)[^&;]+", "$1***");
        if (masked.length() <= 280) {
            return masked;
        }
        return masked.substring(0, 280) + "...";
    }
}
