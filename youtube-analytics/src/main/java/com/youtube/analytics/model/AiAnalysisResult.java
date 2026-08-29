package com.youtube.analytics.model;

/** Result returned by the OpenAI analytics analysis service. */
public record AiAnalysisResult(
        String model,
        String analysis) {
}
