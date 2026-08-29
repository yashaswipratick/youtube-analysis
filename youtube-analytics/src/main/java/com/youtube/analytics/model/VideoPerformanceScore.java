package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

/**
 * Channel-relative performance score for a video.
 * Score is normalized to 0-100 using percentile rank within the comparison set.
 */
@Data
@Builder
public class VideoPerformanceScore {
    private double score;
    private double percentile;
    private PerformanceBand performance;

    public enum PerformanceBand {
        TOP_PERFORMER,
        ABOVE_AVERAGE,
        AVERAGE,
        BELOW_AVERAGE,
        LOW_PERFORMER,
        INSUFFICIENT_DATA
    }
}
