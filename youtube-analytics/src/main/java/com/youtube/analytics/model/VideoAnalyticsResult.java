package com.youtube.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalyticsResult {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private Map<String, Object> metrics;
    /** Original date-filtered response returned by YouTube Analytics API v2. */
    private Map<String, Object> analyticsMetrics;
    /** Current, non-date-filtered public counters from YouTube Data API v3, when fetched. */
    private Map<String, Object> currentStatistics;
    /** Explains which source is used for fresh counters and the expected Analytics API lag. */
    private Map<String, Object> dataFreshness;
    /** Independent, report-specific datasets merged into this response. */
    private Map<String, Object> reportSections;
    /** Identifier for the raw response archive associated with this analysis. */
    private String archiveSnapshotId;
    /** Populated for replay requests; raw API responses as archived text, keyed by call id. */
    private Map<String, String> rawReportResponses;
    /** Deterministic insights derived from the returned raw report sections. */
    private Map<String, Object> derivedInsights;
}
