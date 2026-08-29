package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Deterministic channel-wide findings produced from normalized analytics.
 */
@Data
@Builder
public class PatternDetectionResult {

    private long analyzedVideos;
    private long videosWithAnalytics;
    private String topCategory;
    private Double topCategoryAverageViews;
    private String topTopic;
    private Double topTopicAverageViews;
    private Double shortAverageViews;
    private Double longFormAverageViews;
    private String strongestTrafficSource;
    private Double strongestTrafficSourceShare;
    private List<Finding> findings;

    @Data
    @Builder
    public static class Finding {
        private String type;
        private String subject;
        private String description;
        private Map<String, Object> evidence;
    }
}
