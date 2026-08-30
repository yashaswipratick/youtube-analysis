package com.youtube.analytics.model;

import java.util.List;

/** Structured recommendation result with explicit evidence and confidence. */
public record RecommendationResult(
        String videoId,
        String summary,
        List<Recommendation> recommendations,
        List<String> missingData) {

    public record Recommendation(
            String recommendation,
            RecommendationType type,
            String evidence,
            Confidence confidence) {
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
