package com.youtube.analytics.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Request for an AI-powered analytics analysis. */
public record AiAnalysisRequest(
        @NotBlank(message = "prompt is required") String prompt,
        Map<String, Object> context) {
}
