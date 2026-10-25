package com.youtube.analytics.service.businesslogic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.ContentClassification;
import com.youtube.analytics.model.NormalizedVideoAnalytics;
import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import com.youtube.analytics.model.VideoPerformanceScore;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.service.AnalyticsDateRangeResolver;
import com.youtube.analytics.service.AnalyticsRequestValidator;
import com.youtube.analytics.service.AnalyticsDateRangeResolver.DateRange;
import com.youtube.analytics.service.AnalyticsDecisionService;
import com.youtube.analytics.service.ContentClassificationService;
import com.youtube.analytics.service.DiscoveryOptimizationService;
import com.youtube.analytics.service.RecommendationEngineService;
import com.youtube.analytics.service.RetentionAnalysisService;
import com.youtube.analytics.service.VideoPerformanceScoringService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import com.youtube.analytics.service.YouTubeDataService;
import com.youtube.analytics.service.YouTubePublicApiService;
import com.youtube.analytics.service.YouTubeStudioReportsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Composes the existing analytics services into user-facing workflows.
 * Channel-owned Analytics reports are never requested for another channel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsWorkspaceService {
    private static final String DATA_API = "https://www.googleapis.com/youtube/v3";
    private static final Pattern ISO_DURATION = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
    private static final int PUBLIC_UPLOAD_PAGE_SIZE = 50;
    private static final int MAX_PUBLIC_PAGES = 1;

    private final WebClient youTubeWebClient;
    private final ObjectMapper objectMapper;
    private final AnalyticsDateRangeResolver dateRangeResolver;
    private final YouTubeAnalyticsService analyticsService;
    private final YouTubeStudioReportsService reportsService;
    private final YouTubeDataService youTubeDataService;
    private final YouTubePublicApiService publicApiService;
    private final RetentionAnalysisService retentionAnalysisService;
    private final DiscoveryOptimizationService discoveryOptimizationService;
    private final AnalyticsDecisionService analyticsDecisionService;
    private final RecommendationEngineService recommendationEngineService;
    private final ContentClassificationService contentClassificationService;
    private final VideoPerformanceScoringService performanceScoringService;
    private final AnalyticsWorkspaceBusinessLogic businessLogic;

    public Map<String, Object> channel(String requestedChannelId, Integer lastNDays) {
        return channel(requestedChannelId, lastNDays, true);
    }

    private Map<String, Object> channel(String requestedChannelId, Integer lastNDays, boolean includeAnalyticsReports) {
        int days = validDays(lastNDays);
        DateRange reportRange = dateRangeResolver.resolve(days);
        JsonNode mine = firstItem(dataApi("/channels", params("part", "id,snippet,contentDetails,statistics", "mine", "true")));
        String mineId = mine.path("id").asText("");
        String channelId = requestedChannelId == null || requestedChannelId.isBlank()
                || "mine".equalsIgnoreCase(requestedChannelId) ? mineId : requestedChannelId.trim();
        if (channelId.isBlank()) throw new IllegalStateException("Could not resolve the signed-in YouTube channel. Re-authorize the account with YouTube Data API access.");
        JsonNode channel = channelId.equals(mineId) ? mine
                : firstItem(dataApi("/channels", params("part", "id,snippet,contentDetails,statistics", "id", channelId)));
        if (channel.isMissingNode() || channel.isNull() || channel.isEmpty()) {
            throw new IllegalArgumentException("No YouTube channel was found for channel ID " + channelId);
        }
        boolean owner = !mineId.isBlank() && mineId.equals(channel.path("id").asText(""));
        List<Map<String, Object>> uploads = fetchUploads(channel, PUBLIC_UPLOAD_PAGE_SIZE, MAX_PUBLIC_PAGES);
        LocalDate uploadEnd = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE);
        LocalDate uploadStart = uploadEnd.minusDays(days - 1L);
        // Retain the newest upload sample for video comparison. The consistency calculation
        // applies the selected date window itself; otherwise older items could never be compared
        // when lastNDays is short.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", channel.path("id").asText(channelId));
        result.put("title", channel.path("snippet").path("title").asText("YouTube channel"));
        result.put("description", channel.path("snippet").path("description").asText(""));
        result.put("customUrl", channel.path("snippet").path("customUrl").asText(""));
        result.put("thumbnailUrl", channel.path("snippet").path("thumbnails").path("high").path("url").asText(""));
        result.put("publishedAt", channel.path("snippet").path("publishedAt").asText(""));
        result.put("statistics", publicStatistics(channel));
        result.put("accessLevel", owner ? "FULL_ANALYTICS" : "PUBLIC_ONLY");
        result.put("isAuthorizedChannel", owner);
        result.put("lastNDays", days);
        result.put("analyticsStartDate", reportRange.startDateText());
        result.put("analyticsEndDate", reportRange.endDateText());
        result.put("recentVideos", uploads);
        result.put("publishingConsistency", businessLogic.publishingConsistency(
                uploads, uploadStart, uploadEnd,
                "Public upload list is limited to the first 50 uploads returned by YouTube Data API v3; counts can be incomplete for channels publishing more frequently."));
        if (owner) {
            if (includeAnalyticsReports) {
                Map<String, Object> overview = safeReport(() -> reportsService.overview(reportRange.startDateText(), reportRange.endDateText()));
                result.put("overview", overview);
                result.put("topVideos", safeReport(() -> reportsService.topVideos(reportRange.startDateText(), reportRange.endDateText(), 15)));
                result.put("formats", safeReport(() -> reportsService.formatBreakdown(reportRange.startDateText(), reportRange.endDateText())));
                result.put("trafficSources", safeReport(() -> reportsService.trafficSources(reportRange.startDateText(), reportRange.endDateText(), null)));
                result.put("audience", safeReport(() -> reportsService.audience(reportRange.startDateText(), reportRange.endDateText(), null)));
                result.put("uploadFrequency", safeReport(() -> reportsService.uploadFrequency(reportRange.startDateText(), reportRange.endDateText())));
                result.put("privacyNote", "Full metrics are available because the requested channel matches the authenticated YouTube channel. Analytics reports may lag 48–72 hours.");
            } else {
                result.put("overview", Map.of());
                result.put("topVideos", Map.of());
                result.put("formats", Map.of());
                result.put("trafficSources", Map.of());
                result.put("audience", Map.of());
                result.put("uploadFrequency", Map.of());
            }
        } else {
            result.put("overview", Map.of());
            result.put("topVideos", Map.of("rows", uploads.stream()
                    .sorted(Comparator.comparingDouble((Map<String, Object> v) -> numeric(v.get("views"))).reversed())
                    .limit(15).toList(),
                    "basis", "Public lifetime counters from YouTube Data API v3"));
            result.put("formats", Map.of("rows", List.of(), "available", false));
            result.put("trafficSources", Map.of("rows", List.of(), "available", false));
            result.put("audience", Map.of("available", false));
            result.put("uploadFrequency", Map.of("available", false));
            result.put("privacyNote", "This is a different channel. Only public profile, upload dates, views, likes and comments are shown. Private analytics, watch time, retention, audience, revenue and impressions CTR cannot be read for other channels.");
        }
        return result;
    }

    public Map<String, Object> video(String videoId, Integer lastNDays) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        int days = validDays(lastNDays);
        JsonNode videoResource = firstItem(dataApi("/videos", params(
                "part", "snippet,contentDetails,statistics", "id", videoId)));
        if (videoResource.isNull() || videoResource.isMissingNode() || videoResource.isEmpty()) {
            throw new IllegalArgumentException("No YouTube video was found for video ID " + videoId);
        }
        JsonNode mine = firstItem(dataApi("/channels", params("part", "id", "mine", "true")));
        String myChannelId = mine.path("id").asText("");
        String videoChannelId = videoResource.path("snippet").path("channelId").asText("");
        boolean owner = !myChannelId.isBlank() && myChannelId.equals(videoChannelId);
        if (!owner) {
            List<Map<String, Object>> rows = videoRows(wrapVideo(videoResource));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("video", rows.isEmpty() ? Map.of("videoId", videoId) : rows.get(0));
            result.put("accessLevel", "PUBLIC_ONLY");
            result.put("publicOnly", true);
            result.put("range", Map.of("lastNDays", days));
            result.put("analytics", Map.of("available", false));
            result.put("retentionAnalysis", Map.of("available", false));
            result.put("discovery", Map.of("available", false));
            result.put("recommendations", Map.of("available", false));
            result.put("daily", Map.of("available", false));
            result.put("privacyNote", "This video belongs to a channel other than the authenticated channel. Only public lifetime views, likes, comments, title, duration and publication date are available; these counters are not filtered by lastNDays. Private impressions CTR, watch time, retention, audience and revenue are unavailable.");
            result.put("ctrNote", "No measured video impressions CTR is available for a video you do not own. No query-level keyword CTR is estimated.");
            return result;
        }
        DateRange range = dateRangeResolver.resolveForVideo(videoId, days);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicOnly", false);
        result.put("accessLevel", "FULL_ANALYTICS");
        VideoAnalyticsResult analytics = analyticsService.getSingleVideoAnalytics(videoId, range.startDateText(), range.endDateText(), null);
        result.put("analytics", analytics);
        result.put("range", Map.of("lastNDays", days, "startDate", range.startDateText(), "endDate", range.endDateText()));
        try {
            VideoRetentionAnalyticsResult retention = analyticsService.getVideoRetentionAnalytics(videoId, range.startDateText(), range.endDateText());
            result.put("retention", retention);
            result.put("retentionAnalysis", retentionAnalysisService.analyze(retention));
        } catch (RuntimeException ex) {
            result.put("retention", Map.of("available", false));
            result.put("retentionAnalysis", retentionAnalysisService.analyze(null));
        }
        try {
            result.put("discovery", discoveryOptimizationService.analyze(videoId, range.startDateText(), range.endDateText()));
        } catch (RuntimeException ex) {
            result.put("discovery", Map.of("available", false, "message", safeMessage(ex)));
        }
        try {
            result.put("decision", analyticsDecisionService.decide(
                    result.get("discovery") instanceof com.youtube.analytics.model.DiscoveryOptimizationResult d ? d : null));
        } catch (RuntimeException ex) {
            result.put("decision", Map.of("action", "INSUFFICIENT_DATA", "summary", safeMessage(ex)));
        }
        try {
            RecommendationResult recommendations = recommendationEngineService.recommend(videoId, range.startDateText(), range.endDateText());
            result.put("recommendations", recommendations);
        } catch (RuntimeException ex) {
            result.put("recommendations", Map.of("summary", "Recommendations are unavailable for this range.", "missingData", List.of(safeMessage(ex))));
        }
        try { result.put("daily", analyticsService.getDailyVideoAnalytics(videoId, range.startDateText(), range.endDateText(), List.of("views", "estimatedMinutesWatched"))); }
        catch (RuntimeException ex) { result.put("daily", Map.of("days", List.of())); }
        try { result.put("reach", reportsService.videoReach(videoId, range.startDateText(), range.endDateText())); }
        catch (RuntimeException ex) { result.put("reach", Map.of("available", false, "message", safeMessage(ex))); }
        try { result.put("trafficSources", reportsService.trafficSources(range.startDateText(), range.endDateText(), videoId)); }
        catch (RuntimeException ex) { result.put("trafficSources", Map.of("available", false, "message", safeMessage(ex))); }
        result.put("ctrNote", "Impressions CTR is measured at video level from the reach report; it is not measured per search keyword.");
        return result;
    }

    public Map<String, Object> compare(String requestedChannelId, String contentType, Integer count, Integer lastNDays) {
        int limit = count == null ? 10 : count;
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("count must be between 1 and 50");
        String resolvedType = contentType == null ? "LONG_FORM" : contentType.trim().toUpperCase();
        if (!Set.of("SHORT", "SHORT_FORM", "LONG", "LONG_FORM").contains(resolvedType)) {
            throw new IllegalArgumentException("contentType must be SHORT or LONG_FORM");
        }
        boolean shortFormat = resolvedType.equals("SHORT") || resolvedType.equals("SHORT_FORM");
        Map<String, Object> channel = channel(requestedChannelId, lastNDays);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) channel.getOrDefault("recentVideos", List.of());
        String channelId = String.valueOf(channel.get("channelId"));
        boolean owner = Boolean.TRUE.equals(channel.get("isAuthorizedChannel"));
        List<Map<String, Object>> selected = candidates.stream()
                .filter(v -> {
                    double seconds = numeric(v.get("durationSeconds"));
                    return shortFormat ? seconds >= 0 && seconds <= 180 : seconds > 180;
                })
                .sorted(Comparator.comparing(v -> String.valueOf(v.getOrDefault("publishedAt", "")), Comparator.reverseOrder()))
                .limit(limit).map(LinkedHashMap::new).map(v -> (Map<String, Object>) v).collect(Collectors.toCollection(ArrayList::new));
        String note = selected.size() < limit
                ? "Only " + selected.size() + " matching uploads were found in the current 50-upload sample. Increase the backend pagination depth to compare older videos."
                : "Comparison contains the latest matching videos from the current 50-upload sample.";
        if (owner && !selected.isEmpty()) {
            DateRange range = dateRangeResolver.resolve(validDays(lastNDays));
            List<String> ids = selected.stream().map(v -> String.valueOf(v.get("videoId"))).toList();
            try {
                Map<String, Object> analyticsRows = reportsService.compareVideos(range.startDateText(), range.endDateText(), ids, null);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) analyticsRows.getOrDefault("rows", List.of());
                Map<String, Map<String, Object>> byId = rows.stream().collect(Collectors.toMap(
                        row -> String.valueOf(row.get("video")), row -> row, (a,b) -> a, LinkedHashMap::new));
                selected.forEach(video -> {
                    Map<String, Object> data = byId.get(String.valueOf(video.get("videoId")));
                    if (data != null) {
                        video.putAll(data);
                        video.put("metricsSource", "DATE_FILTERED_ANALYTICS");
                        video.put("analyticsMetricsAvailable", true);
                    } else {
                        // Never mix lifetime public counters into a date-filtered Analytics comparison.
                        video.put("views", null);
                        video.put("likes", null);
                        video.put("comments", null);
                        video.put("metricsSource", "NO_DATE_FILTERED_ROW");
                        video.put("analyticsMetricsAvailable", false);
                    }
                });
            } catch (RuntimeException ex) {
                note += " Date-filtered Analytics API comparison was unavailable; public counters are shown instead.";
            }
        }
        Map<String, Object> comparison = businessLogic.annotateComparison(selected, !owner);
        List<Map<String, Object>> rows = castRows(comparison.get("rows"));
        List<NormalizedVideoAnalytics> normalized = rows.stream().map(v -> NormalizedVideoAnalytics.builder()
                .videoId(String.valueOf(v.get("videoId")))
                .title(String.valueOf(v.getOrDefault("title", "")))
                .publishedAt(String.valueOf(v.getOrDefault("publishedAt", "")))
                .aggregateMetrics(metricsForScoring(v, owner))
                .build()).toList();
        for (int i = 0; i < rows.size(); i++) {
            VideoPerformanceScore score = performanceScoringService.score(normalized.get(i), normalized);
            rows.get(i).put("relativePerformanceScore", score.getScore());
            rows.get(i).put("relativePerformanceBand", score.getPerformance().name());
        }
        Map<String, Object> result = new LinkedHashMap<>(comparison);
        result.put("channelId", channelId);
        result.put("contentType", shortFormat ? "SHORT" : "LONG_FORM");
        result.put("lastNDays", validDays(lastNDays));
        result.put("notes", List.of(note, "Format classification is duration-based: <=180 seconds is a Shorts approximation, not a definitive platform classification. Horizontal videos can also be shorter than three minutes."));
        result.put("periodMetricBasis", owner ? "Authorized channel YouTube Analytics reports for the selected reporting window, with public metadata as fallback." : "Public lifetime video counters; not date-filtered. Competitor retention and impressions CTR are unavailable.");
        return result;
    }

    public Map<String, Object> reports(String requestedChannelId) {
        // Resolve ownership and public metadata without preloading a second full set of reports.
        Map<String, Object> channel = channel(requestedChannelId, 365, false);
        boolean owner = Boolean.TRUE.equals(channel.get("isAuthorizedChannel"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", channel.get("channelId"));
        result.put("channelTitle", channel.get("title"));
        result.put("accessLevel", channel.get("accessLevel"));
        result.put("publicOnly", !owner);
        if (!owner) {
            result.put("periods", Map.of());
            result.put("note", "Weekly/monthly/yearly Analytics reports require channel authorization. This channel exposes only public lifetime counters; those cannot be split into accurate weekly, monthly or yearly performance.");
            result.put("publicStatistics", channel.get("statistics"));
            result.put("recentVideos", channel.get("recentVideos"));
            return result;
        }
        Map<String, Object> periods = new LinkedHashMap<>();
        for (int days : List.of(7, 30, 365)) {
            DateRange range = dateRangeResolver.resolve(days);
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("label", days == 7 ? "Last 7 days" : days == 30 ? "Last 30 days" : "Last 365 days");
            period.put("lastNDays", days);
            period.put("startDate", range.startDateText());
            period.put("endDate", range.endDateText());
            period.put("overview", safeReport(() -> reportsService.overview(range.startDateText(), range.endDateText())));
            period.put("topVideos", safeReport(() -> reportsService.topVideos(range.startDateText(), range.endDateText(), 10)));
            period.put("uploadFrequency", safeReport(() -> reportsService.uploadFrequency(range.startDateText(), range.endDateText())));
            period.put("formats", safeReport(() -> reportsService.formatBreakdown(range.startDateText(), range.endDateText())));
            periods.put(days == 7 ? "weekly" : days == 30 ? "monthly" : "yearly", period);
        }
        result.put("periods", periods);
        result.put("note", "Periods are rolling 7-, 30- and 365-reporting-day windows, not calendar week/month/year totals. YouTube Analytics data may lag 48–72 hours.");
        return result;
    }

    public Map<String, Object> keywords(String seed, Integer lastNDays) {
        if (seed == null || seed.trim().length() < 2) throw new IllegalArgumentException("seed must contain at least 2 characters");
        DateRange range = dateRangeResolver.resolve(validDays(lastNDays));
        JsonNode autocomplete = publicApiService.searchSuggestions(seed.trim());
        List<String> suggestions = new ArrayList<>();
        JsonNode suggestionNode = autocomplete.path("suggestions");
        if (suggestionNode.isArray()) suggestionNode.forEach(n -> suggestions.add(n.asText()));
        Map<String, Object> searchTerms;
        try { searchTerms = reportsService.searchTerms(range.startDateText(), range.endDateText(), 50); }
        catch (RuntimeException ex) { searchTerms = Map.of("rows", List.of(), "available", false, "message", safeMessage(ex)); }
        List<Map<String, Object>> measuredRows = castRows(searchTerms.get("rows"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seed", seed.trim());
        result.put("startDate", range.startDateText());
        result.put("endDate", range.endDateText());
        result.put("autocompleteSuggestions", businessLogic.keywordOpportunities(seed.trim(), suggestions, measuredRows));
        result.put("measuredSearchTerms", measuredRows);
        result.put("autocompleteSource", autocomplete.path("source").asText("YouTube public autocomplete"));
        result.put("measuredSearchTermsAvailable", !Boolean.FALSE.equals(searchTerms.get("available")));
        result.put("ctrNote", "Impressions CTR is available only as a video/report metric. No query-level keyword CTR is returned by the supported APIs; no CTR values are fabricated.");
        result.put("opportunityNote", "Autocomplete is a suggestion signal, not search volume, ranking difficulty or a guarantee of high CTR. Candidate tiers are qualitative and explicitly separated from measured search terms.");
        return result;
    }

    public Map<String, Object> benchmark(String query) {
        if (query == null || query.trim().length() < 2) throw new IllegalArgumentException("query must contain at least 2 characters");
        JsonNode search = dataApi("/search", params("part", "snippet", "type", "video", "order", "viewCount", "maxResults", "10", "q", query.trim()));
        List<String> ids = new ArrayList<>();
        if (search.path("items").isArray()) search.path("items").forEach(item -> {
            String id = item.path("id").path("videoId").asText("");
            if (!id.isBlank()) ids.add(id);
        });
        if (ids.isEmpty()) return Map.of("query", query.trim(), "rows", List.of(), "basis", "YouTube Data API v3 public search results");
        JsonNode details = dataApi("/videos", params("part", "snippet,contentDetails,statistics", "id", String.join(",", ids)));
        List<Map<String, Object>> rows = videoRows(details);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query.trim());
        result.put("rows", rows);
        result.put("basis", "Public lifetime counters for the first 10 YouTube search results ordered by viewCount; this is a discoverable comparison sample, not a controlled or exact-match benchmark.");
        result.put("privateMetricsAvailable", false);
        result.put("notAvailable", List.of("competitor impressions CTR", "competitor audience retention", "watch time", "private traffic sources", "revenue"));
        return result;
    }

    private List<Map<String, Object>> fetchUploads(JsonNode channel, int maxResults, int maxPages) {
        String playlist = channel.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        if (playlist.isBlank()) return List.of();
        List<String> ids = new ArrayList<>();
        Map<String, String> playlistDates = new LinkedHashMap<>();
        String pageToken = null;
        int pages = 0;
        while (pages++ < maxPages && ids.size() < maxResults) {
            Map<String, String> query = params("part", "snippet,contentDetails", "playlistId", playlist, "maxResults", String.valueOf(PUBLIC_UPLOAD_PAGE_SIZE));
            if (pageToken != null && !pageToken.isBlank()) query.put("pageToken", pageToken);
            JsonNode page = dataApi("/playlistItems", query);
            if (page.path("items").isArray()) page.path("items").forEach(item -> {
                String id = item.path("contentDetails").path("videoId").asText(item.path("snippet").path("resourceId").path("videoId").asText(""));
                if (!id.isBlank() && !playlistDates.containsKey(id)) {
                    ids.add(id);
                    playlistDates.put(id, item.path("contentDetails").path("videoPublishedAt").asText(item.path("snippet").path("publishedAt").asText("")));
                }
            });
            pageToken = page.path("nextPageToken").asText("");
            if (pageToken.isBlank()) break;
        }
        if (ids.isEmpty()) return List.of();
        List<String> usedIds = ids.subList(0, Math.min(ids.size(), maxResults));
        JsonNode response = dataApi("/videos", params("part", "snippet,contentDetails,statistics", "id", String.join(",", usedIds)));
        List<Map<String, Object>> rows = videoRows(response);
        rows.forEach(row -> {
            String published = playlistDates.get(String.valueOf(row.get("videoId")));
            if (published != null && !published.isBlank()) row.put("publishedAt", published);
        });
        rows.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("publishedAt", "")), Comparator.reverseOrder()));
        return rows;
    }

    private List<Map<String, Object>> videoRows(JsonNode response) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode items = response.path("items");
        if (!items.isArray()) return rows;
        for (JsonNode item : items) {
            String id = item.path("id").asText("");
            JsonNode snippet = item.path("snippet");
            JsonNode stats = item.path("statistics");
            String duration = item.path("contentDetails").path("duration").asText("");
            long seconds = durationSeconds(duration);
            VideoMeta meta = new VideoMeta(id, snippet.path("title").asText(""), snippet.path("publishedAt").asText(""));
            ContentClassification classification = contentClassificationService.classify(meta);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoId", id);
            row.put("title", snippet.path("title").asText(""));
            row.put("description", snippet.path("description").asText(""));
            row.put("publishedAt", snippet.path("publishedAt").asText(""));
            row.put("thumbnailUrl", snippet.path("thumbnails").path("high").path("url").asText(snippet.path("thumbnails").path("medium").path("url").asText("")));
            row.put("channelId", snippet.path("channelId").asText(""));
            row.put("channelTitle", snippet.path("channelTitle").asText(""));
            row.put("duration", duration);
            row.put("durationSeconds", seconds);
            row.put("contentType", seconds < 0 ? "UNKNOWN" : seconds <= 180 ? "SHORT" : "LONG_FORM");
            row.put("titleClassification", classification);
            row.put("viewCount", nullableLong(stats.path("viewCount")));
            row.put("likeCount", nullableLong(stats.path("likeCount")));
            row.put("commentCount", nullableLong(stats.path("commentCount")));
            row.put("views", nullableLong(stats.path("viewCount")));
            row.put("likes", nullableLong(stats.path("likeCount")));
            row.put("comments", nullableLong(stats.path("commentCount")));
            row.put("publicLifetimeCounters", true);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> publicStatistics(JsonNode channel) {
        JsonNode stats = channel.path("statistics");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewCount", nullableLong(stats.path("viewCount")));
        result.put("subscriberCount", nullableLong(stats.path("subscriberCount")));
        result.put("videoCount", nullableLong(stats.path("videoCount")));
        result.put("hiddenSubscriberCount", stats.path("hiddenSubscriberCount").asBoolean(false));
        result.put("basis", "Public channel lifetime counters from YouTube Data API v3.");
        return result;
    }

    private JsonNode firstItem(JsonNode response) {
        JsonNode items = response.path("items");
        return items.isArray() && !items.isEmpty() ? items.get(0) : objectMapper.nullNode();
    }

    private JsonNode wrapVideo(JsonNode video) {
        var wrapper = objectMapper.createObjectNode();
        var items = objectMapper.createArrayNode();
        items.add(video);
        wrapper.set("items", items);
        return wrapper;
    }

    private JsonNode dataApi(String endpoint, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(DATA_API + endpoint);
        params.forEach((key, value) -> { if (value != null && !value.isBlank()) builder.queryParam(key, value); });
        URI uri = builder.build().encode().toUri();
        JsonNode response = youTubeWebClient.get().uri(uri).retrieve().bodyToMono(JsonNode.class).block();
        return response == null ? objectMapper.createObjectNode() : response;
    }

    private Map<String, String> params(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) result.put(keyValues[i], keyValues[i + 1]);
        return result;
    }

    private List<Map<String, Object>> castRows(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, Object> metricsForScoring(Map<String, Object> video, boolean owner) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String key : List.of("views", "likes", "comments", "estimatedMinutesWatched", "averageViewDuration", "averageViewPercentage", "subscribersGained", "subscribersLost")) {
            if (video.get(key) != null) metrics.put(key, video.get(key));
        }
        if (!owner) {
            if (video.get("likeCount") != null) metrics.put("likes", video.get("likeCount"));
            if (video.get("commentCount") != null) metrics.put("comments", video.get("commentCount"));
        }
        return metrics;
    }

    private Map<String, Object> safeReport(java.util.function.Supplier<Map<String, Object>> action) {
        try { return action.get(); }
        catch (RuntimeException ex) { return Map.of("available", false, "message", safeMessage(ex)); }
    }

    private Object firstNumber(Object first, Object second) { return first != null ? first : second; }
    private Long nullableLong(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asLong(); }
    private double numeric(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException ignored) {}
        return Double.NaN;
    }
    private LocalDate parseDate(Object value) {
        if (value == null) return null;
        try { return Instant.parse(String.valueOf(value)).atZone(AnalyticsDateRangeResolver.REPORTING_ZONE).toLocalDate(); }
        catch (RuntimeException ignored) { try { return LocalDate.parse(String.valueOf(value).substring(0,10)); } catch (RuntimeException ignoredAgain) { return null; } }
    }
    private long durationSeconds(String iso) {
        Matcher matcher = ISO_DURATION.matcher(iso == null ? "" : iso);
        if (!matcher.matches()) return -1;
        long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        return hours * 3600 + minutes * 60 + seconds;
    }
    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "The source API did not return this report." : message;
    }
    private int validDays(Integer days) {
        int result = days == null ? AnalyticsDateRangeResolver.DEFAULT_LAST_N_DAYS : days;
        if (result < 1 || result > AnalyticsDateRangeResolver.MAX_LAST_N_DAYS) {
            throw new IllegalArgumentException("lastNDays must be between 1 and " + AnalyticsDateRangeResolver.MAX_LAST_N_DAYS);
        }
        return result;
    }
}
