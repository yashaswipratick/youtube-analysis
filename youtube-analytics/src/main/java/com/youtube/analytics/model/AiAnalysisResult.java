package com.youtube.analytics.model;

import java.util.Map;

/** Result returned by the OpenAI analytics analysis service. */
public record AiAnalysisResult(
        String model,
        Map<String, Object> inputContext,
        String analysis) {
}
