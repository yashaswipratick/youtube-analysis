package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RawVideoAnalysisRequest(
        @NotBlank String projectId,
        @NotBlank String storyIntent,
        @Positive Long targetDurationMinutes) {
}
