package com.youtube.analytics.service;

import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.VideoAnalyticsResult;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Calls the YouTube Analytics API v2 to fetch video-level metrics.
 *
 * API base: https://youtubeanalytics.googleapis.com/v2/reports
 *
 * Key parameters:
 *   ids         = channel==MINE
 *   startDate   = yyyy-MM-dd
 *   endDate     = yyyy-MM-dd
 *   metrics     = views,estimatedMinutesWatched,...
 *   filters     = video==<videoId>   (single or comma-separated)
 *   dimensions  = video              (when fetching per-video breakdown)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAnalyticsService {

    private static final String YT_ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String CHANNEL_IDS = "channel==MINE";
    private static final int ANALYTICS_BATCH_SIZE = 200;

    @Value("${youtube.analytics.default-metrics:views,engagedViews,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,comments,shares,subscribersGained,subscribersLost}")
    private String defaultMetrics;

    private final WebClient youTubeWebClient;
    private final YouTubeDataService youTubeDataService;

    /**
     * Fetches analytics for a single video.
     */
    public VideoAnalyticsResult getSingleVideoAnalytics(
            String videoId,
            String startDate,
            String endDate,
            List<String> metrics) {

        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching single video analytics: {} | {} → {} | metrics: {}",
                videoId, resolvedStart, resolvedEnd, resolvedMetrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics))
                .queryParam("filters", "video==" + videoId)
                .build()
                .toUri();

        YouTubeAnalyticsApiResponse response = fetchAnalyticsResponse(uri);

        Map<String, Object> metricsMap = parseSingleRowResponse(response);

        Map<String, VideoMeta> meta = youTubeDataService.getVideoMeta(List.of(videoId));
        VideoMeta videoMeta = meta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));

        return VideoAnalyticsResult.builder()
                .videoId(videoId)
                .title(videoMeta.getTitle())
                .publishedAt(videoMeta.getPublishedAt())
                .startDate(resolvedStart)
                .endDate(resolvedEnd)
                .metrics(metricsMap)
                .build();
    }

    /**
     * Fetches analytics for a given list of video IDs.
     * Internally batches into groups of {@value ANALYTICS_BATCH_SIZE} per request.
     */
    public List<VideoAnalyticsResult> getMultipleVideoAnalytics(
            List<String> videoIds,
            String startDate,
            String endDate,
            List<String> metrics) {

        videoIds.forEach(AnalyticsRequestValidator::validateVideoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching analytics for {} videos | {} → {} | metrics: {}",
                videoIds.size(), resolvedStart, resolvedEnd, resolvedMetrics);

        Map<String, VideoMeta> allMeta = youTubeDataService.getVideoMeta(videoIds);
        List<VideoAnalyticsResult> results = new ArrayList<>();
        List<List<String>> batches = partition(videoIds, ANALYTICS_BATCH_SIZE);

        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            log.debug("Analytics batch {}/{} ({} videos)", i + 1, batches.size(), batch.size());

            String filter = "video==" + String.join(",", batch);

            URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                    .queryParam("ids", CHANNEL_IDS)
                    .queryParam("startDate", resolvedStart)
                    .queryParam("endDate", resolvedEnd)
                    .queryParam("metrics", String.join(",", resolvedMetrics))
                    .queryParam("filters", filter)
                    .queryParam("dimensions", "video")
                    .build()
                    .toUri();

            YouTubeAnalyticsApiResponse response = fetchAnalyticsResponse(uri);

            results.addAll(parseMultiVideoResponse(response, resolvedStart, resolvedEnd, allMeta));
        }

        log.info("Returned analytics for {} videos", results.size());
        return results;
    }

    /**
     * Fetches analytics for ALL videos ever uploaded to the authenticated channel.
     * Lists all video IDs first via the Data API, then delegates to getMultipleVideoAnalytics().
     */
    public List<VideoAnalyticsResult> getAllVideosAnalytics(
            String startDate,
            String endDate,
            List<String> metrics) {

        log.info("Fetching all video IDs from channel...");
        List<String> allVideoIds = youTubeDataService.getAllVideoIds();

        if (allVideoIds.isEmpty()) {
            log.warn("No videos found on the channel");
            return Collections.emptyList();
        }

        log.info("Found {} total videos — fetching analytics...", allVideoIds.size());
        return getMultipleVideoAnalytics(allVideoIds, startDate, endDate, metrics);
    }

    // ---------------------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------------------

    private Map<String, Object> parseSingleRowResponse(YouTubeAnalyticsApiResponse response) {
        Map<String, Object> metricsMap = new LinkedHashMap<>();
        if (response == null) return metricsMap;

        List<YouTubeAnalyticsColumnHeader> columnHeaders = response.getColumnHeaders();
        List<List<Object>> rows = response.getRows();

        if (columnHeaders == null || rows == null || rows.isEmpty()) {
            log.warn("Analytics API returned no data rows");
            return metricsMap;
        }

        List<Object> firstRow = rows.get(0);
        for (int i = 0; i < columnHeaders.size() && i < firstRow.size(); i++) {
            String colName = columnHeaders.get(i).getName();
            if (colName != null) {
                metricsMap.put(colName, firstRow.get(i));
            }
        }

        return metricsMap;
    }

    private List<VideoAnalyticsResult> parseMultiVideoResponse(
            YouTubeAnalyticsApiResponse response,
            String startDate,
            String endDate,
            Map<String, VideoMeta> metaMap) {

        List<VideoAnalyticsResult> results = new ArrayList<>();
        if (response == null) return results;

        List<YouTubeAnalyticsColumnHeader> columnHeaders = response.getColumnHeaders();
        List<List<Object>> rows = response.getRows();
        if (columnHeaders == null || rows == null) return results;

        for (List<Object> row : rows) {
            String videoId = null;
            Map<String, Object> metricsMap = new LinkedHashMap<>();

            for (int i = 0; i < columnHeaders.size() && i < row.size(); i++) {
                String colName = columnHeaders.get(i).getName();
                if ("video".equals(colName)) {
                    videoId = String.valueOf(row.get(i));
                } else {
                    metricsMap.put(colName, row.get(i));
                }
            }

            VideoMeta meta = metaMap.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));

            results.add(VideoAnalyticsResult.builder()
                    .videoId(videoId)
                    .title(meta.getTitle())
                    .publishedAt(meta.getPublishedAt())
                    .startDate(startDate)
                    .endDate(endDate)
                    .metrics(metricsMap)
                    .build());
        }

        return results;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private String resolveStartDate(String startDate) {
        return (startDate != null && !startDate.isBlank())
                ? startDate
                : LocalDate.now().minusDays(365).toString();
    }

    private String resolveEndDate(String endDate) {
        return (endDate != null && !endDate.isBlank())
                ? endDate
                : LocalDate.now().toString();
    }

    private List<String> resolveMetrics(List<String> metrics) {
        List<String> requestedMetrics = (metrics == null || metrics.isEmpty())
                ? Stream.of(defaultMetrics.split(",")).map(String::trim).toList()
                : metrics.stream().flatMap(metric -> metric == null
                        ? Stream.of((String) null)
                        : Stream.of(metric.split(","))).map(value -> value == null ? null : value.trim()).toList();
        return AnalyticsRequestValidator.validateVideoMetrics(requestedMetrics);
    }

    private YouTubeAnalyticsApiResponse fetchAnalyticsResponse(URI uri) {
        return youTubeWebClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.isError(), response -> {
                    log.warn("YouTube Analytics API request failed with status {}", response.statusCode().value());
                    return Mono.error(new YouTubeAnalyticsApiException(response.statusCode()));
                })
                .bodyToMono(YouTubeAnalyticsApiResponse.class)
                .block();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
