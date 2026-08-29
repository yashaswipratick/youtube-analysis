package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Audience-retention report for a single YouTube video. */
@Data
@Builder
public class VideoRetentionAnalyticsResult {
    private String videoId;
    private String startDate;
    private String endDate;
    private Double averageViewDurationSeconds;
    private Double averageViewPercentage;
    private List<RetentionPoint> retention;

    @Data
    @Builder
    public static class RetentionPoint {
        /** Fraction of the video elapsed, from 0.01 to 1.0. */
        private Double elapsedVideoTimeRatio;
        /** Absolute audience-watch ratio returned by YouTube. */
        private Double audienceWatchRatio;
        /** Relative retention performance compared with videos of similar length. */
        private Double relativeRetentionPerformance;
    }
}
