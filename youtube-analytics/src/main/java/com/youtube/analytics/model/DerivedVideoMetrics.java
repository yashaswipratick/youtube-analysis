package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

/**
 * Metrics derived from normalized YouTube analytics.
 * Percentages are represented as percentage points (for example, 15.79 means 15.79%).
 */
@Data
@Builder
public class DerivedVideoMetrics {

    private Double likeRate;
    private Double commentRate;
    private Double subscriberConversionRate;
    private Long netSubscribers;
    private Double averageWatchTimePerViewSeconds;
}
