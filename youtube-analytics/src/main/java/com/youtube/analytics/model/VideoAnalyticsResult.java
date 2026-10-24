package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
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
}
