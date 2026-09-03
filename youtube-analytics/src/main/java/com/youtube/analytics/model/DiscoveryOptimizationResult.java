package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Combined discovery, retention, and view-momentum analysis for a video. */
@Data
@Builder
public class DiscoveryOptimizationResult {

    private String videoId;
    private Long views;
    private Long impressions;
    private Double impressionsClickThroughRate;
    private Double viewsPerDay;
    private Double impressionsPerDay;
    private DiscoveryStatus reachStatus;
    private DiscoveryStatus packagingStatus;
    private DiscoveryDiagnosis primaryDiagnosis;
    private RetentionAnalysisResult retentionAnalysis;
    private ViewVelocity viewVelocity;
    private List<String> recommendations;
    private List<String> missingData;

    public enum DiscoveryStatus {
        STRONG,
        HEALTHY,
        WEAK,
        CRITICAL,
        UNKNOWN
    }

    public enum DiscoveryDiagnosis {
        HEALTHY_DISCOVERY,
        LOW_REACH,
        LOW_CTR,
        LOW_REACH_AND_LOW_CTR,
        INSUFFICIENT_DATA
    }

    /** Compares the second half of the requested daily view window with the first half. */
    public record ViewVelocity(
            Double recentViewsPerDay,
            Double previousViewsPerDay,
            Double changePercent,
            MomentumStatus status) {
    }

    public enum MomentumStatus {
        ACCELERATING,
        STABLE,
        DECELERATING,
        INSUFFICIENT_DATA
    }
}
