package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Explains whether a video's discovery problem is primarily reach or packaging.
 */
@Data
@Builder
public class DiscoveryOptimizationResult {

    private String videoId;
    private Long views;
    private Long impressions;
    private Double impressionsClickThroughRate;
    private Double viewsPerDay;
    private DiscoveryStatus reachStatus;
    private DiscoveryStatus packagingStatus;
    private DiscoveryDiagnosis primaryDiagnosis;
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
}
