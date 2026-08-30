package com.youtube.analytics.model;

import java.util.List;

/** Structured recommendation result with explicit evidence and confidence. */
public record RecommendationResult(
        String videoId,
        String summary,
        List<Recommendation> recommendations,
        List<String> missingData) {

    public record Recommendation(
            Priority priority,
            Area area,
            String recommendation,
            RecommendationType type,
            String evidence,
            Confidence confidence,
            String measurement) {
    }

    public enum Priority {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum Area {
        RETENTION,
        ENGAGEMENT,
        SUBSCRIBER_CONVERSION,
        WATCH_TIME,
        DISCOVERY,
        PACKAGING
    }

    public enum RecommendationType {
        EVIDENCE_BASED,
        EXPERIMENTAL
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }
}
