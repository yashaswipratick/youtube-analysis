package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Geography analytics for a single YouTube video.
 * Each row represents a country returned by YouTube Analytics.
 */
@Data
@Builder
public class GeographyAnalyticsResult {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private List<GeographyMetricRow> countries;

    @Data
    @Builder
    public static class GeographyMetricRow {
        private String country;
        private Map<String, Object> metrics;
    }
}
