package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.VideoMeta;
import com.youtube.analytics.model.YouTubeAnalyticsApiResponse;
import com.youtube.analytics.model.YouTubeAnalyticsColumnHeader;
import com.youtube.analytics.model.YouTubeReachReportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dedicated YouTube Studio reports. This service uses official YouTube Analytics v2 and
 * YouTube Reporting v1; metrics unavailable through those APIs are described, not synthesized.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeStudioReportsService {

    private static final String ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String REPORTING_BASE = "https://youtubereporting.googleapis.com/v1";
    private static final String CHANNEL = "channel==MINE";
    private static final ZoneId YOUTUBE_REPORTING_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Set<String> TREND_METRICS = Set.of(
            "views", "engagedViews", "estimatedMinutesWatched", "averageViewDuration",
            "averageViewPercentage", "likes", "comments", "shares", "subscribersGained",
            "subscribersLost", "uniques", "viewerPercentage", "redViews",
            "estimatedRedMinutesWatched", "estimatedRevenue", "estimatedAdRevenue",
            "estimatedRedPartnerRevenue", "grossRevenue", "cpm", "adImpressions",
            "monetizedPlaybacks", "playbackBasedCpm", "cardImpressions", "cardClicks",
            "cardClickRate", "cardTeaserImpressions", "cardTeaserClicks",
            "cardTeaserClickRate", "videosAddedToPlaylists", "videosRemovedFromPlaylists",
            "annotationImpressions", "annotationClicks", "annotationClickThroughRate",
            "annotationClosableImpressions", "annotationCloses", "annotationCloseRate",
            "annotationClickableImpressions");
    private static final List<String> OVERVIEW_METRICS = List.of(
            "views", "estimatedMinutesWatched", "subscribersGained", "subscribersLost",
            "averageViewDuration", "averageViewPercentage", "likes", "comments", "shares");
    private static final List<String> PERFORMANCE_METRICS = List.of(
            "views", "averageViewDuration", "averageViewPercentage", "subscribersGained", "shares");
    private static final List<String> REVENUE_METRICS = List.of(
            "estimatedRevenue", "estimatedAdRevenue", "estimatedRedPartnerRevenue",
            "grossRevenue", "cpm", "playbackBasedCpm", "adImpressions", "monetizedPlaybacks");

    private final WebClient youTubeWebClient;
    private final YouTubeDataService youTubeDataService;
    private final YouTubeReachReportingService reachReportingService;

    public Map<String, Object> overview(String startDate, String endDate) {
        DateWindow window = resolveWindow(startDate, endDate);
        Map<String, Object> metrics = firstRow(query(window, OVERVIEW_METRICS, null, null, null, null));
        Object revenue;
        boolean revenueAvailable;
        try {
            revenue = firstRow(query(window, REVENUE_METRICS, null, null, null, null));
            revenueAvailable = !((Map<?, ?>) revenue).isEmpty();
        } catch (RuntimeException ex) {
            revenue = null;
            revenueAvailable = false;
        }
        Map<String, Object> result = envelope(window, "channel overview");
        result.put("metrics", metrics);
        Object gained = metrics.get("subscribersGained");
        Object lost = metrics.get("subscribersLost");
        if (gained instanceof Number g && lost instanceof Number l) {
            metrics.put("netSubscribers", g.longValue() - l.longValue());
        }
        result.put("revenue", revenue);
        result.put("revenueAvailable", revenueAvailable);
        if (!revenueAvailable) {
            result.put("revenueNote", "Revenue metrics require channel monetization and the yt-analytics-monetary.readonly OAuth scope.");
        }
        result.put("notes", List.of(
                "The Subscribers headline is represented by subscriber gains, losses and net change for the selected period; current lifetime subscriber count is a separate channel statistic.",
                "Estimated revenue can be null when the channel is not in the YouTube Partner Program or monetary access has not been granted."));
        return result;
    }

    public Map<String, Object> topVideos(String startDate, String endDate, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        DateWindow window = resolveWindow(startDate, endDate);
        YouTubeAnalyticsApiResponse response = query(window,
                List.of("views", "estimatedMinutesWatched", "averageViewDuration",
                        "averageViewPercentage", "subscribersGained", "shares"),
                "video", null, "-views", limit);
        List<Map<String, Object>> rows = rows(response);
        List<String> ids = rows.stream().map(row -> String.valueOf(row.get("video")))
                .filter(id -> !id.isBlank() && !"null".equals(id)).toList();
        Map<String, VideoMeta> metadata = ids.isEmpty() ? Map.of() : youTubeDataService.getVideoMeta(ids);
        rows.forEach(row -> {
            String id = String.valueOf(row.get("video"));
            VideoMeta meta = metadata.get(id);
            if (meta != null) {
                row.put("title", meta.getTitle());
                row.put("publishedAt", meta.getPublishedAt());
            }
        });
        Map<String, Object> result = envelope(window, "top videos");
        result.put("limit", limit);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> compareVideos(String startDate, String endDate, List<String> videoIds, List<String> metrics) {
        if (videoIds == null || videoIds.isEmpty() || videoIds.size() > 50) {
            throw new IllegalArgumentException("videoIds must contain between 1 and 50 video IDs");
        }
        videoIds.forEach(AnalyticsRequestValidator::validateVideoId);
        List<String> resolvedMetrics = resolveMetrics(metrics, List.of(
                "views", "estimatedMinutesWatched", "averageViewDuration",
                "averageViewPercentage", "likes", "comments", "shares",
                "subscribersGained", "subscribersLost"));
        DateWindow window = resolveWindow(startDate, endDate);
        YouTubeAnalyticsApiResponse response = query(window, resolvedMetrics, "video",
                "video==" + String.join(",", videoIds), "-views", 50);
        List<Map<String, Object>> rows = rows(response);
        Map<String, VideoMeta> metadata = youTubeDataService.getVideoMeta(videoIds);
        rows.forEach(row -> {
            VideoMeta meta = metadata.get(String.valueOf(row.get("video")));
            if (meta != null) {
                row.put("title", meta.getTitle());
                row.put("publishedAt", meta.getPublishedAt());
            }
        });
        Map<String, Object> result = envelope(window, "video comparison");
        result.put("metrics", resolvedMetrics);
        result.put("rows", rows);
        result.put("requestedVideoIds", videoIds);
        return result;
    }

    public Map<String, Object> audience(String startDate, String endDate, String videoId) {
        if (videoId != null && !videoId.isBlank()) AnalyticsRequestValidator.validateVideoId(videoId);
        DateWindow window = resolveWindow(startDate, endDate);
        String filter = videoId == null || videoId.isBlank() ? null : "video==" + videoId;
        Object subscribed;
        Object demographics;
        Object uniqueViewers;
        try {
            subscribed = rows(query(window, List.of("views", "estimatedMinutesWatched"),
                    "subscribedStatus", filter, "-views", 20));
        } catch (RuntimeException ex) {
            subscribed = List.of();
        }
        try {
            demographics = rows(query(window, List.of("viewerPercentage"),
                    "ageGroup,gender", filter, "-viewerPercentage", 50));
        } catch (RuntimeException ex) {
            demographics = List.of();
        }
        try {
            uniqueViewers = firstRow(query(window, List.of("uniques"), null, filter, null, null)).get("uniques");
        } catch (RuntimeException ex) {
            uniqueViewers = null;
        }

        Map<String, Object> result = envelope(window, "audience reports");
        result.put("uniqueViewers", uniqueViewers);
        result.put("watchTimeBySubscribedStatus", subscribed);
        result.put("ageAndGender", demographics);
        result.put("returningViewers", null);
        result.put("returningViewersNote", "Returning viewers is shown in YouTube Studio but is not available as a supported metric in the official YouTube Analytics API v2.");
        result.put("whenViewersAreOnYouTube", null);
        result.put("whenViewersAreOnYouTubeNote", "The audience activity heatmap is not exposed by the official YouTube Analytics API.");
        result.put("otherChannelsAudienceWatches", null);
        result.put("otherVideosAudienceWatches", null);
        result.put("audienceResearchNote", "Other channels/videos watched are Studio audience-research surfaces and are not returned by the official reporting endpoints.");
        return result;
    }

    public Map<String, Object> formatBreakdown(String startDate, String endDate) {
        DateWindow window = resolveWindow(startDate, endDate);
        YouTubeAnalyticsApiResponse response = query(window,
                List.of("views", "estimatedMinutesWatched", "averageViewDuration", "averageViewPercentage"),
                "creatorContentType", null, "-views", 20);
        Map<String, Object> result = envelope(window, "Shorts/video/live format comparison");
        result.put("rows", rows(response));
        result.put("formatDimension", "creatorContentType");
        result.put("note", "Uses the official creatorContentType dimension. Availability and processing delay depend on the channel and date range.");
        return result;
    }

    public Map<String, Object> trafficSources(String startDate, String endDate, String videoId) {
        if (videoId != null && !videoId.isBlank()) AnalyticsRequestValidator.validateVideoId(videoId);
        DateWindow window = resolveWindow(startDate, endDate);
        String filter = videoId == null || videoId.isBlank() ? null : "video==" + videoId;
        YouTubeAnalyticsApiResponse response = query(window,
                List.of("views", "estimatedMinutesWatched"),
                "insightTrafficSourceType", filter, "-views", 50);
        Map<String, Object> result = envelope(window, "traffic source breakdown");
        result.put("rows", rows(response));
        result.put("videoId", videoId);
        result.put("note", "Traffic source types include Browse features, Suggested videos, YouTube Search, External, Shorts feed and other sources where reportable.");
        return result;
    }

    public Map<String, Object> searchTerms(String startDate, String endDate, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        DateWindow window = resolveWindow(startDate, endDate);
        List<String> ids = youTubeDataService.getAllVideoIds();
        Map<String, Long> termViews = new HashMap<>();
        List<List<String>> batches = partition(ids, 500);
        for (List<String> batch : batches) {
            if (batch.isEmpty()) continue;
            try {
                String filter = "video==" + String.join(",", batch) + ";insightTrafficSourceType==YT_SEARCH";
                YouTubeAnalyticsApiResponse response = query(window, List.of("views"),
                        "insightTrafficSourceDetail", filter, "-views", 50);
                for (Map<String, Object> row : rows(response)) {
                    Object detail = row.get("insightTrafficSourceDetail");
                    long views = asLong(row.get("views"));
                    if (detail != null && !String.valueOf(detail).isBlank() && views > 0) {
                        termViews.merge(String.valueOf(detail), views, Long::sum);
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("Unable to fetch a YouTube search terms batch: {}", ex.getMessage());
            }
        }
        List<Map<String, Object>> rows = termViews.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> Map.<String, Object>of("searchTerm", entry.getKey(), "views", entry.getValue()))
                .toList();
        Map<String, Object> result = envelope(window, "YouTube Search terms");
        result.put("limit", limit);
        result.put("rows", rows);
        result.put("note", "This reports searches that generated views for your videos, not all searches happening on YouTube or unmet topic demand.");
        return result;
    }

    public Map<String, Object> revenue(String startDate, String endDate, String videoId, int limit) {
        if (videoId != null && !videoId.isBlank()) AnalyticsRequestValidator.validateVideoId(videoId);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        DateWindow window = resolveWindow(startDate, endDate);
        String filter = videoId == null || videoId.isBlank() ? null : "video==" + videoId;
        try {
            YouTubeAnalyticsApiResponse response = query(window, REVENUE_METRICS,
                    videoId == null || videoId.isBlank() ? "video" : null, filter,
                    "-estimatedRevenue", limit);
            Map<String, Object> result = envelope(window, "revenue report");
            result.put("available", true);
            result.put("videoId", videoId);
            if (videoId == null || videoId.isBlank()) {
                List<Map<String, Object>> revenueRows = rows(response);
                Map<String, Long> viewsByVideo = new HashMap<>();
                try {
                    YouTubeAnalyticsApiResponse viewsResponse = query(window, List.of("views"),
                            "video", null, "-views", 200);
                    for (Map<String, Object> viewsRow : rows(viewsResponse)) {
                        Object id = viewsRow.get("video");
                        if (id != null) viewsByVideo.put(String.valueOf(id), asLong(viewsRow.get("views")));
                    }
                } catch (RuntimeException ex) {
                    log.debug("Could not calculate RPM because the views report was unavailable: {}", ex.getMessage());
                }
                revenueRows.forEach(row -> {
                    Object id = row.get("video");
                    Long views = id == null ? null : viewsByVideo.get(String.valueOf(id));
                    row.put("rpm", calculateRpm(row.get("estimatedRevenue"), views));
                });
                result.put("metrics", revenueRows);
            } else {
                Map<String, Object> revenueMetrics = firstRow(response);
                Long views = null;
                try {
                    views = asLong(firstRow(query(window, List.of("views"), null, filter, null, null)).get("views"));
                } catch (RuntimeException ex) {
                    log.debug("Could not calculate RPM for video {}: {}", videoId, ex.getMessage());
                }
                revenueMetrics.put("rpm", calculateRpm(revenueMetrics.get("estimatedRevenue"), views));
                result.put("metrics", revenueMetrics);
            }
            result.put("note", "Revenue values are estimated. Google requires the channel to be in the YouTube Partner Program and the yt-analytics-monetary.readonly scope.");
            return result;
        } catch (RuntimeException ex) {
            Map<String, Object> result = envelope(window, "revenue report");
            result.put("available", false);
            result.put("metrics", null);
            result.put("reason", "Revenue is unavailable. Confirm channel monetization and re-authorize the Google account with yt-analytics-monetary.readonly.");
            return result;
        }
    }

    public Map<String, Object> videoReach(String videoId, String startDate, String endDate) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        DateWindow window = resolveWindow(startDate, endDate);
        YouTubeReachReportResult reach = reachReportingService.getVideoReach(
                videoId, window.startDate().toString(), window.endDate().toString());
        Map<String, Object> result = envelope(window, "video reach");
        result.put("videoId", videoId);
        result.put("available", reach.available());
        result.put("impressions", reach.impressions());
        result.put("impressionsClickThroughRate", reach.impressionsClickThroughRate());
        if (!reach.available()) {
            result.put("note", "The YouTube Reporting API generates reach reports asynchronously. Impressions and CTR may be null until the report is ready.");
        }
        return result;
    }

    public Map<String, Object> recentVideoBenchmarks(int limit) {
        if (limit < 1 || limit > 15) throw new IllegalArgumentException("limit must be between 1 and 15");
        LocalDate today = LocalDate.now(YOUTUBE_REPORTING_ZONE);
        List<String> allIds = youTubeDataService.getAllVideoIds();
        List<String> ids = allIds.stream().limit(limit).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOfDate", today.toString());
        result.put("requestedLimit", limit);
        if (ids.isEmpty()) {
            result.put("rows", List.of());
            return result;
        }
        Map<String, VideoMeta> metadata = youTubeDataService.getVideoMeta(ids);
        Map<String, YouTubeReachReportingService.DateRange> first48Ranges = new LinkedHashMap<>();
        Map<String, YouTubeReachReportingService.DateRange> first28Ranges = new LinkedHashMap<>();
        Map<String, DateWindow> validWindows48 = new LinkedHashMap<>();
        Map<String, DateWindow> validWindows28 = new LinkedHashMap<>();
        Map<String, Map<String, Object>> videoRows = new LinkedHashMap<>();

        for (String id : ids) {
            VideoMeta meta = metadata.get(id);
            LocalDate published = parsePublishDate(meta == null ? null : meta.getPublishedAt());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("videoId", id);
            item.put("title", meta == null ? id : meta.getTitle());
            item.put("publishedAt", meta == null ? null : meta.getPublishedAt());
            videoRows.put(id, item);
            if (published == null || published.isAfter(today)) {
                item.put("benchmarkAvailable", false);
                item.put("note", "No valid published date is available for this video.");
                continue;
            }
            LocalDate end48 = minDate(published.plusDays(1), today);
            LocalDate end28 = minDate(published.plusDays(27), today);
            DateWindow window48 = new DateWindow(published, end48);
            DateWindow window28 = new DateWindow(published, end28);
            validWindows48.put(id, window48);
            validWindows28.put(id, window28);
            first48Ranges.put(id, new YouTubeReachReportingService.DateRange(published, end48));
            first28Ranges.put(id, new YouTubeReachReportingService.DateRange(published, end28));
            item.put("first48HoursWindow", Map.of("startDate", published.toString(), "endDate", end48.toString()));
            item.put("first48HoursExact", false);
            item.put("first48HoursNote", "YouTube Analytics is date-granular, so this uses the publication date and the following calendar date rather than an exact rolling 48-hour window.");
            item.put("first28DaysWindow", Map.of("startDate", published.toString(), "endDate", end28.toString()));
            item.put("first28DaysComplete", !published.plusDays(27).isAfter(today));
            item.put("benchmarkAvailable", true);
        }

        for (Map.Entry<String, DateWindow> entry : validWindows48.entrySet()) {
            try {
                videoRows.get(entry.getKey()).put("first48Hours", firstRow(query(entry.getValue(), PERFORMANCE_METRICS,
                        null, "video==" + entry.getKey(), null, null)));
            } catch (RuntimeException ex) {
                videoRows.get(entry.getKey()).put("first48Hours", Map.of());
                videoRows.get(entry.getKey()).put("first48HoursAvailable", false);
            }
        }
        for (Map.Entry<String, DateWindow> entry : validWindows28.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> item = videoRows.get(id);
            try {
                item.put("first28Days", firstRow(query(entry.getValue(), PERFORMANCE_METRICS,
                        null, "video==" + id, null, null)));
            } catch (RuntimeException ex) {
                item.put("first28Days", Map.of());
                item.put("first28DaysAvailable", false);
            }
            try {
                YouTubeAnalyticsApiResponse traffic = query(entry.getValue(), List.of("views"),
                        "insightTrafficSourceType", "video==" + id, "-views", 50);
                List<Map<String, Object>> sources = rows(traffic);
                item.put("topTrafficSource", sources.stream().max(Comparator.comparingLong(row -> asLong(row.get("views"))))
                        .map(row -> row.get("insightTrafficSourceType")).orElse(null));
                item.put("trafficSourcesFirst28Days", sources);
            } catch (RuntimeException ex) {
                item.put("topTrafficSource", null);
                item.put("trafficSourcesFirst28Days", List.of());
            }
        }

        Map<String, YouTubeReachReportResult> reach48 = reachReportingService.getVideoReachForRanges(first48Ranges);
        Map<String, YouTubeReachReportResult> reach28 = reachReportingService.getVideoReachForRanges(first28Ranges);
        videoRows.forEach((id, item) -> {
            YouTubeReachReportResult first48 = reach48.getOrDefault(id, YouTubeReachReportResult.unavailable());
            YouTubeReachReportResult first28 = reach28.getOrDefault(id, YouTubeReachReportResult.unavailable());
            item.put("first48HoursImpressions", first48.impressions());
            item.put("first48HoursCtr", first48.impressionsClickThroughRate());
            item.put("first28DaysImpressions", first28.impressions());
            item.put("first28DaysCtr", first28.impressionsClickThroughRate());
            item.put("reachReportAvailable", first48.available() || first28.available());
            if (!first48.available() && !first28.available()) {
                item.put("reachNote", "Reach data is unavailable yet or the YouTube Reporting API has not generated the required CSV report.");
            }
        });
        result.put("rows", new ArrayList<>(videoRows.values()));
        result.put("notes", List.of(
                "For each recent upload, the response includes first-48-hour (date-based approximation) and first-28-day metrics.",
                "Impressions/CTR are read from the asynchronous YouTube Reporting API and may be unavailable until reports are generated.",
                "The upload list is limited to the latest videos returned by the channel's uploads playlist."));
        return result;
    }

    public Map<String, Object> subtitleLanguages(String startDate, String endDate) {
        DateWindow window = resolveWindow(startDate, endDate);
        Map<String, Object> result = envelope(window, "subtitle language report");
        try {
            String jobId = findOrCreateReportingJob("channel_subtitles_a3", "YouTube subtitle language analysis");
            if (jobId == null) {
                result.put("available", false);
                result.put("rows", List.of());
                result.put("note", "YouTube Reporting API subtitle job could not be created. Re-check OAuth scope and Reporting API availability.");
                return result;
            }
            Map<String, Map<String, Long>> languageTotals = new LinkedHashMap<>();
            JsonNode reports = youTubeWebClient.get().uri(REPORTING_BASE + "/jobs/" + jobId + "/reports")
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (reports != null && reports.path("reports").isArray()) {
                for (var report : reports.path("reports")) {
                    LocalDate start = parsePublishDate(report.path("startTime").asText(null));
                    LocalDate end = parsePublishDate(report.path("endTime").asText(null));
                    if (start == null || end == null || end.isBefore(window.startDate()) || start.isAfter(window.endDate())) continue;
                    String downloadUrl = report.path("downloadUrl").asText("");
                    if (downloadUrl.isBlank()) continue;
                    String csv = youTubeWebClient.get().uri(downloadUrl).retrieve().bodyToMono(String.class).block();
                    aggregateSubtitleCsv(csv, window, languageTotals);
                }
            }
            List<Map<String, Object>> rows = languageTotals.entrySet().stream()
                    .sorted((left, right) -> Long.compare(right.getValue().getOrDefault("views", 0L),
                            left.getValue().getOrDefault("views", 0L)))
                    .map(entry -> Map.<String, Object>of(
                            "subtitleLanguage", entry.getKey(),
                            "views", entry.getValue().getOrDefault("views", 0L),
                            "watchTimeMinutes", entry.getValue().getOrDefault("watch_time_minutes", 0L)))
                    .toList();
            result.put("available", !rows.isEmpty());
            result.put("rows", rows);
            result.put("note", "The Reporting API subtitle report identifies the caption language used for the longest portion of each view; it excludes views where captions are mostly off and can lag behind current data.");
        } catch (RuntimeException ex) {
            result.put("available", false);
            result.put("rows", List.of());
            result.put("note", "Subtitle reports are not available yet. The YouTube Reporting API is asynchronous and needs to generate the report first.");
        }
        return result;
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", Map.ofEntries(
                Map.entry("overview", "YouTube Analytics API v2"),
                Map.entry("impressionsAndCtr", "YouTube Reporting API channel_reach_basic_a1; asynchronous"),
                Map.entry("averageViewDurationAndPercentage", "YouTube Analytics API v2"),
                Map.entry("trafficSources", "YouTube Analytics API v2 insightTrafficSourceType"),
                Map.entry("audienceRetention", "YouTube Analytics API v2 audienceWatchRatio and relativeRetentionPerformance"),
                Map.entry("likesCommentsSharesSubscribersGained", "YouTube Analytics API v2"),
                Map.entry("uniqueViewers", "YouTube Analytics API v2 uniques metric when available"),
                Map.entry("subscribedVsUnsubscribedWatchTime", "YouTube Analytics API v2 subscribedStatus dimension"),
                Map.entry("ageGender", "YouTube Analytics API v2 ageGroup and gender dimensions"),
                Map.entry("subtitleLanguage", "YouTube Reporting API channel_subtitles_a3; asynchronous"),
                Map.entry("formatComparison", "YouTube Analytics API v2 creatorContentType dimension"),
                Map.entry("searchTerms", "YouTube Analytics API insightTrafficSourceDetail filtered to YT_SEARCH"),
                Map.entry("revenue", "YouTube Analytics API v2; requires YPP monetization and monetary OAuth scope")));
        result.put("unsupportedOrNotOfficiallyExposed", Map.ofEntries(
                Map.entry("returningViewers", "The official YouTube Analytics API v2 does not expose a returningViewers metric."),
                Map.entry("whenViewersAreOnYouTube", "The Studio audience activity heatmap has no corresponding supported public API report."),
                Map.entry("otherChannelsAudienceWatches", "Studio audience research is not exposed through the official Analytics API."),
                Map.entry("otherVideosAudienceWatches", "Studio audience research is not exposed through the official Analytics API."),
                Map.entry("uncoveredSearchDemand", "The API can return search terms that generated views for your videos, not all searches without matching content.")));
        return result;
    }

    private YouTubeAnalyticsApiResponse query(DateWindow window, List<String> metrics, String dimensions,
                                               String filters, String sort, Integer maxResults) {
        List<String> validatedMetrics = resolveMetrics(metrics, new ArrayList<>(TREND_METRICS));
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(ANALYTICS_BASE)
                .queryParam("ids", CHANNEL)
                .queryParam("startDate", window.startDate())
                .queryParam("endDate", window.endDate())
                .queryParam("metrics", String.join(",", validatedMetrics));
        if (dimensions != null && !dimensions.isBlank()) builder.queryParam("dimensions", dimensions);
        if (filters != null && !filters.isBlank()) builder.queryParam("filters", filters);
        if (sort != null && !sort.isBlank()) builder.queryParam("sort", sort);
        if (maxResults != null) builder.queryParam("maxResults", maxResults);
        URI uri = builder.build().encode().toUri();
        return youTubeWebClient.get().uri(uri).retrieve()
                .onStatus(status -> status.isError(), response ->
                        Mono.error(new YouTubeAnalyticsApiException(response.statusCode())))
                .bodyToMono(YouTubeAnalyticsApiResponse.class).block();
    }

    private List<String> resolveMetrics(List<String> requested, List<String> allowed) {
        List<String> values = (requested == null || requested.isEmpty())
                ? List.of("views")
                : requested.stream().flatMap(item -> item == null ? java.util.stream.Stream.empty()
                        : java.util.Arrays.stream(item.split(",")))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        for (String value : values) {
            if (!allowed.contains(value)) throw new IllegalArgumentException("Unsupported YouTube Analytics metric: " + value);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("At least one metric is required");
        return values;
    }

    private List<Map<String, Object>> rows(YouTubeAnalyticsApiResponse response) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (response == null || response.getColumnHeaders() == null || response.getRows() == null) return rows;
        for (List<Object> row : response.getRows()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < response.getColumnHeaders().size() && i < row.size(); i++) {
                String name = response.getColumnHeaders().get(i).getName();
                if (name != null) values.put(name, row.get(i));
            }
            rows.add(values);
        }
        return rows;
    }

    private Map<String, Object> firstRow(YouTubeAnalyticsApiResponse response) {
        List<Map<String, Object>> rows = rows(response);
        return rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> envelope(DateWindow window, String report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", report);
        result.put("startDate", window.startDate().toString());
        result.put("endDate", window.endDate().toString());
        return result;
    }

    private DateWindow resolveWindow(String startDate, String endDate) {
        LocalDate today = LocalDate.now(YOUTUBE_REPORTING_ZONE);
        LocalDate end = endDate == null || endDate.isBlank() ? today : AnalyticsRequestValidator.parseDate("endDate", endDate);
        LocalDate start = startDate == null || startDate.isBlank() ? end.minusDays(27) : AnalyticsRequestValidator.parseDate("startDate", startDate);
        if (start.isAfter(end)) throw new IllegalArgumentException("startDate must be on or before endDate");
        if (end.isAfter(today)) throw new IllegalArgumentException("endDate must not be in the future");
        return new DateWindow(start, end);
    }

    private String findOrCreateReportingJob(String reportTypeId, String name) {
        var jobs = youTubeWebClient.get().uri(REPORTING_BASE + "/jobs").retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block();
        if (jobs != null && jobs.path("jobs").isArray()) {
            for (var job : jobs.path("jobs")) {
                if (reportTypeId.equals(job.path("reportTypeId").asText())) return job.path("id").asText(null);
            }
        }
        var created = youTubeWebClient.post().uri(REPORTING_BASE + "/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reportTypeId", reportTypeId, "name", name))
                .retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block();
        return created == null ? null : created.path("id").asText(null);
    }

    private void aggregateSubtitleCsv(String csv, DateWindow window, Map<String, Map<String, Long>> totals) {
        if (csv == null || csv.isBlank()) return;
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String firstLine = reader.readLine();
            if (firstLine == null) return;
            List<String> headers = parseCsvLine(firstLine);
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) indexes.put(headers.get(i), i);
            Integer dateIndex = indexes.get("date");
            Integer languageIndex = indexes.get("subtitle_language");
            Integer viewsIndex = indexes.get("views");
            Integer watchTimeIndex = indexes.get("watch_time_minutes");
            if (dateIndex == null || languageIndex == null || viewsIndex == null) return;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsvLine(line);
                int required = Math.max(dateIndex, Math.max(languageIndex, Math.max(viewsIndex,
                        watchTimeIndex == null ? 0 : watchTimeIndex)));
                if (values.size() <= required) continue;
                LocalDate date = parsePublishDate(values.get(dateIndex));
                if (date == null || date.isBefore(window.startDate()) || date.isAfter(window.endDate())) continue;
                String language = values.get(languageIndex);
                if (language == null || language.isBlank()) continue;
                Map<String, Long> counts = totals.computeIfAbsent(language, ignored -> new HashMap<>());
                counts.merge("views", parseLong(values.get(viewsIndex)), Long::sum);
                if (watchTimeIndex != null) counts.merge("watch_time_minutes", parseLong(values.get(watchTimeIndex)), Long::sum);
            }
        } catch (Exception ex) {
            log.debug("Could not parse YouTube subtitle report CSV: {}", ex.getMessage());
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString().trim()); current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString().trim());
        return values;
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value); } catch (RuntimeException ex) { return 0L; }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException ex) { return 0L; }
    }

    private Double calculateRpm(Object estimatedRevenue, Long views) {
        if (estimatedRevenue == null || views == null || views <= 0) return null;
        double revenue;
        try {
            revenue = estimatedRevenue instanceof Number number
                    ? number.doubleValue() : Double.parseDouble(String.valueOf(estimatedRevenue));
        } catch (RuntimeException ex) {
            return null;
        }
        return Double.isFinite(revenue) ? revenue * 1000.0 / views : null;
    }

    private LocalDate parsePublishDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value).atZone(YOUTUBE_REPORTING_ZONE).toLocalDate(); }
        catch (RuntimeException ignored) {
            try { return LocalDate.parse(value.substring(0, Math.min(value.length(), 10))); }
            catch (RuntimeException ex) { return null; }
        }
    }

    private LocalDate minDate(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) result.add(items.subList(i, Math.min(i + size, items.size())));
        return result;
    }

    private record DateWindow(LocalDate startDate, LocalDate endDate) {
    }
}
