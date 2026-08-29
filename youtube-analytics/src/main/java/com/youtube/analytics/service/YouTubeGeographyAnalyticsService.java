package com.youtube.analytics.service;

import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.GeographyAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import com.youtube.analytics.model.YouTubeAnalyticsApiResponse;
import com.youtube.analytics.model.YouTubeAnalyticsColumnHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Fetches geography analytics for a single YouTube video. */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeGeographyAnalyticsService {

    private static final String YT_ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String CHANNEL_IDS = "channel==MINE";
    private static final String COUNTRY_DIMENSION = "country";

    @Value("${youtube.analytics.geography.default-metrics:views}")
    private String defaultMetrics;

    private final WebClient youTubeWebClient;
    private final YouTubeDataService youTubeDataService;

    public GeographyAnalyticsResult getVideoGeography(
            String videoId,
            String startDate,
            String endDate,
            List<String> metrics) {

        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching geography analytics: {} | {} → {} | metrics: {}",
                videoId, resolvedStart, resolvedEnd, resolvedMetrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics))
                .queryParam("dimensions", COUNTRY_DIMENSION)
                .queryParam("filters", "video==" + videoId)
                .build()
                .toUri();

        YouTubeAnalyticsApiResponse response = fetchAnalyticsResponse(uri);
        List<GeographyAnalyticsResult.GeographyMetricRow> countries = parseRows(response);

        Map<String, VideoMeta> meta = youTubeDataService.getVideoMeta(List.of(videoId));
        VideoMeta videoMeta = meta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));

        return GeographyAnalyticsResult.builder()
                .videoId(videoId)
                .title(videoMeta.getTitle())
                .publishedAt(videoMeta.getPublishedAt())
                .startDate(resolvedStart)
                .endDate(resolvedEnd)
                .countries(countries)
                .build();
    }

    private List<GeographyAnalyticsResult.GeographyMetricRow> parseRows(YouTubeAnalyticsApiResponse response) {
        List<GeographyAnalyticsResult.GeographyMetricRow> results = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) {
            return results;
        }

        List<YouTubeAnalyticsColumnHeader> headers = response.getColumnHeaders();
        int countryIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (COUNTRY_DIMENSION.equals(headers.get(i).getName())) {
                countryIndex = i;
                break;
            }
        }
        if (countryIndex < 0) {
            throw new IllegalStateException("YouTube Analytics geography response did not contain the country dimension");
        }

        for (List<Object> row : response.getRows()) {
            if (row == null || row.size() <= countryIndex) continue;
            String country = String.valueOf(row.get(countryIndex));
            Map<String, Object> metricsMap = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                if (i == countryIndex) continue;
                String columnName = headers.get(i).getName();
                if (columnName != null) metricsMap.put(columnName, row.get(i));
            }
            results.add(GeographyAnalyticsResult.GeographyMetricRow.builder()
                    .country(country)
                    .metrics(metricsMap)
                    .build());
        }
        return results;
    }

    private List<String> resolveMetrics(List<String> metrics) {
        List<String> requested = (metrics == null || metrics.isEmpty())
                ? Stream.of(defaultMetrics.split(",")).map(String::trim).toList()
                : metrics.stream()
                .flatMap(metric -> metric == null ? Stream.empty() : Stream.of(metric.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return AnalyticsRequestValidator.validateVideoMetrics(requested);
    }

    private String resolveStartDate(String startDate) {
        return startDate == null || startDate.isBlank()
                ? LocalDate.now().minusDays(365).toString() : startDate;
    }

    private String resolveEndDate(String endDate) {
        return endDate == null || endDate.isBlank()
                ? LocalDate.now().toString() : endDate;
    }

    private YouTubeAnalyticsApiResponse fetchAnalyticsResponse(URI uri) {
        return youTubeWebClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    log.warn("YouTube Analytics geography request failed with status {}", response.statusCode().value());
                    return Mono.error(new YouTubeAnalyticsApiException(response.statusCode()));
                })
                .bodyToMono(YouTubeAnalyticsApiResponse.class)
                .block();
    }
}
