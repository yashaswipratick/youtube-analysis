package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Geography analytics for the authenticated YouTube channel.
 * Each row represents a country returned by YouTube Analytics.
 */
@Data
@Builder
public class ChannelGeographyAnalyticsResult {

    private String startDate;
    private String endDate;
    private List<CountryMetricRow> countries;

    @Data
    @Builder
    public static class CountryMetricRow {
        private String country;
        private Map<String, Object> metrics;
    }
}
