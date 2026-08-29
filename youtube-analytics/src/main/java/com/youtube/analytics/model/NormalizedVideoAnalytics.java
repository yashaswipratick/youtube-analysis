package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Internal normalized representation used by the analysis layer.
 * Keeps analysis code independent from individual YouTube API response DTOs.
 */
@Data
@Builder
public class NormalizedVideoAnalytics {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private Map<String, Object> aggregateMetrics;
    private List<DailyMetric> dailyMetrics;
    private List<DimensionMetric> trafficSources;
    private List<DimensionMetric> countries;

    @Data
    @Builder
    public static class DailyMetric {
        private String date;
        private Map<String, Object> metrics;
    }

    @Data
    @Builder
    public static class DimensionMetric {
        private String dimension;
        private Map<String, Object> metrics;
    }
}
