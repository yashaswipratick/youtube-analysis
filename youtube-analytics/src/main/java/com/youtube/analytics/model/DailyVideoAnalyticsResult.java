package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Daily analytics for a single YouTube video.
 * Each row represents one reporting day returned by YouTube Analytics.
 */
@Data
@Builder
public class DailyVideoAnalyticsResult {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private List<DailyMetricRow> days;

    @Data
    @Builder
    public static class DailyMetricRow {
        private String date;
        private Map<String, Object> metrics;
    }
}
