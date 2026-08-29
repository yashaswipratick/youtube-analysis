package com.youtube.analytics.service;

import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.ChannelAnalyticsResult;
import com.youtube.analytics.model.DailyVideoAnalyticsResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
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

/** Calls the YouTube Analytics API v2 to fetch channel and video-level metrics. */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAnalyticsService {

    private static final String YT_ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String CHANNEL_IDS = "channel==MINE";
    private static final int ANALYTICS_BATCH_SIZE = 500;

    @Value("${youtube.analytics.default-metrics:views,engagedViews,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,comments,shares,subscribersGained,subscribersLost}")
    private String defaultMetrics;

    private final WebClient youTubeWebClient;
    private final YouTubeDataService youTubeDataService;

    public ChannelAnalyticsResult getChannelAnalytics(String startDate, String endDate, List<String> metrics) {
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        log.info("Fetching channel analytics | {} → {} | metrics: {}", resolvedStart, resolvedEnd, resolvedMetrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics))
                .build().toUri();

        Map<String, Object> metricsMap = parseSingleRowResponse(fetchAnalyticsResponse(uri));
        return ChannelAnalyticsResult.builder().startDate(resolvedStart).endDate(resolvedEnd).metrics(metricsMap).build();
    }

    public VideoAnalyticsResult getSingleVideoAnalytics(String videoId, String startDate, String endDate, List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS).queryParam("startDate", resolvedStart).queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics)).queryParam("filters", "video==" + videoId)
                .build().toUri();

        Map<String, Object> metricsMap = parseSingleRowResponse(fetchAnalyticsResponse(uri));
        Map<String, VideoMeta> meta = youTubeDataService.getVideoMeta(List.of(videoId));
        VideoMeta videoMeta = meta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));

        return VideoAnalyticsResult.builder().videoId(videoId).title(videoMeta.getTitle()).publishedAt(videoMeta.getPublishedAt())
                .startDate(resolvedStart).endDate(resolvedEnd).metrics(metricsMap).build();
    }

    /**
     * Fetches the 100-point audience-retention report for one video.
     * YouTube exposes elapsedVideoTimeRatio as the x-axis and audienceWatchRatio
     * and relativeRetentionPerformance as retention signals.
     */
    public VideoRetentionAnalyticsResult getVideoRetentionAnalytics(String videoId, String startDate, String endDate) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", resolvedStart)
                .queryParam("endDate", resolvedEnd)
                .queryParam("dimensions", "elapsedVideoTimeRatio")
                .queryParam("metrics", "audienceWatchRatio,relativeRetentionPerformance")
                .queryParam("filters", "video==" + videoId)
                .build().toUri();

        YouTubeAnalyticsApiResponse response = fetchAnalyticsResponse(uri);
        List<VideoRetentionAnalyticsResult.RetentionPoint> points = parseRetentionRows(response);

        Map<String, Object> summaryMetrics = fetchVideoSummaryMetrics(videoId, resolvedStart, resolvedEnd);
        Double averageViewDuration = toDouble(summaryMetrics.get("averageViewDuration"));
        Double averageViewPercentage = toDouble(summaryMetrics.get("averageViewPercentage"));

        return VideoRetentionAnalyticsResult.builder()
                .videoId(videoId)
                .startDate(resolvedStart)
                .endDate(resolvedEnd)
                .averageViewDurationSeconds(averageViewDuration)
                .averageViewPercentage(averageViewPercentage)
                .retention(points)
                .build();
    }

    private Map<String, Object> fetchVideoSummaryMetrics(String videoId, String startDate, String endDate) {
        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .queryParam("metrics", "averageViewDuration,averageViewPercentage")
                .queryParam("filters", "video==" + videoId)
                .build().toUri();
        return parseSingleRowResponse(fetchAnalyticsResponse(uri));
    }

    private List<VideoRetentionAnalyticsResult.RetentionPoint> parseRetentionRows(YouTubeAnalyticsApiResponse response) {
        List<VideoRetentionAnalyticsResult.RetentionPoint> points = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) return points;

        List<YouTubeAnalyticsColumnHeader> headers = response.getColumnHeaders();
        int ratioIndex = indexOf(headers, "elapsedVideoTimeRatio");
        int watchIndex = indexOf(headers, "audienceWatchRatio");
        int relativeIndex = indexOf(headers, "relativeRetentionPerformance");
        if (ratioIndex < 0 || watchIndex < 0 || relativeIndex < 0) {
            throw new IllegalStateException("YouTube retention response did not contain the expected columns");
        }

        for (List<Object> row : response.getRows()) {
            if (row == null || row.size() <= Math.max(ratioIndex, Math.max(watchIndex, relativeIndex))) continue;
            points.add(VideoRetentionAnalyticsResult.RetentionPoint.builder()
                    .elapsedVideoTimeRatio(toDouble(row.get(ratioIndex)))
                    .audienceWatchRatio(toDouble(row.get(watchIndex)))
                    .relativeRetentionPerformance(toDouble(row.get(relativeIndex)))
                    .build());
        }
        return points;
    }

    private int indexOf(List<YouTubeAnalyticsColumnHeader> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (name.equals(headers.get(i).getName())) return i;
        }
        return -1;
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { return null; }
    }

    public DailyVideoAnalyticsResult getDailyVideoAnalytics(String videoId, String startDate, String endDate, List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);

        URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                .queryParam("ids", CHANNEL_IDS).queryParam("startDate", resolvedStart).queryParam("endDate", resolvedEnd)
                .queryParam("metrics", String.join(",", resolvedMetrics)).queryParam("dimensions", "day")
                .queryParam("filters", "video==" + videoId).build().toUri();

        List<DailyVideoAnalyticsResult.DailyMetricRow> dailyRows = parseDailyRows(fetchAnalyticsResponse(uri));
        Map<String, VideoMeta> meta = youTubeDataService.getVideoMeta(List.of(videoId));
        VideoMeta videoMeta = meta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));
        return DailyVideoAnalyticsResult.builder().videoId(videoId).title(videoMeta.getTitle()).publishedAt(videoMeta.getPublishedAt())
                .startDate(resolvedStart).endDate(resolvedEnd).days(dailyRows).build();
    }

    public List<VideoAnalyticsResult> getMultipleVideoAnalytics(List<String> videoIds, String startDate, String endDate, List<String> metrics) {
        videoIds.forEach(AnalyticsRequestValidator::validateVideoId);
        String resolvedStart = resolveStartDate(startDate);
        String resolvedEnd = resolveEndDate(endDate);
        AnalyticsRequestValidator.validateProvidedDates(resolvedStart, resolvedEnd);
        List<String> resolvedMetrics = resolveMetrics(metrics);
        Map<String, VideoMeta> allMeta = youTubeDataService.getVideoMeta(videoIds);
        List<VideoAnalyticsResult> results = new ArrayList<>();

        List<List<String>> batches = partition(videoIds, ANALYTICS_BATCH_SIZE);
        for (List<String> batch : batches) {
            URI uri = UriComponentsBuilder.fromHttpUrl(YT_ANALYTICS_BASE)
                    .queryParam("ids", CHANNEL_IDS).queryParam("startDate", resolvedStart).queryParam("endDate", resolvedEnd)
                    .queryParam("metrics", String.join(",", resolvedMetrics)).queryParam("filters", "video==" + String.join(",", batch))
                    .queryParam("dimensions", "video").queryParam("sort", "-views").build().toUri();
            results.addAll(parseMultiVideoResponse(fetchAnalyticsResponse(uri), resolvedStart, resolvedEnd, allMeta));
        }
        return results;
    }

    public List<VideoAnalyticsResult> getAllVideosAnalytics(String startDate, String endDate, List<String> metrics) {
        List<String> allVideoIds = youTubeDataService.getAllVideoIds();
        if (allVideoIds.isEmpty()) return Collections.emptyList();
        List<VideoAnalyticsResult> analyticsResults = getMultipleVideoAnalytics(allVideoIds, startDate, endDate, metrics);
        Map<String, VideoAnalyticsResult> byVideoId = new LinkedHashMap<>();
        analyticsResults.forEach(result -> byVideoId.put(result.getVideoId(), result));
        String resolvedStart = resolveStartDate(startDate), resolvedEnd = resolveEndDate(endDate);
        Map<String, VideoMeta> allMeta = youTubeDataService.getVideoMeta(allVideoIds);
        List<VideoAnalyticsResult> completeResults = new ArrayList<>(allVideoIds.size());
        for (String videoId : allVideoIds) {
            VideoAnalyticsResult result = byVideoId.get(videoId);
            if (result != null) { completeResults.add(result); continue; }
            VideoMeta meta = allMeta.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));
            completeResults.add(VideoAnalyticsResult.builder().videoId(videoId).title(meta.getTitle()).publishedAt(meta.getPublishedAt())
                    .startDate(resolvedStart).endDate(resolvedEnd).metrics(new LinkedHashMap<>()).build());
        }
        return completeResults;
    }

    private Map<String, Object> parseSingleRowResponse(YouTubeAnalyticsApiResponse response) {
        Map<String, Object> metricsMap = new LinkedHashMap<>();
        if (response == null) return metricsMap;
        List<YouTubeAnalyticsColumnHeader> columnHeaders = response.getColumnHeaders();
        List<List<Object>> rows = response.getRows();
        if (columnHeaders == null || rows == null || rows.isEmpty()) return metricsMap;
        List<Object> firstRow = rows.get(0);
        for (int i = 0; i < columnHeaders.size() && i < firstRow.size(); i++) {
            String colName = columnHeaders.get(i).getName();
            if (colName != null) metricsMap.put(colName, firstRow.get(i));
        }
        return metricsMap;
    }

    private List<DailyVideoAnalyticsResult.DailyMetricRow> parseDailyRows(YouTubeAnalyticsApiResponse response) {
        List<DailyVideoAnalyticsResult.DailyMetricRow> results = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) return results;
        List<YouTubeAnalyticsColumnHeader> headers = response.getColumnHeaders();
        int dayIndex = indexOf(headers, "day");
        if (dayIndex < 0) throw new IllegalStateException("YouTube Analytics daily response did not contain the day dimension");
        for (List<Object> row : response.getRows()) {
            if (row == null || row.size() <= dayIndex) continue;
            String date = String.valueOf(row.get(dayIndex));
            Map<String, Object> metricsMap = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                if (i != dayIndex && headers.get(i).getName() != null) metricsMap.put(headers.get(i).getName(), row.get(i));
            }
            results.add(DailyVideoAnalyticsResult.DailyMetricRow.builder().date(date).metrics(metricsMap).build());
        }
        return results;
    }

    private List<VideoAnalyticsResult> parseMultiVideoResponse(YouTubeAnalyticsApiResponse response, String startDate, String endDate, Map<String, VideoMeta> metaMap) {
        List<VideoAnalyticsResult> results = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) return results;
        List<YouTubeAnalyticsColumnHeader> headers = response.getColumnHeaders();
        for (List<Object> row : response.getRows()) {
            String videoId = null;
            Map<String, Object> metricsMap = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                String colName = headers.get(i).getName();
                if ("video".equals(colName)) videoId = String.valueOf(row.get(i));
                else metricsMap.put(colName, row.get(i));
            }
            VideoMeta meta = metaMap.getOrDefault(videoId, new VideoMeta(videoId, videoId, null));
            results.add(VideoAnalyticsResult.builder().videoId(videoId).title(meta.getTitle()).publishedAt(meta.getPublishedAt())
                    .startDate(startDate).endDate(endDate).metrics(metricsMap).build());
        }
        return results;
    }

    private String resolveStartDate(String startDate) { return (startDate != null && !startDate.isBlank()) ? startDate : LocalDate.now().minusDays(365).toString(); }
    private String resolveEndDate(String endDate) { return (endDate != null && !endDate.isBlank()) ? endDate : LocalDate.now().toString(); }

    private List<String> resolveMetrics(List<String> metrics) {
        List<String> requestedMetrics = (metrics == null || metrics.isEmpty())
                ? Stream.of(defaultMetrics.split(",")).map(String::trim).toList()
                : metrics.stream().flatMap(metric -> metric == null ? Stream.of((String) null) : Stream.of(metric.split(",")))
                .map(value -> value == null ? null : value.trim()).toList();
        return AnalyticsRequestValidator.validateVideoMetrics(requestedMetrics);
    }

    private YouTubeAnalyticsApiResponse fetchAnalyticsResponse(URI uri) {
        return youTubeWebClient.get().uri(uri).retrieve()
                .onStatus(status -> status.isError(), response -> Mono.error(new YouTubeAnalyticsApiException(response.statusCode())))
                .bodyToMono(YouTubeAnalyticsApiResponse.class).block();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) parts.add(list.subList(i, Math.min(i + size, list.size())));
        return parts;
    }
}
