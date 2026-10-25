package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.YouTubeAnalyticsProperties;
import com.youtube.analytics.config.YouTubeReportingProperties;
import com.youtube.analytics.model.VideoAnalyticsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fetches supported Analytics API report groups for one video, merges them into the existing
 * response model, and archives each raw upstream response for later replay.
 *
 * Reports are deliberately isolated: a missing scope or an unsupported metric/dimension pair
 * marks that section unavailable without discarding successful sections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAnalyticsReportAggregationService {

    private static final String ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2/reports";
    private static final String REPORTING_BASE = "https://youtubereporting.googleapis.com/v1";
    private static final String CHANNEL = "channel==MINE";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("America/Los_Angeles");
    private static final List<String> DEVICE_METRICS =
            List.of("engagedViews", "views", "estimatedMinutesWatched");
    private static final List<String> PLAYBACK_LOCATION_METRICS =
            List.of("engagedViews", "views", "estimatedMinutesWatched");
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429);

    private final WebClient youTubeWebClient;
    private final ObjectMapper objectMapper;
    private final YouTubeAnalyticsProperties analyticsProperties;
    private final YouTubeReportingProperties reportingProperties;

    /**
     * Fetch one Analytics API v2 report using the common request builder.
     * Filters should use the API's filter syntax, such as video==VIDEO_ID.
     */
    public Map<String, Object> fetchReport(
            String videoId,
            String startDate,
            String endDate,
            List<String> metrics,
            String dimensions,
            String filters,
            String sort,
            Integer maxResults) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        ArchiveContext archive = new ArchiveContext();
        archive.snapshotId = newSnapshotId();
        LocalDate start = parseDate(startDate, null);
        LocalDate end = parseDate(endDate, null);
        Map<String, Object> section = analyticsReport(
                "report", videoId, start, end, metrics, dimensions, filters, sort, maxResults, archive);
        Map<String, Object> archiveInfo = persistRawCalls(archive, null);
        section.put("archiveSnapshotId", archive.snapshotId);
        section.put("archive", archiveInfo);
        return section;
    }

    /**
     * Adds supplementary report groups to a standard video response. Existing fields (including
     * metrics, analyticsMetrics, and currentStatistics) are left intact.
     */
    public VideoAnalyticsResult enrich(VideoAnalyticsResult result) {
        if (result == null || result.getVideoId() == null) return result;
        AnalyticsRequestValidator.validateVideoId(result.getVideoId());

        String snapshotId = newSnapshotId();
        ArchiveContext archive = new ArchiveContext();
        archive.snapshotId = snapshotId;
        LocalDate today = LocalDate.now(REPORTING_ZONE);
        LocalDate safeEnd = today.minusDays(analyticsProperties.getReportingLagDays());
        LocalDate requestedEnd = parseDate(result.getEndDate(), safeEnd);
        if (requestedEnd.isBefore(safeEnd)) safeEnd = requestedEnd;
        LocalDate requestedStart = parseDate(result.getStartDate(), safeEnd.minusDays(27));
        LocalDate publishDay = parsePublishDay(result.getPublishedAt(), requestedStart);
        LocalDate boundedStart = requestedStart.isBefore(publishDay) ? publishDay : requestedStart;

        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("reportingWindow", mapOf(
                "requestedStartDate", requestedStart.toString(),
                "requestedEndDate", requestedEnd.toString(),
                "effectiveStartDate", boundedStart.toString(),
                "effectiveEndDate", safeEnd.toString(),
                "reportingLagDays", analyticsProperties.getReportingLagDays(),
                "reportingZone", REPORTING_ZONE.getId(),
                "note", "Analytics reports exclude the most recent configured lag days because YouTube processing is not final."));
        sections.put("core", coreWindows(result, publishDay, safeEnd, archive));
        sections.put("cards", analyticsReport("cards", result.getVideoId(), boundedStart, safeEnd,
                analyticsProperties.getMetrics().getCards(), null, null, null, null, archive));
        sections.put("premium", analyticsReport("premium", result.getVideoId(), boundedStart, safeEnd,
                analyticsProperties.getMetrics().getPremium(), null, null, null, null, archive));
        sections.put("retention", retentionReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("demographics", analyticsReport("demographics", result.getVideoId(), boundedStart, safeEnd,
                analyticsProperties.getMetrics().getDemographics(), "ageGroup,gender", null, "-viewerPercentage", 50, archive));
        sections.put("traffic", trafficReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("trend", trendReports(result.getVideoId(), boundedStart, safeEnd, publishDay, archive));
        sections.put("dimensions", dimensionReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("liveStream", liveStreamReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("liveOrOnDemand", liveOrOnDemandReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("youtubeProduct", youtubeProductReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("channelBenchmark", channelBenchmarkReport(result, boundedStart, safeEnd, archive));
        sections.put("revenue", revenueReports(result.getVideoId(), boundedStart, safeEnd, archive));
        sections.put("reach", reportingApiReport(result.getVideoId(), boundedStart, safeEnd,
                "channel_reach_basic_a1", "reach", reportingProperties.getMetrics().getReach(), archive));
        sections.put("endScreens", reportingApiReport(result.getVideoId(), boundedStart, safeEnd,
                "channel_end_screens_a2", "endScreens", reportingProperties.getMetrics().getEndScreens(), archive));
        sections.put("playlist", notApplicable(
                "Playlist-level metrics require a playlist ID. Video add/remove playlist counters remain available in core."));
        sections.put("optionalMetricValidation", optionalMetricValidation(sections));

        result.setReportSections(sections);
        result.setDerivedInsights(deriveInsights(result, sections));
        result.setArchiveSnapshotId(snapshotId);
        try {
            Map<String, Object> archiveInfo = persistRawCalls(archive, result);
            sections.put("archive", archiveInfo);
        } catch (RuntimeException ex) {
            log.warn("Could not archive report responses for video {}: {}", result.getVideoId(), ex.getMessage());
            sections.put("archive", mapOf("status", "FAILED", "reason", "Raw report responses could not be persisted."));
        }
        return result;
    }

    /**
     * Rebuilds an enriched video response from the saved manifest and returns archived source
     * response bodies. This method deliberately performs no YouTube API calls.
     */
    public VideoAnalyticsResult replay(String videoId, String snapshotId) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        if (snapshotId == null || !snapshotId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("snapshotId is invalid");
        }
        Path snapshotPath = snapshotDirectory(videoId, snapshotId);
        Path manifestPath = snapshotPath.resolve("snapshot.json").normalize();
        if (!manifestPath.startsWith(snapshotPath) || !Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("No archived analytics snapshot exists for the requested video and snapshotId");
        }
        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            JsonNode savedResult = manifest.path("result");
            VideoAnalyticsResult result = objectMapper.treeToValue(savedResult, VideoAnalyticsResult.class);
            Map<String, String> raw = new LinkedHashMap<>();
            JsonNode rawFiles = manifest.path("rawCallFiles");
            if (rawFiles.isObject()) {
                var fields = rawFiles.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String relative = entry.getValue().asText();
                    Path file = snapshotPath.resolve(relative).normalize();
                    if (!file.startsWith(snapshotPath) || !Files.isRegularFile(file)) continue;
                    raw.put(entry.getKey(), Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            result.setRawReportResponses(raw);
            result.setArchiveSnapshotId(snapshotId);
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read the archived analytics snapshot", ex);
        }
    }

    private Map<String, Object> coreWindows(
            VideoAnalyticsResult result, LocalDate publishDay, LocalDate safeEnd, ArchiveContext archive) {
        Map<String, Object> windows = new LinkedHashMap<>();
        LocalDate first48End = publishDay.plusDays(1);
        windows.put("first48Hours", windowedCore("first48Hours", result.getVideoId(), publishDay,
                first48End, safeEnd, true, archive));
        windows.put("first7Days", windowedCore("first7Days", result.getVideoId(), publishDay,
                publishDay.plusDays(6), safeEnd, false, archive));
        windows.put("first28Days", windowedCore("first28Days", result.getVideoId(), publishDay,
                publishDay.plusDays(27), safeEnd, false, archive));
        if (safeEnd.isBefore(publishDay)) {
            windows.put("lifetime", notApplicable("No final report dates are available yet for this video."));
        } else {
            windows.put("lifetime", analyticsReport("core-lifetime", result.getVideoId(), publishDay, safeEnd,
                    analyticsProperties.getMetrics().getCore(), null, null, null, null, archive));
        }
        windows.put("first48HoursNote", "Analytics API dates have day granularity; this is a two-calendar-day approximation, not an exact rolling 48-hour window.");
        return windows;
    }

    private Map<String, Object> windowedCore(
            String name,
            String videoId,
            LocalDate start,
            LocalDate end,
            LocalDate safeEnd,
            boolean approximate,
            ArchiveContext archive) {
        if (safeEnd.isBefore(end)) {
            return mapOf("report", name, "status", "PENDING_PROCESSING",
                    "startDate", start.toString(), "endDate", end.toString(),
                    "note", "The complete requested window is newer than the data-safe end date; partial data was not mislabeled as final.");
        }
        Map<String, Object> section = analyticsReport(name, videoId, start, end,
                analyticsProperties.getMetrics().getCore(), null, null, null, null, archive);
        if (approximate) section.put("windowAccuracy", "calendar-day approximation");
        return section;
    }

    private Map<String, Object> retentionReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        List<String> metrics = concat(
                analyticsProperties.getMetrics().getRetention(),
                analyticsProperties.getMetrics().getRetentionEvents());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall", analyticsReport("retention", videoId, start, end, metrics,
                "elapsedVideoTimeRatio", null, null, 200, archive));
        Map<String, Object> audiences = new LinkedHashMap<>();
        for (String audienceType : List.of("ORGANIC", "AD_INSTREAM", "AD_INDISPLAY")) {
            audiences.put(audienceType, analyticsReport("retention-" + audienceType.toLowerCase(Locale.ROOT),
                    videoId, start, end, metrics, "elapsedVideoTimeRatio",
                    "video==" + videoId + ";audienceType==" + audienceType, null, 200, archive));
        }
        result.put("byAudienceType", audiences);
        result.put("units", mapOf(
                "elapsedVideoTimeRatio", "fraction of video elapsed (0-1)",
                "audienceWatchRatio", "ratio returned by YouTube; may exceed 1 when segments are replayed",
                "relativeRetentionPerformance", "relative performance value returned by YouTube",
                "startedWatching", "segment starts",
                "stoppedWatching", "segment stops",
                "totalSegmentImpressions", "segment impressions"));
        return result;
    }

    private Map<String, Object> trafficReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("types", analyticsReport("traffic-types", videoId, start, end,
                analyticsProperties.getMetrics().getTraffic(), "insightTrafficSourceType",
                null, "-views", 50, archive));
        Map<String, Object> details = new LinkedHashMap<>();
        for (String source : List.of("YT_SEARCH", "RELATED_VIDEO", "EXT_URL")) {
            details.put(source, analyticsReport("traffic-detail-" + source.toLowerCase(Locale.ROOT),
                    videoId, start, end, List.of("views", "estimatedMinutesWatched"),
                    "insightTrafficSourceDetail",
                    "video==" + videoId + ";insightTrafficSourceType==" + source,
                    "-views", 25, archive));
        }
        traffic.put("details", details);
        traffic.put("detailLimit", 25);
        return traffic;
    }

    private Map<String, Object> trendReports(
            String videoId, LocalDate requestedStart, LocalDate safeEnd, LocalDate publishDay, ArchiveContext archive) {
        Map<String, Object> trends = new LinkedHashMap<>();
        LocalDate currentStart = maxDate(requestedStart, safeEnd.minusDays(27));
        if (currentStart.isBefore(publishDay)) currentStart = publishDay;
        if (currentStart.isAfter(safeEnd)) {
            trends.put("dailyVelocity", notApplicable("No final days are available for the requested window."));
        } else {
            trends.put("dailyVelocity", analyticsReport("daily-velocity", videoId, currentStart, safeEnd,
                    analyticsProperties.getMetrics().getTrend(), "day", null, "day", 200, archive));
        }

        LocalDate previousEnd = safeEnd.minusDays(28);
        LocalDate previousStart = previousEnd.minusDays(27);
        if (previousStart.isBefore(publishDay)) {
            trends.put("previous28Days", notApplicable("The video does not have 28 complete earlier days."));
        } else {
            trends.put("previous28Days", analyticsReport("previous-28-days", videoId, previousStart, previousEnd,
                    analyticsProperties.getMetrics().getTrend(), "day", null, "day", 200, archive));
        }

        LocalDate firstOfCurrentMonth = safeEnd.withDayOfMonth(1);
        LocalDate lastCompleteMonth = firstOfCurrentMonth.minusMonths(1);
        LocalDate firstMonth = requestedStart.isAfter(publishDay) ? requestedStart : publishDay;
        firstMonth = firstMonth.withDayOfMonth(1);
        if (firstMonth.isAfter(lastCompleteMonth)) {
            trends.put("monthly", notApplicable("No complete calendar month falls within the requested video window."));
        } else {
            trends.put("monthly", analyticsReport("monthly-trend", videoId, firstMonth, lastCompleteMonth,
                    analyticsProperties.getMetrics().getTrend(), "month", null, "month", 200, archive));
        }
        trends.put("units", mapOf("estimatedMinutesWatched", "minutes", "averageViewDuration", "seconds",
                "averageViewPercentage", "percentage points; no conversion required"));
        return trends;
    }

    private Map<String, Object> dimensionReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> reports = new LinkedHashMap<>();
        List<String> geographyMetrics = analyticsProperties.getMetrics().getGeography();
        reports.put("country", analyticsReport("country", videoId, start, end, geographyMetrics,
                "country", null, "-views", 200, archive));
        reports.put("city", analyticsReport("city", videoId, start, end, geographyMetrics,
                "city", null, "-views", 250, archive));
        reports.put("subscribedStatus", analyticsReport("subscribed-status", videoId, start, end,
                List.of("views", "estimatedMinutesWatched"), "subscribedStatus", null, "-views", 20, archive));
        reports.put("deviceType", analyticsReport("device-type", videoId, start, end,
                DEVICE_METRICS, "deviceType", null, "-views", 50, archive));
        reports.put("operatingSystem", analyticsReport("operating-system", videoId, start, end,
                DEVICE_METRICS, "operatingSystem", null, "-views", 50, archive));
        reports.put("sharingService", analyticsReport("sharing-service", videoId, start, end,
                List.of("shares"), "sharingService", null, "-shares", 50, archive));
        reports.put("playbackLocation", analyticsReport("playback-location", videoId, start, end,
                PLAYBACK_LOCATION_METRICS, "insightPlaybackLocationType", null, "-views", 50, archive));
        reports.put("embeddedPlaybackDetail", analyticsReport("embedded-playback-detail", videoId, start, end,
                PLAYBACK_LOCATION_METRICS, "insightPlaybackLocationDetail",
                "video==" + videoId + ";insightPlaybackLocationType==EMBEDDED", "-views", 25, archive));
        reports.put("format", analyticsReport("creator-content-type", videoId, start, end,
                analyticsProperties.getMetrics().getFormat(), "video,creatorContentType", null, "-views", 200, archive));
        return reports;
    }

    private Map<String, Object> liveStreamReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> live = new LinkedHashMap<>();
        List<String> metrics = analyticsProperties.getMetrics().getLivestream();
        live.put("summary", analyticsReport("livestream-summary", videoId, start, end,
                metrics, null, null, null, null, archive));
        live.put("byPosition", analyticsReport("livestream-position", videoId, start, end,
                metrics, "livestreamPosition", null, null, 200, archive));
        live.put("note", "These metrics are only populated for eligible live-streamed videos; an empty response is expected for ordinary on-demand videos.");
        return live;
    }

    private Map<String, Object> liveOrOnDemandReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String mode : List.of("LIVE", "ON_DEMAND")) {
            result.put(mode, analyticsReport("live-or-on-demand-" + mode.toLowerCase(Locale.ROOT),
                    videoId, start, end, analyticsProperties.getMetrics().getTraffic(), "day",
                    "video==" + videoId + ";liveOrOnDemand==" + mode, "day", 200, archive));
        }
        return result;
    }

    private Map<String, Object> youtubeProductReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String product : List.of("CORE", "GAMING", "KIDS", "MUSIC")) {
            result.put(product, analyticsReport("youtube-product-" + product.toLowerCase(Locale.ROOT),
                    videoId, start, end, analyticsProperties.getMetrics().getTraffic(), "day",
                    "video==" + videoId + ";youtubeProduct==" + product, "day", 200, archive));
        }
        return result;
    }

    private Map<String, Object> channelBenchmarkReport(
            VideoAnalyticsResult target, LocalDate start, LocalDate end, ArchiveContext archive) {
        String reportName = "channel-video-benchmark";
        // The documented top-video report supports playback metrics, not the broader core list.
        List<String> metrics = List.of("engagedViews", "views", "estimatedMinutesWatched",
                "averageViewDuration", "averageViewPercentage");
        if (start == null || end == null || start.isAfter(end)) {
            return notApplicable("Invalid or empty report window.");
        }
        try {
            ApiResponse response = fetchReportResponse(target.getVideoId(), start.toString(), end.toString(),
                    metrics, "video", null, "-views", 200, reportName);
            archive.add(reportName, "json", response.body());
            List<Map<String, Object>> rows = parseRows(objectMapper.readTree(response.body()));
            List<Double> views = metricValues(rows, "views");
            List<Double> watchMinutes = metricValues(rows, "estimatedMinutesWatched");
            List<Double> averageDurations = metricValues(rows, "averageViewDuration");
            List<Double> averagePercentages = metricValues(rows, "averageViewPercentage");

            Double q1Views = quantile(views, 0.25);
            Double q3Views = quantile(views, 0.75);
            Double iqr = q1Views == null || q3Views == null ? null : q3Views - q1Views;
            Double lowerFence = iqr == null ? null : Math.max(0, q1Views - 1.5 * iqr);
            Double upperFence = iqr == null ? null : q3Views + 1.5 * iqr;
            Double medianViews = quantile(views, 0.5);
            Double targetViews = metricNumber(
                    target.getAnalyticsMetrics() == null ? target.getMetrics() : target.getAnalyticsMetrics(), "views");
            boolean targetFound = false;
            Double targetCohortViews = null;
            int targetRank = -1;
            List<String> outlierIds = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                Double rowViews = asDouble(row.get("views"));
                if (target.getVideoId().equals(String.valueOf(row.get("video")))) {
                    targetFound = true;
                    targetRank = i + 1;
                    targetCohortViews = rowViews;
                }
                if (rowViews != null && lowerFence != null && upperFence != null
                        && (rowViews < lowerFence || rowViews > upperFence)) {
                    Object id = row.get("video");
                    if (id != null) outlierIds.add(String.valueOf(id));
                }
            }
            Boolean targetIsOutlier = targetFound && targetCohortViews != null
                    && lowerFence != null && upperFence != null
                    ? targetCohortViews < lowerFence || targetCohortViews > upperFence : null;

            Map<String, Object> summary = mapOf(
                    "returnedVideoCount", rows.size(),
                    "cohort", "Videos returned by YouTube sorted by views; capped at 200 results.",
                    "medianViews", medianViews,
                    "medianEstimatedMinutesWatched", quantile(watchMinutes, 0.5),
                    "medianAverageViewDurationSeconds", quantile(averageDurations, 0.5),
                    "medianAverageViewPercentage", quantile(averagePercentages, 0.5),
                    "viewsQ1", q1Views,
                    "viewsQ3", q3Views,
                    "viewsLowerOutlierFence", lowerFence,
                    "viewsUpperOutlierFence", upperFence,
                    "targetVideoInCohort", targetFound,
                    "targetVideoRankByViews", targetFound ? targetRank : null,
                    "targetViews", targetViews,
                    "targetVsMedianViewsPercent", targetViews == null || medianViews == null || medianViews <= 0
                            ? null : round((targetViews - medianViews) * 100.0 / medianViews),
                    "targetViewIsOutlierWithinTopCohort", targetIsOutlier,
                    "outlierVideoIds", outlierIds,
                    "outlierRule", "Tukey 1.5 × IQR fences calculated from the returned cohort.");
            return mapOf(
                    "report", reportName,
                    "api", "YouTube Analytics API v2",
                    "status", rows.isEmpty() ? "EMPTY" : "AVAILABLE",
                    "startDate", start.toString(),
                    "endDate", end.toString(),
                    "metrics", metrics,
                    "dimensions", "video",
                    "sort", "-views",
                    "maxResults", 200,
                    "rows", rows,
                    "summary", summary,
                    "rawResponseArchived", true,
                    "note", "This is a top-video cohort capped at 200 rows, not a census of every channel upload. Outlier fences apply only to this returned cohort.");
        } catch (ReportApiException ex) {
            archive.add(reportName, "json", ex.body());
            logReportFailure(reportName, ex);
            return failureSection(reportName, "YouTube Analytics API v2", start, end, metrics, "video", ex);
        } catch (Exception ex) {
            log.warn("Could not parse channel video benchmark report: {}", ex.getMessage());
            return mapOf("report", reportName, "api", "YouTube Analytics API v2",
                    "status", "ERROR", "reason", "The benchmark response could not be parsed.");
        }
    }

    private List<Double> metricValues(List<Map<String, Object>> rows, String metric) {
        return rows.stream().map(row -> asDouble(row.get(metric)))
                .filter(value -> value != null && Double.isFinite(value))
                .sorted().toList();
    }

    private Double metricNumber(Map<String, Object> metrics, String metric) {
        return metrics == null ? null : asDouble(metrics.get(metric));
    }

    private Double quantile(List<Double> sortedValues, double q) {
        if (sortedValues == null || sortedValues.isEmpty()) return null;
        List<Double> ordered = sortedValues.stream().sorted().toList();
        double position = (ordered.size() - 1) * q;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return ordered.get(lower);
        double fraction = position - lower;
        return ordered.get(lower) + (ordered.get(upper) - ordered.get(lower)) * fraction;
    }

    private Double asDouble(Object value) {
        Number number = asNumber(value);
        if (number != null) return number.doubleValue();
        return null;
    }

    private Map<String, Object> revenueReports(
            String videoId, LocalDate start, LocalDate end, ArchiveContext archive) {
        Map<String, Object> revenue = new LinkedHashMap<>();
        Map<String, Object> estimates = new LinkedHashMap<>();
        for (String metric : analyticsProperties.getMetrics().getRevenue()) {
            if (!List.of("estimatedRevenue", "estimatedAdRevenue", "estimatedRedPartnerRevenue").contains(metric)) continue;
            estimates.put(metric, analyticsReport("estimated-" + metric.toLowerCase(Locale.ROOT),
                    videoId, start, end, List.of(metric), null, null, null, null, archive));
        }
        revenue.put("estimated", estimates);
        List<String> adPerformance = analyticsProperties.getMetrics().getRevenue().stream()
                .filter(metric -> List.of("grossRevenue", "adImpressions", "cpm").contains(metric))
                .toList();
        revenue.put("adPerformance", analyticsReport("ad-performance", videoId, start, end,
                adPerformance, "adType", null, null, 50, archive));
        List<String> playback = analyticsProperties.getMetrics().getRevenue().stream()
                .filter(metric -> List.of("playbackBasedCpm", "monetizedPlaybacks").contains(metric))
                .toList();
        revenue.put("playbackPerformance", analyticsReport("playback-performance", videoId, start, end,
                playback, null, null, null, null, archive));
        revenue.put("availabilityNote", "Revenue sections are independently unavailable if monetary access, monetization, or a report combination is unsupported.");
        return revenue;
    }

    private Map<String, Object> analyticsReport(
            String callName,
            String videoId,
            LocalDate start,
            LocalDate end,
            List<String> metrics,
            String dimensions,
            String filters,
            String sort,
            Integer maxResults,
            ArchiveContext archive) {
        if (start == null || end == null || start.isAfter(end)) {
            return notApplicable("Invalid or empty report window.");
        }
        if (metrics == null || metrics.isEmpty()) return notApplicable("No configured metric is available for this report.");
        try {
            String queryFilters = filters == null || filters.isBlank() ? "video==" + videoId : filters;
            ApiResponse response = fetchReportResponse(videoId, start.toString(), end.toString(),
                    metrics, dimensions, queryFilters, sort, maxResults, callName);
            archive.add(callName, "json", response.body());
            JsonNode root = objectMapper.readTree(response.body());
            List<Map<String, Object>> rows = parseRows(root);
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("report", callName);
            section.put("api", "YouTube Analytics API v2");
            section.put("status", rows.isEmpty() ? "EMPTY" : "AVAILABLE");
            section.put("startDate", start.toString());
            section.put("endDate", end.toString());
            section.put("metrics", metrics);
            section.put("dimensions", dimensions);
            section.put("filters", filters == null || filters.isBlank() ? "video==" + videoId : filters);
            section.put("rows", rows);
            section.put("rowCount", rows.size());
            section.put("rawResponseArchived", true);
            return section;
        } catch (ReportApiException ex) {
            archive.add(callName, "json", ex.body());
            logReportFailure(callName, ex);
            return failureSection(callName, "YouTube Analytics API v2", start, end, metrics, dimensions, ex);
        } catch (Exception ex) {
            log.warn("Could not parse YouTube Analytics report {}: {}", callName, ex.getMessage());
            return mapOf("report", callName, "api", "YouTube Analytics API v2",
                    "status", "ERROR", "reason", "The report response could not be parsed.");
        }
    }

    /**
     * Reusable Analytics API query builder. The supplied metrics and dimension must form a
     * documented combination; errors are captured by the calling section rather than propagated.
     */
    private ApiResponse fetchReportResponse(
            String videoId, String startDate, String endDate, List<String> metrics,
            String dimensions, String filters, String sort, Integer maxResults, String callName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(ANALYTICS_BASE)
                .queryParam("ids", CHANNEL)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .queryParam("metrics", String.join(",", metrics));
        if (dimensions != null && !dimensions.isBlank()) builder.queryParam("dimensions", dimensions);
        if (filters != null && !filters.isBlank()) builder.queryParam("filters", filters);
        if (sort != null && !sort.isBlank()) builder.queryParam("sort", sort);
        if (maxResults != null) builder.queryParam("maxResults", maxResults);
        URI uri = builder.build().encode().toUri();
        return executeWithRetry(() -> youTubeWebClient.get().uri(uri).exchangeToMono(response ->
                response.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
                    if (response.statusCode().isError()) {
                        return Mono.error(new ReportApiException(response.statusCode().value(), body,
                                response.headers().asHttpHeaders().getFirst("Retry-After")));
                    }
                    return Mono.just(new ApiResponse(response.statusCode().value(), body));
                })), callName);
    }

    private Map<String, Object> reportingApiReport(
            String videoId,
            LocalDate start,
            LocalDate end,
            String reportTypeId,
            String sectionName,
            List<String> configuredFields,
            ArchiveContext archive) {
        if (start == null || end == null || start.isAfter(end)) {
            return notApplicable("Invalid or empty report window.");
        }
        String prefix = "reporting-" + sectionName;
        try {
            List<JsonNode> jobs = listReportingResources(
                    REPORTING_BASE + "/jobs", "jobs", archive, prefix + "-jobs");
            String jobId = null;
            for (JsonNode job : jobs) {
                if (reportTypeId.equals(job.path("reportTypeId").asText())) {
                    jobId = job.path("id").asText(null);
                    break;
                }
            }
            if (jobId == null) {
                String created = apiCall(HttpMethod.POST, REPORTING_BASE + "/jobs",
                        mapOf("reportTypeId", reportTypeId, "name", "YouTube " + sectionName + " analysis"),
                        archive, prefix + "-create-job", "json");
                jobId = objectMapper.readTree(created).path("id").asText(null);
            }
            if (jobId == null || jobId.isBlank()) {
                return mapOf("report", sectionName, "api", "YouTube Reporting API v1",
                        "status", "UNAVAILABLE", "reason", "No Reporting API job is available.");
            }
            List<JsonNode> reports = listReportingResources(
                    REPORTING_BASE + "/jobs/" + jobId + "/reports", "reports", archive, prefix + "-list-reports");
            List<Map<String, Object>> matchingRows = new ArrayList<>();
            Map<String, Double> totals = new LinkedHashMap<>();
            Map<String, Double> weightedRateNumerators = new LinkedHashMap<>();
            Map<String, Double> weightedRateDenominators = new LinkedHashMap<>();
            Set<String> discoveredFields = new LinkedHashSet<>();
            int availableReports = 0;
            for (JsonNode report : reports) {
                LocalDate reportStart = parseDate(report.path("startTime").asText(null), null);
                LocalDate reportEnd = parseDate(report.path("endTime").asText(null), null);
                if (reportStart == null || reportEnd == null || reportEnd.isBefore(start) || reportStart.isAfter(end)) continue;
                String downloadUrl = report.path("downloadUrl").asText(null);
                if (downloadUrl == null || downloadUrl.isBlank()) continue;
                String csv = apiCall(HttpMethod.GET, downloadUrl, null, archive,
                        prefix + "-csv-" + (++availableReports), "csv");
                matchingRows.addAll(parseCsvReport(csv, videoId, start, end, totals, sectionName, discoveredFields,
                        weightedRateNumerators, weightedRateDenominators));
            }
            weightedRateNumerators.forEach((rateName, numerator) -> {
                double denominator = weightedRateDenominators.getOrDefault(rateName, 0.0);
                if (denominator > 0) totals.put(rateName, numerator / denominator);
            });
            Map<String, Object> optionalFields = validateReportingFields(sectionName, configuredFields, discoveredFields);
            return mapOf(
                    "report", sectionName,
                    "api", "YouTube Reporting API v1",
                    "reportTypeId", reportTypeId,
                    "status", matchingRows.isEmpty() ? "PENDING_OR_EMPTY" : "AVAILABLE",
                    "startDate", start.toString(),
                    "endDate", end.toString(),
                    "availableReportFiles", availableReports,
                    "rowCount", matchingRows.size(),
                    "rows", matchingRows,
                    "totals", totals,
                    "optionalFieldValidation", optionalFields,
                    "note", "Reporting API files are asynchronous; an empty result can mean the report is not ready yet.");
        } catch (ReportApiException ex) {
            logReportFailure(sectionName, ex);
            return failureSection(sectionName, "YouTube Reporting API v1", start, end, configuredFields, null, ex);
        } catch (Exception ex) {
            log.warn("Could not read YouTube Reporting API report {}: {}", sectionName, ex.getMessage());
            return mapOf("report", sectionName, "api", "YouTube Reporting API v1",
                    "status", "ERROR", "reason", "The reporting job or file could not be read.");
        }
    }

    private List<JsonNode> listReportingResources(
            String baseUri, String collectionName, ArchiveContext archive, String callPrefix) throws Exception {
        List<JsonNode> collected = new ArrayList<>();
        String pageToken = null;
        int page = 0;
        do {
            if (++page > 100) {
                log.warn("Stopped paginating Reporting API resource '{}' after 100 pages", collectionName);
                break;
            }
            String uri = pageToken == null ? baseUri
                    : UriComponentsBuilder.fromHttpUrl(baseUri).queryParam("pageToken", pageToken)
                    .build().encode().toUriString();
            String body = apiCall(HttpMethod.GET, uri, null, archive,
                    callPrefix + "-page-" + page, "json");
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.path(collectionName);
            if (items.isArray()) items.forEach(item -> collected.add(item.deepCopy()));
            pageToken = root.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return collected;
    }

    private String apiCall(
            HttpMethod method, String uri, Object body, ArchiveContext archive, String callName, String extension) {
        try {
            ApiResponse response = executeWithRetry(() -> {
                if (method == HttpMethod.POST) {
                    return youTubeWebClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body).exchangeToMono(clientResponse ->
                                    clientResponse.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> {
                                        if (clientResponse.statusCode().isError()) {
                                            return Mono.error(new ReportApiException(clientResponse.statusCode().value(), text,
                                                    clientResponse.headers().asHttpHeaders().getFirst("Retry-After")));
                                        }
                                        return Mono.just(new ApiResponse(clientResponse.statusCode().value(), text));
                                    }));
                }
                return youTubeWebClient.get().uri(uri).exchangeToMono(clientResponse ->
                        clientResponse.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> {
                            if (clientResponse.statusCode().isError()) {
                                return Mono.error(new ReportApiException(clientResponse.statusCode().value(), text,
                                        clientResponse.headers().asHttpHeaders().getFirst("Retry-After")));
                            }
                            return Mono.just(new ApiResponse(clientResponse.statusCode().value(), text));
                        }));
            }, callName);
            archive.add(callName, extension, response.body());
            return response.body();
        } catch (ReportApiException ex) {
            archive.add(callName, extension, ex.body());
            throw ex;
        }
    }

    private List<Map<String, Object>> parseCsvReport(
            String csv, String videoId, LocalDate start, LocalDate end,
            Map<String, Double> totals, String reportType, Set<String> discoveredFields,
            Map<String, Double> weightedRateNumerators, Map<String, Double> weightedRateDenominators) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> lines = csv.lines().toList();
        if (lines.isEmpty()) return List.of();
        List<String> headers = parseCsvLine(lines.get(0));
        discoveredFields.addAll(headers);
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) indexes.put(headers.get(i), i);
        Integer videoIndex = indexes.get("video_id");
        Integer dateIndex = indexes.get("date");
        if (videoIndex == null || dateIndex == null) {
            log.warn("Reporting CSV {} omitted expected video_id/date columns", reportType);
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (line.isBlank()) continue;
            List<String> values = parseCsvLine(line);
            if (values.size() <= Math.max(videoIndex, dateIndex)) continue;
            if (!videoId.equals(values.get(videoIndex))) continue;
            LocalDate day = parseDate(values.get(dateIndex), null);
            if (day == null || day.isBefore(start) || day.isAfter(end)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < values.size(); i++) {
                String key = headers.get(i);
                String value = values.get(i);
                row.put(key, value);
                if (i != dateIndex && i != videoIndex) {
                    try {
                        double number = Double.parseDouble(value);
                        if (Double.isFinite(number)) {
                            if (key.endsWith("_ctr") || key.endsWith("_click_rate")) {
                                row.put(key, number);
                                String weightColumn = rateWeightColumn(key);
                                Integer weightIndex = weightColumn == null ? null : indexes.get(weightColumn);
                                if (weightIndex != null && weightIndex < values.size()) {
                                    try {
                                        double weight = Double.parseDouble(values.get(weightIndex));
                                        if (Double.isFinite(weight) && weight > 0) {
                                            weightedRateNumerators.merge(key, number * weight, Double::sum);
                                            weightedRateDenominators.merge(key, weight, Double::sum);
                                        }
                                    } catch (NumberFormatException ignored) {
                                        // Preserve the row rate but exclude it from weighted totals if its denominator is missing.
                                    }
                                }
                            } else {
                                row.put(key, number);
                                totals.merge(key, number, Double::sum);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // Preserve nonnumeric Reporting API fields as strings.
                    }
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private String rateWeightColumn(String rateColumn) {
        if (rateColumn == null) return null;
        if (rateColumn.endsWith("_ctr")) return rateColumn.substring(0, rateColumn.length() - "_ctr".length());
        if (rateColumn.endsWith("_click_rate")) {
            return rateColumn.substring(0, rateColumn.length() - "_click_rate".length()) + "_impressions";
        }
        return null;
    }

    private Map<String, Object> validateReportingFields(
            String sectionName, List<String> configuredFields, Set<String> discovered) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> fieldMap = reportingFieldMap();
        for (String configured : configuredFields == null ? List.<String>of() : configuredFields) {
            String normalized = fieldMap.getOrDefault(configured, configured);
            boolean available = discovered.contains(normalized);
            result.put(configured, available ? "AVAILABLE" : "NOT_PRESENT_IN_CURRENT_REPORT");
            if (!available && !discovered.isEmpty()) {
                log.warn("Optional {} field '{}' is not present in the downloaded report schema", sectionName, normalized);
            }
        }
        return result;
    }

    private Map<String, String> reportingFieldMap() {
        return Map.of(
                "videoThumbnailImpressions", "video_thumbnail_impressions",
                "videoThumbnailImpressionsClickRate", "video_thumbnail_impressions_ctr",
                "endScreenElementImpressions", "end_screen_element_impressions",
                "endScreenElementClicks", "end_screen_element_clicks",
                "endScreenElementClickRate", "end_screen_element_click_rate");
    }

    private Map<String, Object> optionalMetricValidation(Map<String, Object> sections) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> mapping = reportingFieldMap();
        Map<String, String> groupByField = Map.of(
                "videoThumbnailImpressions", "reach",
                "videoThumbnailImpressionsClickRate", "reach",
                "endScreenElementImpressions", "endScreens",
                "endScreenElementClicks", "endScreens",
                "endScreenElementClickRate", "endScreens");
        for (String metric : analyticsProperties.getMetrics().getOptional()) {
            String field = mapping.getOrDefault(metric, metric);
            String group = groupByField.get(metric);
            Map<?, ?> section = group == null || !(sections.get(group) instanceof Map<?, ?> value) ? Map.of() : value;
            Map<?, ?> fields = section.get("optionalFieldValidation") instanceof Map<?, ?> value ? value : Map.of();
            Object state = fields.get(field);
            if ("AVAILABLE".equals(state)) {
                result.put(metric, "AVAILABLE");
            } else if ("NOT_PRESENT_IN_CURRENT_REPORT".equals(state)) {
                result.put(metric, "NOT_IN_REPORT_SCHEMA");
            } else {
                result.put(metric, "PENDING_REPORT_SCHEMA_VALIDATION");
            }
        }
        result.put("validationRule", "Reporting API fields are validated against the schema of each predefined report; they are not queried as Analytics API v2 metric names.");
        return result;
    }

    private Map<String, Object> deriveInsights(VideoAnalyticsResult video, Map<String, Object> sections) {
        Map<String, Object> metrics = video.getMetrics() == null ? Map.of() : video.getMetrics();
        Long views = number(metrics.get("views"));
        Long likes = number(metrics.get("likes"));
        Long comments = number(metrics.get("comments"));
        Long gained = number(metrics.get("subscribersGained"));
        Long lost = number(metrics.get("subscribersLost"));
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("likeRatePercent", percentage(likes, views));
        derived.put("commentRatePercent", percentage(comments, views));
        derived.put("subscriberConversionPercent", percentage(gained, views));
        derived.put("subscribersGainedPer1000Views", perThousand(gained, views));
        derived.put("netSubscribers", gained == null && lost == null ? null : valueOrZero(gained) - valueOrZero(lost));
        Long watchedMinutes = number(metrics.get("estimatedMinutesWatched"));
        derived.put("averageWatchTimePerViewSeconds",
                watchedMinutes == null || views == null || views <= 0 ? null : round(watchedMinutes * 60.0 / views));

        Object trendObject = sections.get("trend");
        if (trendObject instanceof Map<?, ?> trends) {
            Object current = trends.get("dailyVelocity");
            Object previous = trends.get("previous28Days");
            Map<String, Object> currentTotals = aggregateTrendViews(current);
            Map<String, Object> previousTotals = aggregateTrendViews(previous);
            derived.put("last28Days", currentTotals);
            derived.put("previous28Days", previousTotals);
            Object currentViews = currentTotals.get("views");
            Object previousViews = previousTotals.get("views");
            if (currentViews instanceof Number c && previousViews instanceof Number p && p.doubleValue() > 0) {
                derived.put("viewsChangePercent", round((c.doubleValue() - p.doubleValue()) * 100.0 / p.doubleValue()));
            } else {
                derived.put("viewsChangePercent", null);
            }
        }
        Object benchmarkObject = sections.get("channelBenchmark");
        if (benchmarkObject instanceof Map<?, ?> benchmark
                && benchmark.get("summary") instanceof Map<?, ?> summary) {
            derived.put("channelMedianViews", summary.get("medianViews"));
            derived.put("targetVsChannelMedianViewsPercent", summary.get("targetVsMedianViewsPercent"));
            derived.put("targetViewIsOutlierWithinTopCohort", summary.get("targetViewIsOutlierWithinTopCohort"));
            derived.put("channelBenchmarkCohortSize", summary.get("returnedVideoCount"));
        }
        derived.put("note", "Derived values are calculated from the API data available in this response and inherit the source report's processing delay.");
        return derived;
    }

    private Map<String, Object> aggregateTrendViews(Object sectionObject) {
        if (!(sectionObject instanceof Map<?, ?> section) || !(section.get("rows") instanceof List<?> rows)) {
            return mapOf("status", "UNAVAILABLE", "views", null, "estimatedMinutesWatched", null, "subscribersGained", null, "shares", null);
        }
        double views = 0, minutes = 0, gained = 0, shares = 0;
        boolean hasViews = false;
        for (Object rowObject : rows) {
            if (!(rowObject instanceof Map<?, ?> row)) continue;
            Number rowViews = asNumber(row.get("views"));
            Number rowMinutes = asNumber(row.get("estimatedMinutesWatched"));
            Number rowGained = asNumber(row.get("subscribersGained"));
            Number rowShares = asNumber(row.get("shares"));
            if (rowViews != null) { views += rowViews.doubleValue(); hasViews = true; }
            if (rowMinutes != null) minutes += rowMinutes.doubleValue();
            if (rowGained != null) gained += rowGained.doubleValue();
            if (rowShares != null) shares += rowShares.doubleValue();
        }
        return mapOf("status", section.get("status"), "views", hasViews ? views : null,
                "estimatedMinutesWatched", minutes, "subscribersGained", gained, "shares", shares);
    }

    private Map<String, Object> failureSection(
            String name, String api, LocalDate start, LocalDate end,
            List<String> metrics, String dimensions, ReportApiException ex) {
        String status = ex.status() == 400 ? "INVALID_COMBINATION"
                : ex.status() == 403 ? "FORBIDDEN_OR_MONETIZATION_REQUIRED"
                : isTransientStatus(ex.status()) ? "TEMPORARILY_UNAVAILABLE" : "API_ERROR";
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("report", name);
        section.put("api", api);
        section.put("status", status);
        section.put("upstreamStatus", ex.status());
        if (start != null) section.put("startDate", start.toString());
        if (end != null) section.put("endDate", end.toString());
        if (metrics != null) section.put("metrics", metrics);
        if (dimensions != null) section.put("dimensions", dimensions);
        section.put("reason", status.equals("INVALID_COMBINATION")
                ? "The API rejected this report's metric, dimension, or filter combination."
                : status.equals("FORBIDDEN_OR_MONETIZATION_REQUIRED")
                ? "The report may require an additional OAuth scope, channel permission, or monetization eligibility."
                : status.equals("TEMPORARILY_UNAVAILABLE")
                ? "The API is rate-limiting or temporarily unavailable; retries were attempted."
                : "The upstream report request failed.");
        return section;
    }

    private Map<String, Object> notApplicable(String note) {
        return mapOf("status", "NOT_APPLICABLE", "note", note);
    }

    private void logReportFailure(String name, ReportApiException ex) {
        log.warn("Report '{}' failed with upstream status {}. The other report sections will continue.",
                name, ex.status());
    }

    private boolean isTransientStatus(int status) {
        return TRANSIENT_STATUSES.contains(status) || (status >= 500 && status < 600);
    }

    private ApiResponse executeWithRetry(java.util.function.Supplier<Mono<ApiResponse>> request, String callName) {
        try {
            return request.get().retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                    .maxBackoff(Duration.ofSeconds(2))
                    .filter(error -> error instanceof ReportApiException api
                            && isTransientStatus(api.status()))
                    .doBeforeRetry(signal -> log.warn("Retrying YouTube report call '{}' after transient failure (attempt {})",
                            callName, signal.totalRetries() + 1)))
                    .block();
        } catch (ReportApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ReportApiException(502, "", null, ex);
        }
    }

    private Map<String, Object> persistRawCalls(ArchiveContext archive, VideoAnalyticsResult result) {
        if (archive.snapshotId == null) archive.snapshotId = newSnapshotId();
        String videoId = result == null ? "standalone" : result.getVideoId();
        Path dir = snapshotDirectory(videoId, archive.snapshotId);
        try {
            Files.createDirectories(dir);
            Map<String, String> fileNames = new LinkedHashMap<>();
            for (RawCall raw : archive.calls.values()) {
                String fileName = raw.callName() + "." + raw.extension();
                Path file = dir.resolve(fileName).normalize();
                if (!file.startsWith(dir)) continue;
                Files.writeString(file, raw.body() == null ? "" : raw.body(), StandardCharsets.UTF_8);
                fileNames.put(raw.callName(), fileName);
            }
            Map<String, Object> archiveInfo = mapOf("status", "SAVED", "snapshotId", archive.snapshotId,
                    "rawCallCount", fileNames.size(), "directory", dir.toString(),
                    "replayHint", "GET /api/youtube-analytics/v1/analytics/video/" + videoId + "/reports/replay/" + archive.snapshotId);
            if (result != null) {
                // Do not duplicate the full raw bodies inside the manifest; replay loads them from individual files.
                if (result.getReportSections() != null) result.getReportSections().put("archive", archiveInfo);
                Map<String, Object> manifest = mapOf(
                        "snapshotId", archive.snapshotId,
                        "videoId", result.getVideoId(),
                        "createdAt", Instant.now().toString(),
                        "rawCallFiles", fileNames,
                        "result", result);
                result.setRawReportResponses(null);
                Files.writeString(dir.resolve("snapshot.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        StandardCharsets.UTF_8);
            }
            return archiveInfo;
        } catch (IOException ex) {
            throw new IllegalStateException("Raw analytics response archival failed", ex);
        }
    }

    private Path snapshotDirectory(String videoId, String snapshotId) {
        Path base = Path.of(analyticsProperties.getArchiveDirectory()).toAbsolutePath().normalize();
        Path videoDir = base.resolve(videoId).normalize();
        Path snapshot = videoDir.resolve(snapshotId).normalize();
        if (!videoDir.startsWith(base) || !snapshot.startsWith(videoDir)) {
            throw new IllegalArgumentException("Invalid analytics archive path");
        }
        return snapshot;
    }

    private String newSnapshotId() {
        return Instant.now().toString().replace(":", "").replace(".", "") + "_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private List<Map<String, Object>> parseRows(JsonNode root) {
        JsonNode headers = root.path("columnHeaders");
        JsonNode rows = root.path("rows");
        if (!headers.isArray() || !rows.isArray()) return List.of();
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray()) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            for (int i = 0; i < headers.size() && i < row.size(); i++) {
                String name = headers.get(i).path("name").asText(null);
                if (name != null) value.put(name, objectMapper.convertValue(row.get(i), Object.class));
            }
            values.add(value);
        }
        return values;
    }

    private Map<String, Object> mapOf(Object... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < items.length; i += 2) result.put(String.valueOf(items[i]), items[i + 1]);
        return result;
    }

    private List<String> concat(List<String> first, List<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return List.copyOf(result);
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalDate.parse(raw.substring(0, Math.min(raw.length(), 10)));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private LocalDate parsePublishDay(String publishedAt, LocalDate fallback) {
        if (publishedAt == null || publishedAt.isBlank()) return fallback;
        try {
            return Instant.parse(publishedAt).atZone(REPORTING_ZONE).toLocalDate();
        } catch (RuntimeException ex) {
            return parseDate(publishedAt, fallback);
        }
    }

    private LocalDate maxDate(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private Long number(Object value) {
        Number number = asNumber(value);
        return number == null ? null : number.longValue();
    }

    private Number asNumber(Object value) {
        if (value instanceof Number number) return number;
        if (value instanceof String string) {
            try { return Double.valueOf(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Double percentage(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        return round(numerator * 100.0 / denominator);
    }

    private Double perThousand(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        return round(numerator * 1000.0 / denominator);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private record ApiResponse(int status, String body) {
    }

    private record RawCall(String callName, String extension, String body) {
    }

    private static final class ArchiveContext {
        private String snapshotId;
        private final Map<String, RawCall> calls = new LinkedHashMap<>();

        private void add(String name, String extension, String body) {
            String safe = name == null ? "call" : name.replaceAll("[^A-Za-z0-9_-]", "_");
            calls.put(safe, new RawCall(safe, extension, body));
        }
    }

    private static final class ReportApiException extends RuntimeException {
        private final int status;
        private final String body;
        private final String retryAfter;

        private ReportApiException(int status, String body, String retryAfter) {
            super("YouTube report request failed with status " + status);
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        private ReportApiException(int status, String body, String retryAfter, Throwable cause) {
            super("YouTube report request failed with status " + status, cause);
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        private int status() { return status; }
        private String body() { return body; }
        @SuppressWarnings("unused")
        private String retryAfter() { return retryAfter; }
    }
}
