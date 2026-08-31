package com.youtube.analytics.model;

import java.util.List;

/** Deterministic analysis of a YouTube audience-retention curve. */
public record RetentionAnalysisResult(
        String videoId,
        Double averageViewDurationSeconds,
        Double averageViewPercentage,
        RetentionSeverity overallSeverity,
        RetentionFinding earlyDrop,
        RetentionFinding largestDrop,
        RetentionFinding strongestSection,
        RetentionFinding weakestSection,
        List<RetentionFinding> recoverySections,
        RetentionFinding endRetention,
        List<RetentionSegment> segments,
        List<String> recommendations,
        List<String> missingData) {

    public enum RetentionSeverity {
        STRONG,
        HEALTHY,
        WEAK,
        CRITICAL,
        UNKNOWN
    }

    public record RetentionFinding(
            String type,
            Double fromVideoPercent,
            Double toVideoPercent,
            Double fromAudiencePercent,
            Double toAudiencePercent,
            Double changePercentagePoints,
            RetentionSeverity severity,
            String evidence) {
    }

    /** Fixed video windows used to compare meaningful retention sections. */
    public record RetentionSegment(
            Double fromVideoPercent,
            Double toVideoPercent,
            Double startAudiencePercent,
            Double endAudiencePercent,
            Double averageAudiencePercent,
            Double changePercentagePoints,
            Double volatilityPercentagePoints,
            RetentionSeverity severity,
            String signal) {
    }
}
