package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.model.YouTubeReachReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Reads the asynchronous YouTube Reporting API reach report used for impressions and CTR. */
@Service
@RequiredArgsConstructor
public class YouTubeReachReportingService {

    private static final String BASE_URL = "https://youtubereporting.googleapis.com/v1";
    private static final String REPORT_TYPE_ID = "channel_reach_basic_a1";
    private static final String IMPRESSIONS_COLUMN = "video_thumbnail_impressions";
    private static final String CTR_COLUMN = "video_thumbnail_impressions_ctr";
    private static final String DATE_COLUMN = "date";
    private static final String VIDEO_ID_COLUMN = "video_id";

    private final WebClient youTubeWebClient;
    private final ObjectMapper objectMapper;

    public YouTubeReachReportResult getVideoReach(String videoId, String startDate, String endDate) {
        return getVideoReachForRanges(Map.of(videoId, new DateRange(
                LocalDate.parse(startDate), LocalDate.parse(endDate))))
                .getOrDefault(videoId, YouTubeReachReportResult.unavailable());
    }

    /**
     * Fetches the reach CSVs once and calculates impressions/CTR for each video's own date window.
     * This is used by the recent-video benchmark to avoid creating/report-fetching a separate
     * Reporting API job for every video.
     */
    public Map<String, YouTubeReachReportResult> getVideoReachForRanges(Map<String, DateRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return Collections.emptyMap();
        String jobId = findOrCreateJob();
        if (jobId == null) {
            return unavailableFor(ranges.keySet());
        }

        JsonNode reports = listReports(jobId);
        if (reports == null || !reports.has("reports")) {
            return unavailableFor(ranges.keySet());
        }

        LocalDate requestedStart = ranges.values().stream().map(DateRange::startDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate requestedEnd = ranges.values().stream().map(DateRange::endDate).max(LocalDate::compareTo).orElseThrow();
        Map<String, ReachAggregation> totals = new LinkedHashMap<>();

        for (JsonNode report : reports.get("reports")) {
            LocalDate reportStart = parseDate(report.path("startTime").asText(null));
            LocalDate reportEnd = parseDate(report.path("endTime").asText(null));
            if (reportStart == null || reportEnd == null || reportEnd.isBefore(requestedStart)
                    || reportStart.isAfter(requestedEnd)) {
                continue;
            }

            String downloadUrl = report.path("downloadUrl").asText(null);
            if (downloadUrl == null || downloadUrl.isBlank()) {
                continue;
            }

            String csv = downloadReport(downloadUrl);
            if (csv == null || csv.isBlank()) {
                continue;
            }

            for (Map.Entry<String, DateRange> videoRange : ranges.entrySet()) {
                DateRange range = videoRange.getValue();
                if (reportEnd.isBefore(range.startDate()) || reportStart.isAfter(range.endDate())) continue;
                ReachAggregation aggregation = parseCsv(
                        csv, videoRange.getKey(), range.startDate(), range.endDate());
                if (aggregation.rowCount > 0) {
                    totals.merge(videoRange.getKey(), aggregation, ReachAggregation::merge);
                }
            }
        }

        Map<String, YouTubeReachReportResult> result = new LinkedHashMap<>();
        for (Map.Entry<String, DateRange> entry : ranges.entrySet()) {
            String videoId = entry.getKey();
            ReachAggregation aggregation = totals.get(videoId);
            if (aggregation == null || aggregation.rowCount == 0) {
                result.put(videoId, YouTubeReachReportResult.unavailable());
                continue;
            }
            Double ctr = aggregation.ctrImpressionWeight == 0 ? null
                    : aggregation.weightedCtr / aggregation.ctrImpressionWeight;
            result.put(videoId, new YouTubeReachReportResult(aggregation.impressions, ctr, true));
        }
        return result;
    }

    private Map<String, YouTubeReachReportResult> unavailableFor(Set<String> videoIds) {
        Map<String, YouTubeReachReportResult> result = new LinkedHashMap<>();
        videoIds.forEach(id -> result.put(id, YouTubeReachReportResult.unavailable()));
        return result;
    }

    private String findOrCreateJob() {
        JsonNode jobs = getJson(BASE_URL + "/jobs");
        if (jobs != null && jobs.has("jobs")) {
            for (JsonNode job : jobs.get("jobs")) {
                if (REPORT_TYPE_ID.equals(job.path("reportTypeId").asText())) {
                    return job.path("id").asText(null);
                }
            }
        }

        try {
            JsonNode created = youTubeWebClient.post()
                    .uri(BASE_URL + "/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("reportTypeId", REPORT_TYPE_ID,
                            "name", "YouTube reach analysis"))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return created == null ? null : created.path("id").asText(null);
        } catch (RuntimeException ex) {
            // Job creation is idempotent at the application level; a concurrent existing job is
            // discovered on the next request. Do not turn report bootstrapping into a 500 response.
            return null;
        }
    }

    private JsonNode listReports(String jobId) {
        return getJson(BASE_URL + "/jobs/" + jobId + "/reports");
    }

    private JsonNode getJson(String uri) {
        try {
            return youTubeWebClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String downloadReport(String downloadUrl) {
        try {
            return youTubeWebClient.get()
                    .uri(downloadUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ReachAggregation parseCsv(String csv, String videoId, LocalDate startDate, LocalDate endDate) {
        List<String> headers;
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return ReachAggregation.empty();
            headers = parseCsvLine(headerLine);

            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                indexes.put(headers.get(i), i);
            }
            Integer dateIndex = indexes.get(DATE_COLUMN);
            Integer videoIndex = indexes.get(VIDEO_ID_COLUMN);
            Integer impressionsIndex = indexes.get(IMPRESSIONS_COLUMN);
            Integer ctrIndex = indexes.get(CTR_COLUMN);
            if (dateIndex == null || videoIndex == null || impressionsIndex == null || ctrIndex == null) {
                return ReachAggregation.empty();
            }

            long impressions = 0L;
            double weightedCtr = 0.0;
            long ctrImpressionWeight = 0L;
            int rowCount = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                int requiredIndex = Math.max(Math.max(dateIndex, videoIndex), Math.max(impressionsIndex, ctrIndex));
                if (values.size() <= requiredIndex || !videoId.equals(values.get(videoIndex))) continue;

                LocalDate date = parseDate(values.get(dateIndex));
                if (date == null || date.isBefore(startDate) || date.isAfter(endDate)) continue;

                long rowImpressions = parseLong(values.get(impressionsIndex));
                if (rowImpressions < 0) continue;
                rowCount++;
                impressions += rowImpressions;

                Double rowCtr = parseDouble(values.get(ctrIndex));
                if (rowCtr != null && rowImpressions > 0) {
                    weightedCtr += rowCtr * rowImpressions;
                    ctrImpressionWeight += rowImpressions;
                }
            }
            return new ReachAggregation(impressions, weightedCtr, ctrImpressionWeight, rowCount);
        } catch (Exception ex) {
            return ReachAggregation.empty();
        }
    }

    public record DateRange(LocalDate startDate, LocalDate endDate) {
        public DateRange {
            if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("A reach date range must have a start date on or before its end date");
            }
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
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ex) { return 0L; }
    }

    private Double parseDouble(String value) {
        try { return Double.valueOf(value); }
        catch (RuntimeException ex) { return null; }
    }

    private record ReachAggregation(long impressions, double weightedCtr, long ctrImpressionWeight, int rowCount) {
        private static ReachAggregation empty() {
            return new ReachAggregation(0L, 0.0, 0L, 0);
        }

        private ReachAggregation merge(ReachAggregation other) {
            return new ReachAggregation(impressions + other.impressions,
                    weightedCtr + other.weightedCtr,
                    ctrImpressionWeight + other.ctrImpressionWeight,
                    rowCount + other.rowCount);
        }
    }
}
