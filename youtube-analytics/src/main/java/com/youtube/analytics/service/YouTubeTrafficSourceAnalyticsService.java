package com.youtube.analytics.service;

import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.TrafficSourceAnalyticsResult;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches traffic-source analytics for a single YouTube video.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeTrafficSourceAnalyticsService {

    private static final String YT_ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String CHANNEL_IDS = "channel==MINE";
    private static final String TRAFFIC_SOURCE_DIMENSION = "insightTrafficSourceType";

    @Value("${youtube.analytics.traffic-source.default-metrics:views}")
    private String defaultMetrics;

    private final WebClient youTubeWebClient;
    private final YouTubeDataService youTubeDataService;

    public TrafficSourceAnalyticsResult getVideoTrafficSources(
            String videoId,
            String startDate,
            String endDate,
            List<String> metrics) {

        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching traffic-source analytics: {} | {} → {} | metrics: {}",
                videoId, resolvedStart, resolvedEnd, resolvedMetrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics))
                .queryParam("dimensions", TRAFFIC_SOURCE_DIMENSION)
                .queryParam("filters", "video==" + videoId)
                .build()
                .toUri();

        YouTubeAnalyticsApiResponse response = fetchAnalyticsResponse(uri);
        List<TrafficSourceAnalyticsResult.TrafficSourceMetricRow> sources = parseRows(response);

        Map<String, VideoMeta> meta = youTubeDataService.getVideoMeta(List.of(videoId));
        VideoMeta videoMeta = meta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));

        return TrafficSourceAnalyticsResult.builder()
                .videoId(videoId)
                .title(videoMeta.getTitle())
                .publishedAt(videoMeta.getPublishedAt())
                .startDate(resolvedStart)
                .endDate(resolvedEnd)
                .sources(sources)
                .build();
    }

    private List<TrafficSourceAnalyticsResult.TrafficSourceMetricRow> parseRows(
            YouTubeAnalyticsApiResponse response) {

        List<TrafficSourceAnalyticsResult.TrafficSourceMetricRow> results = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) {
            return results;
        }

        List<YouTubeAnalyticsColumnHeader> headers = response.getColumnHeaders();
        int sourceIndex = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (TRAFFIC_SOURCE_DIMENSION.equals(headers.get(i).getName())) {
                sourceIndex = i;
                break;
            }
        }

        if (sourceIndex < 0) {
            throw new IllegalStateException(
                    "YouTube Analytics traffic-source response did not contain the "
                            + TRAFFIC_SOURCE_DIMENSION + " dimension");
        }

        for (List<Object> row : response.getRows()) {
            if (row == null || row.size() <= sourceIndex) continue;

            String source = String.valueOf(row.get(sourceIndex));
            Map<String, Object> metricsMap = new LinkedHashMap<>();

            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                if (i == sourceIndex) continue;
                String columnName = headers.get(i).getName();
                if (columnName != null) {
                    metricsMap.put(columnName, row.get(i));
                }
            }

            results.add(TrafficSourceAnalyticsResult.TrafficSourceMetricRow.builder()
                    .trafficSource(source)
                    .metrics(metricsMap)
                    .build());
        }

        return results;
    }

    private List<String> resolveMetrics(List<String> metrics) {
        List<String> requested = metrics == null || metrics.isEmpty()
                ? List.of(defaultMetrics)
                : metrics.stream()
                .flatMap(metric -> metric == null ? java.util.stream.Stream.empty()
                        : java.util.Arrays.stream(metric.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        return AnalyticsRequestValidator.validateVideoMetrics(requested);
    }

    private String resolveStartDate(String startDate) {
        return startDate == null || startDate.isBlank()
                ? java.time.LocalDate.now().minusDays(365).toString()
                : startDate;
    }

    private String resolveEndDate(String endDate) {
        return endDate == null || endDate.isBlank()
                ? java.time.LocalDate.now().toString()
                : endDate;
    }

    private YouTubeAnalyticsApiResponse fetchAnalyticsResponse(URI uri) {
        return youTubeWebClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    log.warn("YouTube Analytics traffic-source request failed with status {}",
                            response.statusCode().value());
                    return Mono.error(new YouTubeAnalyticsApiException(response.statusCode()));
                })
                .bodyToMono(YouTubeAnalyticsApiResponse.class)
                .block();
    }
}
