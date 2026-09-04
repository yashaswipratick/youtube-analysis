package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RawVideoAnalysisRequest(
        @NotBlank String projectId,
        @NotBlank String storyIntent,
        @Positive @Max(180) Long targetDurationMinutes) {
    public RawVideoAnalysisRequest {
        if (targetDurationMinutes == null) {
            throw new IllegalArgumentException("targetDurationMinutes is required");
        }
    }
}
