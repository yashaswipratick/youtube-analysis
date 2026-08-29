package com.youtube.analytics.model;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request payload for AI-powered analytics analysis. */
public record AiAnalysisRequest(
        @NotBlank(message = "prompt must not be blank")
        String prompt,
        Map<String, Object> context) {
}
