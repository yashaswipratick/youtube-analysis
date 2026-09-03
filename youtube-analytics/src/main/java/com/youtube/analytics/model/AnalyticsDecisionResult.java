package com.youtube.analytics.model;

import java.util.List;

/** Deterministic primary action derived from the existing discovery, retention, and momentum signals. */
public record AnalyticsDecisionResult(
        String videoId,
        DecisionAction action,
        String summary,
        List<String> evidence,
        List<String> missingData) {

    public enum DecisionAction {
        PACKAGING,
        DISTRIBUTION_TOPIC,
        CONTENT_RETENTION,
        CONTINUE_OBSERVING,
        INSUFFICIENT_DATA
    }
}
