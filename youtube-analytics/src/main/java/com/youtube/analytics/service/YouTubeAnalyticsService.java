package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Value("${youtube.analytics.default-metrics:views,estimatedMinutesWatched,averageViewDuration,likes,comments,shares,subscribersGained}")
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

        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd   = resolveEndDate(endDate);
        String resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching single video analytics: {} | {} → {} | metrics: {}",
                videoId, resolvedStart, resolvedEnd, resolvedMetrics);

        String url = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("metrics", resolvedMetrics)
                .queryParam("filters", "video==" + videoId)
                .toUriString();

        Map<?, ?> response = youTubeWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

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

        String resolvedStart   = resolveStartDate(startDate);
        String resolvedEnd     = resolveEndDate(endDate);
        String resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching analytics for {} videos | {} → {} | metrics: {}",
                videoIds.size(), resolvedStart, resolvedEnd, resolvedMetrics);

        Map<String, VideoMeta> allMeta = youTubeDataService.getVideoMeta(videoIds);
        List<VideoAnalyticsResult> results = new ArrayList<>();
        List<List<String>> batches = partition(videoIds, ANALYTICS_BATCH_SIZE);

        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            log.debug("Analytics batch {}/{} ({} videos)", i + 1, batches.size(), batch.size());

            String filter = "video==" + String.join(",", batch);

            String url = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                    .queryParam("ids", CHANNEL_IDS)
                    .queryParam("startDate", resolvedStart)
                    .queryParam("endDate", resolvedEnd)
                    .queryParam("metrics", resolvedMetrics)
                    .queryParam("filters", filter)
                    .queryParam("dimensions", "video")
                    .toUriString();

            Map<?, ?> response = youTubeWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSingleRowResponse(Map<?, ?> response) {
        Map<String, Object> metricsMap = new LinkedHashMap<>();
        if (response == null) return metricsMap;

        List<?> columnHeaders = (List<?>) response.get("columnHeaders");
        List<?> rows = (List<?>) response.get("rows");

        if (columnHeaders == null || rows == null || rows.isEmpty()) {
            log.warn("Analytics API returned no data rows");
            return metricsMap;
        }

        List<?> firstRow = (List<?>) rows.get(0);
        for (int i = 0; i < columnHeaders.size(); i++) {
            Map<?, ?> header = (Map<?, ?>) columnHeaders.get(i);
            String colName = (String) header.get("name");
            metricsMap.put(colName, firstRow.get(i));
        }

        return metricsMap;
    }

    @SuppressWarnings("unchecked")
    private List<VideoAnalyticsResult> parseMultiVideoResponse(
            Map<?, ?> response,
            String startDate,
            String endDate,
            Map<String, VideoMeta> metaMap) {

        List<VideoAnalyticsResult> results = new ArrayList<>();
        if (response == null) return results;

        List<?> columnHeaders = (List<?>) response.get("columnHeaders");
        List<?> rows = (List<?>) response.get("rows");
        if (columnHeaders == null || rows == null) return results;

        for (Object rowObj : rows) {
            List<?> row = (List<?>) rowObj;
            String videoId = null;
            Map<String, Object> metricsMap = new LinkedHashMap<>();

            for (int i = 0; i < columnHeaders.size(); i++) {
                Map<?, ?> header = (Map<?, ?>) columnHeaders.get(i);
                String colName = (String) header.get("name");
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

    private String resolveMetrics(List<String> metrics) {
        return (metrics != null && !metrics.isEmpty())
                ? String.join(",", metrics)
                : defaultMetrics;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
