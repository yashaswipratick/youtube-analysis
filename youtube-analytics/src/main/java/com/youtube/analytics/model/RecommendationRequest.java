package com.youtube.analytics.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Request for generating evidence-based recommendations for a video. */
public record RecommendationRequest(
        @NotBlank(message = "videoId is required")
        String videoId,
        @Min(value = 1, message = "lastNDays must be at least 1")
        @Max(value = 3650, message = "lastNDays must not exceed 3650")
        Integer lastNDays) {
}
