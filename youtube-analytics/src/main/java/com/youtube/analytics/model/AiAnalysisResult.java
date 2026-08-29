package com.youtube.analytics.model;

import java.util.List;
import java.util.Map;

/** Structured result returned by the OpenAI analytics analysis service. */
public record AiAnalysisResult(
        String model,
        Map<String, Object> inputContext,
        String summary,
        List<String> observations,
        List<String> strengths,
        List<String> weaknesses,
        List<Recommendation> recommendations,
        List<String> missingData) {

    public record Recommendation(
            String recommendation,
            RecommendationType type,
            String reason) {
    }

    public enum RecommendationType {
        EVIDENCE_BASED,
        EXPERIMENTAL
    }
}
