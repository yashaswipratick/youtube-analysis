package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Aggregate analytics for the authenticated YouTube channel over a date range.
 */
@Value
@Builder
public class ChannelAnalyticsResult {
    String startDate;
    String endDate;
    Map<String, Object> metrics;
}
