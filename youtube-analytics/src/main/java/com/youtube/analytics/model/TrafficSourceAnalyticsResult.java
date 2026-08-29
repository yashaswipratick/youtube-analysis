package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Traffic-source analytics for a single YouTube video.
 * Each row represents a traffic source returned by YouTube Analytics.
 */
@Data
@Builder
public class TrafficSourceAnalyticsResult {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private List<TrafficSourceMetricRow> sources;

    @Data
    @Builder
    public static class TrafficSourceMetricRow {
        private String trafficSource;
        private Map<String, Object> metrics;
    }
}
